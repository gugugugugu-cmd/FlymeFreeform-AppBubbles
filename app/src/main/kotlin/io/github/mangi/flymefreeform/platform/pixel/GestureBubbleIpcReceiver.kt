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
                // 先打到达性影子日志：能走到这一行，就说明这条 action 的分发已经
                // 通过 requiredPermission 校验。放在 action 过滤之前是有意的——
                // 探针 action 也要留下到达记录。
                shadow(
                    "IPC_ARRIVED action=$action id=${intent.getStringExtra(GestureBubbleIpcProtocol.EXTRA_REQUEST_ID)} " +
                        "uid=${sentFromUidOrUnknown(this)}",
                )
                if (action == PROBE_ACTION) {
                    log(Log.INFO, "SECURITY_PROBE_REACHED 权限分发校验通过（system_server 侧）")
                    return
                }
                if (action != GestureBubbleIpcProtocol.ACTION_COMMIT &&
                    action != GestureBubbleIpcProtocol.ACTION_COMMIT_FINALIZE
                ) {
                    return
                }
                // 来源可信性由注册时的 requiredPermission 保证：只有持有
                // STATUS_BAR_SERVICE 的进程能发进来，普通应用广播在分发阶段就被拦掉，
                // 根本不会进入本方法。
                //
                // 曾在此处用 getSentFromUid() 二次校验，并对 !=0/1000 一律拒绝——
                // 真机实测该 API 对普通 sendBroadcast 恒返回 -1（发送者信息只在
                // 携带 PendingIntent 转发时才填充），导致 system_server 自己发的
                // 合法请求也被判 untrusted-sender 全量拒绝，气泡永远起不来。
                // 权限限定是框架级校验，比事后读 uid 更强且可靠。
                log(
                    Log.INFO,
                    "GESTURE_IPC_RECEIVED action=$action uid=${sentFromUidOrUnknown(this)}",
                )
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
                // 安全验证夹具的 request_id 带固定前缀，单独打一条：内容仍是 INFO 级
                // 正常日志，只是便于在导出日志里 grep，不需要额外的日志通道。
                if (requestId.startsWith("forge-security-probe-")) {
                    log(
                        Log.WARN,
                        "SECURITY_PROBE_PASSED id=$requestId target=${component.flattenToShortString()} " +
                            "=== 广播穿透了权限限定，GESTURE_IPC 鉴权失效 ===",
                    )
                }
                if (seen.putIfAbsent(requestId, now) != null) return
                log(Log.INFO, "GESTURE_IPC_RECEIVED id=$requestId target=${component.flattenToShortString()} source=$source")
                executor.execute { run(requestId, component, userId, source) }
            }
        }

    /**
     * 仅用于日志的发送方 uid。
     *
     * 普通 `sendBroadcast` 不会填充 `mSentFromUid`，因此这里恒得 -1，
     * **不可用于鉴权**。鉴权一律由注册时的 [GestureBubbleIpcProtocol.PERMISSION_SEND]
     * 在分发阶段完成。
     */
    private fun sentFromUidOrUnknown(receiver: BroadcastReceiver): Int =
        try {
            BroadcastReceiver::class.java.getMethod("getSentFromUid").invoke(receiver) as Int
        } catch (_: Throwable) {
            -1
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

    /**
     * 注册接收器。
     *
     * 审查意见 P1：旧实现先置 `registered = true` 再注册，注册抛异常后永不重试。
     * 现在只在成功后置位；失败时记录并允许下次调用重新尝试。
     *
     * @return true 表示已注册（本次或此前）。
     */
    @Synchronized
    fun start(): Boolean {
        if (registered) return true
        try {
            // 独立 HandlerThread：onReceive 不占 SystemUI 主线程
            val looper = android.os.HandlerThread("flyme-gesture-bubble-rx").apply { start() }.looper
            val filter =
                IntentFilter().apply {
                    addAction(GestureBubbleIpcProtocol.ACTION_COMMIT)
                    addAction(GestureBubbleIpcProtocol.ACTION_COMMIT_FINALIZE)
                    // 安全验证入口：与真实指令同权限、同接收器，只多打一条到达记录。
                    // 不需要它是独立接收器——共用这条链路才说明“注册链路本身”是通的。
                    addAction(PROBE_ACTION)
                }
            // API 34+ 动态注册自定义广播必须显式声明导出状态，否则抛 SecurityException
            // （真机日志 GESTURE_IPC_RECEIVER_FAILED 即此原因）。
            // 导出是必需的（发送方是 system_server，不同 UID），
            // 来源可信性由下面 requiredPermission 的框架级权限校验保证，
            // **不是**由 onReceive 内的 getSentFromUid 保证（该 API 对普通广播恒为 -1）。
            val handler = android.os.Handler(looper)
            // 5 参重载 registerReceiver(receiver, filter, permission, scheduler, flags)
            // 同时满足：绑定独立线程 + 显式导出状态（签名经 android.jar javap 核对）
            // 发送方权限限定：只有持有 STATUS_BAR_SERVICE 的进程（system_server，
            // 以及同样持该权限的 SystemUI 自身）能发进来。
            // 这样鉴权由框架在分发时完成，不再依赖 getSentFromUid()——
            // 后者对普通广播恒返回 -1（真机日志 senderUid=-1 即此原因），
            // 因为它只对携带 PendingIntent 发送者信息的广播有效。
            val requiredPermission = GestureBubbleIpcProtocol.PERMISSION_SEND
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(
                    receiver,
                    filter,
                    requiredPermission,
                    handler,
                    android.content.Context.RECEIVER_EXPORTED,
                )
            } else {
                context.registerReceiver(receiver, filter, requiredPermission, handler)
            }
            registered = true
            log(Log.INFO, "GESTURE_IPC_RECEIVER_READY")
            return true
        } catch (t: Throwable) {
            log(Log.WARN, "GESTURE_IPC_RECEIVER_FAILED ${t.javaClass.simpleName}: ${t.message}（下次将重试）")
            return false
        }
    }

    private fun log(priority: Int, message: String) {
        module.log(priority, TAG, message)
    }

    /**
     * 影子日志：同时写 LSPosed 模块日志与系统 logcat。
     *
     * `XposedModule.log()` 只进 LSPosed 自己的日志文件，**不走 logcat**，
     * 所以任何“广播到底有没有到达 onReceive”的排查都被锁在导出日志里。
     * 这个入口只用于证明到达性，不进业务链路。
     */
    private fun shadow(message: String) {
        runCatching { android.util.Log.i(SHADOW_TAG, message) }
        log(Log.INFO, message)
    }

    private companion object {
        const val TAG = "FlymeFreeform"
        const val SHADOW_TAG = "FlymeFreeformIpc"

        /** 只有持有 STATUS_BAR_SERVICE 的进程（system_server / SystemUI）能触发。 */
        const val PROBE_ACTION = "io.github.mangi.flymefreeform.action.SECURITY_PROBE"
    }
}
