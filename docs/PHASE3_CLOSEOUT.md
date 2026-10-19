# 阶段 3 收尾报告

**结论：阶段 3 已闭环，准许进入阶段 4。**

日期：2026-10-18
分支：`phase3`（HEAD `6487e35`）
收尾标签：`phase3-complete`

---

## 1. 阶段目标与达成情况

实施计划第 6 节对阶段 3 的要求是：

> Pixel 上角落手势可稳定触发测试回调，且不会明显破坏返回、主页、最近任务等系统导航手势。

| 验收项 | 状态 | 证据 |
|---|---|---|
| Pixel/AOSP 初始化入口 | 通过 | `HOOK_INSTALLED sig=(Runnable,TimingsTraceAndSlog)` `ENTRY_FIRED` `COORDINATOR_START` |
| WMS 指针监听注册 | 通过 | `POINTER_LISTENER_REGISTERED sig=(PointerEventListener,int)` |
| SystemUI 左右热区创建 | 通过 | `CORNER_ZONE_ADDED side=Left/Right` |
| pilferPointers 成功 | 通过 | `CORNER_PILFER_OK` ×2 |
| 手势阈值触发 | 通过 | 扇形弹出并完成选择 |
| 端到端气泡链路 | 通过 | IPC 三态 + `BUBBLE_EXPAND ok=true` |
| 导航兼容性 | 通过 | 用户实机确认，热区 405px 下三项导航手势均正常 |
| 阶段报告与构建产物 | 通过 | `docs/logs/phase3-run-log.md` + CI 产物 SHA |

实机日志：`modules_2026-10-18T19_24_24.423644.log`

---

## 2. 本阶段修复清单

阶段 3 共暴露五个阻断项，均已在源码修复并实机验证：

### 2.1 设置项被作用域强制屏蔽（`1129d51`）

`canChangeSettings` 要求 `missingScopes.isEmpty()`，而 `REQUIRED_SCOPES`
含 Pixel 上不存在的 `com.coloros.smartsidebar`，导致总开关永久灰化。
表现为 `enabled` 恒为 false，指针监听永不注册。

修复：去掉该条件，仅保留「框架已连接且非忙」；作用域列表移除 ColorOS 专属项；
桌面包名改为按实装包探测。

### 2.2 配置/工具接收器缺 RECEIVER_EXPORTED（`4f88925`）

`CONFIG_PUSH_RECEIVER_FAILED` 导致 `configuration.observe{}` 永不回调。
修复：补 flags，并在 `start()` 末尾主动 `applySettings()` 兜底。

### 2.3 IPC 发送方校验误杀（`0f092f0`）

`getSentFromUid()` 对普通广播恒返回 -1，两侧「取不到即拒绝」
把 system_server 自己的合法请求也挡了。

修复：改用 `STATUS_BAR_SERVICE` 框架级权限限定，收发对称，
非授权进程在分发阶段即被拦截。

### 2.4 AOSP 入口按签名精确匹配（`18ea5d2` / `e2661a7`）

原实现取「第一个有参数的 systemReady」，依赖反射返回顺序。
修复：按完整参数类型白名单匹配；并区分「安装成功」与「已触发」两类日志。

### 2.5 WMS 指针注册完整类型匹配（`add80b2`）

原用 `!params[0].isPrimitive` 判断首参，只能说明不是基本类型。
修复：与真实 `PointerEventListener` 接口类做相等比较。

---

## 3. 遗留项（转阶段 4）

均不阻断阶段 3：

- `SIDEBAR_TOOL_TARGET_UNAVAILABLE` —— ColorOS 侧边栏组件，Pixel 无对应目标
- `RADIAL_NATIVE_ICON_SHAPE_UNAVAILABLE` —— 原生图标形状读取失败，仅影响图标外形
- `OUTSIDE_TAP_TARGET_UNAVAILABLE` / `HANDLE_SWIPE_UP_TARGET_UNAVAILABLE` —— 同类
- `PIXEL_BUBBLE_TEST_ENTRY_READY launcherAvailable=false` —— 测试旁路，与主链独立

---

## 4. 阶段 4 前置约束

1. **不得把角落输入入口当作已验证的稳定依赖**——它已通过验收，但
   后续改动若触碰 `SystemUiCornerInputMonitor` 或协调器注册逻辑，须重跑导航兼容性检查。

2. **保持 IPC 协议稳定**。阶段 2/3 建立的 `GestureBubbleIpcProtocol`
   已具备三态处理与权限鉴权，阶段 4 应在其上扩展，不重写。

3. **测试入口与生产路径分离**。`PIXEL_BUBBLE_TEST_ENTRY_*` 依赖
   `persist.flymefreeform.test_entry`，生产构建下不可用，属预期。

4. 阶段 4 范围：扇形菜单整合与回归测试。阶段 3 不要求提前完成。
