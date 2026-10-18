# PIXEL_PORT_AUDIT — FlymeFreeform 平台专用依赖审计

> 阶段 0 产出 · 只读侦察（未修改任何业务代码）
> 侦察基线（commit）：
> - FlymeFreeform `b2c877924f1396af8f70b8f1564e55bb5f4336ad`（v0.2.6，2026-10-17）
> - MoreBubbleButton `81630ab0bed89dd5365afc134d3491361291f9d7`（v1.7，2026-10-15）
>
> 证据类型标注：**源码**（本仓库内代码引用）/ **README**（仓库声明）/ **DEX 核验**（MT 反编译目标 APK 确认）/ **待验证**（需真机确认）
>
> **2026-10-18 补充核验**：MT MCP 已恢复。已反编译 **Pixel Launcher 17**（`com.google.android.apps.nexuslauncher` v17/907，minSdk 37）与 **SystemUI 17**（`com.android.systemui` v17/37，minSdk 37）两个目标 APK，核验结果见 §10「阶段 0 补充 DEX 核验结果」。

---

## 0. 结论速览

| # | 依赖 | 所在进程 | Pixel Android 17 可用性 | 风险等级 |
|---|------|---------|------------------------|---------|
| 1 | `com.android.server.wm.FlexibleTaskController` | system_server | ❌ ColorOS 专用，Pixel 不存在 | 🔴 致命 |
| 2 | `com.android.server.wm.FlexibleTaskScaleManager` | system_server | ❌ ColorOS 专用 | 🔴 致命 |
| 3 | `com.android.server.wm.FlexibleCaptionView` | system_server | ❌ ColorOS 专用 | 🔴 致命 |
| 4 | `com.android.quickstep.OplusBaseTouchInteractionService` | Launcher(Quickstep) | ❌ Oplus 专用 | 🔴 致命 |
| 5 | `com.coloros.smartsidebar` 双进程 | 独立进程 | ❌ 不存在 | 🟠 高（可旁路） |
| 6 | `com.oplus.smartsidebar.*` 系列类 | 侧边栏进程 | ❌ 不存在 | 🟠 高（可旁路） |
| 7 | `com.android.systemui.application.impl.SystemUIApplicationImpl` | SystemUI | ❌ ColorOS 17 专属候选 | 🟡 中（有回退候选） |
| 8 | `com.android.systemui.SystemUIApplication` | SystemUI | ✅ AOSP 标准类 | 🟢 低 |
| 9 | `PROCESS_LAUNCHER = "com.android.launcher"` | Launcher | ❌ Pixel 用 `com.google.android.apps.nexuslauncher` | 🟠 高 |
| 10 | `SystemUiProxy.showAppBubble(...)`（MoreBubbleButton） | Launcher | ✅ AOSP 类（A17 存在） | 🟢 已核验：`(Intent, UserHandle, EntryPoint, BubbleBarLocation)V` |
| 11 | `BubbleController.expandStackAndSelectAppBubble(...)` | SystemUI/WMShell | ✅ AOSP 类（A17 新签名） | 🟢 已核验：`(Bubble, EntryPoint, UpdateLocationRequest)V` |
| 12 | `WindowManagerPolicyConstants$PointerEventListener` | system_server | ✅ AOSP 标准接口 | 🟢 低 |
| 13 | `setTrustedOverlay` / `getViewRootImpl` / `pilferPointers` | SystemUI | ✅ AOSP API（隐藏） | 🟡 中 |
| 14 | 自由窗启动 Bundle 协议（`FLEXIBLE_WINDOWING_MODE=100` 等） | system_server | ❌ ColorOS 私有协议 | 🔴 致命（即将被替换） |

**一句话结论**：FlymeFreeform 的**初始化入口、进程匹配、触摸输入、自由窗启动**四层全部绑定 ColorOS/Oplus 类；在 Pixel 上模块当前会因 `FlexibleTaskController` 不存在而在 system_server 分支安全跳过（有 try/catch），但**手势协调器永远不会启动**。移植 = 换掉这四层 + 保留菜单/选择 UI 层。

---

## 1. 进程匹配与模块入口

### 1.1 `ModuleMain.kt`（118 行）

| 行号 | 内容 | Pixel 可用性 |
|------|------|-------------|
| 73 | `processName == ColorOsSidebarTarget.NATIVE_PROCESS_NAME && ...` | ❌ `com.coloros.smartsidebar:edgepanel` 不存在 |
| 78 | `processName == ColorOsSidebarTarget.PROCESS_NAME && ...` | ❌ `com.coloros.smartsidebar:ui` 不存在 |
| 87 | `processName == PROCESS_SYSTEM_UI && ...` → `SystemUiHookInstaller` | ⚠️ SystemUI 进程存在，但 hook 目标类需检查（见 §3） |
| 91 | `processName == PROCESS_LAUNCHER && param.packageName == PROCESS_LAUNCHER` → `LauncherHookInstaller` | ❌ `PROCESS_LAUNCHER="com.android.launcher"`；Pixel 为 `com.google.android.apps.nexuslauncher`，分支永不命中 |
| 102-105 | `isExpectedProcess()` 白名单 | ⚠️ Pixel 的 Launcher 进程名不在白名单 → `MODULE_SKIPPED_UNEXPECTED_PROCESS` 直接 detach |

**关键发现**：
1. `PROCESS_LAUNCHER` 被同时用作 **processName 与 packageName** 的双重匹配（88 行），而 Pixel Launcher 的进程名就是包名 `com.google.android.apps.nexuslauncher`（进程名 == 包名，无冒号子进程）。需要把进程匹配与平台能力检测拆开（对应任务书 3.1）。
2. `onPackageReady` 中 `hooksInstalled` 是**单一全局标志**（29 行），一旦某个分支命中并置 true，其他进程的分支会被跳过——但每个进程是独立实例，实际影响有限；真正的风险是 `isExpectedProcess` 白名单在 Pixel 上直接拦掉 Launcher。
3. ColorOS 侧边栏分支是**进程内 UI 桥接**（`SidebarHookInstaller` 装到 `:ui` 进程、`NativeSidebarHookInstaller` 装到 `:edgepanel`），Pixel 上这两个进程不存在，分支自然跳过，不会崩溃（无副作用）。

### 1.2 改造方向（仅供阶段 3 参考，本轮不改）

- 新增 `PROCESS_LAUNCHER_PIXEL = "com.google.android.apps.nexuslauncher"`（以及候选 `com.android.launcher3`），按 **包名**（而非硬编码进程名）解析 Launcher 分支。
- `isExpectedProcess` 增加 Pixel Launcher 包名；进程匹配与平台检测解耦。
- 保留 ColorOS 分支（不做破坏性删除），用平台检测开关隔离。

---

## 2. system_server 进程的 ColorOS 依赖（🔴 核心风险区）

### 2.1 `SystemServerHookInstaller.kt`（76 行）

| 行号 | 内容 | 证据 |
|------|------|------|
| 59 | `FLEXIBLE_TASK_CONTROLLER_CLASS = "com.android.server.wm.FlexibleTaskController"` | 源码 |
| 32-52 | `install()`：加载该类 → hook `systemReady(boolean)` → 成功才 `ColorOsFreeformCoordinator(...).start()` | 源码 |

**结论**：`FlexibleTaskController` 是 ColorOS/Oplus 自由窗（Flexible Window）的 WMS 控制器。AOSP/Pixel Android 17 **不存在此类**（AOSP 自由窗为 `FreeformController` / `FreeformPolicy`，Android 16+ 的桌面自由窗体系，类名与职责不同）。当前代码在 Pixel 上会走进 catch（`SYSTEM_FREEFORM_TARGET_UNAVAILABLE`），**模块不崩溃但手势协调器完全不启动**。这是移植必须提供"另一条已验证初始化入口"的点（任务书 3.2）。

### 2.2 `HandleSwipeUpHookInstaller.kt`（145 行）

| 行号 | 内容 | 证据 |
|------|------|------|
| 120 | `SCALE_MANAGER_CLASS = "com.android.server.wm.FlexibleTaskScaleManager"` | 源码 |
| 121 | `FLEXIBLE_TASK_CONTROLLER_CLASS = "com.android.server.wm.FlexibleTaskController"` | 源码 |
| 122 | `TASK_CLASS = "com.android.server.wm.Task"` | 源码 |
| 27-29 | `loadClass` 三个类后 hook `getGestureMode`（在 `FlexibleTaskScaleManager` 上） | 源码 |

**结论**：`FlexibleTaskScaleManager.getGestureMode()` 是 ColorOS 专用手势模式查询（用于"上滑迷你窗"等自由窗状态判断）。Pixel 上三个 `loadClass` 中前两个必然抛 `ClassNotFoundException` → 整体 catch → 该 Hook 不安装（安全失败）。**此 Hook 的职责（自由窗状态机判断）在 Pixel 气泡场景中无对应物，可整体停用**。

### 2.3 `OutsideTapCloseHookInstaller.kt`（~630 行）

| 行号 | 内容 | 证据 |
|------|------|------|
| 570 | `"com.android.server.wm.FlexibleTaskController$TouchListener"` | 源码 |
| 571 | `FLEXIBLE_TASK_CONTROLLER_CLASS = "com.android.server.wm.FlexibleTaskController"` | 源码 |
| 573 | `DISPLAY_CONTENT_CLASS = "com.android.server.wm.DisplayContent"`（✅ AOSP 存在） | 源码 |
| 574 | `WINDOW_STATE_CLASS = "com.android.server.wm.WindowState"`（✅ AOSP 存在） | 源码 |
| 575 | `FLEXIBLE_CAPTION_VIEW_CLASS = "com.android.server.wm.FlexibleCaptionView"`（❌ ColorOS） | 源码 |

**结论**：该 Hook 拦截"小窗外点击关闭自由窗"。`FlexibleCaptionView` 与 `FlexibleTaskController$TouchListener` 在 Pixel 不存在 → 整个 installer 走 catch 停用。**功能本身依赖自由窗 caption 体系，气泡场景无对应需求，可整体停用**（气泡的点击外部收起由 BubbleStackView 自带）。

### 2.4 自由窗启动协议（🔴 将被替换的核心）

`ColorOsFreeformLauncher.kt`（87 行）：

| 行号 | 内容 | 证据 |
|------|------|------|
| 49 | `putInt(WINDOWING_MODE_KEY, FLEXIBLE_WINDOWING_MODE)`，`FLEXIBLE_WINDOWING_MODE = 100` | 源码 |
| 74 | `ZOOM_FLAGS_KEY = "android:activity.mZoomLaunchFlags"`，`ZOOM_LAUNCH_FLAG = 4` | 源码 |
| 71 | `WINDOWING_MODE_KEY = "android.activity.windowingMode"` | 源码 |
| 53-65 | `createPlatformOptions()`：反射 `ActivityOptions.setLaunchWindowingMode(100)` | 源码 |
| 26 | `context.startActivity(intent, options)` | 源码 |
| 27-36 | 结果密封类：`Started / TargetUnavailable / Failed(diagnosticCode, cause)` | 源码 |

**结论**：`windowingMode=100`（FLEXIBLE）+ `mZoomLaunchFlags=4` 是 ColorOS 私有启动协议。Pixel 上 `startActivity` 会照常启动（windowMode 100 无对应策略）→ **退化为普通全屏启动**，这正是项目书禁止的"假装成功"。**此方法整体替换为 Bubble 后端**，`FreeformLaunchResult` 可映射为任务书 §2.3 的阶段枚举。该 Launcher 类继续保留给 ColorOS 后端使用（非目标：不破坏旧功能）。

### 2.5 `ColorOsFreeformCoordinator.kt`（830 行）—— 移植的核心承载

关键行：

| 行号 | 内容 | 备注 |
|------|------|------|
| 42 | `internal class ColorOsFreeformCoordinator(...)` | 构造入参：`controller: Any`（即 `FlexibleTaskController` 实例）、classLoader、configuration、environmentState、logger |
| 45 | `val context = readField(controller, "mContext") as Context` | **直接读 ColorOS 控制器字段** |
| 64 | `private val launcher = ColorOsFreeformLauncher(context)` | 自由窗启动器（§2.4） |
| 65 | `private val sidebar = ColorOsSidebarClient(context, handler, logger)` | 侧边栏桥接（§4） |
| 117-126 | `init`：`sidebar.onToolCatalog / onRecentComponent / onLaunchComponent` 回调 | 侧边栏直发回调 |
| 183-193 | `registerPointerListener()`：`Class.forName("android.view.WindowManagerPolicyConstants$PointerEventListener")` + `registerPointerEventListener(listener, Display.DEFAULT_DISPLAY)` | ✅ **AOSP 标准接口**（`WindowManagerService.registerPointerEventListener` 自 API 早版本即存在，A17 仍可用，签名 `(PointerEventListener, int displayId)` 为 2 参） |
| 449-457 | `override fun onAppCommitted(entry: RadialAppEntry)`：`launchCommittedApp(entry)` | **任务书 4.2 的替换点** |
| 459-490 | `launchCommittedApp(entry)`：工具组件 → `sidebar.runTool`；普通应用 → `launcher.launch(entry.component)` → `publishRecentFreeform` | **任务书 2.1 的抽离点** |
| 95-103 | `sideBar.onLaunchComponent` → `launchCommittedApp(RadialAppEntry(component, ...))` | 侧边栏路径同样走提交 |

**关键发现（对移植极重要）**：
1. **指针输入入口本身是 AOSP 标准的**（`WindowManagerPolicyConstants$PointerEventListener` + `WMS.registerPointerEventListener`）。这意味着"角落手势输入"这层在 Pixel system_server 上**可能直接可用**，不必依赖 ColorOS 类——`registerPointerListener()` 是 Coordinator 内部方法，只是没人调用它（因为 `start()` 只有 `FlexibleTaskController.systemReady` hook 触发）。
2. `ColorOsFreeformCoordinator` 与 ColorOS 的耦合点实际只有两处：**构造入参 `controller`（用来读 mContext）** 与 **`launcher`/`sidebar` 两个内部服务**。若为 Pixel 提供一个新的初始化入口（例如 hook 某个 Pixel 存在的 SystemUI/SystemServer 生命周期方法拿到 Context），Coordinator 主体（手势引擎、Overlay、目录、提交流程）可复用。
3. `RadialAppEntry(component, className, icon)` 是提交载体；`entry.component` 是 `ComponentName`。**userId 信息当前不存在**（无多用户处理）——任务书 2.2 要求显式处理 userId，当前实现隐含主用户，移植时需补。

---

## 3. SystemUI 进程依赖

### 3.1 `SystemUiHookInstaller.kt`（74 行）

| 行号 | 内容 | Pixel 可用性 |
|------|------|-------------|
| 30-35 | 候选类列表：`com.android.systemui.application.impl.SystemUIApplicationImpl`（ColorOS 17）、`com.android.systemui.SystemUIApplication`（AOSP） | ⚠️ Pixel A17 用 `com.android.systemui.SystemUIApplication`（AOSP 标准，候选第 2 个命中）；ColorOS 17 的 `application.impl` 子包是厂商迁移，Pixel 不存在，但代码用 `firstNotNullOfOrNull` 逐个尝试 → **安全** |
| 33 | `firstNotNullOfOrNull { runCatching { loadClass(name) }.getOrNull() }` | ✅ 容错设计良好 |
| 41-55 | hook `onCreate` → `startInputMonitor(context)` | ✅ AOSP `Application.onCreate` |
| 53-61 | `SystemUiCornerInputMonitor`：`setTrustedOverlay`、`getViewRootImpl`、`getInputToken`、`InputManager.pilferPointers` | ✅ 全部 AOSP 隐藏 API（`WindowManager.LayoutParams#setTrustedOverlay` 为 AOSP 隐藏方法，A14+ 存在；`InputManager#pilferPointers(IBinder)` AOSP 存在） |

**结论**：SystemUI 分支是四个分支中**唯一在 Pixel 上大概率可直接工作的**（候选类回退 + AOSP 隐藏 API）。`SystemUiCornerInputMonitor` 是"SystemUI 内 overlay 输入监控"，与 system_server 的 `PointerEventListener` 是**两套并行输入路径**。移植时需确认 Pixel 上最终采用哪条（建议优先 system_server PointerEventListener，理由见 §6）。

### 3.2 气泡后端目标类（来自 MoreBubbleButton，供阶段 1 验证）

| 类 | 进程 | Pixel A17 | 备注 |
|----|------|-----------|------|
| `com.android.systemui.wmshell.BubblesManager` | SystemUI | ✅ AOSP | MoreBubbleButton 的 hook 目标（SystemUI 侧） |
| `com.android.wm.shell.bubbles.BubbleController` | SystemUI(wmshell) | ✅ AOSP | 展开入口所在 |
| `com.android.wm.shell.bubbles.Bubble` | SystemUI | ✅ AOSP | A17 构造函数 `(Intent, UserHandle, Icon, Bubble$BubbleType, String key)` |
| `com.android.wm.shell.bubbles.BubbleData` | SystemUI | ✅ AOSP | `findAndRemoveBubbleFromOverflow(String)` |
| `com.android.wm.shell.shared.bubbles.logging.EntryPoint` | SystemUI | ✅ AOSP | 枚举 `NOTIFICATION_BUBBLE_BUTTON` 等 |
| `com.android.wm.shell.shared.bubbles.BubbleBarLocation$UpdateLocationRequest` | SystemUI | ✅ AOSP | `expandStackAndSelectAppBubble` 第 3 参 |

> 以上"可用性"指类名存在于 AOSP 源码体系；**方法签名与字段名必须由目标 ROM DEX 核验**（阶段 1 任务 1.2）。

---

## 4. Launcher / Quickstep 进程依赖

### 4.1 `LauncherHookInstaller.kt`（305 行）

| 行号 | 内容 | Pixel 可用性 |
|------|------|-------------|
| 299 | `TOUCH_SERVICE_CLASS = "com.android.quickstep.OplusBaseTouchInteractionService"` | ❌ Oplus 专用，Pixel 不存在 |
| 300 | `INPUT_METHOD_NAME = "onInputEventInternal"` | ❌ 同上 |
| 303-304 | `InputMonitor` / `pilferFailure` 常量 | ✅ AOSP 类（但被 Oplus 服务类拦住，整体不可达） |

**结论**：Launcher 分支整体绑定 Oplus 触摸服务，Pixel 上不可用（进程匹配在 ModuleMain 已拦，双保险）。**该分支职责（Launcher 侧触摸输入采集）在 Pixel 上可由 system_server 的 PointerEventListener 替代**——MoreBubbleButton 已证明 Pixel Launcher 进程可被正常 hook（它 hook 的是 `com.android.quickstep.SystemUiProxy` 与最近任务 UI，不依赖 Oplus）。

### 4.2 MoreBubbleButton 的 Launcher 侧证据（✅ 可直接参考）

`MoreBubbleHookModule.java`：

| 行号 | 内容 | 证据 |
|------|------|------|
| 45-48 | `onPackageLoaded` 匹配 `com.google.android.apps.nexuslauncher` / `com.android.launcher3` | 源码 |
| 1146-1183 | `bubbleCurrentTask(...)`：`mLauncherClassLoader.loadClass("com.android.quickstep.SystemUiProxy")` → `INSTANCE.get(ctx)` → 遍历方法名 `showAppBubble` → `invoke(proxy, bIntent, userHandle, ep, null)` | 源码 |
| 1162-1171 | 从 `Task.key.baseIntent` 取 Intent、`Task.key.userId` 取 userId、`task.getTopComponent()` 精确定位组件 | 源码 |
| 1176 | `showAppBubble(Intent, UserHandle, EntryPoint, BubbleBarLocation)` — 方法名+反射找签名 | ✅ 已 DEX 核验（Launcher 17） |

**结论**：MoreBubbleButton 已实证 **Pixel Launcher（nexuslauncher/launcher3）进程可注入、`SystemUiProxy.INSTANCE` 可获取、`showAppBubble` 存在**（README 声明其 Release 在 Android 16+/Pixel Launcher 上工作）。这为任务书路线 A 提供了"源码级存在性"证据；**但**：
- 它只处理"**最近任务中的既有任务**"→ 气泡化，Intent 来源是 Task 的 baseIntent；**不是**"任意新应用直接气泡启动"。
- `showAppBubble` 参数：`(Intent, UserHandle, EntryPoint, null)`；第 4 参传 null，`EntryPoint` 用 `NOTIFICATION`。手势场景需要确定用哪个 EntryPoint 与是否可传 null。
- 是否接受"非现有任务的任意 Intent"**未验证** → 阶段 1 必测。

---

## 5. ColorOS 侧边栏依赖（✅ 可整体旁路）

| 位置 | 类/常量 | Pixel |
|------|--------|-------|
| `ColorOsSidebarTarget.kt:22` | `com.coloros.smartsidebar` | ❌ |
| `ColorOsSidebarTarget.kt:24` | `$PACKAGE_NAME:ui` 进程 | ❌ |
| `ColorOsSidebarTarget.kt:25` | `$PACKAGE_NAME:edgepanel` 进程 | ❌ |
| `ColorOsSidebarTarget.kt:26` | `com.oplus.smartsidebar.panelview.edgepanel.UIService` | ❌ |
| `ColorOsSidebarTarget.kt:30` | `com.coloros.sidebar` provider | ❌ |
| `NativeSidebarHookInstaller.kt:137-139` | `InputUtils` / `EdgePanelService` / `FloatBarMainView` | ❌ |
| `ColorOsAllAppsContent.kt:663-673` | `PanelMainView` / `AllAppPanelView` / `AppLabelData` / `SidebarPlatformBlurHelper` 等 10+ 类 | ❌ |
| `ColorOsToolCatalog.kt:523` | `com.oplus.smartsidebar.aidl.SingleStateType` | ❌ |
| `ColorOsAlphabetGrouper.kt:201-204` | `TitleLabelData` / `ViewType` | ❌ |
| `ColorOsRecentFilesWindow.kt:448-455` | `RecentFilePanel` / `COUICardView` / `com.coloros.common.App` | ❌ |
| `ColorOsSidebarClient.kt` | 整类（BIND_ACTION / OPPO_COMPONENT_SAFE 权限 / `bindServiceForTransferDock`） | ❌ |
| `SharedStateProtocol.kt:45` | `SIDEBAR_PACKAGE` | ❌ |
| `ColorOsFreeformCoordinator.kt:65` | `ColorOsSidebarClient` 实例 | ❌ |

**结论**：侧边栏体系（~15 个文件）全部绑定 ColorOS 包名与 OPPO 私有权限（`oppo.permission.OPPO_COMPONENT_SAFE`）。Pixel 上**这些类永不加载**（包不存在），`ColorOsSidebarClient` 构造/调用会失败。移植时：
1. 侧边栏相关的**目录拉取、工具执行、最近小窗回写**在 Pixel 上无对应物 → 需要新的应用目录来源（`LauncherAppRepository.kt` 已存在，见 §7）或干脆跳过工具类。
2. `ColorOsFreeformCoordinator` 构造时 `sidebar = ColorOsSidebarClient(...)` —— 若 Pixel 初始化入口复用 Coordinator，**必须将 sidebar 依赖改为可空/平台可选**，否则构造即崩。

---

## 6. 输入方案对比（阶段 3 前置侦察）

| 方案 | 类/接口 | 进程 | 证据 | Pixel 可用 | 备注 |
|------|--------|------|------|-----------|------|
| A. WMS 全局指针监听 | `WindowManagerPolicyConstants$PointerEventListener` + `WMS.registerPointerEventListener(listener, displayId)` | system_server | `ColorOsFreeformCoordinator.kt:183-193` | ✅ AOSP 标准 | **优先候选**：与手势引擎/Overlay 同进程，无 IPC；需验证 A17 WMS 该重载签名（2 参）未变 |
| B. SystemUI overlay 输入监控 | `SystemUiCornerInputMonitor`（`setTrustedOverlay` / `InputMonitor` / `pilferPointers`） | SystemUI | `SystemUiHookInstaller.kt:53-61`、`LauncherHookInstaller.kt:139-144` | ⚠️ AOSP API 但实现细节待核 | 两套路径并存于现状代码；SystemUI 侧崩溃影响面大 |
| C. Launcher 触摸服务 | `OplusBaseTouchInteractionService.onInputEventInternal` | Launcher | `LauncherHookInstaller.kt:299-300` | ❌ | 仅 ColorOS |

**建议（阶段 3 验证方向）**：优先方案 A。理由：输入源与手势/菜单 UI 天然同进程（system_server），且已实证存在于现状代码；方案 B 需要 `setTrustedOverlay` 的 A17 行为验证，且 SystemUI 崩溃代价高。**注意**：A17 上 WMS 的 `registerPointerEventListener` 重载（2 参带 displayId vs 1 参旧版）需在目标 ROM DEX 确认（任务书：不得猜测签名）。

---

## 7. 可复用资产（✅ 与平台无关或接近无关）

| 文件 | 说明 | 平台耦合 |
|------|------|---------|
| `gesture/CornerGestureEngine.kt` | 角落手势状态机 | 无（纯算法） |
| `gesture/CornerTriggerRegion.kt` | 触发区域计算 | 无 |
| `gesture/RadialGeometry.kt` | 扇形几何 | 无 |
| `window/CornerRadialOverlayView.kt` | 扇形菜单 Overlay View | 低（AOSP WindowManager 悬浮窗；`TYPE_APPLICATION_OVERLAY` 可用，注意 Android 14+ 触摸限制） |
| `window/AdaptiveOverlayGeometry.kt` / `PanelRevealMotion.kt` / `Radial*Motion.kt` | 动画/几何 | 无 |
| `apps/LauncherAppRepository.kt` | 应用目录（查 PackageManager） | 无（替代侧边栏目录的关键） |
| `apps/AppSelectionPolicy.kt` | 应用选择策略 | 无 |
| `config/*` | 配置快照/编解码 | 无 |
| `framework/*` | 跨进程配置中继（Settings.Global） | 无 |
| `hook/DiagnosticTracer.kt` / `ModuleEnvironmentState.kt` | 诊断/环境闸门 | 低 |
| `hook/SystemUiCornerInputMonitor.kt` | SystemUI 输入监控 | 中（AOSP 隐藏 API，签名待核） |
| `ColorOsFreeformCoordinator.kt` 主体 | 手势协调/提交流程（`onAppCommitted` → `launchCommittedApp`） | **中**（构造依赖 `controller`/`sidebar`，需解耦） |

---

## 8. 风险表（汇总）

| 风险 | 等级 | 影响 | 缓解 |
|------|------|------|------|
| `FlexibleTaskController.systemReady` hook 在 Pixel 不触发 → 协调器永不启动 | 🔴 | 核心功能全灭 | 阶段 3 提供 Pixel 初始化入口（候选：WMS/SystemUI 生命周期方法） |
| 自由窗 Bundle 协议在 Pixel 退化为全屏启动（假成功） | 🔴 | 违背产品目标 | 阶段 1 先证明 Bubble 后端，再替换 `launchCommittedApp` 调用点 |
| Launcher 进程匹配错误 → hook 不进入 | 🟠 | 输入源缺失 | ModuleMain 增加 nexuslauncher 包名（阶段 3.1） |
| Coordinator 构造依赖 `controller`/`sidebar` 字段 | 🟠 | Pixel 初始化时构造崩溃 | 平台适配层解耦（阶段 2.2） |
| `BubbleController.expandStackAndSelectAppBubble` A17 签名未核验 | 🟡 | 反射失败/参数错配 | 阶段 1.2 在目标 DEX 核验；失败安全返回 |
| `SystemUiProxy.showAppBubble` 仅支持既有任务 Intent | 🟡 | 手势任意应用场景不可用 | 阶段 1 实测"非任务 Intent"路径 |
| 侧边栏目录来源缺失 | 🟡 | 应用选择无数据 | 用 `LauncherAppRepository` 替代 |
| A17 `registerPointerEventListener` 重载签名变化 | 🟡 | 输入注册失败 | 反射时按 2 参/1 参候选尝试 |
| MT MCP 服务不可达（~~本次会话 Connection refused~~） | 🟢 | ~~无法自动反编译目标 ROM~~ | **已恢复（2026-10-18）**：已反编译 SystemUI 17 / Pixel Launcher 17 完成 DEX 核验（§10）。首次连接失败作为历史记录保留（当时 MT 服务尚未启动） |

---

## 9. 待真机 / 目标 ROM 验证清单（本轮未验证项）

1. Pixel ROM 实际 `Build.FINGERPRINT` / SDK_INT / 构建号（任务 0.3）。
2. `com.android.server.wm` 下是否存在任何 Flexible 前缀类（SystemUI APK 已确认无，但 system_server 的 framework.jar 不在 APK 内，仍待系统镜像核验）。
3. `WMS.registerPointerEventListener` 在 A17 的精确签名（framework 层，不在 SystemUI/Launcher APK 内，待系统镜像核验）。
4. ~~`SystemUiProxy.showAppBubble` 的精确签名与第 4 参是否可传 null~~ → **已 DEX 核验**：`SystemUiProxy.showAppBubble(Intent, UserHandle, EntryPoint, BubbleBarLocation)V`（见 §10）。
5. ~~`BubbleController.expandStackAndSelectAppBubble` A17 精确签名~~ → **已 DEX 核验**：`expandStackAndSelectAppBubble(Bubble, EntryPoint, UpdateLocationRequest)V`（见 §10）。
6. ~~`Bubble` 构造函数 `(Intent, UserHandle, Icon, BubbleType, String)` 可用性~~ → **已 DEX 核验**（见 §10）。
7. Pixel Launcher 实际包名 → **已 DEX 核验**：`com.google.android.apps.nexuslauncher` v17/907（见 §10）。
8. LSPosed 在 Pixel ROM 上对 system_server / SystemUI / Launcher 三进程的注入能力（待真机）。
9. 微信 / 起点读书 / 普通测试应用的气泡兼容性（阶段 5 测试矩阵，待真机）。
10. `showAppBubble` 是否接受"非现有任务的任意 Intent"（待真机，阶段 1 必测）。

---

## 10. 阶段 0 补充：DEX 核验结果（MT 反编译，2026-10-18）

### 10.1 核验素材

| APK | 包名 | 版本 | versionCode | minSdk | 工作区 ID | 大小 | 签名证书 SHA-256 |
|-----|------|------|-------------|--------|-----------|------|-----------------|
| Pixel 启动器_17.apk | `com.google.android.apps.nexuslauncher` | 17 | 907 | 37 | `holgxhyk` | 17,335,691 B | `a86bdb059f28f265162d64ce6c8d9772901d227e741581d1f04a5ed132a574d0` |
| 系统界面_17.apk | `com.android.systemui` | 17 | 37 | 37 | `81i4aezl` | 48,329,834 B | `6b969075087141e27e9daab39e18bafac34ea3ef8d469986530f83f85fd5d70e` |
| 微信_8.0.79.apk | `com.tencent.mm` | 8.0.79 | — | — | `43iws0i1` | 285,284,760 B | `0fe4ff85c215918396dadc7cd8ce6963339af33d37751a56e54c7206b63a3c7c` |
| 起点读书_7.9.480.apk | `com.qidian.QDReader` | 7.9.480 | — | — | `gg5gw90n` | 119,058,710 B | `675e09c940ea63c6fde1552eb8c868743335df7fa60f1ef864ebc13612cc957e` |
| Telegram_13.0.3.apk | `org.telegram.messenger` | 13.0.3 | — | — | `fp7m07t5` | 70,395,155 B | `49c1522548ebacd46ce322b6fd47f6092bb745d0f88082145caf35e14dcc38e1` |

**来源路径**：全部位于 MT 设备侧 `/storage/emulated/0/MT2/mcp/`。

**证据说明**：
- 证书 SHA-256 由 `mt_apk_read_signature` 直接读取（比文件哈希更强的真伪证据——证书由 APK 签名私钥生成，伪造需同一私钥）。
- `系统界面_17.apk` 证书 subject 为 `CN=cnmrlin`（**第三方定制 ROM 签名，非 Google 原厂**）——核验结论限定于该构建；`Pixel 启动器_17.apk` 为 Google 签名（与 Play 版 nexuslauncher 一致）。
- **来源同源性分析（2026-10-18 补充）**：
  - 两者都是 `compileSdkVersion=37 / versionName=17 / minSdk=37 / targetSdk=37` 的 A17 构建。
  - Launcher manifest：`com.google.android.apps.nexuslauncher`，`uses-feature PIXEL_EXPERIENCE required=true` → **Google 原厂 Pixel 构建**。
  - SystemUI manifest：`sharedUserId=android.uid.systemui`、`coreApp=true`；**不含** `cnmrlin` 字符串；**含** `ScreenDecorations`、`UdfpsOverlayInteractor`、`RoundedCornerResDelegateImpl` 等 **Pixel 专属硬件类**（屏幕装饰/屏下指纹/圆角协调）。
  - **结论**：SystemUI 是**目标 Pixel 设备的 A17 定制 ROM 组件**（重签名，作者 cnmrlin），Launcher 是**原厂 Pixel 构建**。两者同属 A17 Pixel 生态，但 **SystemUI 与目标设备实际运行的 ROM 是否逐字节一致仍未证实**——需真机比对（`pm path` + 签名哈希）。
- **文件级 SHA-256 回填命令**（真机执行，MT 目录下）：

```bash
sha256sum /storage/emulated/0/MT2/mcp/Pixel\ 启动器_17.apk
sha256sum /storage/emulated/0/MT2/mcp/系统界面_17.apk
sha256sum /storage/emulated/0/MT2/mcp/微信_8.0.79.apk
sha256sum /storage/emulated/0/MT2/mcp/起点读书_7.9.480.apk
sha256sum /storage/emulated/0/MT2/mcp/Telegram_13.0.3.apk
```

> `81i4aezl` 与 MoreBubbleButton README 记载的"MT 工作区 `81i4aezl`"完全一致——证实这套 APK 就是作者核验时使用的目标素材。

### 10.2 核验结果（命中即 `dex_class`/`dex_method` locator 存在）

| 目标符号 | 位置 | 结果 | 精确签名 |
|----------|------|------|---------|
| `BubbleController.expandStackAndSelectAppBubble` | SystemUI | ✅ **存在** | `(Lcom/android/wm/shell/bubbles/Bubble;Lcom/android/wm/shell/shared/bubbles/logging/EntryPoint;Lcom/android/wm/shell/shared/bubbles/BubbleBarLocation$UpdateLocationRequest;)V` |
| `Bubble#<init>(Intent,UserHandle,Icon,BubbleType,String)` | SystemUI | ✅ **存在** | 见上；另有 `(BubbleEntry, Bubbles$BubbleMetadataFlagListener, PendingIntentCanceledListener, Executor)` |
| `Bubble.getAppBubbleKeyForApp` | SystemUI | ✅ **存在** | `(Ljava/lang/String;Landroid/os/UserHandle;)Ljava/lang/String;`（static） |
| `BubbleData.findAndRemoveBubbleFromOverflow` | SystemUI | ✅ **存在** | `(Ljava/lang/String;)Lcom/android/wm/shell/bubbles/Bubble;` |
| `BubbleController.mBubbleData`（字段，A17 反射配方依赖） | SystemUI | ✅ **存在** | `BubbleController;->mBubbleData:Lcom/android/wm/shell/bubbles/BubbleData;` |
| `Bubble$BubbleType` | SystemUI | ✅ **存在** | 枚举成员 `TYPE_APP` / `TYPE_CHAT` / `TYPE_NOTE` / `TYPE_SHORTCUT` |
| `EntryPoint` | SystemUI | ✅ **存在** | 枚举成员 `NOTIFICATION` / `NOTIFICATION_BUBBLE_BUTTON` / `TASKBAR_ICON_DRAG` |
| `BubbleBarLocation$UpdateLocationRequest` | SystemUI | ✅ **存在** | `expandStackAndSelectAppBubble` 第 3 参类型 |
| `BubbleTaskView` | SystemUI | ✅ **存在** | A17 双击转全屏路径依赖 |
| `BubbleController$IBubblesImpl` | SystemUI | ✅ **存在** | Binder 实现；`this$0 → BubbleController` 字段确认 |
| `SystemUiProxy.showAppBubble` | **Launcher** | ✅ **存在** | `(Landroid/content/Intent;Landroid/os/UserHandle;Lcom/android/wm/shell/shared/bubbles/logging/EntryPoint;Lcom/android/wm/shell/shared/bubbles/BubbleBarLocation;)V` |
| `QuickstepLauncher.showAppBubble` | Launcher | ✅ **存在** | `(Intent, UserHandle, EntryPoint)V`（Launcher 自带 App 信息气泡入口） |
| `SystemShortcut$BubbleActivityStarter.showAppBubble` | Launcher | ✅ **存在** | `(Intent, UserHandle, EntryPoint)V` |
| `BaseTaskbarContext.showAppBubble` | Launcher | ✅ **存在** | `(Intent, UserHandle, EntryPoint)V` |
| `com.android.quickstep.views.RecentsView#getCurrentPageTaskView` | Launcher | ✅ **存在** | `()Lcom/android/quickstep/views/TaskView;` |
| `RecentsViewInteractor`（taskbar 侧） | Launcher | ✅ **存在** | `com.android.launcher3.taskbar.RecentsViewInteractor` |
| `OplusBaseTouchInteractionService` | SystemUI | ❌ **不存在** | 0 命中 → 确认 ColorOS 专属 |
| `FlexibleTaskController` | SystemUI | ❌ **不存在** | 0 命中 → 确认 ColorOS 专属 |
| `PointerEventListener`（framework 层接口） | SystemUI | ⚠️ 未命中（**符合预期**） | framework.jar 接口，不在 SystemUI/Launcher APK 内；`GesturePointerEventListener` 存在佐证手势体系 |
| `IBubbles`（AIDL 接口） | SystemUI | ⚠️ 无独立 dex_class | `IBubblesImpl` 的 `onTransact` 动态分发；接口定义可能在 framework 或 Binder stub |

### 10.3 核验结论（对移植的意义）

1. **路线 B 入口完整成立**：`expandStackAndSelectAppBubble(Bubble, EntryPoint, UpdateLocationRequest)` + `Bubble` 5 参构造 + `TYPE_APP` + `NOTIFICATION_BUBBLE_BUTTON` + `mBubbleData` 字段，与 MoreBubbleButton 的 A17 配方**逐项吻合**，签名全部在目标 ROM 核验通过。
2. **路线 A 入口成立且有系统级佐证**：`SystemUiProxy.showAppBubble(Intent, UserHandle, EntryPoint, BubbleBarLocation)` 在 Pixel Launcher 17 存在，且 Launcher 自带 3 处调用点（快捷方式/任务栏/QuickstepLauncher）——证明 A17 系统自己就用此路径创建 app bubble。
3. **A17 气泡是 wmshell 事务**：`SystemUiProxy.showAppBubble` 方法体经 `IBubbles` AIDL `transact`（`writeTypedObject(Intent)` + `writeTypedObject(UserHandle)`）→ SystemUI `IBubblesImpl.onTransact` 分发。调用方无需关心事务码，但**进程边界确认**：手势进程（system_server）→ 气泡后端（SystemUI wmshell）跨进程。
4. **ColorOS 依赖确认在 Pixel 全部缺失**：`OplusBaseTouchInteractionService`、`FlexibleTaskController` 在 SystemUI 17 dex 均 0 命中，坐实 §2 的推断（framework.jar 内的 WMS 类待系统镜像核验，但 SystemUI 侧已无任何 Flexible 痕迹）。
5. **framework 层两项仍待核验**：`WMS.registerPointerEventListener` 签名、`PointerEventListener` 接口（均在 framework.jar / system_server，不在 APK 内）——需系统镜像或真机反射验证。

---

## 11. 阶段 1 最小验证方案（审查报告要求）

> 目的：以**最小成本**将 L1（签名存在）升级为 L3（端到端可用），不写业务代码。
> 原则：**用官方/开源现成模块验证后端，不自己发明调用**。

### 11.1 验证目标（按优先级）

| # | 假设 | 验证方法 | 通过判据 |
|---|------|---------|---------|
| V1 | `SystemUiProxy.showAppBubble` 接受任意 Intent 创建新 app bubble | 安装原版 MoreBubbleButton v1.7，勾选 SystemUI + nexuslauncher 作用域，从「最近任务」点气泡按钮启动**从未开过的应用**（起点读书） | 起点读书气泡出现于气泡栏，且可展开为悬浮窗 |
| V2 | `expandStackAndSelectAppBubble` 接受"构造 Bubble + 任意 Intent" | 同上，用「当前任务气泡化」按钮启动另一新应用（微信） | 微信气泡出现，悬浮窗展开正常 |
| V3 | LSPosed 可注入 nexuslauncher 进程 | 观察 LSPosed 日志中模块对 nexuslauncher 的注入记录 | `Loaded Xposed module ... into process ...nexuslauncher` |
| V4 | 跨进程手势→气泡链路（本项目最终形态的预研） | 阶段 1 不做代码，仅确认：system_server 注入能力（LSPosed 对 system_server 勾选后日志） | 日志显示 system_server 注入成功 |

### 11.2 最小工具链（全部开源/现有）

| 组件 | 来源 | 用途 |
|------|------|------|
| LSPosed | 官方 | 注入 SystemUI / nexuslauncher / system_server |
| MoreBubbleButton v1.7（**原版未修改**） | `upstream/MoreBubbleButton` @ `81630ab` | 现成的气泡按钮入口（路径 L + S 都已实现） |
| GitHub Actions 编译 APK | 开发仓库 | 产出可安装 APK（compileSdk 37，任务书要求不本地构建） |
| MT 工作区 `81i4aezl` / `holgxhyk` | 现有 | 复核/调试签名 |

### 11.3 验证流程（用户真机侧，阶段 1）

1. 在开发仓库启用 GitHub Actions，构建 **MoreBubbleButton v1.7 原版** APK（不改代码）。
2. 安装 APK → LSPosed 勾选 `SystemUI` + `com.google.android.apps.nexuslauncher`（不勾 system_server，先验证普通路径）。
3. 测试矩阵（对应 V1/V2）：
   - 冷启动路径：开一个**从未气泡化过**的应用（起点读书）→ 最近任务 → 点气泡按钮 → 是否出现气泡。
   - 热启动路径：同一应用二次进入 → 气泡按钮 → 气泡是否复用（验证 `findAndRemoveBubbleFromOverflow` 行为）。
4. 记录：气泡是否出现 / 悬浮窗是否可展开 / 双击是否转全屏 / 微信与起点读书差异。
5. 回填 `DEVICE_BASELINE_TEMPLATE.md` §1/§2/§4 + APK 文件 SHA-256。

### 11.4 预期结果与分支

- **V1 通过** → 路线 A 成立（任意 Intent 可气泡化），阶段 2 在 system_server 侧实现手势→`SystemUiProxy` 调用（或直接 Binder）。
- **V1 失败、V2 通过** → 路线 B 成立，阶段 2 需新增 system_server→SystemUI 的 IPC 入口（最大技术决策点）。
- **V1/V2 都失败** → 气泡后端被 ROM 改动/裁剪，需检查 `BubbleController` 初始化条件（`bubblesEnabled`/feature flag）——回到 DEX 深挖。
- **V3 失败**（nexuslauncher 注入被拒）→ 检查 LSPosed 作用域/黑名单，或改用 launcher3 变体。

### 11.5 成功标准（严格）

- ✅ 成功 = **气泡出现且可展开为悬浮窗**。
- ❌ 不算成功 = 应用直接全屏打开（任务书禁止的"假成功"）、无反应、仅通知栏出现气泡。

*文档结束 · PIXEL_PORT_AUDIT.md · 阶段 0 · 只读侦察 + DEX 核验 + 阶段 1 预研*
