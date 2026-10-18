# Pixel A17 气泡后端运行时发现记录（RUNTIME FINDINGS）

> 阶段 1 交付物 · 运行时验证的持续记录文件
> 目的：**将三类证据严格分层**——静态签名证据（L1）、方法逻辑证据（L2）、真机运行证据（L3），
> 避免把"类存在"误当"功能可用"。本文件随阶段 1 验证推进持续更新。

---

## 0. 证据分级约定

| 级别 | 名称 | 获取方式 | 含义 |
|------|------|---------|------|
| **L1** | 静态签名证据 | MT 反编译 APK（dex_class / dex_method locator 命中） | 类/方法/字段**存在**且签名与预期一致 |
| **L2** | 方法逻辑证据 | outline 方法体 / xref 调用关系 | 方法**行为**符合预期（如 `expandStackAndSelectAppBubble` 接受构造好的 Bubble） |
| **L3** | 真机运行证据 | 目标设备实测（LSPosed 日志 / 气泡可见 / dumpsys） | **端到端可用**，气泡实际出现且可展开 |

> ⚠️ **L1/L2 ≠ L3**：签名存在只证明"代码在 ROM 里"，不证明"目标设备上权限/行为/触发链可用"。阶段 1 通过条件 = 至少一条路线达到 L3。

---

## 1. 已建立证据（截至 2026-10-18，阶段 0 尾声）

### 1.1 L1 静态签名证据（DEX 核验，全部 locator 可复核）

| 符号 | 证据级别 | 结果 | APK 来源 |
|------|---------|------|---------|
| `com.android.wm.shell.bubbles.BubbleController#expandStackAndSelectAppBubble(Bubble, EntryPoint, UpdateLocationRequest)V` | L1 | ✅ 存在 | SystemUI 17（`81i4aezl`） |
| `com.android.wm.shell.bubbles.Bubble#<init>(Intent, UserHandle, Icon, BubbleType, String)V` | L1 | ✅ 存在 | SystemUI 17 |
| `com.android.wm.shell.bubbles.Bubble#getAppBubbleKeyForApp(String, UserHandle)String` | L1 | ✅ 存在 | SystemUI 17 |
| `com.android.wm.shell.bubbles.BubbleData#findAndRemoveBubbleFromOverflow(String)Bubble` | L1 | ✅ 存在 | SystemUI 17 |
| `BubbleController.mBubbleData:BubbleData`（字段） | L1 | ✅ 存在 | SystemUI 17 |
| `Bubble$BubbleType`（`TYPE_APP`/`TYPE_CHAT`/`TYPE_NOTE`/`TYPE_SHORTCUT`） | L1 | ✅ 存在 | SystemUI 17 |
| `EntryPoint`（`NOTIFICATION`/`NOTIFICATION_BUBBLE_BUTTON`/`TASKBAR_ICON_DRAG`） | L1 | ✅ 存在 | SystemUI 17 |
| `BubbleBarLocation$UpdateLocationRequest` | L1 | ✅ 存在 | SystemUI 17 |
| `BubbleTaskView` / `BubbleTaskViewController` / `BubbleTaskViewFactory` | L1 | ✅ 存在 | SystemUI 17 |
| `BubbleController$IBubblesImpl`（`this$0→BubbleController`） | L1 | ✅ 存在 | SystemUI 17 |
| `com.android.quickstep.SystemUiProxy#showAppBubble(Intent, UserHandle, EntryPoint, BubbleBarLocation)V` | L1 | ✅ 存在 | Pixel Launcher 17（`holgxhyk`） |
| `SystemUiProxy$Companion.INSTANCE` | L1 | ✅ 存在 | Pixel Launcher 17 |
| `RecentsView#getCurrentPageTaskView()TaskView` | L1 | ✅ 存在 | Pixel Launcher 17 |
| `QuickstepLauncher#showAppBubble(Intent,UserHandle,EntryPoint)V` | L1 | ✅ 存在 | Pixel Launcher 17 |
| `SystemShortcut$BubbleActivityStarter` / `BaseTaskbarContext#showAppBubble` | L1 | ✅ 存在 | Pixel Launcher 17 |
| ❌ `OplusBaseTouchInteractionService` / `FlexibleTaskController` / `FlexibleTaskScaleManager` | L1（否定） | ❌ 0 命中 → ColorOS 专属确认 | SystemUI 17 |

### 1.2 L2 方法逻辑证据（outline 方法体）

| 方法 | 逻辑证据 | 含义 |
|------|---------|------|
| `SystemUiProxy.showAppBubble` | 方法体写 `Parcel.writeInterfaceToken("com.android.wm.shell.bubbles.IBubbles")` + `writeTypedObject(Intent)` + `writeTypedObject(UserHandle)` + `IBinder.transact` | **确认是 AIDL 跨进程调用**（Launcher → SystemUI wmshell）。调用方无需关心 transact 码 |
| `BubbleController.expandStackAndSelectAppBubble` | outline 显示调用 `Bubble.isInflated` / `updateExpandedViewForBubbleBarLocation` / `BubbleLogger.logEntryPoint` | 方法**接受构造好的 Bubble 对象**直接进入展开流程（与 MoreBubbleButton 注释"官方配方"一致） |
| `BubbleController$IBubblesImpl` | 仅 4 方法：`asBinder`/`invalidate`/`onTransact`/`<init>`；`onTransact` 统一分发 | Binder 事务码对调用方透明；SystemUI dex 无 `showAppBubble` 方法名（在 AIDL 协议中） |

### 1.3 L3 真机运行证据

> **当前为空。** 阶段 1 目标即填满此表。严禁以 L1/L2 冒充 L3。

| 验证项 | 结果 | 证据（日志/截图/命令输出） |
|--------|------|---------------------------|
| （待）`SystemUiProxy.showAppBubble` 处理非当前任务 Intent | — | — |
| （待）`expandStackAndSelectAppBubble` 端到端可用 | — | — |
| （待）LSPosed 注入 SystemUI / nexuslauncher / system_server | — | — |
| （待）微信 / 起点读书 气泡实机表现 | — | — |

---

## 2. 真机验证记录表（阶段 1 填充）

### 2.1 设备基线（对应 DEVICE_BASELINE_TEMPLATE.md）

| 项 | 值 | 采集命令 |
|----|----|---------|
| 设备型号 | | `getprop ro.product.model` |
| Android 版本 | | `getprop ro.build.version.release` |
| SDK | | `getprop ro.build.version.sdk` |
| Build fingerprint | | `getprop ro.build.fingerprint` |
| SystemUI 实际版本 | | `dumpsys package com.android.systemui \| grep -E "versionName|versionCode"` |
| SystemUI 安装路径 | | `pm path com.android.systemui` |
| Launcher 实际版本 | | `dumpsys package com.google.android.apps.nexuslauncher \| grep versionName` |
| LSPosed 版本 | | LSPosed Manager → 设置 |
| LSPosed 注入日志 | | LSPosed Manager → 日志 |

### 2.2 路线 A（Launcher 侧）验证

**测试矩阵**（每项记录 `BUBBLE_REQUEST_SUBMITTED` 与气泡可见性分开）：

| 用例 | 操作 | BUBBLE_REQUEST_SUBMITTED | 气泡实际出现 | 展开为悬浮窗 | 备注 |
|------|------|------------------------|-------------|-------------|------|
| A1 冷启动·起点读书 | 开起点读书（从未气泡化）→ 最近任务 → 气泡按钮 | | | | |
| A2 冷启动·微信 | 开微信 → 最近任务 → 气泡按钮 | | | | |
| A3 热启动·同应用 | 起点读书二次进入 → 气泡按钮 | | | | 验证 `findAndRemoveBubbleFromOverflow` 复用 |
| A4 普通测试应用 | 开测试应用 → 气泡按钮 | | | | 对照 |

### 2.3 路线 B（SystemUI 侧）验证

| 用例 | 操作 | 记录 | 结论 |
|------|------|------|------|
| B1 | 若 A 路线失败，验证 `expandStackAndSelectAppBubble` 构造路径 | 对象构造 / 执行上下文 / 调用权限 / 跨进程边界 | |

### 2.4 成功判据（严格）

- ✅ **成功** = `BUBBLE_REQUEST_SUBMITTED=true` **且** 气泡实际出现 **且** 可展开为悬浮窗（L3）。
- ⚠️ 部分成功 = 请求已提交但气泡未出现 → 记录后端拒绝原因（feature flag / 权限 / 进程）。
- ❌ 失败 = 无响应 / 直接全屏打开（任务书禁止的"假成功"）→ 记录日志与原因。
- 每项必须区分：**请求已提交** vs **气泡可见** 是两个独立事实。

---

## 3. 修订历史

| 日期 | 变更 |
|------|------|
| 2026-10-18 | 创建；登记 L1/L2 证据（阶段 0 DEX 核验结果）；L3 空表待阶段 1 填充 |
| 2026-10-18 | §4 新增：APK 云端构建完成（GitHub Actions），待真机安装验证 |

---

## 4. 阶段 1 进展：APK 云端构建（2026-10-18，已修正）

### 4.0 分支修正记录（重要）

> **2026-10-18 修正**：首次构建误用了 `master` 分支（@ `81630ab`，10-15），
> 但**正确源码在 `feature/a17-bubble-corner-actions` 分支**（@ `706b0f6`，10-16，16 个提交领先 master）。
> feature 分支包含关键 A17 修改：
> - **右角动作使用官方 Bubble → 全屏 handoff**（不再走旧的全屏转换）
> - **管理按钮双按钮方案**（左下角=关闭小窗、右侧克隆按钮=全屏展开 `moveTaskViewToFullscreen`）
> - 删除旧 `BubbleIconDoubleTap` 与旧 tests
> - 自带 `.github/workflows/build-release.yml`（无 keystore 时输出 unsigned release APK）
>
> 已改用 feature/a17 分支重新构建。**master 分支产物作废**（`MoreBubbleButton-v1.7-debug.apk` 仅存档）。

### 4.1 构建配置（修正后）

| 项 | 值 |
|----|----|
| 源码 | `feature/a17-bubble-corner-actions` @ `706b0f6`（**16 提交领先 master**，未改动） |
| 构建平台 | GitHub Actions（Ubuntu 24.04）+ JDK 17 |
| Gradle | 9.4.1（wrapper） |
| workflow | 作者自带 `build-release.yml`（注册于 main 分支，`workflow_dispatch` 触发） |
| 产物 | `app-release-unsigned.apk`（release 未签名，LSPosed 可安装） |

### 4.2 构建结果（修正后）

| 项 | 值 |
|----|----|
| 运行 ID | `39093084336`（首次 `39092831159` 因 wrapper jar 缺失失败） |
| 结果 | ✅ **success** |
| 分支 | `ci-build` @ `be8c37e`（feature/a17 源码 + 强制跟踪 wrapper jar） |
| APK | `MoreBubbleButton-v1.7-A17-unsigned.apk` |
| 大小 | 1,704,361 B |
| **SHA-256** | `c6804dd6ef7bf30faa8e611f47de9b3c61049ebe87db02f05e12822161c8c689` |
| 归档 | `artifacts/MoreBubbleButton-v1.7-A17-unsigned.apk` |
| 内容验证 | ✅ APK 内含 `expandAppBubbleA17` / `bubbleCurrentTask`（feature/a17 分支特征代码） |

### 4.3 调试过程（修正后 4 次迭代）

1. `465f348` — master 分支 + 自写 workflow，路径错误。
2. `72b3fce` — 路径修正，但 wrapper jar 被 `.gitignore` 排除 → `GradleWrapperMain` 缺失。
3. `b2ae999` — 强制跟踪 jar → ✅ 构建成功（**但误用 master 分支**）。
4. `e0e96b1` → `be8c37e` — **改用 feature/a17 分支** + 作者自带 workflow + 强制跟踪 jar → ✅ 成功。

### 4.4 下一步（真机验证，等你操作）

1. 真机安装 `MoreBubbleButton-v1.7-A17-unsigned.apk`（LSPosed 勾选 `SystemUI` + `com.google.android.apps.nexuslauncher`）。
2. 按 §2 测试矩阵执行 A1-A4，记录 `BUBBLE_REQUEST_SUBMITTED` 与气泡可见性（分开记录）。
3. 回填 §2.3 表格 + `DEVICE_BASELINE_TEMPLATE.md`。

*文档结束 · PIXEL_BUBBLE_RUNTIME_FINDINGS.md · 阶段 1 运行时验证记录*

---

# 阶段 2 真机验证记录（L3 达成）

> 日期：2026-10-18 · 设备：Android 17（SDK 37）· 目标：微信 `com.tencent.mm/.ui.LauncherUI`
> 结论：**✅ L3 端到端可用 —— 微信气泡小窗成功弹出**（产物 SHA `d0830ef5`，phase2 @ `082d4f9`）

## 5. 达成的证据链

| 证据 | 值 | 说明 |
|------|----|------|
| controller 绑定 | `PIXEL_BUBBLE_CONTROLLER_CAPTURED via=BUBBLES_MANAGER / CONTROLLER / IBUBBLES_IMPL` | 三条路径均命中，`available=true` |
| 广播接收 | `PIXEL_BUBBLE_TEST_RECEIVED action=...ACTION_TEST_LAUNCH_BUBBLE` | 双 action 兼容生效 |
| A17 配方 | `BUBBLE_A17 step1..step10` 全过 | 无 `stepN miss` |
| 提交结果 | `BUBBLE_TEST_RESULT BUBBLE_REQUEST_SUBMITTED available=true` | 同步等待 `finished=true ok=true` |
| **视觉** | **微信气泡小窗弹出** | ✅ L3 |

## 6. 阶段 2 排查出的 6 个连环缺陷（按发现顺序）

| # | 现象 | 根因 | 修复 |
|---|------|------|------|
| 1 | 广播 `result=0` 但无任何反应 | 接收器未注册 `RECEIVER_EXPORTED`（API 34+ 要求） | 显式 `Context.RECEIVER_EXPORTED` |
| 2 | `launcherAvailable=false` | A17 无 `BubbleController.INSTANCE` 静态单例 | 改走 `BubblesManager.mBubbles → this$0` 链 |
| 3 | 仍 `launcherAvailable=false` | hook 构造器装在 `SystemUIApplication.onCreate` 之后，对象早已构造 | 改 hook **任意实例方法**（启动后必调），三路径独立首次命中 |
| 4 | 广播永不触发处理 | `IntentFilter` 注册双 action，但 `onReceive` 首行只认主 action，LEGACY 被静默丢弃 | `onReceive` 接受双 action |
| 5 | **所有 `BUBBLE_A17` 日志缺失** | `PixelAppBubbleLauncher` 用 `android.util.Log`，LSPosed 模块进程内**不写入模块日志文件**，只有 `module.log()` 可见 —— 导致多轮误判「配方全过」 | 增加 `debugSink` 钩子转发 `module.log()` |
| 6 | `BUBBLE_A17 step4 miss` | `Intent.setComponent()` **不填充** `getPackage()`，配方第 4 步 `intent.getPackage() ?: return false` | `resolveTarget` 显式 `setPackage(component.packageName)` |

> **教训（写入 §0 证据分级补充）**：日志通道本身也是被测对象。`android.util.Log` 在 LSPosed 模块进程内不可见于模块日志，任何诊断代码必须走 `module.log()`，否则「无输出」会被误读为「无问题」。

## 7. 阶段 2 收尾交付（本次补充）

### 7.1 手势 → 气泡闭环接线

此前 `AppLaunchCommitter` 只有接口定义、**全项目无实现类**，`ColorOsFreeformCoordinator` 的 `committer` 参数恒为 `null`，扇形手势始终回落到 ColorOS 原生启动。现补齐：

| 文件 | 作用 |
|------|------|
| `platform/pixel/PixelBubbleBackend.kt` | SystemUI 进程内 `PixelAppBubbleLauncher` 共享单例（测试入口与手势提交共用同一 controller 捕获） |
| `platform/pixel/PixelBubbleBackend.kt#PixelBubbleLaunchCommitter` | `AppLaunchCommitter` 实现：`commit(ComponentName)` → 气泡后端，仅 `BubbleRequestSubmitted` 视为 accepted |
| `platform/coloros/ColorOsFreeformCoordinator.kt` | `launchWithCommitter()` 惰性解析 `pixelCommitter()`；平台判定 = SystemUI 进程 + `BubbleController` 类存在，其它平台行为不变 |

### 7.2 真机测试步骤（可复现）

```bash
# LSPosed 作用域：com.android.systemui（必需）
am broadcast -a io.github.mangi.flymefreeform.ACTION_TEST_LAUNCH_BUBBLE \
  --es component com.tencent.mm/.ui.LauncherUI --el user_id 0 com.android.systemui
```

预期日志：`BUBBLE_TEST_RECEIVED` → `BUBBLE_A17 ...` → `BUBBLE_TEST_RESULT BUBBLE_REQUEST_SUBMITTED`，微信气泡弹出。

*阶段 2 记录结束 · 2026-10-18*
