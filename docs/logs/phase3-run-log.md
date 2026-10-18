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

**状态：未验证。**

需在设备上实测并记录：

- [ ] 底部中间上划 → 回桌面
- [ ] 底部右侧上划并停留 → 最近任务
- [ ] 底部左/右滑 → 切换应用

已知风险点：当前热区 405px，在 1080 宽屏上约占 37.5% 宽度，
存在遮挡底部导航手势条的可能。若实测受阻，将触发范围从约 135dp
调回默认 84dp（热区约 252px）后复测。

## 6. 构建产物

| 版本 | CI Run | commit | SHA-256 |
|---|---|---|---|
| d4-perm | 39121378733 | `0f092f0` | `85fd6c74881a3aca95f2778afcb2fe88dbf3ade15a0f202dfd32e42d86f19712` |
| d5-p0 | 39122505545 | `18ea5d2` | `ae9229ac3583869d90edb279530631651f788a253029a2791004b8e9930a9814` |
| d6-sig | 39123304177 | `e2661a7` | `9bde713762185ea20117c99aa6ff018e2dd470efc70f007e25e10011ef74b383` |

产物由 GitHub Actions 构建，仓库 `https://github.com/gugugugugu-cmd/FlymeFreeform-AppBubbles`
分支 `phase3`，任一 Run 均可独立复核。

## 7. 遗留告警（不阻断阶段三）

- `RADIAL_NATIVE_ICON_SHAPE_UNAVAILABLE` —— 原生图标形状读取失败，仅影响图标外形
- `SIDEBAR_TOOL_TARGET_UNAVAILABLE` —— ColorOS 侧边栏组件，Pixel 无对应目标
- `PIXEL_BUBBLE_TEST_ENTRY_READY launcherAvailable=false` —— 测试入口旁路，与主链独立
