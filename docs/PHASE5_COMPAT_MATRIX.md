# 阶段 5：应用兼容性矩阵

日期：2026-10-18
前置：`phase4-complete`（HEAD `703c52b`）

---

## 1. 目标

实施计划与 `PIXEL_PORT_AUDIT.md` §11.3 对阶段 5 的要求：

> 微信 / 起点读书 / 普通测试应用的气泡兼容性矩阵，区分冷启动、后台启动、
> 已有任务转化三种状态，记录各应用表现差异。

阶段 4 已证明「机制可用」：手势 → IPC → `BubbleController` → 展开请求 → 目视可见。
阶段 5 要回答的是**哪些应用在这个机制下行为不同，为什么**，而不是继续验证机制本身。

---

## 2. 测试矩阵

三种状态 × 四类目标：

| 状态 | 微信 `com.tencent.mm` | 起点读书 `com.qidian.QDReader` | Telegram `org.telegram.messenger` | 普通测试应用 |
|---|---|---|---|---|
| A. 冷启动（进程未起） | | | | |
| B. 后台（进程存活，任务在最近任务里） | | | | |
| C. 已有气泡（二次触发） | | | | |

每个格子记录：

1. 气泡是否出现（目视）
2. 是否复用了已有气泡（`bubble fromOverflow=true`）
3. `BUBBLE_MODEL` 行（bubbles / modelExpanded / selected）
4. 点开气泡后是全屏还是小窗
5. 异常时的完整日志片段

---

## 3. 已知差异点（需重点观察）

来自 `PIXEL_PORT_AUDIT.md` §11 与阶段 4 实机：

- **微信**：气泡走 `Notification.Builder#setBubbleMetadata` 的标准路径，
  `BubbleController` 能在 `BubbleData` 里找到通知来源；但微信可能仅在一对一聊天
  有通知时才允许气泡化，群聊/服务号通知是否可气泡化未知。
- **起点读书**：`PIXEL_PORT_AUDIT.md` 记为「从未气泡化过」的冷启动样本，
  用于验证 `findAndRemoveBubbleFromOverflow` 行为。
- **Telegram**：标准 AndroidX bubble 实现，作为「预期正常」的对照组。
- **普通测试应用**：验证 `showAppBubble` 接受「非现有任务的任意 Intent」的边界，
  即 `PIXEL_PORT_AUDIT.md` §9 第 10 项。

---

## 4. 待补的安全验证（自阶段 4 转出）

- 普通应用伪造 `GestureBubbleIpcProtocol` 广播 → 应在分发阶段被
  `STATUS_BAR_SERVICE` 权限拦下，日志中不应出现 `GESTURE_IPC_RECEIVED`
- 验证方法：写一个最小 APK，`sendBroadcast(intent)` 到同一 action，观察
  SystemUI 侧是否记录收到

---

## 5. 进度

- 2026-10-18：阶段 5 立项，建立测试矩阵与差异点假设
