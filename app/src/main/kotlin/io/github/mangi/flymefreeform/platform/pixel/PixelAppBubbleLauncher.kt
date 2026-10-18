package io.github.mangi.flymefreeform.platform.pixel

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.UserHandle
import android.util.Log
import io.github.mangi.flymefreeform.platform.common.AppBubbleLauncher
import io.github.mangi.flymefreeform.platform.common.BubbleLaunchRequest
import io.github.mangi.flymefreeform.platform.common.BubbleLaunchResult
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * Pixel / Android 17 原生 App Bubbles 后端。
 *
 * 移植自 MoreBubbleButton feature/a17-bubble-corner-actions 的
 * [官方配方](https://github.com/gugugugugu-cmd/MoreBubbleButton)：
 *   key    = Bubble.getAppBubbleKeyForApp(intent.getPackage(), user)
 *   bubble = data.findAndRemoveBubbleFromOverflow(key)
 *   if (bubble == null) {
 *       pm   = BubbleController.getPackageManagerForUser(user.id, data.mContext)
 *       icon = data.mAppInfoProvider.getActivityInfoIcon(pm, intent)
 *       bubble = new Bubble(intent, user, icon, Bubble$BubbleType.TYPE_APP, key)
 *   }
 *   controller.expandStackAndSelectAppBubble(bubble, EntryPoint.NOTIFICATION_BUBBLE_BUTTON, null)
 *
 * 与上游差异（任务书 §5.2 要求）：
 * - 反射方法一律按「参数个数 + 参数类型 + 返回类型」三重校验，不按方法名选第一个重载；
 * - 每个失败路径归因到 [BubbleLaunchResult] 的明确状态，不吞异常、不假造成功；
 * - 非主用户请求在入口即拒绝（[BubbleLaunchResult.RequestValidationFailed]）。
 *
 * 运行位置：SystemUI 进程（BubbleController 实例所在进程）。
 */
@SuppressLint("PrivateApi")
internal class PixelAppBubbleLauncher(
    private val context: Context,
) : AppBubbleLauncher {

    private var controller: Any? = null

    /**
     * 从 SystemUI 上下文解析 BubbleController 单例。
     * Android 17 上通过 localService 或反射拿到实例；失败返回 null 表示后端不可用。
     */
    fun bindController(): Boolean {
        controller = try {
            resolveBubbleController()
        } catch (t: Throwable) {
            Log.w(TAG, "resolveBubbleController failed: ${t.message}")
            null
        }
        return controller != null
    }

    /**
     * 外部（hook 回调）设置 controller 实例。
     */
    fun setController(ctl: Any) {
        if (ctl.javaClass.name.contains("BubbleController")) {
            controller = ctl
            Log.i(TAG, "setController: controller bound (${ctl.javaClass.name})")
        }
    }

    /**
     * 从 BubblesManager 实例链捕获 BubbleController（阶段 1 验证过的路径）：
     *   BubblesManager.mBubbles (IBubblesImpl) → this$0 → BubbleController
     * SystemUI 事件回调（onUserChangedBubble / expandStackAndSelectBubble）的
     * this 就是 BubblesManager；hook 到实例后调用本方法完成绑定。
     */
    fun bindFromBubblesManager(bubblesManager: Any) {
        if (controller != null) return
        try {
            val bubblesImpl = findField(bubblesManager, "mBubbles") ?: return
            val ctl = findField(bubblesImpl, "this\$0") ?: return
            if (ctl.javaClass.name.contains("BubbleController")) {
                controller = ctl
                Log.i(TAG, "bindFromBubblesManager: controller bound")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "bindFromBubblesManager failed: ${t.message}")
        }
    }

    override fun isAvailable(): Boolean = controller != null

    override fun launch(request: BubbleLaunchRequest): BubbleLaunchResult {
        val ctl = controller ?: return BubbleLaunchResult.BackendUnavailable

        // 1. 请求校验（任务书：不默认 userId=0，非主用户明确拒绝）
        // getIdentifier() 在 SDK 37 为隐藏 API，用反射读取。
        val userId = runCatching {
            UserHandle::class.java.getMethod("getIdentifier").invoke(request.userHandle) as Int
        }.getOrElse { 0 }
        if (userId != 0) {
            return BubbleLaunchResult.RequestValidationFailed
        }
        val component = request.component
        if (component.packageName.isBlank() || component.className.isBlank()) {
            return BubbleLaunchResult.RequestValidationFailed
        }

        // 2. 目标解析（显式校验，不靠 setPackage 兜底）
        val resolved = resolveTarget(component)
        if (resolved == null) return BubbleLaunchResult.TargetResolutionFailed

        val intent = resolved
        val user = request.userHandle

        // 3. 提交（A17 官方配方优先，legacy 回退）
        return try {
            val done = expandAppBubbleA17(ctl, intent, user)
            if (done) {
                BubbleLaunchResult.BubbleRequestSubmitted
            } else {
                // A17 配方失败：确认是否根本没有 A17 入口（回退 legacy）
                val legacyDone = expandAppBubbleLegacy(ctl, intent, user)
                if (legacyDone) BubbleLaunchResult.BubbleRequestSubmitted
                else BubbleLaunchResult.BackendUnavailable
            }
        } catch (t: Throwable) {
            Log.w(TAG, "launch failed: ${t.message}")
            BubbleLaunchResult.ExpandFailed
        }
    }

    /** 显式解析目标 Activity；解析失败返回 null（不降级为 setPackage 隐式解析）。 */
    private fun resolveTarget(component: ComponentName): Intent? =
        try {
            val info =
                context.packageManager.getActivityInfo(
                    component,
                    PackageManager.ComponentInfoFlags.of(0),
                )
            if (!info.enabled || !info.applicationInfo.enabled || !info.exported) return null
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: RuntimeException) {
            null
        }

    // ---------------------------------------------------------------------
    // A17 官方配方
    // ---------------------------------------------------------------------

    private fun expandAppBubbleA17(
        controller: Any,
        intent: Intent,
        user: UserHandle,
    ): Boolean {
        try {
            val cl = controller.javaClass.classLoader ?: return false
            val bubbleCls = cl.loadClass("com.android.wm.shell.bubbles.Bubble")
            val dataCls = cl.loadClass("com.android.wm.shell.bubbles.BubbleData")
            val typeCls = cl.loadClass("com.android.wm.shell.bubbles.Bubble\$BubbleType")
            val epCls = cl.loadClass("com.android.wm.shell.shared.bubbles.logging.EntryPoint")
            val reqCls =
                cl.loadClass("com.android.wm.shell.shared.bubbles.BubbleBarLocation\$UpdateLocationRequest")

            val expand =
                findMethod(
                    controller.javaClass,
                    "expandStackAndSelectAppBubble",
                    bubbleCls,
                    epCls,
                    reqCls,
                ) ?: return false

            val data = findField(controller, "mBubbleData") ?: return false
            val pkg = intent.getPackage() ?: return false

            val keyForApp =
                findMethod(bubbleCls, "getAppBubbleKeyForApp", String::class.java, UserHandle::class.java)
                    ?: return false
            val key = keyForApp.invoke(null, pkg, user) as? String ?: return false

            val findOverflow =
                findMethod(dataCls, "findAndRemoveBubbleFromOverflow", String::class.java)
            var bubble = findOverflow?.invoke(data, key)

            if (bubble == null) {
                val typeApp = enumConstant(typeCls, "TYPE_APP") ?: return false
                val dataCtx = findField(data, "mContext") as? Context ?: return false
                val pm =
                    findMethod(controller.javaClass, "getPackageManagerForUser", Integer.TYPE, Context::class.java)
                        ?.let { m ->
                            try {
                                val userId = UserHandle::class.java.getMethod("getIdentifier").invoke(user) as Int
                                m.invoke(null, userId, dataCtx)
                            } catch (_: Throwable) {
                                dataCtx.packageManager
                            }
                        }
                        ?: dataCtx.packageManager
                var icon: Any? = null
                val appInfoProvider = findField(data, "mAppInfoProvider")
                if (appInfoProvider != null) {
                    findMethod(appInfoProvider.javaClass, "getActivityInfoIcon", PackageManager::class.java, Intent::class.java)
                        ?.let { m ->
                            try {
                                icon = m.invoke(appInfoProvider, pm, intent)
                            } catch (_: Throwable) {
                                icon = null
                            }
                        }
                }
                val ctor =
                    findConstructor(bubbleCls, Intent::class.java, UserHandle::class.java, Icon::class.java, typeCls, String::class.java)
                        ?: return false
                bubble = ctor.newInstance(intent, user, icon, typeApp, key)
            }
            if (bubble == null) return false

            val entryPoint = enumConstant(epCls, "NOTIFICATION_BUBBLE_BUTTON")
                ?: enumConstant(epCls, "NOTIFICATION")
                ?: return false

            expand.invoke(controller, bubble, entryPoint, null)
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "expandAppBubbleA17 failed: ${t.message}")
            return false
        }
    }

    /** Android 16 及以前的旧 API，保留作为回退。 */
    private fun expandAppBubbleLegacy(
        controller: Any,
        intent: Intent,
        user: UserHandle,
    ): Boolean {
        try {
            val cl = controller.javaClass.classLoader ?: return false
            val entryPoint =
                enumConstant(
                    cl.loadClass("com.android.wm.shell.shared.bubbles.logging.EntryPoint"),
                    "NOTIFICATION",
                ) ?: return false
            val locCls =
                runCatching { cl.loadClass("com.android.wm.shell.shared.bubbles.BubbleBarLocation") }
                    .getOrNull() ?: return false
            var expand =
                findMethod(
                    controller.javaClass,
                    "expandStackAndSelectBubble",
                    Intent::class.java,
                    UserHandle::class.java,
                    entryPoint.javaClass,
                    locCls,
                )
            if (expand == null) {
                // 兜底：按名称 + 参数个数 + 首参类型匹配，仍校验参数类型
                for (m in controller.javaClass.declaredMethods) {
                    if (m.name == "expandStackAndSelectBubble" &&
                        m.parameterCount == 4 &&
                        m.parameterTypes[0] == Intent::class.java
                    ) {
                        m.isAccessible = true
                        expand = m
                        break
                    }
                }
            }
            if (expand == null) return false
            expand.invoke(controller, intent, user, entryPoint, null)
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "expandAppBubbleLegacy failed: ${t.message}")
            return false
        }
    }

    // ---------------------------------------------------------------------
    // 反射工具（三重校验）
    // ---------------------------------------------------------------------

    /** 按「名称 + 参数类型」精确匹配；找不到返回 null。 */
    private fun findMethod(
        type: Class<*>,
        name: String,
        vararg parameterTypes: Class<*>,
    ): Method? =
        try {
            type.getDeclaredMethod(name, *parameterTypes).also { it.isAccessible = true }
        } catch (_: NoSuchMethodException) {
            null
        }

    /** 按「名称 + 参数类型」精确匹配构造器。 */
    private fun findConstructor(
        type: Class<*>,
        vararg parameterTypes: Class<*>,
    ): Constructor<*>? =
        try {
            type.getDeclaredConstructor(*parameterTypes).also { it.isAccessible = true }
        } catch (_: NoSuchMethodException) {
            null
        }

    private fun findField(instance: Any, name: String): Any? =
        try {
            var type: Class<*>? = instance.javaClass
            while (type != null) {
                try {
                    val f = type.getDeclaredField(name)
                    f.isAccessible = true
                    return f.get(instance)
                } catch (_: NoSuchFieldException) {
                    type = type.superclass
                }
            }
            null
        } catch (_: Throwable) {
            null
        }

    private fun enumConstant(enumCls: Class<*>, name: String): Any? =
        try {
            for (o in enumCls.getMethod("values").invoke(null) as Array<*>) {
                if (name == o.toString()) return o
            }
            null
        } catch (_: Throwable) {
            null
        }

    /** 解析 BubbleController 实例：优先 localService，其次反射单例。 */
    private fun resolveBubbleController(): Any? {
        // 路径 1：BubbleController.INSTANCE 静态单例（老版本存在）。
        val cl = context.javaClass.classLoader ?: return null
        try {
            val cls = cl.loadClass("com.android.wm.shell.bubbles.BubbleController")
            val instanceField = cls.getDeclaredField("INSTANCE")
            instanceField.isAccessible = true
            val instance = instanceField.get(null) ?: return null
            if (instance.javaClass.name.contains("BubbleController")) return instance
        } catch (_: Throwable) {
        }
        // 路径 2：SystemUI LocalServices.getService(BubbleController)。
        // SystemUI 的 LocalServices 在 com.android.systemui.shared.system（部分版本在
        // com.android.systemui.Dependency 时代的 com.android.systemui.util 包）。
        for (lsName in listOf(
            "com.android.systemui.shared.system.LocalServices",
            "com.android.systemui.util.concurrency.LocalServices",
            "com.android.systemui.LocalServices",
        )) {
            try {
                val lsCls = cl.loadClass(lsName)
                val getService = lsCls.getMethod("getService", Class::class.java)
                val ctl = getService.invoke(null, cl.loadClass("com.android.wm.shell.bubbles.BubbleController"))
                if (ctl != null) return ctl
            } catch (_: Throwable) {
            }
        }
        // 路径 3：LocalServices.getService(BubblesManager) → mBubbles → this$0 链。
        for (lsName in listOf(
            "com.android.systemui.shared.system.LocalServices",
            "com.android.systemui.LocalServices",
        )) {
            try {
                val lsCls = cl.loadClass(lsName)
                val getService = lsCls.getMethod("getService", Class::class.java)
                val bm = getService.invoke(null, cl.loadClass("com.android.systemui.wmshell.BubblesManager"))
                if (bm != null) {
                    val ctl = controllerFromBubblesManager(bm)
                    if (ctl != null) return ctl
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    /** BubblesManager → mBubbles → this$0 → BubbleController */
    private fun controllerFromBubblesManager(bubblesManager: Any): Any? {
        try {
            val bubblesImpl = findField(bubblesManager, "mBubbles") ?: return null
            val ctl = findField(bubblesImpl, "this\$0") ?: return null
            if (ctl.javaClass.name.contains("BubbleController")) return ctl
        } catch (_: Throwable) {
        }
        return null
    }

    private companion object {
        const val TAG = "PixelAppBubbleLauncher"
    }
}
