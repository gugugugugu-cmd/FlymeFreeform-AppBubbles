package io.github.mangi.flymefreeform.platform.pixel

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
        launcher.bindController()
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
                    val request =
                        BubbleLaunchRequest(
                            component = component,
                            userHandle = UserHandle.of(userId),
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
        context.registerReceiver(receiver, filter)
        module.log(Log.INFO, TAG, "PIXEL_BUBBLE_TEST_ENTRY_READY launcherAvailable=${launcher.isAvailable()}")
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
