package io.github.mangi.flymefreeform.platform.common

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * `system_server → SystemUI` 的手势提交通道（阶段 2 收尾）。
 *
 * ## 为什么必须跨进程
 *
 * 手势协调器 [io.github.mangi.flymefreeform.platform.coloros.ColorOsFreeformCoordinator]
 * 与 Pixel 气泡后端不在同一进程：
 *  - 协调器：system_server
 *  - `BubbleController` / `PixelAppBubbleLauncher`：SystemUI
 *
 * ## 全异步（审查意见 P0）
 *
 * 早期版本在调用线程上阻塞等待 ACK。协调器的 `onAppCommitted` 跑在
 * **system_server 主线程**（`Handler(Looper.getMainLooper())`），阻塞 3 秒
 * 等于冻结整个系统的主线程调度。
 *
 * 因此本通道**没有任何阻塞等待**：
 *  - 请求发出后立即返回；
 *  - 结果通过 [GestureBubbleIpcClient.submit] 的回调在后台线程送达；
 *  - 超时由独立的调度线程判定。
 *
 * ## 结果语义
 *
 * 提交结果用 [Outcome] 三分：
 *  - [Outcome.Accepted]：SystemUI 确认请求已交给 `BubbleController`
 *  - [Outcome.Rejected]：明确失败，可安全回退原生启动
 *  - [Outcome.Unknown]：超时 / 通道不可用，**不得**重试或回退，避免重复开窗
 *
 * [Outcome.Accepted] 也不等于「气泡已显示并可展开」——后者需观察
 * `BubbleController.mBubbles` 状态，属于实机验收项。
 */
internal object GestureBubbleIpcProtocol {

    /** 手势提交请求（system_server → SystemUI）。 */
    const val ACTION_COMMIT = "io.github.mangi.flymefreeform.action.GESTURE_COMMIT"

    /**
     * 同一请求的 `finalize` 变体：顺序投递兜底，接收端按 requestId 幂等。
     */
    const val ACTION_COMMIT_FINALIZE = "io.github.mangi.flymefreeform.action.GESTURE_COMMIT_FINALIZE"

    /** 回执（SystemUI → system_server），定向发送。 */
    const val ACTION_COMMIT_ACK = "io.github.mangi.flymefreeform.action.GESTURE_COMMIT_ACK"

    /** 回执接收器所在包：system_server 的包名恒为 "android"。 */
    const val SYSTEM_SERVER_PACKAGE = "android"

    /** SystemUI 包名。 */
    const val SYSTEM_UI_PACKAGE = "com.android.systemui"

    const val EXTRA_COMPONENT = "component"
    const val EXTRA_USER_ID = "user_id"
    const val EXTRA_SOURCE = "source"
    const val EXTRA_REQUEST_ID = "request_id"
    const val EXTRA_OUTCOME = "outcome"
    const val EXTRA_DIAGNOSTIC = "diagnostic"

    /** 提交结果三分语义。 */
    enum class Outcome {
        /** 请求已交给 BubbleController（不代表气泡已可见）。 */
        Accepted,

        /** 明确失败，调用方可安全回退。 */
        Rejected,

        /** 结果未知（通道不可用 / 超时），调用方不得回退也不得重试。 */
        Unknown,
        ;

        companion object {
            fun parse(raw: String?): Outcome =
                entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: Unknown
        }
    }

    fun buildRequestIntent(
        component: ComponentName,
        userId: Int,
        requestId: String,
        source: String,
    ): Intent =
        Intent(ACTION_COMMIT)
            .putExtra(EXTRA_COMPONENT, component.flattenToString())
            .putExtra(EXTRA_USER_ID, userId)
            .putExtra(EXTRA_REQUEST_ID, requestId)
            .putExtra(EXTRA_SOURCE, source)

    fun parseComponent(intent: Intent): ComponentName? =
        intent.getStringExtra(EXTRA_COMPONENT)?.let(ComponentName::unflattenFromString)

    /** 定向发送到 system_server，携带三态结果。 */
    fun sendAck(
        context: Context,
        requestId: String,
        outcome: Outcome,
        diagnostic: String? = null,
    ) {
        val intent =
            Intent(ACTION_COMMIT_ACK)
                .setPackage(SYSTEM_SERVER_PACKAGE)
                .putExtra(EXTRA_REQUEST_ID, requestId)
                .putExtra(EXTRA_OUTCOME, outcome.name)
                .putExtra(EXTRA_DIAGNOSTIC, diagnostic)
        runCatching { context.sendBroadcast(intent) }
            .onFailure { Log.w(TAG, "ack send failed: ${it.javaClass.simpleName}", it) }
    }

    /** 解析回执。 */
    fun readAck(intent: Intent): Triple<String, Outcome, String?>? {
        val id = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return null
        return Triple(
            id,
            Outcome.parse(intent.getStringExtra(EXTRA_OUTCOME)),
            intent.getStringExtra(EXTRA_DIAGNOSTIC),
        )
    }

    /** 保留给以后可能出现的 binder 化实现。 */
    @Suppress("unused")
    fun bundleOf(component: ComponentName, userId: Int): Bundle =
        Bundle().apply {
            putString(EXTRA_COMPONENT, component.flattenToString())
            putInt(EXTRA_USER_ID, userId)
        }

    private const val TAG = "GestureBubbleIpc"
}

/**
 * system_server 侧的手势提交客户端（**全异步**）。
 *
 * - [commit]（[AppLaunchCommitter] 接口）：只发不等，返回 true 表示请求已发出；
 *   用于无需区分回退的场景。
 * - [submit]：发请求 + 结果回调，回调可能来自 Binder/主线程之外的后台线程，
 *   调用方应自行切回需要的线程。返回的 [Handle] 可主动取消。
 *
 * 回执接收器注册失败后会**重试**（见 [ensureReceiver]），失败有独立诊断码。
 */
internal class GestureBubbleIpcClient(
    private val context: Context,
    logger: (priority: Int, code: String, error: Throwable?) -> Unit,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : AppLaunchCommitter {

    private val logger: (priority: Int, code: String, error: Throwable?) -> Unit = logger

    /** requestId → 待决请求（含超时任务）。 */
    private val pending = ConcurrentHashMap<String, Pending>()

    /** 超时调度：独立单线程，不占用任何主线程。 */
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "flyme-gesture-ipc-timeout").apply { isDaemon = true }
        }

    @Volatile
    private var receiverRegistered = false

    /**
     * 注册回执接收器。
     *
     * 审查意见 P1：旧实现先置 `true` 再注册，注册失败后永不重试，所有请求都会超时。
     * 现在只在**注册成功后**才置位；失败时记录诊断码，下次调用会重新尝试。
     */
    private fun ensureReceiver(): Boolean {
        if (receiverRegistered) return true
        synchronized(this) {
            if (receiverRegistered) return true
            return try {
                val ackReceiver =
                    object : BroadcastReceiver() {
                        override fun onReceive(ctx: Context?, intent: Intent?) {
                            // 回执同样要验来源：否则任意应用伪造 Accepted 即可欺骗协调器
                            val senderUid = sentFromUidOrUnknown(this)
                            if (!isTrustedAckSender(this, senderUid)) {
                                logger(
                                    Log.WARN,
                                    "GESTURE_IPC_ACK_REJECTED senderUid=$senderUid",
                                    null,
                                )
                                return
                            }
                            val parsed = intent?.let(GestureBubbleIpcProtocol::readAck) ?: return
                            val (id, outcome, diagnostic) = parsed
                            logger(
                                Log.INFO,
                                "GESTURE_IPC_ACK id=$id outcome=${outcome.name} diag=$diagnostic",
                                null,
                            )
                            pending.remove(id)?.deliver(outcome)
                        }
                    }
                val filter = IntentFilter(GestureBubbleIpcProtocol.ACTION_COMMIT_ACK)
                // 审查意见：自定义广播接收器必须显式声明导出状态（API 34+），
                // 否则注册抛 SecurityException → submit() 直接判 Unknown，链路全断。
                // 必须导出：回执来自 SystemUI（不同 UID）。
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    context.registerReceiver(
                        ackReceiver,
                        filter,
                        Context.RECEIVER_EXPORTED,
                    )
                } else {
                    context.registerReceiver(ackReceiver, filter)
                }
                receiverRegistered = true
                logger(Log.INFO, "GESTURE_IPC_ACK_RECEIVER_READY", null)
                true
            } catch (t: Throwable) {
                logger(Log.WARN, "GESTURE_IPC_ACK_RECEIVER_FAILED ${t.javaClass.simpleName}: ${t.message}", t)
                false
            }
        }
    }

    /** 读取广播发送方 uid；API 34+ 方法名经 android.jar javap 核对为 getSentFromUid()。 */
    private fun sentFromUidOrUnknown(receiver: BroadcastReceiver): Int =
        try {
            BroadcastReceiver::class.java.getMethod("getSentFromUid").invoke(receiver) as Int
        } catch (_: Throwable) {
            -1
        }

    /**
     * 回执发送方可信性：只接受 SystemUI 与 root。
     *
     * SystemUI 的 uid 是 1000(system)；其包名由 getSentFromPackage() 提供。
     * 取不到 uid（-1）一律拒绝。
     */
    private fun isTrustedAckSender(receiver: BroadcastReceiver, uid: Int): Boolean {
        if (uid != android.os.Process.SYSTEM_UID && uid != 0) return false
        val pkg =
            try {
                BroadcastReceiver::class.java.getMethod("getSentFromPackage").invoke(receiver) as? String
            } catch (_: Throwable) {
                null
            }
        return pkg == null || pkg == "com.android.systemui" || pkg == GestureBubbleIpcProtocol.SYSTEM_SERVER_PACKAGE
    }

    /** [AppLaunchCommitter] 实现：只发不等。 */
    override fun commit(component: ComponentName): Boolean {
        val id = newRequestId(component)
        return send(component, 0, id)
    }

    /**
     * 异步提交。
     *
     * @param onResult 结果回调（可能在后台线程）；超时会以 [GestureBubbleIpcProtocol.Outcome.Unknown] 回调。
     * @return 句柄，可调用 [Handle.cancel] 放弃等待（不影响已发出的请求）。
     */
    fun submit(
        component: ComponentName,
        userId: Int = 0,
        onResult: (GestureBubbleIpcProtocol.Outcome) -> Unit,
    ): Handle {
        val requestId = newRequestId(component)

        if (!ensureReceiver()) {
            // 收不到回执，直接判未知，并明确告知调用方（不回退、不重试）
            logger(Log.WARN, "GESTURE_IPC_NO_ACK_CHANNEL id=$requestId", null)
            onResult(GestureBubbleIpcProtocol.Outcome.Unknown)
            return Handle(requestId)
        }

        val entry = Pending(onResult)
        pending[requestId] = entry
        // 超时任务：独立调度线程，绝不阻塞调用线程
        entry.timeout =
            scheduler.schedule(
                {
                    val timedOut = pending.remove(requestId)
                    if (timedOut != null) {
                        logger(Log.WARN, "GESTURE_IPC_TIMEOUT id=$requestId", null)
                        timedOut.deliver(GestureBubbleIpcProtocol.Outcome.Unknown)
                    }
                },
                timeoutMillis,
                TimeUnit.MILLISECONDS,
            )

        if (!send(component, userId, requestId)) {
            pending.remove(requestId)?.let { failed ->
                failed.timeout?.cancel(false)
                failed.deliver(GestureBubbleIpcProtocol.Outcome.Unknown)
            }
        }
        return Handle(requestId)
    }

    private fun send(component: ComponentName, userId: Int, requestId: String): Boolean =
        try {
            val intent =
                GestureBubbleIpcProtocol.buildRequestIntent(
                    component = component,
                    userId = userId,
                    requestId = requestId,
                    source = "radial-gesture",
                )
            intent.setPackage(GestureBubbleIpcProtocol.SYSTEM_UI_PACKAGE)
            context.sendBroadcast(intent)
            logger(Log.INFO, "GESTURE_IPC_SENT id=$requestId target=${component.flattenToShortString()}", null)
            true
        } catch (t: Throwable) {
            logger(Log.WARN, "GESTURE_IPC_SEND_FAILED ${t.javaClass.simpleName}", t)
            false
        }

    private fun newRequestId(component: ComponentName): String =
        "${System.currentTimeMillis()}-${component.hashCode()}"

    /** 等待中的请求。 */
    private class Pending(private val callback: (GestureBubbleIpcProtocol.Outcome) -> Unit) {
        var timeout: ScheduledFuture<*>? = null
        private var delivered = false

        @Synchronized
        fun deliver(outcome: GestureBubbleIpcProtocol.Outcome) {
            if (delivered) return
            delivered = true
            timeout?.cancel(false)
            runCatching { callback(outcome) }
        }
    }

    /** 请求句柄。 */
    inner class Handle(val requestId: String) {
        /** 放弃本地等待（已发出的请求不受影响）。 */
        fun cancel() {
            pending.remove(requestId)?.timeout?.cancel(false)
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 3_000L
    }
}
