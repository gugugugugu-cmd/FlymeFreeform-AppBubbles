# MoreBubbleButton

Xposed 模块，为 Pixel Launcher 最近任务界面和 SystemUI 通知中心添加「消息气泡」能力。

## 功能

- 在 Pixel Launcher 多任务界面底部操作栏添加「🫧 消息气泡」按钮
- 在任务卡片菜单（点击 app 左上角图标）中添加「🫧 消息气泡」选项
- 点击后将当前选中的应用变为 app bubble
- 在 SystemUI 通知中心为可打开的普通通知显示消息气泡按钮
- 点击通知气泡按钮后：
  - 创建并展开 app bubble
  - 优先跳转通知 `contentIntent` 对应界面，而不是只打开 app 首页
  - 自动收起通知中心
  - 对 `FLAG_AUTO_CANCEL` 通知执行移除，使其表现为已读/已处理
- 自动过滤无启动入口或特殊用户通知，避免 `android` / `user=-1` 等无效场景导致 SystemUI 崩溃
- 设置界面支持 X/Y 轴位置调节，滑杆旁提供 `+` / `−` 精细调节按钮

## 要求

- Android 16+ (API 36+)
- 已安装 LSPosed / KernelSU + Zygisk
- Pixel Launcher (NexusLauncher)
- SystemUI 通知气泡功能需要把作用域同时勾选到 `com.android.systemui`

## 安装

1. 下载 [最新 Release](https://github.com/TYOPXN360/MoreBubbleButton/releases) APK
2. 在 LSPosed 中安装并启用模块
3. 设置作用域：
   - `com.google.android.apps.nexuslauncher`
   - `com.android.launcher3`（如设备使用该包名）
   - `com.android.systemui`（通知中心气泡按钮必需）
4. 强制停止 Pixel Launcher / SystemUI，或重启设备

## 使用

### 方式一：最近任务底部操作栏

进入多任务界面 → 点击底部「🫧 消息气泡」按钮。

### 方式二：任务卡片菜单

进入多任务界面 → 点击任务卡片左上角 app 图标 → 点击「🫧 消息气泡」。

### 方式三：通知中心气泡按钮

下拉通知中心 → 对支持打开的普通通知点击气泡图标 → 自动打开对应通知界面的 app bubble。

### 方式四：设置界面

打开 MoreBubbleButton 应用 → 调整开关和按钮位置。

### 方式五：双击应用气泡图标转为全屏

展开经典 BubbleStackView 小窗后，双击小窗上方的**同一个应用气泡图标**，优先让 SystemUI 将当前气泡任务正式转换为全屏。若官方转换路径无法尝试或未同步进入，再像点击桌面启动器图标一样启动对应应用。不是双击小窗内的应用内容；官方转换保留气泡内当前页面，启动器回退则由应用自身决定最终页面。

兼容保留 `bubble_double_tap_fullscreen` 设置键（默认开启）。单击图标仍等待系统双击超时，然后执行一次原生点击（收起/切换）。拖动、长按、取消手势不启动应用；不同图标不组成双击。关闭设置后恢复即时原生点击；待处理单击会在下一次事件或超时检查时安全收尾，图标移除时取消。

目标 SystemUI DEX（MT 工作区 `81i4aezl`）核验了双击的首选路径：点击图标对应 Bubble 的 `mBubbleTaskView.taskView`，取其 `mTaskViewController` 与 `mTaskViewTaskController`，在任务信息有效时调用 `moveTaskViewToFullscreen(TaskViewTaskController)`，由 SystemUI 正式启动气泡到全屏的转换。仅当官方转换无法建立/调用，或同步转换入口未进入时，才使用启动器回退：应用气泡构造函数将 `Intent.getPackage()` 写入 `mPackageName`、将传入的 `UserHandle` 写入 `mUser`，再通过 `LauncherApps.getActivityList` 解析该用户的 MAIN/LAUNCHER 入口并调用 `startMainActivity`。个人用户和工作资料均使用原始用户，不会在资料不可用/权限不足时改开另一个用户的同名应用。两条路径都不使用气泡 PendingIntent，不清除或移除气泡，也不额外收起气泡栈。

`MoreBubbleModule` 的正常可见日志包含 Hook 安装、单击延迟、`icon double tap recognized`、包名/用户解析、`icon launch request submitted` 或失败。未出现双击日志时可先检查 Hook/手势是否进入。启动请求返回不等于视觉完成；DEX 核验及 CI 桩测试也不是设备运行验证。其他 ROM/版本可能不兼容，最终效果需设备验证。

## 设置项

| 设置 | 说明 | 默认值 |
|------|------|--------|
| 任务卡片菜单 | 在菜单中显示消息气泡选项 | 开 |
| 底部操作栏 | 在底部显示消息气泡按钮 | 开 |
| 通知横幅气泡 | 在 SystemUI 通知中心显示气泡按钮 | 开 |
| 按钮位置 | 跟随原按钮 / 第二行 | 跟随原按钮 |
| X 轴 | 水平位置，支持滑杆和 `+` / `−` 精调 | 50% |
| Y 轴 | 垂直位置，支持滑杆和 `+` / `−` 精调 | 50% |
| 重启启动器 + 系统界面 | 通过 root 重启 Pixel Launcher / SystemUI 使设置生效 | - |

## 技术实现

基于 [libxposed API 102](https://github.com/libxposed/api)，Hook Pixel Launcher 与 SystemUI。

### Pixel Launcher

| Hook 目标 | 方法 | 作用 |
|-----------|------|------|
| `OverviewActionsView` | `onFinishInflate` | 注入底部操作栏按钮 |
| `OverviewActionsView` | `onClick` | 处理按钮点击 |
| `TaskMenuView` | `addMenuOptions` | 注入任务卡片菜单项 |

最近任务气泡触发通过 `SystemUiProxy.showAppBubble()` 调用 WMShell Bubble 服务。

### SystemUI

| Hook 目标 | 作用 |
|-----------|------|
| `NotificationContentView.shouldShowBubbleButton` | 为符合条件的通知显示气泡按钮 |
| `BubblesManager.onUserChangedBubble` / `expandStackAndSelectBubble` | 拦截通知气泡点击，改走稳定的 app bubble 路径 |
| `BubbleController.expandStackAndSelectBubble(Intent, UserHandle, EntryPoint, null)` | 使用通知 `contentIntent` 创建并展开 app bubble |
| `BubbleCoordinator.removeNotification` | 在气泡成功展开后移除 auto-cancel 通知 |

## 构建

```bash
cd source/BubbleButtonModule
source /mnt/TY/android/android-project/classapp/set-env.sh
export GRADLE_USER_HOME=$PWD/../.gradle
./gradlew assembleDebug
./gradlew assembleRelease
```

APK 输出：

- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release.apk`

## Release

Release 构建启用 R8 minify 与 resource shrink，并使用 release keystore 签名。

## AI 声明

本项目部分代码、调试与发布说明由 AI 辅助完成，最终行为以真实设备验证为准。

## License

Apache License 2.0
