# MITIGATION_ANALYSIS.md — 阶段 0 风险缓解分析

> 审查报告 P0 交付物 #4 · 2026-10-18
> 目的：对阶段 0 全部已知风险与不确定性，逐一给出**已落地缓解**或**明确缓解路径**，并标注残留风险。凡涉及"签名存在"一律与"后端可用"区分（审查报告要求）。

## 1. 风险总表

| # | 风险 | 严重度 | 当前状态 | 缓解措施 | 残留 |
|---|------|--------|---------|---------|------|
| R1 | MT MCP 不可达，无法反编译核验 | 高 | ✅ 已恢复 | 已用 SystemUI 17 + Pixel Launcher 17 核验 21 项 | 无 |
| R2 | 无真机，运行时行为未知 | 高 | ⚠️ 进行中 | 交付 DEVICE_BASELINE 模板 + 真机命令清单；用户确认有真机 | 真机实测尚未回填 |
| R3 | 隐藏 API 签名"存在"≠"可用" | 高 | ⚠️ 已区分 | 文档区分"签名存在"（DEX 核验）与"后端可用"（真机/注入测试）；§3 专章 | 后端可用性待阶段 1 |
| R4 | showAppBubble 接受任意 Intent 未知 | 高 | ⚠️ 待测 | 列入阶段 1 首测项；双路线（A 现有任务/B 任意 Intent）兜底 | 待真机 |
| R5 | 跨进程边界（手势 system_server → 气泡 SystemUI） | 高 | ✅ 已实证 | 反编译确认 `SystemUiProxy.showAppBubble` 是 `IBubbles` AIDL 跨进程调用；文档已更新 | 注入能力待测 |
| R6 | 上游源码被改动（基线漂移） | 中 | ✅ 已证伪 | `git status` 干净；HEAD `b2c8779`/`81630ab` 与文档一致 | 无 |
| R7 | ColorOS 类在 Pixel 是否存在 | 中 | ✅ 已证伪 | SystemUI 17 dex 0 命中 `OplusBaseTouchInteractionService`/`FlexibleTaskController` | framework.jar 层未核（待系统镜像） |
| R8 | 文档矛盾/不一致（审查报告指出） | 中 | ✅ 已修正 | 见 §2 修正清单 | 无 |
| R9 | 证据可复核性（无哈希/无落盘） | 中 | ✅ 已补 | 证书 SHA-256×5；locator 附录；真机命令回填文件哈希 | 文件级哈希待真机 |
| R10 | GITHUB_TOKEN 权限边界 | 中 | ✅ 已处理 | token 属 `gugugugugu-cmd`，无上游写权限 → 新建独立开发仓库（已公开） | 无 |
| R11 | 假成功风险（窗口模式 100 在 Pixel 退化为全屏） | 高 | ⚠️ 监控 | 文档明确禁止窗口模式 100 启动作为"成功"判据；成功标准 = 气泡出现 | 阶段 2 实现期 |
| R12 | SystemUI APK 来自第三方 ROM（`CN=cnmrlin` 签名） | 中 | ⚠️ 已标注 | 已记录证书 subject；核验结论限定于该构建 | 需用户确认 ROM 来源 |

## 2. 阶段 0 文档矛盾修正清单（审查报告所指）

| 位置 | 原内容 | 问题 | 修正后 |
|------|--------|------|--------|
| DEVICE_BASELINE §8 | "Launcher 进程含 `com.android.launcher3` 类 ✅ 有" | 与 PIXEL_PORT_AUDIT §2.2 断言"仅 com.android.launcher 匹配"矛盾 | 明确：`com.android.launcher3` 类存在于 nexuslauncher APK（进程名是 `com.google.android.apps.nexuslauncher`），进程注入目标必须用后者 |
| BUBBLE_CALL_CHAIN §9.2 | 未注明 Launcher 路径进程边界 | 读者易误判为同进程 | 补充：Launcher 侧 proxy 调用经 AIDL 跨进程到 SystemUI wmshell |
| PIXEL_PORT_AUDIT §10.2 | `RecentsView.getCurrentPageTaskView` 标注"待 outline 深读" | 实际已 outline 确认签名 | 升级为 ✅ 已核验（`()Lcom/android/quickstep/views/TaskView;`） |
| PIXEL_PORT_AUDIT §9 清单 #3 | `WMS.registerPointerEventListener` 标"待系统镜像" | 准确但未给路径 | 补充缓解路径：阶段 1 真机反射验证 |
| 三文档头部 | "待 DEX 核验" 泛指 | 区分不细 | 已细分：**签名存在**（DEX 核验 ✓）/ **后端可用**（待真机）/ **待核验**（framework 层） |

> 附带修正一处实际类型错误：早期文档曾把 `BubbleData.findAndRemoveBubbleFromOverflow` 误写为返回 `void`，DEX outline 已确认为 `(String)Bubble`，三处文档均已更正。

## 3. "签名存在" vs "后端可用" 专项说明

**审查报告核心关切**：DEX 中存在某方法 ≠ 调用它就能工作。本项目明确区分三层证据：

| 层级 | 含义 | 证据来源 | 阶段 0 状态 |
|------|------|---------|------------|
| L1 签名存在 | 目标构建 dex 中有该方法/构造/字段，签名匹配 | `mt_apk_search` locator + `mt_apk_dex_read_class_outline` | ✅ 21/21 完成 |
| L2 逻辑可用 | 该方法在目标构建中行为符合调用预期（如 expand 接受任意 Bubble） | 方法体 outline（调用引用）+ 源码注释 | ⚠️ 部分（`expandStackAndSelectAppBubble` 方法体已 outline，确认调用 `Bubble.isInflated` 等；`showAppBubble` 的"任意 Intent"语义待真机） |
| L3 端到端可用 | LSPosed 注入 → 手势触发 → 气泡出现 | 真机实测 | ❌ 待阶段 1 |

**已明确要求**：任何文档/交付物不得以 L1 证据宣称 L3 结论。阶段 0 报告中所有"路线成立"表述均指 **L1 成立**，并在"对移植的意义"段注明 L2/L3 待测。

## 4. 具体风险的缓解路径（阶段 1+）

### 4.1 R4（showAppBubble 任意 Intent）— 阶段 1 首测
- 测试矩阵：现有任务（微信已开）→ 气泡按钮 / 新 Intent（直接 showAppBubble 微信）→ 观察。
- 判据：气泡是否出现（不是任务是否启动）。
- 兜底：若 showAppBubble 拒绝新任务，走路线 B（expandStackAndSelectAppBubble + Bubble 构造），B 已确认接受任意 Bubble。

### 4.2 R3/R5（进程边界）— 阶段 1 验证
- LSPosed 作用域：SystemUI + nexuslauncher 双进程。
- 若 system_server 手势无法直达 SystemUI，验证两条 IPC 候选：`SystemUiProxy`（Launcher 侧，需手势→Launcher）或 `IBubbles` 直接 Binder（system_server→SystemUI）。

### 4.3 R12（ROM 来源）
- 请用户确认 `系统界面_17.apk`（`CN=cnmrlin` 签名）与 `Pixel 启动器_17.apk`（Google 签名 `a86bdb05...`）的实际来源（Pixel 官方 OTA？第三方 ROM？）——影响 L2/L3 结论的外推范围。

### 4.4 R2（真机）
- 用户已确认有真机；阶段 1 将产出可安装 APK + 回填命令清单。

## 5. 残余风险（无法在本阶段消除）

1. **framework.jar / system_server 层**：`WMS.registerPointerEventListener`、`PointerEventListener`、Flexible 类无法 APK 核验 → 真机反射验证。
2. **LSPosed 注入能力**：Pixel ROM 对 system_server/SystemUI/Launcher 注入需实测（LSPosed 本身支持，但 ROM 加固/签名验证未知）。
3. **测试应用兼容性**：微信/起点读书气泡行为差异（通知渠道、bubble metadata）→ 阶段 5 矩阵。
4. **版本漂移**：目标 ROM 后续 OTA 可能改变签名 → 阶段 1 复测。

*分析结束 · 每项风险均有缓解动作或明确路径*
