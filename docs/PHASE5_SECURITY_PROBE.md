# 阶段 5 安全验证：IPC 权限边界

日期：2026-10-18

---

## 1. 要证明什么

阶段 3 把 IPC 鉴权从「`getSentFromUid()` 事后校验」改成了
「注册时 `requiredPermission = STATUS_BAR_SERVICE`，由框架在**分发阶段**拦截」。
这个改动当时只有正面证据（system_server 的合法请求能进来），
**没有反面证据**（普通应用的伪造广播进不来）。

本验证补的就是反面证据。

---

## 2. 为什么不能只看日志

「日志里没有 `GESTURE_IPC_RECEIVED`」单独不构成证据：

- 可能是权限拦下了（期望）
- 可能是接收器没注册（`GESTURE_IPC_RECEIVER_FAILED`）
- 可能是请求解析失败（会记 `GESTURE_IPC_BAD_REQUEST`）
- 可能是 SystemUI 进程重启、日志没跟进

所以判据必须成对，并且带对照组。

---

## 3. 夹具设计

模块：`:androidTest`（`androidTest/`），包名 `io.github.mangi.flymefreeform.forge`。
**独立模块而非 app 的 androidTest 源集**：验证代码不能混进模块主 APK 的发布产物。

夹具做三件事，全部在夹具进程（普通应用 UID）里发：

| 探测 | 方式 | 期望 |
|---|---|---|
| 1 | `sendBroadcast(intent)` 无权限限定 | 被拦 |
| 2 | `sendBroadcast(intent, STATUS_BAR_SERVICE)` | 被拦（无权限方在分发阶段静默丢弃） |
| 3 | 同一 intent 走 `ACTION_COMMIT_FINALIZE` | 被拦 |

三者都用固定前缀的 `request_id`（`forge-security-probe-*`），便于在 SystemUI 日志里定位。

覆盖 `ACTION_COMMIT` 与 `ACTION_COMMIT_FINALIZE` 两个 action，
与接收器 `IntentFilter` 注册的两个 action 一一对应——漏测一个就等于漏测一条入口。

---

## 4. 判据

导出 SystemUI + system_server 的模块日志后：

| 现象 | 结论 |
|---|---|
| 无任何 `forge-security-probe` 相关记录，且**有**对照组记录 | **通过**：权限限定生效 |
| 出现 `GESTURE_IPC_RECEIVED id=forge-security-probe-*` | **失败**：广播穿透，鉴权失效 |
| 出现 `GESTURE_IPC_BAD_REQUEST`（无 id） | **存疑**：说明请求到了接收端，只是解析失败 |
| 连合法的对照组也没有 | **作废**：日志管道本身不通，需重测 |

对照组 = 验证过程中在设备上**正常触发一次手势**（必然产生
`GESTURE_IPC_RECEIVED` + `GESTURE_IPC_ACK`）。没有对照组，第 1 行结论不成立。

---

## 5. 执行步骤

```bash
# 1. 安装夹具（不需要 Launcher 图标，直接 install 即可）
adb install -r FlymeFreeformForge.apk

# 2. 清空并开始记录模块日志；然后运行夹具
adb shell am instrument -w \
  -e class io.github.mangi.flymefreeform.forge.ForgeBroadcastTest \
  io.github.mangi.flymefreeform.forge/android.test.InstrumentationTestRunner

# 3. 立刻在设备上正常触发一次角落手势（对照组）

# 4. 导出模块日志，按 §4 判据核对
```

夹具若是通过模块管理界面手动安装，也可以直接在系统里运行
（若设备无 ADB，可在 LSPosed 管理器里对夹具包执行 instrumentation）。

---

## 6. 结果

待实机执行。
