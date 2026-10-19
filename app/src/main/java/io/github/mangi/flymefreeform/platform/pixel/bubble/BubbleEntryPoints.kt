package io.github.mangi.flymefreeform.platform.pixel.bubble

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.mangi.flymefreeform.hook.ProcessConfiguration

private const val TAG = "FlymeFreeformBubble"

/** 把主模块的配置快照桥接给 Java 侧的气泡 hook（Java 侧看不到 internal 的快照类）。 */
internal class BubbleConfigBridge(
    private val configuration: ProcessConfiguration,
) : BubbleModuleSettings.BubbleConfigSource {
    override fun isTaskMenuEnabled(): Boolean = configuration.snapshot.bubbleTaskMenuEnabled

    override fun isActionBarEnabled(): Boolean = configuration.snapshot.bubbleActionBarEnabled

    override fun isSystemUiBubbleEnabled(): Boolean =
        configuration.snapshot.bubbleNotificationIconEnabled
}

/**
 * 在 SystemUI 进程里安装气泡 hook。
 *
 * 与 FlymeFreeform 原生 hook 的关键区别：气泡功能不应被「FlymeFreeform 总开关」连带关掉。
 * [ProcessConfiguration.snapshot] 在配置已读到时就是真实值；只有读取失败时整个进程才
 * 回落到 FAIL_CLOSED（enabled = false），此时气泡必须整组不装。
 */
internal fun installBubbleSystemUiHooks(
    module: XposedModule,
    classLoader: ClassLoader,
    configuration: ProcessConfiguration,
) {
    if (!configuration.isAvailable) {
        module.log(Log.WARN, TAG, "BUBBLE_SKIPPED_CONFIG_NOT_AVAILABLE")
        return
    }
    guard(module, "systemui") {
        BubbleHooks(module, BubbleConfigBridge(configuration)).hookSystemUi(classLoader)
    }
}

/** 在桌面进程里安装气泡 hook。 */
internal fun installBubbleLauncherHooks(
    module: XposedModule,
    classLoader: ClassLoader,
    configuration: ProcessConfiguration,
) {
    if (!configuration.isAvailable) {
        module.log(Log.WARN, TAG, "BUBBLE_SKIPPED_CONFIG_NOT_AVAILABLE")
        return
    }
    guard(module, "launcher") {
        BubbleHooks(module, BubbleConfigBridge(configuration)).hookLauncher(classLoader)
    }
}

private inline fun guard(module: XposedModule, scope: String, block: () -> Unit) {
    try {
        block()
    } catch (throwable: Throwable) {
        module.log(Log.ERROR, TAG, "BUBBLE_${scope.uppercase()}_INSTALL_FAILED", throwable)
    }
}

