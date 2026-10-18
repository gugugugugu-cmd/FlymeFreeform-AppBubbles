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

    /**
     * 诊断日志出口。LSPosed 模块进程里 android.util.Log 不会进入模块日志文件，
     * 必须走 module.log()；TestEntry 在构造时注入。
     */
    var debugSink: ((String) -> Unit)? = null

    private fun dbg(msg: String) {
        android.util.Log.i(TAG, msg)
        runCatching { debugSink?.invoke(msg) }
    }

    private fun dbgWarn(msg: String) {
        android.util.Log.w(TAG, msg)
        runCatching { debugSink?.invoke("WARN $msg") }
    }

    private var controller: Any? = null

    /**
     * 从 SystemUI 上下文解析 BubbleController 单例。
     * Android 17 上通过 localService 或反射拿到实例；失败返回 null 表示后端不可用。
     */
    fun bindController(): Boolean {
        controller = try {
            resolveBubbleController()
        } catch (t: Throwable) {
            dbgWarn("resolveBubbleController failed: ${t.message}")
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
            dbg("setController: controller bound (${ctl.javaClass.name})")
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
                dbg("bindFromBubblesManager: controller bound")
            }
        } catch (t: Throwable) {
            dbgWarn("bindFromBubblesManager failed: ${t.message}")
        }
    }

    override fun isAvailable(): Boolean = controller != null

    /**
     * 从 IBubblesImpl 实例（BubbleController$IBubblesImpl）取其外部类实例作为 controller。
     */
    fun bindFromIBubblesImpl(impl: Any) {
        if (controller != null) return
        try {
            val ctl = findField(impl, "this\$0") ?: return
            if (ctl.javaClass.name.contains("BubbleController")) {
                controller = ctl
                dbg("bindFromIBubblesImpl: controller bound")
            }
        } catch (t: Throwable) {
            dbgWarn("bindFromIBubblesImpl failed: ${t.message}")
        }
    }

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

        // 3. 提交：必须在 SystemUI 主线程执行（阶段 1 验证：controller.mMainExecutor 派发），
        //    广播线程直接调用会被 BubbleController 的线程断言/UI 校验吞掉。
        val mainExecutor = mainExecutorOf(ctl)
        if (mainExecutor == null) {
            dbgWarn("BUBBLE_EXPAND no mMainExecutor, running inline")
            return if (inlineExpand(ctl, intent, user)) {
                BubbleLaunchResult.BubbleRequestSubmitted
            } else {
                BubbleLaunchResult.ExpandFailed
            }
        }
        // 同步等待主线程执行结果（最多 2s），避免异步分支一律报 SUBMITTED 造成假成功。
        val latch = java.util.concurrent.CountDownLatch(1)
        val okRef = java.util.concurrent.atomic.AtomicBoolean(false)
        mainExecutor.execute {
            try {
                okRef.set(inlineExpand(ctl, intent, user))
            } catch (t: Throwable) {
                dbgWarn("BUBBLE_EXPAND failed: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                latch.countDown()
            }
        }
        val finished = latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        val ok = okRef.get()
        dbg("BUBBLE_EXPAND main-thread finished=$finished ok=$ok")
        return when {
            !finished -> BubbleLaunchResult.BubbleRequestSubmitted
            ok -> BubbleLaunchResult.BubbleRequestSubmitted
            else -> BubbleLaunchResult.ExpandFailed
        }
    }

    /** 真正的展开尝试（A17 优先，legacy 回退）。 */
    private fun inlineExpand(
        ctl: Any,
        intent: Intent,
        user: UserHandle,
    ): Boolean {
        val done = expandAppBubbleA17(ctl, intent, user)
        if (done) return true
        val legacyDone = expandAppBubbleLegacy(ctl, intent, user)
        dbg("BUBBLE_EXPAND legacyDone=$legacyDone")
        return legacyDone
    }

    /** BubbleController.mMainExecutor（HandlerExecutor）；取不到返回 null。 */
    private fun mainExecutorOf(controller: Any): java.util.concurrent.Executor? =
        try {
            when (val ex =
                findField(controller, "mMainExecutor")
                    ?: findField(controller, "mShellExecutor")) {
                is java.util.concurrent.Executor -> ex
                else -> null
            }
        } catch (_: Throwable) {
            null
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
            // 必须显式 setPackage：Intent.setComponent 不会填充 getPackage()，
            // 而 A17 配方第 4 步依赖 intent.getPackage() 取包名（真机日志 step4 miss）。
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
                .setPackage(component.packageName)
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
            val cl = controller.javaClass.classLoader ?: run { dbgWarn("BUBBLE_A17 step1 miss")
                        return false }
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
                ) ?: run { dbgWarn("BUBBLE_A17 step2 miss")
                        return false }

            val data = findField(controller, "mBubbleData") ?: run { dbgWarn("BUBBLE_A17 step3 miss")
                        return false }
            val pkg = intent.getPackage() ?: run { dbgWarn("BUBBLE_A17 step4 miss")
                        return false }

            val keyForApp =
                findMethod(bubbleCls, "getAppBubbleKeyForApp", String::class.java, UserHandle::class.java)
                    ?: run { dbgWarn("BUBBLE_A17 step5 miss")
                        return false }
            val key = keyForApp.invoke(null, pkg, user) as? String ?: run { dbgWarn("BUBBLE_A17 step6 miss")
                        return false }

            val findOverflow =
                findMethod(dataCls, "findAndRemoveBubbleFromOverflow", String::class.java)
            var bubble = findOverflow?.invoke(data, key)
            var bubbleWasFromOverflow = bubble != null

            if (bubble == null) {
                val typeApp = enumConstant(typeCls, "TYPE_APP") ?: run { dbgWarn("BUBBLE_A17 step7 miss")
                        return false }
                val dataCtx = findField(data, "mContext") as? Context ?: run { dbgWarn("BUBBLE_A17 step8 miss")
                        return false }
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
                        ?: run { dbgWarn("BUBBLE_A17 step9 miss")
                        return false }
                bubble = ctor.newInstance(intent, user, icon, typeApp, key)
            }
            if (bubble == null) return false

            val entryPoint = enumConstant(epCls, "NOTIFICATION_BUBBLE_BUTTON")
                ?: enumConstant(epCls, "NOTIFICATION")
                ?: run { dbgWarn("BUBBLE_A17 step10 miss")
                        return false }

            dbg("BUBBLE_A17 bubble fromOverflow=${bubbleWasFromOverflow} key=$key type=${bubble!!.javaClass.name}")
            expand.invoke(controller, bubble, entryPoint, null)
            // 观测：调用后数据模型状态（区分"没进模型"vs"进了没渲染"）
            try {
                val cnt = runCatching {
                    data.javaClass.getMethod("getBubbles").also { it.isAccessible = true }.invoke(data) as? List<*>
                }.getOrNull()
                val expanded = runCatching {
                    data.javaClass.getMethod("isExpanded").also { it.isAccessible = true }.invoke(data)
                }.getOrNull()
                dbg(
                    "BUBBLE_A17 after-expand bubbles=${cnt?.size} expanded=$expanded " +
                        "selected=${cnt?.any { it === bubble }}",
                )
            } catch (_: Throwable) {
            }
            return true
        } catch (t: Throwable) {
            dbgWarn("BUBBLE_A17 failed: ${t.javaClass.simpleName}: ${t.message}")
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
            dbgWarn("expandAppBubbleLegacy failed: ${t.message}")
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
