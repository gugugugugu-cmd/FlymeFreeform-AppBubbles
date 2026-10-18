# 阶段三实机运行记录（Pixel / Android 17 / SDK 37）

> 本文所有条目均来自目标设备导出的 LSPosed 模块日志原始文件，
> 每行标注来源文件名与行号，可直接核对。不含推测内容。

## 0. 环境

| 项 | 值 |
|---|---|
| 设备 | Google Pixel |
| Android | 17（SDK 37） |
| LSPosed | 2.2.1 - Service API 102 |
| 模块 | FlymeFreeform 0.2.7-phase3 |

## 1. 初始化 Hook 触发

来源：`modules_2026-10-18T19_24_24.423644.log`

| 行 | 日志 |
|---|---|
| 56 | `(system) SYSTEM_FREEFORM_READY_HOOK_INSTALLED entry=AOSP sig=(java.lang.Runnable,com.android.server.utils.TimingsTraceAndSlog)` |
| 208 | `(system) SYSTEM_FREEFORM_ENTRY_FIRED entry=AOSP` |
| 209 | `(system) SYSTEM_FREEFORM_COORDINATOR_START entry=AOSP` |

**结论：** AMS.systemReady(Runnable, TimingsTraceAndSlog) 精确命中。
`sig=` 首次输出完整参数类型，取代此前只打参数个数的 `sig=2`。

## 2. WMS 指针监听注册

| 行 | 日志 |
|---|---|
| 379 | `(system) SYSTEM_POINTER_LISTENER_REGISTERED sig=(PointerEventListener,int)` |

**结论：** 命中 `registerPointerEventListener(PointerEventListener, int)`，
与白名单第二形态一致。

注：`uid=-1` 属预期。鉴权已改为注册时 `STATUS_BAR_SERVICE` 权限限定，
框架在分发阶段完成校验；`getSentFromUid()` 对普通广播恒返回 -1，
该值仅作参考日志保留，不参与放行判断。

## 3. SystemUI 左右角落热区创建

| 行 | 日志 |
|---|---|
| 246 | `SYSTEMUI_CORNER_GATE allowed=false ...`（启动瞬间，环境未就绪） |
| 375 | `SYSTEMUI_CORNER_GATE allowed=true left=true right=true` |
| 376 | `SYSTEMUI_CORNER_ZONE_ADDED side=Left size=405 h=405.0 v=405.0` |
| 377 | `SYSTEMUI_CORNER_ZONE_ADDED side=Right size=405 h=405.0 v=405.0` |

**结论：** 左右热区均实际 addView 成功。`size=405` 为像素值，
对应约 135dp（设备密度 3.0），因用户手动调大触发范围所致。

## 4. 手势触发与触摸抢占

| 行 | 日志 |
|---|---|
| 423 | `SYSTEMUI_CORNER_PILFER_OK` |
| 465 | `SYSTEMUI_CORNER_PILFER_OK` |
| 466 | `(system) GESTURE_IPC_ACK_RECEIVER_READY` |
| 467 | `(system) GESTURE_IPC_SENT id=1792322697627-644390124 target=com.tencent.mm/.ui.LauncherUI` |
| 468 | `GESTURE_IPC_RECEIVED action=io.github.mangi.flymefreeform.action.GESTURE_COMMIT uid=-1` |
| 470 | `BUBBLE_DIAG BUBBLE_A17 bubble fromOverflow=false key=key_app_bubble:0:com.tencent.mm` |
| 472 | `BUBBLE_DIAG BUBBLE_EXPAND main-thread finished=true ok=true` |
| 473 | `GESTURE_IPC_RESULT id=1792322697627-644390124 result=BubbleRequestSubmitted` |
| 474 | `(system) GESTURE_IPC_ACK id=... outcome=Accepted diag=null` |

**结论：** 角落触发 → pilferPointers 成功 → IPC 请求 → 气泡创建 → 展开 → ACK Accepted，
全链路闭环。两次 PILFER_OK 对应左右两侧各一次。

## 5. 系统导航兼容性

**状态：已通过（用户实机确认，2026-10-18）。**

- [x] 底部中间上划 → 回桌面
- [x] 底部右侧上划并停留 → 最近任务
- [x] 底部左/右滑 → 切换应用

测试条件：角落热区 405px（约 135dp，高于默认 84dp 的手动配置），
即热区处于偏大状态时导航手势仍正常，属较严格条件下的通过。

注：本项为人工操作确认。模块日志可佐证 `SYSTEMUI_CORNER_PILFER_OK`
仅在角落热点被命中时出现（L423/L465），未在底部导航区触发抢占，
与人工结论一致。

## 6. 构建产物

| 版本 | CI Run | commit | SHA-256 |
|---|---|---|---|
| d4-perm | 39121378733 | `0f092f0` | `85fd6c74881a3aca95f2778afcb2fe88dbf3ade15a0f202dfd32e42d86f19712` |
| d5-p0 | 39122505545 | `18ea5d2` | `ae9229ac3583869d90edb279530631651f788a253029a2791004b8e9930a9814` |
| d6-sig | 39123304177 | `e2661a7` | `9bde713762185ea20117c99aa6ff018e2dd470efc70f007e25e10011ef74b383` |
| d7-type | 39124102350 | `6487e35` | `f6fc2fa67dd1a818a25f9ae6b645000ed42c1e2e4b8de7ee0bd3b4e5b49c9fa7` |

产物由 GitHub Actions 构建，仓库 `https://github.com/gugugugugu-cmd/FlymeFreeform-AppBubbles`
分支 `phase3`，任一 Run 均可独立复核。

## 7. 遗留告警（不阻断阶段三）

- `RADIAL_NATIVE_ICON_SHAPE_UNAVAILABLE` —— 原生图标形状读取失败，仅影响图标外形
- `SIDEBAR_TOOL_TARGET_UNAVAILABLE` —— ColorOS 侧边栏组件，Pixel 无对应目标
- `PIXEL_BUBBLE_TEST_ENTRY_READY launcherAvailable=false` —— 测试入口旁路，与主链独立
