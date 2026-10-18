package io.github.mangi.flymefreeform.platform.pixel

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.UserHandle
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.mangi.flymefreeform.platform.common.BubbleLaunchRequest
import io.github.mangi.flymefreeform.platform.common.BubbleLaunchResult

/**
 * 阶段 2 开发测试入口（不改手势 UI）。
 *
 * 在 SystemUI 进程动态注册广播接收器，收到 [ACTION_TEST_LAUNCH_BUBBLE] 后
 * 从 extra 解析目标组件，构造 [BubbleLaunchRequest] 提交给 [PixelAppBubbleLauncher]，
 * 并把结果以 [BubbleLaunchResult] 状态 + 诊断日志形式暴露。
 *
 * 触发方式（外部 adb / 模块 App）：
 *   adb shell am broadcast -a io.github.mangi.flymefreeform.action.TEST_LAUNCH_BUBBLE \
 *       --es component "com.tencent.mm/com.tencent.mm.ui.LauncherUI" \
 *       --el user_id 0 \
 *       --es source "test-entry"
 *
 * 结果读取：Logcat 关键字 `BUBBLE_TEST_RESULT`（状态码）与 `BUBBLE_TEST_DIAG`（诊断）。
 */
internal class PixelBubbleTestEntry(
    private val context: Context,
    private val module: XposedModule,
) {
    private val launcher = PixelAppBubbleLauncher(context)
    private var bound = false
    private var receiver: BroadcastReceiver? = null

    fun start() {
        if (bound) return
        bound = true
        // 绑定 BubbleController：
        // 1) 先试主动反射（INSTANCE 单例 / LocalServices）
        // 2) hook BubblesManager 构造器 + 事件回调兜底（阶段 1 验证过的
        //    BubblesManager.mBubbles → this$0 → BubbleController 链）
        launcher.bindController()
        installBubblesManagerCapture()
        val filter =
            IntentFilter().apply {
                addAction(ACTION_TEST_LAUNCH_BUBBLE)
            }
        receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != ACTION_TEST_LAUNCH_BUBBLE) return
                    val component =
                        intent.getStringExtra(EXTRA_COMPONENT)
                            ?.let(ComponentName::unflattenFromString)
                    if (component == null) {
                        module.log(Log.WARN, TAG, "BUBBLE_TEST_RESULT REQUEST_VALIDATION_FAILED no-component")
                        return
                    }
                    val userId = intent.getLongExtra(EXTRA_USER_ID, 0L).toInt()
                    val source = intent.getStringExtra(EXTRA_SOURCE) ?: "test-entry"
                    // UserHandle.of(int) 在 SDK 37 为隐藏 API，用反射构造。
                    val userHandle = runCatching {
                        UserHandle::class.java.getMethod("of", Integer.TYPE)
                            .invoke(null, userId) as UserHandle
                    }.getOrNull()
                    if (userHandle == null) {
                        module.log(Log.WARN, TAG, "PIXEL_BUBBLE_TEST_USERHANDLE_REFLECT_FAILED", null)
                        return
                    }
                    val request =
                        BubbleLaunchRequest(
                            component = component,
                            userHandle = userHandle,
                            source = source,
                        )
                    val result = launcher.launch(request)
                    module.log(
                        Log.INFO,
                        TAG,
                        "BUBBLE_TEST_RESULT ${resultStateName(result)} component=$component user=$userId",
                    )
                }
            }
        // Android 14+（API 34+）：动态注册非系统广播必须显式指定 exported 标志。
        // 测试入口需要接收 adb / 外部进程广播，故用 RECEIVER_EXPORTED。
        if (Build.VERSION.SDK_INT >= 34) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        module.log(Log.INFO, TAG, "PIXEL_BUBBLE_TEST_ENTRY_READY launcherAvailable=${launcher.isAvailable()}")
    }

    /**
     * hook BubblesManager 以捕获 BubbleController 实例。
     *
     * A17 上 BubbleController 没有公开静态单例，阶段 1（MoreBubbleButton）验证的
     * 可靠路径是事件链：BubblesManager.mBubbles (IBubblesImpl) → this$0 → BubbleController。
     * 这里 hook 两个必然经过的点：
     *   - BubblesManager 构造函数（SystemUI 启动即创建，最早拿到实例）
     *   - BubblesManager.onUserChangedBubble(NotificationEntry, boolean)（阶段 1 已证存在）
     * 任一命中即调用 launcher.bindFromBubblesManager() 完成绑定。
     */
    private fun installBubblesManagerCapture() {
        try {
            val cl = context.javaClass.classLoader ?: return
            val bubblesCls = cl.loadClass("com.android.systemui.wmshell.BubblesManager")
            // 构造器捕获（任意签名，命中即绑定）
            for (ctor in bubblesCls.declaredConstructors) {
                try {
                    module
                        .hook(ctor)
                        .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("flymefreeform.bubbles_manager_ctor")
                        .intercept { chain ->
                            val result = chain.proceed()
                            try {
                                // 构造器无 this，用返回值（新实例）绑定
                                launcher.bindFromBubblesManager(result)
                            } catch (_: Throwable) {
                            }
                            result
                        }
                    module.log(Log.INFO, TAG, "PIXEL_BUBBLE_BUBBLES_MANAGER_CTOR_HOOKED")
                    break
                } catch (_: Throwable) {
                }
            }
            // 事件回调捕获（兜底）
            for (m in bubblesCls.declaredMethods) {
                val isUserChanged =
                    m.name == "onUserChangedBubble" &&
                        m.parameterCount == 2 &&
                        m.parameterTypes[0].name.contains("NotificationEntry") &&
                        m.parameterTypes[1] == java.lang.Boolean.TYPE
                val isExpand =
                    m.name == "expandStackAndSelectBubble" &&
                        m.parameterCount == 1 &&
                        m.parameterTypes[0].name.contains("NotificationEntry")
                if (!isUserChanged && !isExpand) continue
                try {
                    module
                        .hook(m)
                        .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("flymefreeform.bubbles_manager_capture_" + m.name)
                        .intercept { chain ->
                            val result = chain.proceed()
                            try {
                                launcher.bindFromBubblesManager(chain.thisObject)
                            } catch (_: Throwable) {
                            }
                            result
                        }
                    module.log(Log.INFO, TAG, "PIXEL_BUBBLE_BUBBLES_MANAGER_EVENT_HOOKED ${m.name}")
                } catch (_: Throwable) {
                }
            }
        } catch (t: Throwable) {
            module.log(Log.WARN, TAG, "PIXEL_BUBBLE_BUBBLES_MANAGER_HOOK_FAILED ${t.message}")
        }
    }

    private fun resultStateName(result: BubbleLaunchResult): String =
        when (result) {
            BubbleLaunchResult.RequestValidationFailed -> "REQUEST_VALIDATION_FAILED"
            BubbleLaunchResult.TargetResolutionFailed -> "TARGET_RESOLUTION_FAILED"
            BubbleLaunchResult.BackendUnavailable -> "BACKEND_UNAVAILABLE"
            BubbleLaunchResult.BubbleRequestSubmitted -> "BUBBLE_REQUEST_SUBMITTED"
            BubbleLaunchResult.BubbleCreateFailed -> "BUBBLE_CREATE_FAILED"
            BubbleLaunchResult.TaskLaunchFailed -> "TASK_LAUNCH_FAILED"
            BubbleLaunchResult.ExpandFailed -> "EXPAND_FAILED"
            BubbleLaunchResult.ResultUnknown -> "RESULT_UNKNOWN"
            BubbleLaunchResult.FallbackLaunch -> "FALLBACK_LAUNCH"
            is BubbleLaunchResult.Failed -> "FAILED_${result.diagnosticCode}"
        }

    companion object {
        const val ACTION_TEST_LAUNCH_BUBBLE = "io.github.mangi.flymefreeform.action.TEST_LAUNCH_BUBBLE"
        const val EXTRA_COMPONENT = "component"
        const val EXTRA_USER_ID = "user_id"
        const val EXTRA_SOURCE = "source"
        private const val TAG = "FlymeFreeform"
    }
}
