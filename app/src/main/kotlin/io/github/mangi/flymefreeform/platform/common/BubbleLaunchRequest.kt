package io.github.mangi.flymefreeform.platform.common

import android.content.ComponentName
import android.content.Intent
import android.os.UserHandle

/**
 * 平台无关的「气泡启动请求」。
 *
 * 由手势/测试入口提交，交给 [AppBubbleLauncher] 的具体平台实现消费。
 * 契约要求（任务书 §5.2）：
 * - 组件名来自当前选中项，不做隐式解析；
 * - 对启动 Intent 做显式校验（组件必须存在且可导出，不靠 setPackage 兜底）；
 * - 不默认 userId=0：仅主用户受支持，其他用户显式拒绝；
 * - 不随意加 NEW_TASK / MULTIPLE_TASK 等 flags——flags 由各平台实现按系统预期决定，
 *   本契约只携带「提交者意图」，不携带窗口语义。
 */
data class BubbleLaunchRequest(
    /** 目标组件（显式 ComponentName，来自扇形菜单选中项 / 测试入口）。 */
    val component: ComponentName,
    /** 目标用户。当前仅主用户受支持，非主用户提交会在校验阶段被拒绝。 */
    val userHandle: UserHandle,
    /** 提交来源，用于诊断（如 "gesture" / "test-entry"）。 */
    val source: String,
    /** 触发坐标（屏幕绝对像素），供平台实现决定展开位置，可为空。 */
    val triggerX: Int? = null,
    val triggerY: Int? = null,
) {
    /** 校验通过的显式启动 Intent（每次调用重建，不缓存可变状态）。 */
    fun buildExplicitIntent(): Intent =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .setPackage(component.packageName)
}
