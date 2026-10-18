# MT_MANAGER_MCP_CAPABILITIES.md — MT 管理器 MCP 能力总览

> 审查报告 P0 交付物 #3 · 2026-10-18
> 用途：记录 MT 管理器 MCP 服务（本项目反编译/改包基础设施）的真实能力、限制与调用约定，供阶段 1+ 及协作者参考。
> 详细调用记录见 `docs/logs/phase0_mt_mcp_capabilities.md`。

## 1. 服务概览

- 连接：`minis-mcp-cli`（本地 TCP，服务器名 `MT`）。
- 工具总数：**57**。
- 能力域：APK 反编译分析（dex/资源/签名）、APK 编辑构建（会话/补丁）、设备文件操作、zip 操作。
- 无 shell/命令执行能力。

## 2. 能力分类

### 2.1 APK 分析（本项目核心用途）

| 能力 | 代表工具 | 说明 |
|------|---------|------|
| 打开工作区 | `mt_apk_workspace_open` | 返回 workspaceId、包名、版本、minSdk/targetSdk、zip/dex/xml 计数、能力位 |
| 列出 APK | `mt_apk_list_available_apks` / `mt_apk_workspace_list` | 根目录 APK / 已开工作区 |
| dex 符号检索 | `mt_apk_search` | `target=overview\|dex_names\|dex_strings`；`queryType=literal\|regex`；`matchMode=contains` |
| 类 outline | `mt_apk_dex_read_class_outline` | 类头/父类/接口/字段/方法签名/调用引用/字符串引用 |
| 交叉引用 | `mt_apk_dex_xref` | 方法/字段/类引用图 |
| 资源读取 | `mt_apk_resource_read` / `mt_apk_resource_xref` | arsc 资源表 |
| native 分析 | `mt_apk_native_*`（7 个） | so 库 inspect/list/xref/disassemble/CFG |
| 签名读取 | `mt_apk_read_signature` | 证书 subject/issuer、SHA-256/MD5/SHA-1、算法、有效期 |

### 2.2 APK 编辑构建

- 会话管理：`mt_apk_session_create` / `mt_apk_session_check` / `mt_apk_continue`
- 编辑：`mt_apk_edit_text` / `mt_apk_patch_bytes` / `mt_apk_delete_target` / `mt_apk_resource_edit/add` / `mt_apk_native_patch_*` / `mt_apk_copy_entry`
- 构建：`mt_apk_build`

### 2.3 文件与 zip

- 文件：list/stat/read_text/read_bytes/write/patch/copy/move/delete/search/replace/append/truncate（`mt_file_*`）
- zip：list/read/extract/test/create/update（`mt_zip_*`）

## 3. 关键调用约定（易错点）

| 工具 | 约定 |
|------|------|
| `mt_apk_search` | **必须**传 `editSessionId`（无会话传 `""`）、`target`、`queryType`（仅 `literal\|regex`）、`matchMode`、`prefix`、`caseSensitive`、`includeMatchOffsets`、`snippetMaxChars`；`queryType` 传 `contains` 会报 `Invalid queryType` |
| `mt_apk_dex_read_class_outline` | locator 形如 `dex_class:com.android.wm.shell.bubbles.Bubble;`（冒号后 dex 名） |
| `mt_file_read_bytes` | 每页 ≤65536B；返回 `hex`（非 base64）；分页需回传 `targetVersion` |
| `mt_file_stat` | 返回 `targetVersion` 供后续读取锁定版本 |
| `mt_apk_workspace_open` | `temporary:false` 持久化工作区；重复打开同一 APK 复用 workspaceId |

## 4. 限制（真实记录）

1. **无命令执行**：不能在设备上运行 `sha256sum`/`dumpsys`/`pm` 等。设备运行时信息需用户真机执行（见 DEVICE_BASELINE_TEMPLATE §6）。
2. **文件级 SHA-256 不可经 MCP 计算**：`mt_file_read_bytes` 分页太小（默认 64B，最大 64KB），17MB APK 全读不可行。替代：**证书 SHA-256**（`mt_apk_read_signature`，已取 5 个）——比文件哈希更强的真伪证据。
3. **不解析 system_server / framework.jar**：MT 只能开 APK。`WMS.registerPointerEventListener`、`PointerEventListener`、`FlexibleTaskController`（若存在于 framework）均无法 APK 核验，需系统镜像或真机反射。
4. **无导出/截图 API**：原始 JSON 不落盘；复核靠同版本 APK 重放搜索（附录 locator 已给）。
5. **搜索结果语义**：`dex_names` 的 `contains` 对类名前缀匹配返回 0 命中但列出近似类——用完整类名或 `regex` 精确匹配。

## 5. 与项目各阶段的关系

- **阶段 0**：已用分析能力核验 21 项隐藏 API 签名（21 项全部有 locator 证据）。
- **阶段 1+**：预期使用 `mt_apk_workspace_open` + `mt_apk_search`/`outline` 核验 LSPosed 注入目标、AndroidManifest 权限、测试 APK 目标组件；若需改包（如给测试 APK 加气泡配置），用 `mt_apk_session_*` + `mt_apk_build`。
- **约束**：编辑/构建类工具在阶段 0 未使用；后续使用前需明确记录输入输出。

*总览结束 · 能力与限制均来自真实调用*
