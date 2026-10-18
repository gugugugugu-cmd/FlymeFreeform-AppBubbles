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
