# phase0_source_audit.md — 阶段 0 源码审计记录

> 审查报告 P0 交付物 #1 · 2026-10-18
> 审计性质：只读侦察（未修改任何业务源码）
> 审计对象：FlymeFreeform `b2c8779`（v0.2.6）、MoreBubbleButton `81630ab`（v1.7）

## 1. 审计范围与方法

- 方法：`git clone` 两个上游仓库 → 全仓 `rg` 关键符号检索 → 逐文件读取关键 Hook/协调器/启动器源码 → 整理平台依赖与调用链。
- 未执行：编译、运行、真机测试、任何代码修改。
- 原始工作区：`/var/minis/workspace/flyme-bubbles/{FlymeFreeform,MoreBubbleButton}`（本会话只读）。

## 2. FlymeFreeform 审计结果

### 2.1 平台专用依赖（ColorOS/Oplus）

| 依赖符号 | 使用文件 | 行号 | 证据类型 |
|----------|---------|------|---------|
| `com.android.server.wm.FlexibleTaskController` | `hook/SystemServerHookInstaller.kt` | 55, 96 | 源码 `loadClass` + `getDeclaredMethod("systemReady")` |
| `FlexibleTaskController.systemReady(boolean)` | `hook/SystemServerHookInstaller.kt` | 57-58 | 源码 hook 目标 |
| `com.android.server.wm.FlexibleTaskScaleManager` | `hook/HandleSwipeUpHookInstaller.kt` | 27, 120 | 源码 `loadClass` |
| `com.android.server.wm.Task` | `hook/HandleSwipeUpHookInstaller.kt` / `OutsideTapCloseHookInstaller.kt` | 29/39, 122 | 源码 |
| `com.android.quickstep.OplusBaseTouchInteractionService` | `hook/LauncherHookInstaller.kt` | 48, 299 | 源码 `loadClass` |
| `OplusBaseTouchInteractionService.onInputEventInternal` | `hook/LauncherHookInstaller.kt` | 56-58 | 源码 hook |
| `OplusBaseTouchInteractionService.DISPATCH_...`（手势模式常量） | `hook/HandleSwipeUpHookInstaller.kt` | 46 | 源码 |
| ColorOS Sidebar（`smartsidebar:ui` 进程） | `platform/coloros/ColorOsSidebarTarget.kt` | 全文 | 源码（进程目标） |
| ColorOS 自由窗启动协议 `windowingMode=100`（`FLEXIBLE_WINDOWING_MODE`） | `platform/coloros/ColorOsFreeformLauncher.kt` | 全文 | 源码 |

### 2.2 手势 → 自由窗启动调用链

| 步骤 | 文件 | 行号 |
|------|------|------|
| 初始化入口：hook `FlexibleTaskController.systemReady` | `hook/SystemServerHookInstaller.kt` | 55-70 |
| 协调器启动：`ColorOsFreeformCoordinator.start()` | `hook/SystemServerHookInstaller.kt` | 68 |
| 手势提交：`onAppCommitted(RadialAppEntry)` | `platform/coloros/ColorOsFreeformCoordinator.kt` | 449-457 |
| 启动提交应用：`launchCommittedApp(entry)` | `platform/coloros/ColorOsFreeformCoordinator.kt` | 459-486 |
| 实际启动：`launcher.launch(component)` → `FreeformLaunchResult` | `platform/coloros/ColorOsFreeformCoordinator.kt` | 479-486 |
| 触摸入口：hook `OplusBaseTouchInteractionService` | `hook/LauncherHookInstaller.kt` | 48-58 |
| 侧边栏目标：`smartsidebar:ui` 进程匹配 | `platform/coloros/ColorOsSidebarTarget.kt` | 全文 |

### 2.3 与 Pixel 环境的差异（源码层面）

| 项 | FlymeFreeform 假设 | Pixel A17 实际 | 结论 |
|----|-------------------|---------------|------|
| Launcher 包名 | `com.android.launcher`（`LauncherHookInstaller` 进程匹配） | `com.google.android.apps.nexuslauncher` | 不匹配，Launcher 分支不生效 |
| 触摸服务 | `OplusBaseTouchInteractionService` | 不存在（DEX 0 命中） | Hook 静默失败 |
| 自由窗控制器 | `FlexibleTaskController` | 不存在（DEX 0 命中） | 初始化入口永不触发 |
| 启动模式 | `windowingMode=100` | 无此协议 | 退化为全屏（伪成功） |

## 3. MoreBubbleButton 审计结果

### 3.1 主文件与调用链

- 主文件：`app/src/main/java/com/floatwindow/morebubblebutton/MoreBubbleHookModule.java`（1313 行）。
- 气泡启动两条路径（详见 `BUBBLE_CALL_CHAIN.md` §2）：
  - 路径 L（Launcher）：`SystemUiProxy.showAppBubble(Intent, UserHandle, EntryPoint, BubbleBarLocation)` — Launcher 进程。
  - 路径 S（SystemUI）：`expandAppBubbleA17` 构造 `Bubble(Intent, UserHandle, Icon, TYPE_APP, key)` → `BubbleController.expandStackAndSelectAppBubble(Bubble, EntryPoint, UpdateLocationRequest)` — SystemUI 进程。

### 3.2 关键行号（`MoreBubbleHookModule.java`）

| 逻辑 | 行号（`81630ab`） |
|------|------|
| `bubbleCurrentTask`（Launcher 路径） | ~1123 起 |
| `SystemUiProxy` 反射与 `showAppBubble` 调用 | ~1140-1180 |
| `expandAppBubbleA17` 构造 Bubble | ~350-520 |
| `findEntryPoint`（EntryPoint 枚举反射） | ~634-646 |
| `injectBubbleMetadata`（通知元数据注入） | ~680-700 |
| Launcher Hook 目标（RecentsView/TaskView） | 见 BUBBLE_CALL_CHAIN §2.2 |

## 4. 审计结论

- **已识别全部 ColorOS 专用依赖**：4 个 server.wm 类 + 1 个 Oplus 触摸服务 + Sidebar 进程 + 自由窗协议。
- **Pixel A17 上这些依赖全部缺失**（DEX 0 命中，见 phase0_mt_mcp_capabilities.md 附录）。
- **气泡后端两条路线签名已在目标 APK 核验**（见 BUBBLE_CALL_CHAIN §9），但"签名存在 ≠ 后端可用"——权限/语义/可见性待真机。
- **上游源码零改动**：两仓库 `git status` 均干净；本阶段所有产物仅新增于 `docs/`。

## 5. 未验证项（明确列出）

1. `WMS.registerPointerEventListener` 签名（framework.jar / system_server 层，无法 APK 核验）。
2. `PointerEventListener` 接口（framework 层）。
3. 所有真机行为：LSPosed 注入、气泡可见性、任意 Intent 语义、微信/起点读书兼容性。
4. APK 文件级 SHA-256（证书 SHA-256 已核验；文件级哈希待真机 `sha256sum` 回填，见 DEVICE_BASELINE_TEMPLATE §8）。

*记录结束 · 只读审计 · 无源码改动*
