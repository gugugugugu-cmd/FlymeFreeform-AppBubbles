# 阶段 2：平台无关启动接口（Phase 2 · Launch Interface Layer）

> 状态：✅ **已实现 + 真机 L3 验证通过（微信气泡小窗成功弹出）**
> 日期：2026-10-18
> 分支：`phase2` @ `082d4f9`（L3 验证产物 SHA `d0830ef5`）；`91c7429`（手势接线补完）
> main：@ `6cc9d9e`（源码基线同步）
> 真机记录：见 `PIXEL_BUBBLE_RUNTIME_FINDINGS.md` §5–§7

---

## 1. 目标（任务书 §5.2）

把手势 UI（扇形菜单）与应用启动后端解耦：
- 手势提交 → 平台无关事件（`AppLaunchCommitter.commit(ComponentName)`）
- 启动后端 → 平台无关接口（`AppBubbleLauncher.launch(BubbleLaunchRequest): BubbleLaunchResult`）
- Pixel/Android 17 后端 = 原生气泡启动（移植 MoreBubbleButton feature/a17 分支配方）

## 2. 新增文件

```
app/src/main/kotlin/io/github/mangi/flymefreeform/platform/common/
  BubbleLaunchRequest.kt     # 数据契约（component + userHandle + source）
  BubbleLaunchResult.kt      # 9 态结果状态机
  AppBubbleLauncher.kt       # 启动后端接口
  AppLaunchCommitter.kt      # 手势提交抽象

app/src/main/kotlin/io/github/mangi/flymefreeform/platform/pixel/
  PixelAppBubbleLauncher.kt  # Pixel A17 后端（官方配方 + legacy 回退）
  PixelBubbleTestEntry.kt    # 开发测试入口（广播触发，不改手势 UI）

修改：
  hook/SystemUiHookInstaller.kt          # 挂载 PixelBubbleTestEntry
  platform/coloros/ColorOsFreeformCoordinator.kt  # launchCommittedApp 走 committer
```

## 3. 接口设计

### 3.1 BubbleLaunchRequest（数据契约）
```kotlin
data class BubbleLaunchRequest(
    val component: ComponentName,   // 显式目标
    val userHandle: UserHandle,     // 不默认 userId=0；非主用户 → RequestValidationFailed
    val source: String,             // 请求来源（如 "test-entry"）
)
```

### 3.2 BubbleLaunchResult（9 态状态机，任务书 §5.3）
| 状态 | 含义 |
|------|------|
| `RequestValidationFailed` | 请求校验失败（非主用户等） |
| `TargetResolutionFailed` | 目标组件无法解析 |
| `BackendUnavailable` | Pixel 后端不可用（SystemUI 不在场） |
| `BubbleRequestSubmitted` | ✅ 气泡请求已提交（成功） |
| `BubbleCreateFailed` | 气泡创建失败 |
| `TaskLaunchFailed` | 任务启动失败 |
| `ExpandFailed` | 气泡展开失败 |
| `FallbackLaunch` | 回退启动（非气泡） |
| `ResultUnknown` | 未知/未分类 |

### 3.3 AppBubbleLauncher（后端接口）
```kotlin
interface AppBubbleLauncher {
    fun launch(request: BubbleLaunchRequest): BubbleLaunchResult
}
```

### 3.4 AppLaunchCommitter（手势提交抽象）
```kotlin
interface AppLaunchCommitter {
    fun commit(component: ComponentName): Boolean
}
```
- 注入 `ColorOsFreeformCoordinator`；null 时回退原 ColorOS 直接启动（行为不变）。

## 4. Pixel A17 后端实现要点

`PixelAppBubbleLauncher`（跑在 SystemUI 进程，BubbleController 所在处）：
1. 反射定位 `BubbleController`（`com.android.wm.shell.bubbles.BubbleController`）
2. 主路径：`expandStackAndSelectAppBubble(Bubble, EntryPoint, UpdateLocationRequest)`（A17 官方配方）
   - 构造 Bubble → `getAppBubbleKeyForApp` 找已有气泡 → `expandStackAndSelectAppBubble` 展开
3. Legacy 回退：`expandStackAndSelectBubble(Bubble, EntryPoint, BubbleBarLocation)`（老签名）
4. 三重校验：类存在 / 构造器参数个数 / 方法签名精确匹配，失败归因到 9 态

## 5. 开发测试入口（不改手势 UI）

- SystemUI 进程动态注册广播接收器，action：`io.github.mangi.flymefreeform.ACTION_TEST_LAUNCH_BUBBLE`
- extra：
  - `component`：目标组件（`pkg/Activity`）
  - `user_id`：用户 ID（默认 0）
  - `source`：请求来源
- 触发方式（adb）：
  ```bash
  adb shell am broadcast \
    -a io.github.mangi.flymefreeform.ACTION_TEST_LAUNCH_BUBBLE \
    --es component com.tencent.mm/.ui.LauncherUI \
    --el user_id 0 \
    com.android.systemui
  ```
- 结果通过模块日志输出状态码（`BUBBLE_REQUEST_SUBMITTED` 等）。

## 6. 构建产物

| 项 | 值 |
|----|----|
| L3 验证版运行 ID | `39104493025` |
| 结果 | ✅ success |
| APK | `FlymeFreeform-phase2-v10.apk`（3,149,094 B） |
| **SHA-256** | `d0830ef56631208655db6fca0c1c2b67e84febc0d91fb94431c8cc9d896f6e3e` |
| 归档 | `artifacts/FlymeFreeform-0.2.6-phase2-v10.apk` |
| 验证 | 真机微信气泡小窗成功弹出（L3） |

历史迭代（全部 success）：`39095838812` → `39097949014` → `39098790099` → `39099798780`
→ `39100610551` → `39101491597` → `39102364699` → `39102898769` → `39104493025`。

## 7. 真机测试结果（L3 已达成）

| 项 | 结果 |
|----|------|
| 设备 | Android 17（SDK 37） |
| LSPosed 作用域 | `com.android.systemui` |
| 触发 | `am broadcast -a io.github.mangi.flymefreeform.ACTION_TEST_LAUNCH_BUBBLE --es component com.tencent.mm/.ui.LauncherUI --el user_id 0 com.android.systemui` |
| controller 绑定 | ✅ `available=true`（三路径全命中） |
| 结果码 | ✅ `BUBBLE_REQUEST_SUBMITTED` |
| 视觉 | ✅ **微信气泡小窗弹出** |

排查出的 6 个连环缺陷见 `PIXEL_BUBBLE_RUNTIME_FINDINGS.md` §6（含 `android.util.Log` 在模块进程不可见的日志通道陷阱）。

## 7.1 手势 → 气泡闭环（本次补完）

`AppLaunchCommitter` 此前**只有接口、无实现**，`committer` 恒为 null，手势一直回落 ColorOS 原生启动。现补：

- `PixelBubbleBackend`：SystemUI 进程内 launcher 共享单例
- `PixelBubbleLaunchCommitter`：`commit(ComponentName)` → `PixelAppBubbleLauncher.launch()`
- `ColorOsFreeformCoordinator.launchWithCommitter()` 惰性解析，平台判定（SystemUI 进程 + `BubbleController` 存在）

## 8. 阶段 3 预告（跨进程路由）

当前 Pixel 后端在 SystemUI 进程，手势提交在 launcher 进程——阶段 3 将建立：
- launcher 进程手势提交 → 跨进程（广播/Binder）→ SystemUI 进程 `PixelAppBubbleLauncher`
- 完整闭环：扇形手势 → 气泡启动

*文档结束 · PHASE2_LAUNCH_INTERFACE.md*
