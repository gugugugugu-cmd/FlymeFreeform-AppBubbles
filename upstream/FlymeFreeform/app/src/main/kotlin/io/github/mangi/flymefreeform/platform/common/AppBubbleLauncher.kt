package io.github.mangi.flymefreeform.platform.common

/**
 * 平台无关的气泡启动后端接口。
 *
 * 阶段 2 目标（任务书 §5.2 / §5.3）：
 * - 接收明确的 [BubbleLaunchRequest]；
 * - 返回结构化 [BubbleLaunchResult]，如实反映每个阶段；
 * - 各平台（ColorOS 自由窗 / Pixel Android 17 原生气泡）各自实现，
 *   手势 UI 只依赖本接口，不感知平台差异。
 */
interface AppBubbleLauncher {
    /**
     * 提交气泡启动请求。
     * 实现必须自行完成：请求校验 → 目标解析 → 后端探测 → 提交/失败归因。
     * 禁止抛出未捕获异常；所有失败归入 [BubbleLaunchResult] 状态。
     */
    fun launch(request: BubbleLaunchRequest): BubbleLaunchResult

    /** 当前平台是否可用（SystemUI 侧 BubbleController 是否就绪）。 */
    fun isAvailable(): Boolean
}
