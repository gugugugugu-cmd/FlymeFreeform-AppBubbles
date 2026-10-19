# 阶段三 Pixel Hook 验证报告

> 对应实施计划 §6「Pixel Hook 初始化、角落手势输入、系统导航兼容性」
> 与 §9「每阶段需提供可核验的构建、运行与阶段反馈证据」

- 设备：Pixel，Android 17（SDK 37）
- 框架：LSPosed 2.2.1，API 102
- 分支：`phase3`

---

## 1. 静态可靠性整改（响应审查 P0）

### 1.1 AOSP 初始化入口选择

**原问题**：`installAospEntry()` 用

```kotlin
cls.declaredMethods.firstOrNull { it.name == "systemReady" && it.parameterTypes.isNotEmpty() }
```

按方法名 + 参数个数挑第一个匹配项。反射返回顺序不能作为选择依据，
且「Hook 安装成功」被当成「协调器已启动」。

**整改**：新增 `matchesKnownSystemReady(types)` 白名单，按**参数类型**匹配：

| 签名 | 说明 |
|---|---|
| `systemReady()` | 旧版 |
| `systemReady(Runnable)` | 传入系统就绪回调 |
| `systemReady(Runnable, …)` | 后续版本在 Runnable 后追加参数（≤3 参） |

共同判据：**首参必须是 `Runnable`**。早期 `systemReady(ActivityManagerService)`
等自引用形态不属于启动收尾路径，不再误选。

匹配失败时输出全量候选签名：

```
SYSTEM_FREEFORM_TARGET_UNAVAILABLE entry=AOSP reason=no-exact-signature candidates=(…)；(…)；(…)
```

安装成功时记录**完整签名**而非参数个数：

```
SYSTEM_FREEFORM_READY_HOOK_INSTALLED entry=AOSP sig=(java.lang.Runnable,boolean)
```

新增触发侧探针，把三个环节分开留证：

```
SYSTEM_FREEFORM_ENTRY_FIRED      entry=AOSP            ← Hook 真的被执行
SYSTEM_FREEFORM_ENTRY_NO_THIS    entry=AOSP            ← thisObject 取不到
SYSTEM_FREEFORM_ENTRY_NO_ACCESS  entry=AOSP cls=…      ← 拿到对象但取不到 Context/ATMS
SYSTEM_FREEFORM_COORDINATOR_START entry=AOSP           ← 协调器真正启动
```

### 1.2 WMS 指针注册签名

**原问题**：`findAnyPointerRegisterMethod()` 选取任意同名、参数非空的方法，
再按参数**个数**猜传参。参数个数相同不代表类型语义相同；
异常被捕获也不等于注册成功。

**整改**：删除宽泛回退，改为 `resolvePointerRegistration()` 白名单：

| 签名 | 传参 |
|---|---|
| `registerPointerEventListener(PointerEventListener)` | `[listener]` |
| `registerPointerEventListener(PointerEventListener, int)` | `[listener, Display.DEFAULT_DISPLAY]` |

三参布尔形态从未在任何设备命中过，一并删除——保留只会带来误配风险。

注册成功记录实际命中的签名：

```
SYSTEM_POINTER_LISTENER_REGISTERED sig=(PointerEventListener,int)
```

无精确匹配时输出候选清单：

```
SYSTEM_POINTER_LISTENER_NO_METHOD candidates=[registerPointerEventListener(…)]
```

---

## 2. SystemUI 角落输入证据链（响应审查 P1）

审查指出 `SYSTEMUI_CORNER_SPY_READY` 只证明 `start()` 返回，不能证明热区已建。
现拆为四层，每层独立留证：

| 日志 | 含义 |
|---|---|
| `SYSTEMUI_CORNER_SPY_READY` | 监视器初始化返回（仅此而已） |
| `SYSTEMUI_CORNER_GATE allowed=… left=… right=… env=…` | 闸门判定与各项开关 |
| `SYSTEMUI_CORNER_ZONE_ADDED side=Left size=…` | **热区 View 成功 addView** |
| `SYSTEMUI_CORNER_ZONE_REMOVED side=…` | 热区被移除 |
| `SYSTEMUI_CORNER_SPY_ADD_FAILED side=…` | addView 失败（含 side） |
| `SYSTEMUI_CORNER_PILFER_OK` | **pilferPointers() 抢占成功** |
| `SYSTEMUI_CORNER_SPY_PILFER_FAILED` | 抢占失败（限流日志） |

`SYSTEMUI_CORNER_GATE` 复用 `ModuleEnvironmentState.describe()`，
输出闸门每一项，可直接定位是哪一项关闭。

---

## 3. 桌面作用域动态解析（响应审查 P1）

**原问题**：`REQUIRED_SCOPES` 写死 `com.android.launcher`，Pixel 上该包不存在，
永远提示「缺作用域」。

**整改**：

- `REQUIRED_SCOPES` 只保留与平台无关的基座：`system`、`com.android.systemui`
- 新增 `LAUNCHER_SCOPE_CANDIDATES`：`nexuslauncher` / `com.android.launcher` / `launcher3`
- `FrameworkConnectionRepository.detectedLauncherScope()` 按 `PackageManager`
  探测本机实装包，动态并入必需作用域

`ModuleMain` 的进程匹配本就同时覆盖两个包名，无需改动。

---

## 4. 实机验证记录

见 `phase3-run-log.md`（每次真机验证追加一节）。

---

## 5. 已知非阻断遗留

| 日志 | 影响 |
|---|---|
| `SIDEBAR_TOOL_TARGET_UNAVAILABLE` | ColorOS 侧边栏组件，Pixel 无对应目标 |
| `RADIAL_NATIVE_ICON_SHAPE_UNAVAILABLE` | 原生图标形状读取失败，仅影响图标外形 |
| `OUTSIDE_TAP_TARGET_UNAVAILABLE` | 同上 |
| `HANDLE_SWIPE_UP_TARGET_UNAVAILABLE` | 同上 |
| `PIXEL_BUBBLE_TEST_ENTRY_READY launcherAvailable=false` | 测试旁路，与主链独立 |

---

## 6. 构建验证说明

本地沙盒因网络限制无法执行 Gradle，构建统一由 GitHub Actions 完成。
每次提交的 workflow run ID 与产物 SHA-256 记录在 `phase3-run-log.md`。
