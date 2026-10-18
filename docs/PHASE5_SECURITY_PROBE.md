# 阶段 5 安全验证：IPC 权限边界

日期：2026-10-18

---

## 1. 要证明什么

阶段 3 把 IPC 鉴权从「`getSentFromUid()` 事后校验」改成了
「注册时 `requiredPermission = STATUS_BAR_SERVICE`，由框架在**分发阶段**拦截」。
当时只有正面证据（system_server 的合法请求能进来），**没有反面证据**——
没有验证过普通应用能否伪造同样的广播。

本验证补的就是反面证据。

---

## 2. 为什么不能只看日志

「日志里没有 `GESTURE_IPC_RECEIVED`」单独不构成证据：

- 可能是权限拦下了（期望）
- 可能是接收器没注册（日志里会有 `GESTURE_IPC_RECEIVER_FAILED`）
- 可能是请求解析失败（会记 `GESTURE_IPC_BAD_REQUEST`）
- 可能是 SystemUI 进程重启、日志没跟进

所以判据必须成对，并且带对照组。

---

## 3. 夹具设计

模块 `:androidTest`（`androidTest/`），包名 `io.github.mangi.flymefreeform.forge`，
产物 `FlymeFreeformForge-e1.apk`（`da54d985…57f578ea`）。

**独立模块而非 app 的 androidTest 源集**：验证代码不能混进模块主 APK 的发布产物。

夹具清单里没有任何 Activity / 服务 / 接收器，安装后桌面无图标；
`<instrumentation>` 指向平台自带的 `android.test.InstrumentationTestRunner`，
**编译依赖只有 `android.jar`**（无 androidx.test、无 JUnit、无 Kotlin）。

三个探测，全部在夹具进程（普通应用 UID）里发出：

| 探测 | 方式 | 期望 |
|---|---|---|
| 1 | `ACTION_COMMIT`，`sendBroadcast(intent)` 无权限限定 | 被拦 |
| 2 | `ACTION_COMMIT`，`sendBroadcast(intent, STATUS_BAR_SERVICE)` | 被拦（无权限方在分发阶段被静默丢弃） |
| 3 | `ACTION_COMMIT_FINALIZE`，无权限限定 | 被拦 |

覆盖接收器 `IntentFilter` 注册的两个 action——漏测一个就等于漏测一条入口。

三条都用固定前缀的 `request_id`（`forge-security-probe-*`）便于定位。

---

## 4. 判据

导出 SystemUI + system_server 的模块日志后：

| 现象 | 结论 |
|---|---|
| 无任何 `forge-security-probe` 记录，且**有**对照组记录 | **通过**：权限限定生效 |
| 出现 `GESTURE_IPC_RECEIVED id=forge-security-probe-*` | **失败**：广播穿透，鉴权失效 |
| 出现 `GESTURE_IPC_BAD_REQUEST`（无 id） | **存疑**：请求到了接收端，只是解析失败 |
| 连合法的对照组也没有 | **作废**：日志管道本身不通，需重测 |

对照组 = 验证过程中在设备上**正常触发一次手势**，必然产生
`GESTURE_IPC_RECEIVED` + `GESTURE_IPC_ACK`。没有对照组，第一行结论不成立。

---

## 5. 执行步骤

```bash
# 1. 安装夹具（无桌面图标，装完看不到是正常的）
adb install -r FlymeFreeformForge-e1.apk

# 2. 运行夹具
adb shell am instrument -w \
  io.github.mangi.flymefreeform.forge/android.test.InstrumentationTestRunner

# 3. 立刻在设备上正常触发一次角落手势（对照组）

# 4. 导出模块日志，按 §4 判据核对
```

无 ADB 时：夹具已声明 `functionalTest`，可在 LSPosed 管理器或系统
「开发者选项 → 测试」里跑；最省事的仍是 ADB。

夹具运行时会向 logcat 打 `FORGE_SENT` / `FORGE_BLOCKED`（`SendPermissionDeniedException`）/
`FORGE_DELIVERED`，可作为辅助证据，但**不作为判据**——真正要看的是 SystemUI 侧。

---

## 6. 首次构建记录

| 构建 | commit | CI | 结果 | 原因 |
|---|---|---|---|---|
| forge-1 | `52b5475` | `39139933756` | 失败 | `build.gradle.kts` 注释吞掉闭合花括号 |
| forge-2 | `26fdb5a` | `39140352695` | 失败 | 缺 `src/main/AndroidManifest.xml` |
| forge-3 | `28261022` | `39140635062` | 失败 | `org.junit.Test` 无处解析（刻意不声明依赖） |
| forge-4 | `b85bfa95` | `39140940590` | **成功** | 用例改 `test` 前缀，由 instrumentation 反射发现 |

三次失败都在夹具自身的构建面，没有一次改动到模块主代码。

---

## 7. 结果

待实机执行。
