package io.github.mangi.flymefreeform.platform.common

import android.content.ComponentName

/**
 * 平台无关的「已提交应用启动」抽象（任务书 §5.2.1）。
 *
 * 手势 UI（扇形菜单）在提交一个应用后，只调用本接口，不感知平台差异：
 * - ColorOS 实现：自由窗启动（ColorOsFreeformLauncher）+ 最近小窗记录；
 * - Pixel 实现：Android 17 原生气泡启动（BubbleLaunchRequest / AppBubbleLauncher）。
 *
 * 返回值用于 UI 侧决定是否记录「最近小窗」等状态。
 */
interface AppLaunchCommitter {
    /** 提交启动；返回 true 表示启动已受理并成功。 */
    fun commit(component: ComponentName): Boolean
}
