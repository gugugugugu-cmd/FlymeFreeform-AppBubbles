package io.github.mangi.flymefreeform.platform.common

import android.content.ComponentName

/**
 * 提交被**明确拒绝**时的回退启动（审查意见 P1，2026-10-18）。
 *
 * 回退策略属于平台适配器职责：
 *  - ColorOS：走厂商自由窗启动协议；
 *  - Pixel：走普通全屏启动，**不得**携带 ColorOS 的 `mZoomLaunchFlags` /
 *    `setLaunchWindowingMode(100)`，否则在 AOSP 上等于伪造厂商参数。
 *
 * 硬性约束（与 [AppLaunchCommitter] 相同）：
 *  - 只在明确拒绝（`Outcome.Rejected`）时调用；
 *  - `Unknown`（超时 / 通道不可用 / 后端崩溃）**不得**回退，否则同一次手势可能开出两个窗口。
 *
 * 返回 [Started] 仅表示 `startActivity` 调用成功返回，
 * 不代表窗口已可见——这一点与气泡路径的 `BubbleRequestSubmitted` 语义一致。
 */
interface FallbackLauncher {
    fun launchFallback(component: ComponentName): FallbackLaunchResult
}

/**
 * 回退结果状态机。
 *
 * 不复用 `FreeformLaunchResult`：那个类型定义在 ColorOS 包里，
 * Pixel 回退路径引用它会引入对厂商包的编译期依赖（审查意见 P1 的另一半）。
 * 语义一一对应，避免两套类型各自演化。
 */
sealed interface FallbackLaunchResult {
    /** `startActivity` 已返回成功；**不代表**窗口已可见。 */
    data object Started : FallbackLaunchResult

    /** 组件无法解析或未导出。 */
    data object TargetUnavailable : FallbackLaunchResult

    /** 携带诊断信息的失败。 */
    data class Failed(
        val diagnosticCode: String,
        val cause: Throwable?,
    ) : FallbackLaunchResult
}
