# phase0_mt_mcp_capabilities.md — MT 管理器 MCP 能力与使用记录

> 审查报告 P0 交付物 #2 · 2026-10-18
> 范围：仅记录本会话**实际调用过**的 MT MCP 工具、输入输出摘要、限制与错误。

## 1. 连接状态

- 服务端：`minis-mcp-cli` 连接 MT（本地 TCP）。阶段 0 首轮为 `Connection refused`（不可达）；2026-10-18 恢复。
- 恢复后全部调用成功（`ok:true`），无超时/权限错误。

## 2. 实际使用的工具清单（57 个工具中调用过 8 个）

| 工具 | 调用次数 | 用途 | 结果 |
|------|---------|------|------|
| `mt_apk_list_available_apks` | 1 | 列出 MT 根目录 APK | ✅ 5 个 APK 全部列出 |
| `mt_apk_workspace_open` | 2 | 打开 SystemUI/Launcher 工作区 | ✅ `81i4aezl` / `holgxhyk` |
| `mt_apk_workspace_list` | 1 | 列出所有已开工作区 | ✅ 5 个工作区（含微信/起点/Telegram） |
| `mt_apk_search` | ~25 | dex 方法/类/字符串检索 | ✅ 全部成功 |
| `mt_apk_dex_read_class_outline` | ~8 | 读取类 outline（字段/方法/调用） | ✅ 全部成功 |
| `mt_apk_read_signature` | 5 | 读取 APK 签名证书 | ✅ 5 个 APK 证书哈希 |
| `mt_file_list` | 1 | 列出 MT 文件系统根 | ✅ `/storage/emulated/0/MT2/mcp` |
| `mt_file_stat` | 1 | 查看 APK 文件元数据 | ✅ 17MB 等 |
| `mt_file_read_bytes` | 2 | 读 APK 字节（验证可读性） | ✅ 返回 hex，但**仅 64B/页**，不适合全文件哈希 |

## 3. 关键能力确认

- ✅ **APK 反编译**：可打开 APK 工作区，读取 manifest（包名/版本/minSdk/targetSdk/权限）、dex 类与方法（`mt_apk_search` + `mt_apk_dex_read_class_outline`）、资源表。
- ✅ **DEX 符号检索**：`target=overview/dex_names/dex_strings` 均可用；`queryType=literal|regex`，`matchMode=contains`。
- ✅ **类 outline**：返回类头、父类、接口、字段（含 sig/access/常量）、方法（含 sig/access/instructionCount/interestingInvokes/interestingStrings）——足以验证隐藏 API 签名与调用关系。
- ✅ **签名读取**：返回证书 subject/issuer、SHA-256/MD5/SHA-1、签名算法、有效期。
- ❌ **无 shell 执行**：57 个工具中无 exec/shell/command 类工具（仅 `mt_file_truncate_bytes` 名字含疑似词，实为文件操作）。
- ⚠️ **文件读取分页小**：`mt_file_read_bytes` 每页 ≤65536B 且默认 64B，读 17MB APK 全文件不可行 → 文件级 SHA-256 无法经 MCP 计算，改用**证书 SHA-256**（已取）并留真机命令回填。
- ⚠️ **APK 源文件路径**：`/storage/emulated/0/MT2/mcp/`（MT 设备侧），沙盒 PRoot 无挂载，无法直接 `sha256sum`。

## 4. 调用限制与错误记录

| 错误 | 原因 | 处理 |
|------|------|------|
| `INVALID_ARGUMENT: Missing parameters`（mt_apk_search 首调） | 参数不全（缺 editSessionId/target/queryType 等） | 补全参数后成功 |
| `INVALID_ARGUMENT: Invalid queryType`（`contains` 被拒） | queryType 只接受 `literal`/`regex` | 改 `queryType=literal` + `matchMode=contains` |
| `dex_names` 前缀匹配语义 | `query: "RecentsView"` 返回 0 命中但列出相似类 | 属搜索语义，非错误；改用精确类名/正则确认 |
| `Connection refused`（阶段 0 首轮） | MT 服务未启动 | 服务恢复后重试成功 |

## 5. 未使用的工具（明确声明）

- 编辑/写入类：`mt_apk_edit_text`、`mt_apk_patch_bytes`、`mt_apk_resource_edit`、`mt_apk_build`、`mt_apk_session_*`、`mt_file_write_bytes` 等 —— 阶段 0 只读，未触碰。
- 原生分析类：`mt_apk_native_*` —— 本阶段不涉及 native 库。
- zip 工具：`mt_zip_*` —— 未使用。
- 删除类：`mt_apk_workspace_delete`、`mt_file_delete` 等 —— 未使用。

## 6. 原始输出可复核性

- 本记录所有 `✅` 结果均有 MCP 结构化输出佐证（本会话工具调用历史）。
- MT MCP 无导出/截图 API；原始 JSON 未落盘。如需独立复核：可用同版本 APK（证书 SHA-256 见 DEVICE_BASELINE_TEMPLATE §8）在 MT 管理器内重放 `mt_apk_search`/`mt_apk_dex_read_class_outline` 得到相同 locator。

## 附录：DEX 核验的关键 locator（可直接复核）

| 符号 | 工作区 | locator |
|------|--------|---------|
| `expandStackAndSelectAppBubble` | `81i4aezl` | `dex_method:Lcom/android/wm/shell/bubbles/BubbleController;->expandStackAndSelectAppBubble(Lcom/android/wm/shell/bubbles/Bubble;Lcom/android/wm/shell/shared/bubbles/logging/EntryPoint;Lcom/android/wm/shell/shared/bubbles/BubbleBarLocation$UpdateLocationRequest;)V` |
| `Bubble#<init>` | `81i4aezl` | `dex_method:Lcom/android/wm/shell/bubbles/Bubble;-><init>(Landroid/content/Intent;Landroid/os/UserHandle;Landroid/graphics/drawable/Icon;Lcom/android/wm/shell/bubbles/Bubble$BubbleType;Ljava/lang/String;)V` |
| `SystemUiProxy.showAppBubble` | `holgxhyk` | `dex_method:Lcom/android/quickstep/SystemUiProxy;->showAppBubble(Landroid/content/Intent;Landroid/os/UserHandle;Lcom/android/wm/shell/shared/bubbles/logging/EntryPoint;Lcom/android/wm/shell/shared/bubbles/BubbleBarLocation;)V` |
| `RecentsView.getCurrentPageTaskView` | `holgxhyk` | `dex_method:Lcom/android/quickstep/views/RecentsView;->getCurrentPageTaskView()Lcom/android/quickstep/views/TaskView;` |
| `mBubbleData` 字段 | `81i4aezl` | `dex_field:Lcom/android/wm/shell/bubbles/BubbleController;->mBubbleData:Lcom/android/wm/shell/bubbles/BubbleData;` |
| `Bubble$BubbleType` | `81i4aezl` | `dex_class:Lcom/android/wm/shell/bubbles/Bubble$BubbleType;`（含 `TYPE_APP`） |
| `EntryPoint` | `81i4aezl` | `dex_class:Lcom/android/wm/shell/shared/bubbles/logging/EntryPoint;`（含 `NOTIFICATION_BUBBLE_BUTTON`） |

*记录结束 · 真实调用记录 · 无虚构输出*
