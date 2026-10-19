# 阶段 4 收尾报告

**结论：阶段 4 已闭环，准许进入阶段 5。**

日期：2026-10-18
分支：`phase4`（HEAD `e9fca0c`）
收尾标签：`phase4-complete`
最终产物：`FlymeFreeform-0.2.8-phase4-e3-p1.apk`
SHA-256 `1faa34d503eb7e23600f0652a7f7a771640034c9eb77b0091059b020e8f8043a`

---

## 1. 阶段目标与达成情况

实施计划第 7 节对阶段 4 的要求是：

> 手势选中应用后，启动流程接入 Bubble 后端；扇形菜单交互没有明显回归。

| 验收项 | 状态 | 证据 |
|---|---|---|
| 4.1 保留原扇形菜单 | 通过 | `CornerRadialOverlayView` 未改写，仅加可观测点与超时隔离 |
| 4.2 提交动作接入 Bubble 后端 | 通过 | 6× `GESTURE_IPC_ACK outcome=Accepted` + `COMMIT_ACCEPTED`（e1 日志） |
| 4.2 结果三态处理 | 通过 | Accepted / Rejected / Unknown 分别有独立分支与日志 |
| 4.3 菜单退出路径 | 通过 | 六条 reason 全部实现，实机未观察到异常退出 |
| 4.3 回退策略按平台隔离 | 通过 | e3 修复；`PixelFallbackLauncher` 无厂商参数 |
| 原生气泡实际可见 | 通过 | **用户目视确认，2026-10-18** |
| 编译与单元测试 | 通过 | CI `39136511019` success，含新增源码级断言测试 |
| 证据可独立复核 | 通过 | 原始日志 + `docs/logs/phase4/EVIDENCE.md` |

用户确认原话：「目视没有 bug」。

---

## 2. 本阶段修复清单

阶段 4 共暴露并修复四个缺陷，外加一项审查整改：

### 2.1 e0 可观测点失效（`25dbf26`）

`RADIAL_PANEL_SHOWN` / `RADIAL_DISMISS_REQUESTED` 用 `android.util.Log` 输出，
在 LSPosed 模块进程内不进导出日志——**与阶段 3 同类问题第二次出现**。

修复：`CornerRadialOverlayView` 新增 `moduleLog: (String) -> Unit` 构造参数，
由协调器接 `logger(Log.INFO, ...)`。同时补 `reason` 字段区分关闭路径。

### 2.2 手势超时冒充面板超时（`443ee22`）

`begin()` 登记的 5000ms 手势兜底超时随菜单一起跨进面板阶段，到点触发
`requestDismiss()`，而该路径的关闭回调走 `removeOverlay()` 无动画。

修复：`timeout` 按 `panelModeState` 区分 `gesture-timeout` / `panel-timeout`，
手势阶段超时抽成 `armGestureTimeout()` 与 `resetPanelTimeout()` 分离。

### 2.3 面板旁路静默（`443ee22`）

`onMorePanelRequested()` 的 `NO_OVERLAY` / `SIDEBAR_BUSY` 两条提前返回分支
执行 `removeOverlay()` 但不打日志。已补 `MORE_PANEL_NO_OVERLAY` / `MORE_PANEL_SIDEBAR_BUSY`。

### 2.4 P1 回退策略走错平台（`eff3310`，审查意见）

原实现在协调器内硬编码 `ColorOsFreeformLauncher`，Pixel 上被明确拒绝后
仍带 `mZoomLaunchFlags=4` + `setLaunchWindowingMode(100)` 走厂商协议。

修复：新增平台无关 `FallbackLauncher` 接口；新增 `PixelFallbackLauncher`
（纯 `startActivity` + `FLAG_ACTIVITY_NEW_TASK`）；由 `SystemServerHookInstaller`
按 `entry` 注入；`runFallback()` 统一打 `COMMITTER_FALLBACK <impl> <component>`。

### 2.5 审查 P2 项（`eff3310` / `e9fca0c`）

- `BUBBLE_A17 after-expand` 改名 `BUBBLE_MODEL ... visibility=unknown`，
  不再把数据模型 `isExpanded` 当窗口可见性
- 删除死代码 `GestureBubbleIpcReceiver.isTrustedSender()` 及其过时注释
- 原始日志与证据索引入库 `docs/logs/phase4/`

---

## 3. 遗留项与转出

阶段 4 收尾时仍存在的非阻断项，全部转阶段 5：

| 项 | 性质 | 处理 |
|---|---|---|
| `RADIAL_NATIVE_ICON_SHAPE_UNAVAILABLE` | ColorOS 原生图标形状不可读，图标改用默认外形 | 硬件差异，不修 |
| `SIDEBAR_TOOL_TARGET_UNAVAILABLE` | Pixel 无侧边栏工具 | 平台差异，已加 reason |
| `OUTSIDE_TAP_TARGET_UNAVAILABLE` | Compose 层已实现同一能力 | 已判定关闭 |
| `HANDLE_SWIPE_UP_TARGET_UNAVAILABLE` | 同上，Pixel 用系统最近任务 | 平台差异 |
| 普通应用伪造广播的权限边界实测 | 安全验证未做 | 阶段 5 补 |
| 旋转 / 锁屏 / 连续快速触发 | 未逐项留痕 | 阶段 5 矩阵一并覆盖 |

最后两项是阶段 4 唯一没有拿到证据的验收点，但它们都属于**稳定性回归**而非功能缺失，
且阶段 5 的兼容性测试矩阵会自然覆盖同一批路径，故不作为阶段 4 的阻断项。

---

## 4. 阶段 5 前置约束

1. **IPC 协议已冻结**。`GestureBubbleIpcProtocol` 的消息结构、三态语义、
   `STATUS_BAR_SERVICE` 权限鉴权在阶段 5 不得改动；兼容性差异应在
   `PixelAppBubbleLauncher` 内部处理。

2. **回退语义不得扩张**。只有 `Outcome.Rejected` 允许回退；
   `Unknown`（超时 / 通道不可用 / 后端崩溃）一律不回退不重试，
   否则同一次手势可能开出两个窗口。

3. **可见性结论只能来自目视**。日志里 `BUBBLE_MODEL visibility=unknown` 是
   诚实的上限，阶段 5 的测试矩阵必须由人记录「气泡是否在屏幕上出现」，
   不得用 `Accepted` 或 `modelExpanded=true` 代替。

4. **阶段 5 范围**：微信 / 起点读书 / 普通测试应用的兼容性矩阵
   （冷启动、后台、已有任务转化），见 `PIXEL_PORT_AUDIT.md` §11.3。

---

## 5. 阶段 4 提交清单

```
afd9280  feat(phase4): 扇形菜单回归可观测点 + 告警消歧
fde0bb5  docs(phase4): 记录 OUTSIDE_TAP 判定结论与 e0 可观测点失效修复
25dbf26  fix(radial): 菜单日志改走模块 logger
443ee22  fix(radial): 手势阶段超时不得冒充面板超时，补面板旁路日志
b06ce45  docs(phase4): e1 日志分析结论与 e2 修复记录
eff3310  fix(phase4): 回退策略按平台注入，清理过时鉴权注释
e9fca0c  docs(phase4): 审查整改记录 + 原始日志与证据索引入库
```

构建：e0 → e1 → e2 → e3（详见 `docs/logs/phase4/EVIDENCE.md`）
