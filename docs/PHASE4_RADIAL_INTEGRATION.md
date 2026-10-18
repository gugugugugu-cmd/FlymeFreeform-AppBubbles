# 阶段 4：扇形菜单整合与回归测试

**起始点**：标签 `phase3-complete`（commit `6487e35`），分支 `phase4`
**前置条件**：阶段 3 全部验收项闭环，角落输入入口与系统导航兼容性已实机验证

---

## 1. 阶段目标

实施计划将阶段 4 定义为「扇形菜单整合与回归测试」。当前菜单实现已具备相当规模：

| 文件 | 行数 | 职责 |
|---|---|---|
| `window/CornerRadialOverlayView.kt` | 1125 | 菜单主视图：绘制、命中测试、状态机 |
| `window/RadialIconGeometry.kt` | 99 | 图标布局几何 |
| `window/RadialEntryMotion.kt` | 89 | 入场动效 |
| `window/RadialDismissMotion.kt` | 46 | 取消动效 |
| `window/RadialHandoffMotion.kt` | 30 | 交接动效 |
| `window/RadialExitMotion.kt` | 29 | 退场动效 |

因此本阶段重点是**整合与验证**，不是重写：

1. 菜单与阶段 3 输入入口的衔接是否完整
2. 交互状态机是否存在未覆盖的分支
3. 回归测试：菜单各类退出路径不留下残留窗口、不误触发布局

---

## 2. 阶段 3 遗留项（本阶段处理）

| 项 | 现象 | 处理策略 |
|---|---|---|
| `SIDEBAR_TOOL_TARGET_UNAVAILABLE` ×3 | ColorOS 侧边栏组件在 Pixel 无对应目标 | 判定为平台不适用，AOSP 上走系统侧边栏，模块不做替代 |
| `RADIAL_NATIVE_ICON_SHAPE_UNAVAILABLE` | 原生图标形状读取失败 | 影响图标外形，回退到默认形状；确认回退效果可接受 |
| `OUTSIDE_TAP_TARGET_UNAVAILABLE` | 外部点击目标缺失 | 检查是否影响「点空白处关闭」 |
| `HANDLE_SWIPE_UP_TARGET_UNAVAILABLE` | 上滑关闭目标缺失 | 同上 |
| `PIXEL_BUBBLE_TEST_ENTRY_READY launcherAvailable=false` | 测试入口旁路 | 与主链独立，低优先 |

**授权边界安全项**（审查曾要求）：伪造发送方测试尚未执行。
阶段 3 已改为 `STATUS_BAR_SERVICE` 权限限定，理论上非授权进程在分发阶段即被拦截，
但缺一次实测确认。列为本阶段安全项。

---

## 3. 不可破坏的既有约束

阶段 4 的任何改动必须保持以下行为，回归时逐项确认：

1. **IPC 协议不变**：`GESTURE_COMMIT` / `GESTURE_COMMIT_ACK` 三态（Accepted / Rejected / Unknown）语义与阶段 3 一致
2. **权限鉴权不变**：收发双方均限定 `STATUS_BAR_SERVICE`，不退回 `getSentFromUid()` 事后校验
3. **坐标基准**：`Display.DEFAULT_DISPLAY`，不引入多显示器假设
4. **线程约束**：SystemUI 侧气泡创建必须在 MainExecutor；system_server 侧不得阻塞主线程
5. **导航兼容性**：热区 405px 下已验证不干扰返回/主页/最近任务，菜单改动不得扩大抢占范围

---

## 4. 回归测试清单（初版）

菜单退出路径至少覆盖：

- [ ] 选中图标后松手 → 启动应用 → 菜单退场，无残留
- [ ] 拖出扇形区域后松手 → 取消 → 无启动、无残留
- [ ] 点击扇形外空白 → 关闭
- [ ] 上滑关闭
- [ ] 左右两侧分别触发（Left / Right）
- [ ] 菜单打开时旋转屏幕
- [ ] 菜单打开时锁屏 / 解锁
- [ ] 连续快速触发多次

---

## 5. 已确认的遗留项结论

### 5.1 `OUTSIDE_TAP_TARGET_UNAVAILABLE` —— 非功能缺失，判定关闭

`hook/OutsideTapCloseHookInstaller.kt` 依赖 ColorOS 专有的
`FlexibleTaskController$TouchListener` / `FlexibleCaptionView`，Pixel 上不存在，
因此该 hook 必然安装失败。

但「点扇形外空白关闭」并不依赖它：`CornerRadialOverlayView.kt` 第 395–428 行
以全屏 `Box` + `pointerInput { awaitEachGesture { awaitFirstDown(); requestDismiss() } }`
在 Compose 层实现同一能力，`MoreAppsPanel` 在其后声明，触摸优先级更高。
故 AOSP 上行为完整，仅剩「点击穿透到下层应用」与 ColorOS 原生 hook 的差异
（超出屏幕自行扩展区域后才点得到，影响面极小）。

### 5.2 三处 `*_TARGET_UNAVAILABLE` 告警消歧

`OUTSIDE_TAP` / `HANDLE_SWIPE_UP` / `SIDEBAR_TOOL` 三条统一补
`reason=coloros-class-absent (AOSP 无此实现，非功能缺失)`，
避免后续审查再次误判为缺陷。

### 5.3 e0 可观测点失效（已修）

e0 的 `RADIAL_PANEL_SHOWN` / `RADIAL_DISMISS_REQUESTED` 用
`android.util.Log` 打 `TAG=FlymeFreeformTool`，而 LSPosed 模块进程内的
`android.util.Log` 不进入导出日志（阶段 3 已踩过同一坑）。
2026-10-18 实机日志 `modules_2026-10-18T20_07_22.277594.log` 中两条均缺失，
证实该问题。

修复：`CornerRadialOverlayView` 新增构造参数 `moduleLog: (String) -> Unit`，
由 `ColorOsFreeformCoordinator.showOverlay()` 传入 `logger(Log.INFO, code, null)`。
同时补 `reason` 字段区分七条关闭路径：

| reason | 触发点 |
|---|---|
| `timeout` | `PANEL_TIMEOUT_MS` 到期 |
| `outside-tap` | 点击扇形外空白 |
| `back` | 返回键 |
| `size-changed` | 窗口尺寸变化（旋转 / 分屏） |
| `selected` | 选中应用并提交 |
| `cancelled` | 拖出扇形后松手取消 |
| `exit-finished` | 退场动画结束且无待提交项 |

## 6. 进度

- 2026-10-18：阶段 4 立项，盘点菜单实现现状，建立遗留项与回归清单
- 2026-10-18：e0 构建；确认 `OUTSIDE_TAP` 为平台差异；三处告警加 reason
- 2026-10-18：e1 构建（commit `25dbf26`，CI `39126410851` success），
  菜单日志改走模块 logger 并补 `reason`，SHA-256 `039e895b…42e6014`
