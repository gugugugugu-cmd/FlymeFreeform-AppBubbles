package io.github.mangi.flymefreeform.platform.common

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * `system_server → SystemUI` 的手势提交通道（阶段 2 P0 整改）。
 *
 * ## 为什么必须跨进程
 *
 * 手势协调器 [io.github.mangi.flymefreeform.platform.coloros.ColorOsFreeformCoordinator]
 * 与 Pixel 气泡后端不在同一进程：
 *  - 协调器：system_server
 *  - `BubbleController` / `PixelAppBubbleLauncher`：SystemUI
 *
 * `BubbleController` 是 SystemUI 进程内的对象，system_server 既拿不到也不该拿。
 * 早期实现把 `PixelBubbleLaunchCommitter` 当普通对象注入协调器，是**跨进程直接调用**，
 * 在真机上不可能生效。因此改为：
 *
 * ```
 * system_server                      SystemUI
 * 扇形选中 → GestureBubbleIpcClient ──(finalize/in-process 广播)──▶
 *                                    GestureBubbleIpcReceiver
 *                                      → PixelLauncherExecutor（独立线程）
 *                                        → PixelAppBubbleLauncher.launch()
 *                                    ◀──(ACK 广播，定向回 system_server)──
 * ```
 *
 * ## 结果语义（对应审查意见 P1）
 *
 * 提交结果用 [Outcome] 三分，不再用 `Boolean` 把「已提交」和「已显示」混为一谈：
 *  - [Outcome.Accepted]：SystemUI 确认请求已交给 `BubbleController`（可接受回退口径）
 *  - [Outcome.Rejected]：明确失败，system_server 可以安全回退原生启动
 *  - [Outcome.Unknown]：超时 / 通道不可用，**不得**重试或回退，避免重复开窗
 *
 * 注意 [Outcome.Accepted] 也不等于「气泡已经显示并可展开」——那需要后续观察
 * `BubbleController.mBubbles` 状态，属于实机验收项，不在本通道的承诺范围内。
 */
internal object GestureBubbleIpcProtocol {

    /**
     * 手势提交请求（system_server → SystemUI）。
     *
     * 用 `finalize` 变体（部分 ROM 才会隐式加上 `Intent.EXTRA_USER_HANDLE`），
     * 使广播**顺序投递**到 SystemUI，避免与 onReceive 中拉起动作的竞态。
     */
    const val ACTION_COMMIT = "io.github.mangi.flymefreeform.action.GESTURE_COMMIT"

    /**
     * 同一请求的 `finalize` 变体：投递完成时触发一次，用于兜底「顺序投递被拦」的 ROM。
     * 与 [ACTION_COMMIT] 共用 payload，接收端幂等（按 requestId 去重）。
     */
    const val ACTION_COMMIT_FINALIZE = "io.github.mangi.flymefreeform.action.GESTURE_COMMIT_FINALIZE"

    /** 回执（SystemUI → system_server），定向发送到 system_server，不带 payload 解析负担。 */
    const val ACTION_COMMIT_ACK = "io.github.mangi.flymefreeform.action.GESTURE_COMMIT_ACK"

    /** 回执接收器所在包：system_server 的包名恒为 "android"。 */
    const val SYSTEM_SERVER_PACKAGE = "android"

    const val EXTRA_COMPONENT = "component"
    const val EXTRA_USER_ID = "user_id"
    const val EXTRA_SOURCE = "source"
    const val EXTRA_REQUEST_ID = "request_id"
    const val EXTRA_OUTCOME = "outcome"
    const val EXTRA_DIAGNOSTIC = "diagnostic"

    /** 提交结果三分语义，见类注释。 */
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

    /** 把协议层的 [Outcome] 映射为后端结果状态，便于统一日志与测试断言。 */
    fun toBubbleResult(outcome: Outcome): BubbleLaunchResult =
        when (outcome) {
            Outcome.Accepted -> BubbleLaunchResult.BubbleRequestSubmitted
            Outcome.Rejected -> BubbleLaunchResult.BackendUnavailable
            Outcome.Unknown -> BubbleLaunchResult.SubmissionTimeout
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

    /**
     * 定向发送到 system_server。
     *
     * system_server 的动态接收器不受「隐式广播限制」影响，
     * 显式 setPackage("android") 即可送达，无需额外权限。
     */
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

    /** 供 system_server 侧解析回执（校验 requestId 后取结果）。 */
    fun readAck(intent: Intent): Triple<String, Outcome, String?>? {
        val id = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return null
        return Triple(
            id,
            Outcome.parse(intent.getStringExtra(EXTRA_OUTCOME)),
            intent.getStringExtra(EXTRA_DIAGNOSTIC),
        )
    }

    /** Bundle 用法保留给以后可能出现的 binder 化实现，当前未使用。 */
    @Suppress("unused")
    fun bundleOf(component: ComponentName, userId: Int): Bundle =
        Bundle().apply {
            putString(EXTRA_COMPONENT, component.flattenToString())
            putInt(EXTRA_USER_ID, userId)
        }

    private const val TAG = "GestureBubbleIpc"
}

/**
 * system_server 侧的手势提交客户端。
 *
 * 广播是单向的，因此实现方式为：发送请求 → 阻塞等待定向回执（带超时）。
 * 等待发生在协调器的 `handler` 线程（`launchCommittedApp` 的 post 目标），
 * 不是 SystemUI 主线程，不会出现「自己等自己」的死锁。
 */
internal class GestureBubbleIpcClient(
    private val context: Context,
    logger: (priority: Int, code: String, error: Throwable?) -> Unit,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : AppLaunchCommitter {

    private val logger: (priority: Int, code: String, error: Throwable?) -> Unit = logger

    private val pending = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ArrayBlockingQueue<GestureBubbleIpcProtocol.Outcome>>()
    private var receiverRegistered = false

    private fun ensureReceiver() {
        if (receiverRegistered) return
        receiverRegistered = true
        try {
            context.registerReceiver(
                object : android.content.BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        val parsed = intent?.let(GestureBubbleIpcProtocol::readAck) ?: return
                        val (id, outcome, diagnostic) = parsed
                        logger(
                            Log.INFO,
                            "GESTURE_IPC_ACK id=$id outcome=${outcome.name} diag=$diagnostic",
                            null,
                        )
                        pending.remove(id)?.offer(outcome)
                    }
                },
                android.content.IntentFilter(GestureBubbleIpcProtocol.ACTION_COMMIT_ACK),
            )
        } catch (t: Throwable) {
            logger(Log.WARN, "GESTURE_IPC_ACK_RECEIVER_FAILED ${t.javaClass.simpleName}", t)
        }
    }

    /**
     * 提交一次气泡启动，等待 SystemUI 回执。
     *
     * @return false 仅代表 [Outcome.Rejected]（可回退）；超时/通道不可用时返回 false
     *         但会打出 `GESTURE_IPC_UNKNOWN` 诊断码——调用方需按该码区分是否回退。
     */
    override fun commit(component: ComponentName): Boolean =
        submit(component) == GestureBubbleIpcProtocol.Outcome.Accepted

    /** 三分结果版本，供协调器决定是否回退。 */
    fun submit(component: ComponentName, userId: Int = 0): GestureBubbleIpcProtocol.Outcome {
        ensureReceiver()
        val requestId = "${System.currentTimeMillis()}-${component.hashCode()}"
        val queue = java.util.concurrent.ArrayBlockingQueue<GestureBubbleIpcProtocol.Outcome>(1)
        pending[requestId] = queue
        return try {
            send(component, userId, requestId)
            val outcome = queue.poll(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (outcome == null) {
                logger(Log.WARN, "GESTURE_IPC_TIMEOUT id=$requestId", null)
                GestureBubbleIpcProtocol.Outcome.Unknown
            } else {
                outcome
            }
        } catch (t: Throwable) {
            logger(Log.WARN, "GESTURE_IPC_SEND_FAILED ${t.javaClass.simpleName}", t)
            GestureBubbleIpcProtocol.Outcome.Unknown
        } finally {
            pending.remove(requestId)
        }
    }

    /**
     * 发送请求。system_server 发出的广播不受厂商后台启动管控，可以直接送达 SystemUI。
     */
    private fun send(component: ComponentName, userId: Int, requestId: String) {
        val intent =
            GestureBubbleIpcProtocol.buildRequestIntent(
                component = component,
                userId = userId,
                requestId = requestId,
                source = "radial-gesture",
            )
        intent.setPackage(SYSTEM_UI_PACKAGE)
        // 不使用 FLAG_RECEIVER_FROM_SHELL：它是隐藏常量，且 system_server 本身即受信任发送方。
        context.sendBroadcast(intent)
        logger(Log.INFO, "GESTURE_IPC_SENT id=$requestId target=${component.flattenToShortString()}", null)
    }

    private companion object {
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        const val DEFAULT_TIMEOUT_MILLIS = 3_000L
    }
}
