# 阶段 4 证据索引

审查意见 P2（2026-10-18）指出：`PHASE4_RADIAL_INTEGRATION.md` 引用的实机日志不在交付包内，
无法独立复核。本文件把「结论 → 原始材料」的对应关系固定下来。

设备：Pixel，Android 17（SDK 37），LSPosed 2.2.1（API 102）。
触发热区：405px ≈ 135dp（`DEFAULT_CORNER_TRIGGER_RANGE_DP = 84`，用户配置值偏大）。

---

## 构建记录

| 构建 | commit | CI run | 产物 SHA-256 |
|---|---|---|---|
| e0 | `afd9280` | 39125223864 | `e304426a706b4b1c0a9d946e486da20b896f75e8c7239ab2daf832bbffa95f9a` |
| e1 | `25dbf26` | 39126410851 | `039e895b1737b2de8f3a3c1ad5583ab93678aca35e53c19bd1297e9af42e6014` |
| e2 | `443ee22` | 39127321866 | `fee26eeffa9117a9a9a7ef7b370c0ad8a85c7406bee052f0666d31eeec17d559` |
| e3 | `eff3310` | 39136511019 | `1faa34d503eb7e23600f0652a7f7a771640034c9eb77b0091059b020e8f8043a` |

CI：`https://github.com/gugugugugu-cmd/FlymeFreeform-AppBubbles/actions/runs/<run>`

---

## 原始日志

| 文件 | 对应构建 | 大小 | 覆盖内容 |
|---|---|---|---|
| `logs/phase4/modules_2026-10-18T20_07_22.277594.log` | e0 | 141,149 B | 验证 e0 可观测点**失效**（走 `android.util.Log` 未进导出日志） |
| `logs/phase4/modules_2026-10-18T20_22_58.311265.log` | e1 | 150,747 B | 菜单事件链首次完整可见；发现手势超时跨阶段问题 |

阶段 3 日志见 `docs/logs/phase3-run-log.md` §1 的索引表。

---

## 逐项证据对照

| 验收项 | 结论 | 证据 |
|---|---|---|
| 菜单日志可见 | 通过 | e1 日志：5× `RADIAL_PANEL_SHOWN`、13× `RADIAL_DISMISS_REQUESTED` |
| 点扇形外空白关闭 | 通过 | e1 日志：`outside-tap` 间隔 29ms / 1.2s / 1.3s 三档 |
| 选中应用提交 IPC | 通过 | e1 日志：6× `GESTURE_IPC_ACK outcome=Accepted` + `COMMIT_ACCEPTED` |
| 时间戳夹紧与手势起手一致 | 通过 | e1 日志：`GESTURE_IPC_SENT id=1792326244086` ↔ `ms=74433` 同毫秒 |
| 手势超时跨入面板阶段 | **缺陷，已修** | e1 日志 `ms=74433` 后 5s 内无任何事件 → e2 隔离 |
| 面板旁路静默 | **缺陷，已修** | e1 日志 PANEL_SHOWN 后无 JOB、无 DISMISS → e2 补日志 |
| 取消 / 返回键 / 面板超时 | 待验证 | e1 中 `cancelled` / `back` / `panel-timeout` 均未出现 |
| 旋转 / 锁屏 | 待验证 | 未测 |
| 左右两侧 | 待完整回归 | 阶段 3 已确认热区双侧生效，菜单侧未分别记录 |
| 连续快速触发 | 待验证 | 未测 |
| 气泡实际可见 | 未独立验收 | 见下文 |

---

## 关于「气泡是否真的出现」

现有日志能证明的最强结论只到：

```
GESTURE_IPC_SENT → GESTURE_IPC_RECEIVED → BUBBLE_A17 bubble fromOverflow=false
→ BUBBLE_EXPAND on-main-thread finished=true ok=true → ACK Accepted
```

即**系统接受了展开请求并返回成功**。日志中的 `expanded` 读的是 `BubbleData.isExpanded`
（数据模型），不是窗口可见性；模块没有任何可靠的可见性回调可读。

e3 起该诊断改名为 `BUBBLE_MODEL ... visibility=unknown`，避免继续被误读。

真正的可见性验收只能目视：需要用户对微信、起点读书、普通测试应用分别记录
「气泡在屏幕上确实展开」以及冷启动 / 后台 / 已有任务转化三种状态下的表现。
在此之前，任务书 §11 的最终完成定义（原生气泡确实出现）**不成立**。
