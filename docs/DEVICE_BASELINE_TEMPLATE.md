# DEVICE_BASELINE — 目标设备运行时基线记录

> 阶段 0 产出 · 任务 0.3 · 运行时环境确认
>
> ⚠️ **本文件为"待填写"模板**：当前会话无真机访问权限（MT MCP 反编译服务已恢复，可离线核验 APK；但设备侧信息仍需真机实测），
> 下列栏目中**设备运行时项**（fingerprint / LSPosed / ROOT / 气泡实机行为）必须由用户在目标 Pixel 设备上实测后补齐，
> 补齐前不得把表内项当作已验证事实。
>
> 填报路径建议：真机上用 LSPosed 日志 / `dumpsys` / 终止工具收集后填入。
>
> **2026-10-18 更新**：MT 反编译已核验 Pixel Launcher 17 / SystemUI 17 两个目标 APK（见 §8「离线 DEX 核验结果」），
> 其中包名/版本/关键类签名可直接引用，无需真机再查。

---

## 1. 基础系统信息

| 字段 | 值 |
|------|-----|
| `Build.FINGERPRINT` | `<待真机填写>` |
| `Build.VERSION.SDK_INT` | `<待真机填写>`（目标应为 37） |
| `Build.VERSION.RELEASE` | `<待真机填写>` |
| `Build.ID`（构建号） | `<待真机填写>` |
| 设备代号 | `<待真机填写>`（应为 Pixel 系列） |
| ROM 类型 / 是否原厂 | `<待真机填写>` |
| ROOT 方案 | `<待真机填写>`（Magisk / KernelSU / APatch） |
| LSPosed 版本 | `<待真机填写>` |
| Zygisk / 模块框架状态 | `<待真机填写>` |

**离线可预判**（非结论）：目标环境按任务书为 **Pixel ROM / 原生 Android 17（API 37）**。API 37 于 2025 年随 Android 17 发布，对应 compileSdk 37（FlymeFreeform 与 MoreBubbleButton 的 gradle 均已设 `compileSdk 37`，与任务书一致）。

---

## 2. 关键包与版本

### 2.1 需要录入的系统包

| 项 | 包名（预期） | 版本号 | 备注（用途） |
|----|--------------|--------|-------------|
| Pixel Launcher | `com.google.android.apps.nexuslauncher` **或** `com.android.launcher3` / `com.android.launcher` | `<待真机填写>` | **三者不可互换**；此处确认实际安装者。MoreBubbleButton 监听前两者。 |
| SystemUI | `com.android.systemui` | `<待真机填写>` | 气泡后端所在 |
| Quickstep | `com.android.systemui`（AOSP 已合并）或独立包 | `<待真机填写>` | `SystemUiProxy` / `RecentsView` 所在 |
| 微信 | `com.tencent.mm` | `<待真机填写>` | 测试目标 1 |
| 起点读书 | `com.qidian.QDReader` | `<待真机填写>` | 测试目标 2，请核实实际包名 |
| 普通无状态测试应用 | （新建） | - | 测试目标 3 |

### 2.2 进程注入能力确认

| 进程 | libxposed 是否注入 | 依据（日志特征） |
|------|-------------------|------------------|
| `system_server` | `<待真机填写>` | `ModuleMain` 需 `PROP_CAP_SYSTEM`；日志 `SYSTEM_FREEFORM_READY_HOOK_INSTALLED`（现状 ColorOS 下） |
| `com.android.systemui` | `<待真机填写>` | 日志 `SYSTEMUI_CORNER_SPY_HOOK_INSTALLED` |
| Pixel Launcher 进程 | `<待真机填写>` | 现有代码 `PROCESS_LAUNCHER="com.android.launcher"` 在 Pixel 不匹配，**需先确认实际进程名再修正** |

---

## 3. 目标 ROM 隐藏类存在性核验表

> 由 MT MCP（一旦可用）或 `dumpsys`/DEX 反编译核验；务必填「存在/不存在/未核验」+ 依据。

| 类 / 方法 | 存在性 | 签名证据 |
|-----------|--------|---------|
| `com.android.server.wm.FlexibleTaskController#systemReady(boolean)` | ❌ 不存在（SystemUI 17 0 命中；framework.jar 层待系统镜像） | DEX 核验 |
| `com.android.server.wm.FlexibleTaskScaleManager#getGestureMode` | ❌ 不存在（同上） | DEX 核验 |
| `com.android.server.wm.FlexibleCaptionView` | ❌ 不存在（同上） | DEX 核验 |
| `com.android.quickstep.OplusBaseTouchInteractionService.onInputEventInternal` | ❌ 不存在（SystemUI 17 0 命中） | DEX 核验 |
| `WMS.registerPointerEventListener(PointerEventListener, int)` | ⚠️ 待核验（framework.jar 层，APK 不可核） | 真机反射验证（阶段 1） |
| `WindowManagerPolicyConstants$PointerEventListener.onPointerEvent(MotionEvent)` | ⚠️ 待核验（同上） | 真机反射验证（阶段 1） |
| `com.android.systemui.SystemUIApplication` | ✅ 存在（AOSP 标准，SystemUI 17） | DEX 核验 |
| `com.android.systemui.application.impl.SystemUIApplicationImpl` | ❌ 不存在（ColorOS 17 专属） | DEX 核验 |
| `com.android.quickstep.SystemUiProxy#showAppBubble(...)` | ✅ **存在** `(Intent, UserHandle, EntryPoint, BubbleBarLocation)V` | DEX 核验（Launcher 17） |
| `com.android.wm.shell.bubbles.BubbleController#expandStackAndSelectAppBubble(Bubble, EntryPoint, UpdateLocationRequest)` | ✅ **存在** | DEX 核验（SystemUI 17） |
| `com.android.wm.shell.bubbles.Bubble#(Intent, UserHandle, Icon, BubbleType, String)` | ✅ **存在** | DEX 核验（SystemUI 17） |
| `com.android.wm.shell.bubbles.Bubble#getAppBubbleKeyForApp(String, UserHandle)` | ✅ **存在** `(String,UserHandle)String` | DEX 核验（SystemUI 17） |
| `com.android.wm.shell.bubbles.BubbleData#findAndRemoveBubbleFromOverflow(String)` | ✅ **存在** `(String)Bubble` | DEX 核验（SystemUI 17） |
| `com.android.wm.shell.shared.bubbles.logging.EntryPoint`（含 `NOTIFICATION_BUBBLE_BUTTON`） | ✅ **存在**（`NOTIFICATION`/`NOTIFICATION_BUBBLE_BUTTON`/`TASKBAR_ICON_DRAG`） | DEX 核验（SystemUI 17） |
| `com.android.wm.shell.shared.bubbles.BubbleBarLocation$UpdateLocationRequest` | ✅ **存在** | DEX 核验（SystemUI 17） |
| `com.android.systemui.wmshell.BubblesManager` | ⚠️ 待核验（该类在 SystemUI APK 中未单独 outline；经 `BubblesManager$Bubbles` 内类间接确认，低风险） | 阶段 1 outline |

> **证据分级**：`✅ 存在` = DEX 核验（签名存在，L1）；`⚠️ 待核验` = framework.jar / system_server 层，APK 不可核（真机反射）；`❌ 不存在` = DEX 0 命中（但 framework.jar 层同名类不排除——已注明）。**"签名存在"≠"后端可用"**：L1 签名证据不代表 L3 端到端可用，阶段 1 真机验证前不得据此宣称功能可用。

---

## 4. 模块框架兼容记录

| 项 | 值 |
|----|-----|
| libxposed API（本项目用） | FlymeFreeform: **102**；MoreBubbleButton: **101** |
| 是否满足 `PROP_CAP_REMOTE` | `<待真机填写>`（无则 ModuleMain 直接 detach） |
| system_server 是否具备 `PROP_CAP_SYSTEM` | `<待真机填写>` |
| 远端偏好（getRemotePreferences）是否可用 | `<待真机填写>`（现状有"跨进程不热更"限制，见 ProcessConfiguration 注释） |
| 是否需要 Zygisk 模式 | `<待真机填写>` |

---

## 5. 已知约束与风险（当前离线状态）

1. **`GITHUB_TOKEN` 已设置在沙盒环境**（用于后续阶段通过 GitHub Actions 编译 APK，任务书要求不本地构建）。
2. ~~**MT MCP 服务当前不可达**~~ → **已恢复（2026-10-18）**，已反编译 `系统界面_17.apk` / `Pixel 启动器_17.apk` 完成关键类核验（见 §8）。
3. 无真机连接，本阶段**未产生任何运行日志**；不得伪造。
4. Pixel Launcher 包名必须实测（§2.1），凭 `com.android.launcher` 假设会直接导致 Launcher hook 失效。

---

## 6. 补齐本模板的命令建议（真机可执行）

```bash
# 指纹/版本
getprop ro.build.fingerprint
getprop ro.build.version.sdk
getprop ro.build.version.release
getprop ro.build.id
# 关键包版本
pm list packages | grep -E "launcher|systemui|nexus"
dumpsys package com.google.android.apps.nexuslauncher | grep versionName
dumpsys package com.tencent.mm | grep versionName
dumpsys package com.qidian.QDReader | grep versionName
# LSPosed 作用域/注入
# （通过 LSPosed Manager → 日志 查看模块注入进程）
```

---

## 8. 离线 DEX 核验结果（2026-10-18，MT 反编译）

> 设备侧需真机，但**目标 APK 已离线核验**，下列项可直接引用，不再标 `<待真机填写>`。

| 项 | 核验结果 | 来源 |
|----|---------|------|
| Pixel Launcher 实际包名 | ✅ `com.google.android.apps.nexuslauncher`（v17 / code 907 / minSdk 37） | Pixel 启动器_17.apk |
| SystemUI 包名/版本 | ✅ `com.android.systemui`（v17 / code 37 / minSdk 37） | 系统界面_17.apk |
| Launcher 进程含 `com.android.launcher3` 类 | ✅ 有（`com.android.launcher3.taskbar.RecentsViewInteractor` 等） | 同上 |
| `SystemUiProxy.showAppBubble` 签名 | ✅ `(Intent, UserHandle, EntryPoint, BubbleBarLocation)V` | Launcher |
| `BubbleController.expandStackAndSelectAppBubble` 签名 | ✅ `(Bubble, EntryPoint, UpdateLocationRequest)V` | SystemUI |
| `Bubble(Intent,UserHandle,Icon,BubbleType,String)` 构造 | ✅ 存在 | SystemUI |
| `BubbleType.TYPE_APP` / `EntryPoint.NOTIFICATION_BUBBLE_BUTTON` | ✅ 存在 | SystemUI |
| `RecentsView.getCurrentPageTaskView` → `TaskView` | ✅ 存在 | Launcher |
| ColorOS 类（`OplusBaseTouchInteractionService` / `FlexibleTaskController`） | ❌ SystemUI 17 中 0 命中 | SystemUI |

**待真机实测项**（不可离线替代）：
- 微信 `com.tencent.mm` 8.0.79 / 起点读书 `com.qidian.QDReader` 7.9.480 的**实机气泡行为**（已从 MT 素材确认这两个 APK 可用于测试安装，但行为需真机）；
- `showAppBubble` 接受任意 Intent 的语义（阶段 1 必测）；
- LSPosed 三进程注入能力、`PROP_CAP_REMOTE` / `PROP_CAP_SYSTEM`。

*文档结束 · DEVICE_BASELINE_TEMPLATE.md · 阶段 0 · 待真机补齐（APK 层已核验）*