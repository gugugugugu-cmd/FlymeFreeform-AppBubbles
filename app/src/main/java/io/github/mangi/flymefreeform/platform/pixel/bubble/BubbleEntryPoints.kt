package io.github.mangi.flymefreeform.platform.pixel.bubble

import io.github.libxposed.api.XposedModule
import io.github.mangi.flymefreeform.hook.ProcessConfiguration

/** 把主模块的配置快照桥接给 Java 侧的气泡 hook（Java 侧看不到 internal 的快照类）。 */
internal class BubbleConfigBridge(
    private val configuration: ProcessConfiguration,
) : BubbleModuleSettings.BubbleConfigSource {
    override fun isTaskMenuEnabled(): Boolean = configuration.snapshot.bubbleTaskMenuEnabled

    override fun isActionBarEnabled(): Boolean = configuration.snapshot.bubbleActionBarEnabled

    override fun isSystemUiBubbleEnabled(): Boolean =
        configuration.snapshot.bubbleNotificationIconEnabled
}

/** 在 SystemUI 进程里安装气泡 hook。 */
internal fun installBubbleSystemUiHooks(
    module: XposedModule,
    classLoader: ClassLoader,
    configuration: ProcessConfiguration,
) {
    BubbleHooks(module, BubbleConfigBridge(configuration)).hookSystemUi(classLoader)
}

/** 在桌面进程里安装气泡 hook。 */
internal fun installBubbleLauncherHooks(
    module: XposedModule,
    classLoader: ClassLoader,
    configuration: ProcessConfiguration,
) {
    BubbleHooks(module, BubbleConfigBridge(configuration)).hookLauncher(classLoader)
}
