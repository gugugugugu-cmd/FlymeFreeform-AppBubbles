package io.github.mangi.flymefreeform.platform.common

/**
 * 气泡启动结果状态机（任务书 §5.3）。
 *
 * 平台实现必须如实报告每个阶段，无法确认的阶段记 [RESULT_UNKNOWN]，
 * 禁止为了让调用「看起来成功」而吞异常或伪造成功状态。
 */
sealed interface BubbleLaunchResult {
    /** 请求本身不合法（组件为空 / 非主用户 / 来源非法）。 */
    data object RequestValidationFailed : BubbleLaunchResult

    /** 目标组件无法解析（包未安装 / Activity 不存在 / 未导出）。 */
    data object TargetResolutionFailed : BubbleLaunchResult

    /** 后端不可用（BubbleController 未就绪 / 反射目标缺失 / 权限不足）。 */
    data object BackendUnavailable : BubbleLaunchResult

    /** 气泡请求已成功提交给系统（Bubble 已创建或已从 overflow 取出并请求展开）。 */
    data object BubbleRequestSubmitted : BubbleLaunchResult

    /**
     * 提交后等待系统主线程执行超时，结果未知。
     *
     * **不得**与 [BubbleRequestSubmitted] 混用：超时只说明"没有观察到执行完成"，
     * 既不能断言气泡已出现，也不能断言失败（任务可能仍在排队）。
     */
    data object SubmissionTimeout : BubbleLaunchResult

    /** 气泡创建失败（Bubble 构造失败 / 图标解析失败等）。 */
    data object BubbleCreateFailed : BubbleLaunchResult

    /** 任务（activity）启动失败。 */
    data object TaskLaunchFailed : BubbleLaunchResult

    /** 展开失败（气泡已创建但 expand 调用失败）。 */
    data object ExpandFailed : BubbleLaunchResult

    /** 无法确认当前所处阶段。 */
    data object ResultUnknown : BubbleLaunchResult

    /** 后端降级：气泡不可用时回退为普通全屏启动。 */
    data object FallbackLaunch : BubbleLaunchResult

    /** 携带诊断信息的失败（沿用 FreeformLaunchResult 的诊断风格）。 */
    data class Failed(
        val diagnosticCode: String,
        val cause: Throwable?,
    ) : BubbleLaunchResult

    val isSuccess: Boolean
        get() =
            this is BubbleRequestSubmitted ||
                this is FallbackLaunch
}
