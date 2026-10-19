package io.github.mangi.flymefreeform.gesture

import kotlin.math.max

internal enum class CornerSide {
    Left,
    Right,
}

internal enum class GesturePhase {
    Idle,
    Armed,
    Revealing,
    Selecting,
    Committing,
    MorePanel,
    Cancelling,
}

internal sealed interface GestureAction {
    data object Ignore : GestureAction
    data object PassThrough : GestureAction
    data class Activate(
        val side: CornerSide,
        val originX: Float,
        val originY: Float,
        val x: Float,
        val y: Float,
    ) : GestureAction
    data class Update(val side: CornerSide, val x: Float, val y: Float) : GestureAction
    data class Commit(val selectedIndex: Int?) : GestureAction
    data object Cancel : GestureAction
}

internal data class CornerGestureConfig(
    val displayWidth: Float,
    val displayHeight: Float,
    /** @author bomo 角落触发热区（形状 + 横/纵长度 + 半径，均已换算为 px）。 */
    val triggerZone: TriggerHitZone,
    val inwardThreshold: Float,
    val upwardThreshold: Float,
    val reverseTolerance: Float,
    val leftEnabled: Boolean,
    val rightEnabled: Boolean,
)

internal object AdaptiveCornerGestureConfig {
    /**
     * @param triggerZone 角落触发热区（已换算为 px）；由调用方用
     *   [CornerTriggerRegion.zone] 构造，便于同一热区同时用于「角落输入窗口尺寸」与命中判定。
     */
    fun create(
        displayWidth: Float,
        displayHeight: Float,
        touchSlop: Float,
        density: Float,
        triggerZone: TriggerHitZone,
        leftEnabled: Boolean,
        rightEnabled: Boolean,
    ): CornerGestureConfig {
        val safeDensity = density.takeIf { it.isFinite() && it > 0f } ?: 1f
        return CornerGestureConfig(
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            triggerZone = triggerZone,
            inwardThreshold = max(touchSlop * INWARD_SLOP_MULTIPLIER, safeDensity * INWARD_DP),
            upwardThreshold = max(touchSlop * UPWARD_SLOP_MULTIPLIER, safeDensity * UPWARD_DP),
            reverseTolerance = max(touchSlop, safeDensity * REVERSE_TOLERANCE_DP),
            leftEnabled = leftEnabled,
            rightEnabled = rightEnabled,
        )
    }

    private const val INWARD_SLOP_MULTIPLIER = 1.75f
    private const val UPWARD_SLOP_MULTIPLIER = 0.50f
    private const val INWARD_DP = 14f
    private const val UPWARD_DP = 4f
    private const val REVERSE_TOLERANCE_DP = 8f
}

/** 单指角落手势状态机；不持有 MotionEvent，便于跨进程入口复用与单元测试。 */
internal class CornerGestureEngine {
    var phase: GesturePhase = GesturePhase.Idle
        private set
    var selectedIndex: Int? = null
        private set

    private var pointerId = -1
    private var originX = 0f
    private var originY = 0f
    private var side: CornerSide? = null
    private var claimed = false

    fun down(pointerId: Int, x: Float, y: Float, config: CornerGestureConfig): GestureAction {
        reset()
        val detectedSide = detectSide(x, y, config) ?: return GestureAction.PassThrough
        this.pointerId = pointerId
        originX = x
        originY = y
        side = detectedSide
        phase = GesturePhase.Armed
        return GestureAction.PassThrough
    }

    fun move(
        pointerId: Int,
        pointerCount: Int,
        x: Float,
        y: Float,
        config: CornerGestureConfig,
    ): GestureAction {
        val activeSide = side ?: return GestureAction.PassThrough
        if (pointerId != this.pointerId || pointerCount != 1) return cancel()

        val inward = if (activeSide == CornerSide.Left) x - originX else originX - x
        val upward = originY - y
        // 反向容差只用于「还没认领」时的起步抖动过滤；一旦已 Activate（轮盘已经弹出），
        // 反向移动应当交给选择逻辑处理，不能再整手 cancel——否则轮盘还在屏幕上，引擎却装死。
        if (!claimed && (inward < -config.reverseTolerance || upward < -config.reverseTolerance)) {
            return cancel()
        }
        if (!claimed) {
            if (inward < config.inwardThreshold || upward < config.upwardThreshold) {
                return GestureAction.PassThrough
            }
            claimed = true
            phase = GesturePhase.Revealing
            return GestureAction.Activate(activeSide, originX, originY, x, y)
        }

        phase = GesturePhase.Selecting
        return GestureAction.Update(activeSide, x, y)
    }

    fun up(pointerId: Int): GestureAction {
        if (pointerId != this.pointerId) return cancel()
        if (!claimed) {
            // claimed 与「轮盘是否已弹出」是两套状态：SystemUI 侧 pilfer 成功就会显示轮盘，
            // 而引擎可能因起步抖动没走到 claimed=true。此时轮盘已在屏幕上，
            // 返回 PassThrough 会让上层按「什么都没发生」收摊，表现为「轮盘一出现就消失」。
            // 只要阶段表明手势已经启动过，就必须给上层一个明确的收尾动作。
            if (handoffStarted()) {
                val selection = selectedIndex
                clearTracking(GesturePhase.Committing)
                return GestureAction.Commit(selection)
            }
            reset()
            return GestureAction.PassThrough
        }
        val selection = selectedIndex
        clearTracking(GesturePhase.Committing)
        val result = GestureAction.Commit(selection)
        return result
    }

    fun cancel(): GestureAction {
        // 同上：轮盘可能已经弹出（pilfer 成功）而 claimed 仍为 false，
        // 此时必须回 Cancel 让上层收掉轮盘，不能当成「没接管过」。
        val wasClaimed = claimed || handoffStarted()
        clearTracking(if (wasClaimed) GesturePhase.Cancelling else GesturePhase.Idle)
        return if (wasClaimed) GestureAction.Cancel else GestureAction.PassThrough
    }

    /** 轮盘是否已经由本次手势拉起（不依赖 claimed 标记）。 */
    private fun handoffStarted(): Boolean =
        phase == GesturePhase.Revealing ||
            phase == GesturePhase.Selecting ||
            phase == GesturePhase.MorePanel

    fun setSelection(index: Int?) {
        if (claimed) selectedIndex = index
    }

    val isClaimed: Boolean
        get() = claimed

    private fun detectSide(x: Float, y: Float, config: CornerGestureConfig): CornerSide? {
        return CornerTriggerRegion.detectSide(
            x = x,
            y = y,
            displayWidth = config.displayWidth,
            displayHeight = config.displayHeight,
            zone = config.triggerZone,
            leftEnabled = config.leftEnabled,
            rightEnabled = config.rightEnabled,
        )
    }

    private fun reset() {
        clearTracking(GesturePhase.Idle)
    }

    private fun clearTracking(nextPhase: GesturePhase) {
        phase = nextPhase
        selectedIndex = null
        pointerId = -1
        originX = 0f
        originY = 0f
        side = null
        claimed = false
    }
}
