package io.github.mangi.flymefreeform.platform.pixel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserHandle
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.mangi.flymefreeform.platform.common.BubbleLaunchRequest
import io.github.mangi.flymefreeform.platform.common.BubbleLaunchResult
import io.github.mangi.flymefreeform.platform.common.GestureBubbleIpcProtocol
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * SystemUI 侧的手势提交接收器（`system_server → SystemUI` 跨进程链路）。
 *
 * ## 线程模型（审查意见 P0-2）
 *
 * 早期的 `PixelAppBubbleLauncher.launch()` 是「投递到 SystemUI 主线程 → 在当前线程
 * `latch.await(2s)` 等待」。如果调用方本身就在主线程（广播 `onReceive` 默认如此），
 * 就会出现**自己等自己**的情况，必然超时。
 *
 * 本类因此做两件事：
 *  1. 接收器绑到独立 HandlerThread（[receiverLooper]），`onReceive` 不在主线程执行；
 *  2. 真正的启动交给单线程 [executor] 串行执行，主线程只负责跑
 *     `BubbleController` 的既有逻辑，不再有等待者占着它。
 *
 * 串行执行同时避免了「两次快速手势 → 并发构造气泡」的竞态。
 */
internal class GestureBubbleIpcReceiver(
    private val context: Context,
    private val module: XposedModule,
) {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "flyme-gesture-bubble-ipc").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    }
    private var registered = false

    /** requestId 去重：ACTION_COMMIT 与 ACTION_COMMIT_FINALIZE 可能同时到达。 */
    private val seen = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                if (action != GestureBubbleIpcProtocol.ACTION_COMMIT &&
                    action != GestureBubbleIpcProtocol.ACTION_COMMIT_FINALIZE
                ) {
                    return
                }
                val component = GestureBubbleIpcProtocol.parseComponent(intent)
                val requestId = intent.getStringExtra(GestureBubbleIpcProtocol.EXTRA_REQUEST_ID)
                if (component == null || requestId == null) {
                    // 无法定位请求时没有可回执的 id，只能记日志
                    log(Log.WARN, "GESTURE_IPC_BAD_REQUEST action=$action")
                    requestId?.let {
                        GestureBubbleIpcProtocol.sendAck(
                            context, it, GestureBubbleIpcProtocol.Outcome.Rejected, "bad-request",
                        )
                    }
                    return
                }
                val userId = intent.getIntExtra(GestureBubbleIpcProtocol.EXTRA_USER_ID, 0)
                val source = intent.getStringExtra(GestureBubbleIpcProtocol.EXTRA_SOURCE) ?: "gesture-ipc"
                val now = System.currentTimeMillis()
                // 顺手清掉 60s 前的记录，避免无限增长
                if (seen.size > 32) seen.values.removeIf { now - it > 60_000L }
                if (seen.putIfAbsent(requestId, now) != null) return
                log(Log.INFO, "GESTURE_IPC_RECEIVED id=$requestId target=${component.flattenToShortString()} source=$source")
                executor.execute { run(requestId, component, userId, source) }
            }
        }

    private fun run(
        requestId: String,
        component: android.content.ComponentName,
        userId: Int,
        source: String,
    ) {
        val outcome =
            try {
                val launcher = PixelBubbleBackend.get(context) { message -> log(Log.INFO, "BUBBLE_DIAG $message") }
                val result =
                    launcher.launch(
                        BubbleLaunchRequest(
                            component = component,
                            userHandle = UserHandle.getUserHandleForUid(userId),
                            source = source,
                        ),
                    )
                log(Log.INFO, "GESTURE_IPC_RESULT id=$requestId result=$result")
                when (result) {
                    // 成功提交 → 视为受理（不代表气泡已可见，见协议注释）
                    BubbleLaunchResult.BubbleRequestSubmitted -> GestureBubbleIpcProtocol.Outcome.Accepted
                    // 明确失败 → 允许 system_server 回退原生启动
                    BubbleLaunchResult.RequestValidationFailed,
                    BubbleLaunchResult.TargetResolutionFailed,
                    BubbleLaunchResult.BackendUnavailable,
                    -> GestureBubbleIpcProtocol.Outcome.Rejected
                    // 其余（超时 / 结果未知 / 创建失败）一律不回退，避免重复开窗
                    BubbleLaunchResult.SubmissionTimeout,
                    BubbleLaunchResult.BubbleCreateFailed,
                    BubbleLaunchResult.TaskLaunchFailed,
                    BubbleLaunchResult.ExpandFailed,
                    BubbleLaunchResult.ResultUnknown,
                    BubbleLaunchResult.FallbackLaunch,
                    is BubbleLaunchResult.Failed,
                    -> GestureBubbleIpcProtocol.Outcome.Unknown
                }
            } catch (t: Throwable) {
                log(Log.WARN, "GESTURE_IPC_CRASHED id=$requestId ${t.javaClass.simpleName}: ${t.message}")
                GestureBubbleIpcProtocol.Outcome.Unknown
            }
        GestureBubbleIpcProtocol.sendAck(context, requestId, outcome)
    }

    fun start() {
        if (registered) return
        registered = true
        try {
            // 独立 HandlerThread：onReceive 不占 SystemUI 主线程
            val looper = android.os.HandlerThread("flyme-gesture-bubble-rx").apply { start() }.looper
            context.registerReceiver(receiver, IntentFilter().apply {
                addAction(GestureBubbleIpcProtocol.ACTION_COMMIT)
                addAction(GestureBubbleIpcProtocol.ACTION_COMMIT_FINALIZE)
            }, null, android.os.Handler(looper))
            log(Log.INFO, "GESTURE_IPC_RECEIVER_READY")
        } catch (t: Throwable) {
            log(Log.WARN, "GESTURE_IPC_RECEIVER_FAILED ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun log(priority: Int, message: String) {
        module.log(priority, TAG, message)
    }

    private companion object {
        const val TAG = "FlymeFreeform"
    }
}
