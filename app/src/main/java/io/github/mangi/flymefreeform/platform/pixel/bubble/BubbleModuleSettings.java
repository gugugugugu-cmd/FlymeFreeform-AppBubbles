// 迁移自 MoreBubbleButton（分支 feature/a17-bubble-corner-actions @ 706b0f6）。
//
// 原 ModuleSettings.java 直读 SharedPreferences，模块进程与 SystemUI 各缓存 250ms。
// 合并进 FlymeFreeform 后改由调用方传入布尔值：配置来源是主模块的
// ProcessConfiguration 快照，那里已经负责进程内缓存、外部推送与监听。
package io.github.mangi.flymefreeform.platform.pixel.bubble;

/** 气泡相关开关的语义入口。取值由 {@link BubbleHooks} 从主模块配置提供。 */
public final class BubbleModuleSettings {

    private BubbleModuleSettings() {}

    /** 任务卡片菜单：多任务界面里点气泡出的菜单。 */
    public static boolean isMenuEnabled(BubbleConfigSource source) {
        return source != null && source.isTaskMenuEnabled();
    }

    /** 任务卡片底部操作栏按钮。 */
    public static boolean isActionBarEnabled(BubbleConfigSource source) {
        return source != null && source.isActionBarEnabled();
    }

    /** 通知横幅气泡按钮。 */
    public static boolean isSystemUiBubbleEnabled(BubbleConfigSource source) {
        return source != null && source.isSystemUiBubbleEnabled();
    }

    /** 配置来源；主模块实现它并桥接到 {@code ProcessConfiguration}。 */
    public interface BubbleConfigSource {
        boolean isTaskMenuEnabled();

        boolean isActionBarEnabled();

        boolean isSystemUiBubbleEnabled();
    }
}
