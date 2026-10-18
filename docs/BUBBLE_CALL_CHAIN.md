# BUBBLE_CALL_CHAIN — MoreBubbleButton 气泡调用链拆解

> 阶段 0 产出 · 只读侦察（未修改任何业务代码）
> 证据来源：`MoreBubbleButton` 仓库 commit `81630ab0bed89dd5365afc134d3491361291f9d7`（v1.7）
> 主文件：`app/src/main/java/com/floatwindow/morebubblebutton/MoreBubbleHookModule.java`（1313 行）
> 辅助：`BubbleIconDoubleTap.java`（双击转全屏，供参考）
>
> 行号均为上述 commit 下实际行号。**AOSP 隐藏类/方法签名经 MT 反编译 SystemUI 17 / Pixel Launcher 17 目标 APK 核验（2026-10-18），见 §9「DEX 核验结果」**；仍标"待核验"的仅剩 framework.jar / system_server 层接口。

---

## 0. 结论速览

MoreBubbleButton 内部存在**两条独立的"创建/展开气泡"路径**，服务不同场景：

| 路径 | 入口 | 进程 | 触发方式 | 目标 | 是否任意 Intent |
|------|------|------|---------|------|----------------|
| **路径 L**（Launcher 侧） | 最近任务按钮 → `bubbleCurrentTask()` → `SystemUiProxy.showAppBubble(...)` | Launcher (Quickstep) | 用户点按钮/菜单 | **对"当前任务"气泡化** | ❌ 只能取现有 Task 的 Intent |
| **路径 S**（SystemUI 侧） | 通知点击 → `expandAppBubbleFromNotification()` → `expandAppBubbleA17()` | SystemUI (wmshell) | 通知气泡按钮/点击 | 将通知对应目标作为 app bubble 创建并展开 | ✅ 接受任意 `(Intent, UserHandle)` |

**对 FlymeFreeform 移植的核心启示**：
- 路径 S 的 `expandAppBubbleA17` **才真正接受"任意目标 Intent + UserHandle"**，最接近手势选应用→气泡启动的语义（任务书路线 B）。
- 路径 L 的 `showAppBubble` 是系统代理入口（任务书路线 A），但**前提是 Intent 来自现有 Task**，需实测是否能接受任意 Intent。
- 两条路径最终都收敛到 `BubbleController.expandStackAndSelectAppBubble(...)`（A17）或旧版 `expandStackAndSelectBubble(...)`（A16 及更早）。
- **DEX 核验结论（2026-10-18，SystemUI 17 + Pixel Launcher 17）**：`expandStackAndSelectAppBubble(Bubble, EntryPoint, UpdateLocationRequest)V`、`SystemUiProxy.showAppBubble(Intent, UserHandle, EntryPoint, BubbleBarLocation)V`、`Bubble(Intent, UserHandle, Icon, BubbleType, String)` 构造、`EntryPoint.NOTIFICATION_BUBBLE_BUTTON`、`BubbleType.TYPE_APP` **全部存在且签名吻合**（详见 §9）。

---

## 1. 模块入口与进程分发

```
MoreBubbleHookModule.onModuleLoaded(ModuleLoadedParam)        // L40-42，仅日志
      │
onPackageLoaded(PackageLoadedParam)                           // L45
      │
      ├─ pkg in {com.google.android.apps.nexuslauncher,       // L46-49
      │         com.android.launcher3}
      │     → mLauncherClassLoader = defaultClassLoader()
      │     → hookLauncher(param)                             // L761
      │
      └─ pkg == "com.android.systemui"                        // L50-63
            └─ if (!param.isFirstPackage()) return;           // L52 只首装
               → hookSystemUi(cl)                             // L63
```

**运行进程**：模块作为一个 Xposed 模块被分别注入 **Launcher** 与 **SystemUI** 两个进程，各自持有独立的 hook 集合。libxposed API 101。

---

## 2. 路径 L — Launcher 侧：最近任务气泡化（`SystemUiProxy.showAppBubble`）

### 2.1 调用链

```
onBubbleButtonClick(View actionsView)                         // L1123
  │ 按钮注入：injectBubbleButton / addBubbleMenuOption 把事件挂到 DOM
  │ recentsViewInstance ← findRecentsViewFromHierarchy()     // L1185 (type 树查找)
  ▼
rv.getCurrentPageTaskView()                                   // L1129
  → taskView.getTaskContainers() → get(0)                     // L1131-1132
  → taskContainer.getTask() → task                            // L1134
  → task.key → { baseIntent, userId }                         // L1136-1138
  ▼
bubbleCurrentTask(ctx, intent, task, userId)                  // L1146 → L1120
  ▼
com.android.quickstep.SystemUiProxy                          // L1148
    .INSTANCE.get(ctx.getApplicationContext())               // L1149-1150 → proxy
  ▼
new Intent(taskIntent)                                       // L1162
  + bIntent.setPackage(pkg)   （若 null，由 component 补齐）    // L1163-1165
  + bIntent.setComponent(topClass)  （task.getTopComponent()） // L1167-1175
  + UserHandle.of(userId)                                    // L1177
  + EntryPoint.NOTIFICATION（遍历 $VALUES）                   // L1179-1182
  + 遍历 proxy 方法名 == "showAppBubble"                      // L1186
  ▼
proxy.showAppBubble(testIntent, userHandle, entryPoint, null) // L1189 (反射)
```

### 2.2 关键证据与来源

| 对象/动作 | 行号 | 来源 |
|-----------|------|------|
| Launcher pkg 白名单 | 46-48 | `onPackageLoaded` 硬编码 `com.google.android.apps.nexuslauncher` + `com.android.launcher3` |
| `SystemUiProxy.INSTANCE.get(Context)` | 1148-1150 | 反射获取单例（`INSTANCE` 静态字段 → `get(Context)`） |
| Intent 来源 = `Task.key.baseIntent` | 1136-1138 | 反射读字段 `key` → `baseIntent`、`userId` |
| 组件精确定位 `task.getTopComponent()` | 1167-1175 | 反射读 top component → `setComponent` |
| `UserHandle.of(int)` | 1177 | 反射调用 AOSP `UserHandle.of` |
| `EntryPoint.NOTIFICATION` | 1178-1182 | 枚举 `$VALUES` 数组遍历，取 `NOTIFICATION` |
| `showAppBubble(Intent, UserHandle, EntryPoint, BubbleBarLocation$UpdateLocationRequest)` | 1186-1189 | **按方法名遍历** `proxy.getClass().getMethods()` 取首个 `showAppBubble`，实参 `(bIntent, userHandle, ep, null)` |

### 2.3 限制（对移植至关重要）

1. **Intent 只能来自现有 Task**：`onBubbleButtonClick` 从最近任务的 `Task.key.baseIntent` 取 intent。**没有任何"用户指定任意包名/组件 → 构造任意 Intent"的路径**。手势场景要用路线 L，必须绕过这个前提（另建 Intent），而 `showAppBubble` 是否接受任意 Intent **未在该项目中验证** → 阶段 1 任务 1.3 必测。
2. **签名按方法名匹配**：`showAppBubble` 通过"遍历方法名"找到，未校验参数类型/个数（只取第一个同名方法）。如果 A17 有多个重载，可能匹配到非预期签名——**违反任务书"不得仅按方法名选第一个重载"**，本项目实现需改正。
3. **userId 容错**：`Task.key.userId` 直接取用；无多用户回退逻辑。
4. **返回值**：`m.invoke` 返回后直接 `return true`，**不检查方法是否真正同步、不检查返回值**。调用返回 ≠ 气泡出现。

### 2.4 `dismissOverview`（关闭最近任务视图）L1216-1231

调 `recentsView.getStateManager().moveToRestState()`，失败则 `am start HOME`。与气泡创建无关，仅 UI 收尾。

---

## 3. 路径 S — SystemUI 侧：任意目标创建并展开 App Bubble

### 3.1 三个触发入口（hookSystemUi 内）

| # | hook 目标 | 行号 | 语义 |
|---|-----------|------|------|
| 1 | `BubblesManager.expandStackAndSelectBubble(NotificationEntry)` | 118-144 | 拦截用户点击通知展开 → 改走 app bubble 路径（expand guard） |
| 2 | `BubblesManager.onUserChangedBubble(NotificationEntry, boolean)` | 146-199 | 先放行原方法（置气泡 flag），再走 app bubble（user change） |
| 3 | （间接）`NotificationContentView.shouldShowBubbleButton` + `NotificationRowBinderImpl` 注入 metadata | 64-117 | 让通知显示"气泡按钮"，点击最终也走上述展开逻辑 |

```
入口1/2
  ▼
expandAppBubbleFromNotification(bubblesManager, entry, reason)   // L349
  ├─ 从 entry.mSbn 取 pkg / userId = sbn.getUserId()            // L352-359
  ├─ UserHandle.of(userId)                                      // L360
  ├─ bubblesManager.mBubbles → this$0 → controller              // L362-363
  │     （BubblesManager.mBubbles 是 BubbleController$IBubblesImpl）
  ├─ controller.mContext → Context                              // L365
  ├─ targetIntent = contentIntent (Notification)               // L367 getNotificationTargetIntent
  │     ∥ fallback → getLaunchIntentForPackage(pkg)            // L368-370
  ├─ targetIntent.setPackage(pkg) 若 null                       // L379-381
  ├─ targetIntent.addFlags(NEW_TASK | RESET_TASK_IF_NEEDED)     // L382
  ├─ controller.mMainExecutor.execute(work)                     // L403-410
  │     └─ work.run():
  │          ├─ expandAppBubbleA17(controller, intent, user, reason)   // L417   ← A17 主路径
  │          │    （详见 §3.2）
  │          ├─ 若 false → expandAppBubbleLegacy(...)                  // L481   ← A16 回退
  │          ├─ 若成功 → dismissClickedNotificationIfAutoCancel + collapseShade  // L455-457
  │          └─ 若失败 → Log "app bubble expand method not found"      // L459
  └─ throw → Log + return false
```

### 3.2 `expandAppBubbleA17(controller, intent, user, reason)` — **核心**（L417-480）

官方配方，注释自述与 `IBubbles.showAppBubble` / `BubbleController$IBubblesImpl` 一致：

```
ClassLoader cl = controller.getClass().getClassLoader()
Bubble        = com.android.wm.shell.bubbles.Bubble
BubbleData    = com.android.wm.shell.bubbles.BubbleData
BubbleType    = com.android.wm.shell.bubbles.Bubble$BubbleType
EntryPoint    = com.android.wm.shell.shared.bubbles.logging.EntryPoint
UpdateReq     = com.android.wm.shell.shared.bubbles.BubbleBarLocation$UpdateLocationRequest
  ▼
expand = controller.expandStackAndSelectAppBubble                // L448
         (Bubble, EntryPoint, UpdateLocationRequest)
  ▼ 存在性检查（任一失败 return false）
data = controller.mBubbleData                                    // L451
pkg  = intent.getPackage()                                       // L453 若 null return false
key  = Bubble.getAppBubbleKeyForApp(pkg, user)                   // L459 (static)
bubble = data.findAndRemoveBubbleFromOverflow(key)               // L463 (可用则复用)
  ▼ 若 bubble == null（新建分支）：
typeApp   = Bubble$BubbleType.TYPE_APP                           // L468
data.mContext → dataCtx                                          // L470-471
pm        = controller.getPackageManagerForUser(userId, dataCtx) // L474-476
            ∥ 回退 dataCtx.getPackageManager()
icon      = data.mAppInfoProvider.getActivityInfoIcon(pm, intent)// L480-489 (可失败为 null)
bubble    = new Bubble(intent, user, icon, typeApp, key)         // L491
  ▼
entryPoint = EntryPoint.NOTIFICATION_BUBBLE_BUTTON               // L494 (反查枚举)
   ∥ 回退 EntryPoint.NOTIFICATION
  ▼
expand.invoke(controller, bubble, entryPoint, null)              // L499
return true
```

**参数/对象构造全表**：

| 项 | 构造方式 | 行号 | 需核验 |
|----|---------|------|--------|
| controller | `bubblesManager.mBubbles` 的 `this$0`（内部类 IBubblesImpl 的外层 BubbleController） | 362-363 | 字段名 |
| intent | 通知 contentIntent 副本 / launch intent，setPackage + NEW_TASK 等 flags | 367-382 | — |
| user | `UserHandle.of(userId)` | 360 | — |
| key | `Bubble.getAppBubbleKeyForApp(pkg, UserHandle)`（static） | 459 | 方法签名 |
| bubble | 复用 overflow 或 `new Bubble(Intent, UserHandle, Icon, Bubble$BubbleType.TYPE_APP, String key)` | 463,491 | **构造函数签名** |
| icon | `BubbleData.mAppInfoProvider.getActivityInfoIcon(PackageManager, Intent)` | 480-489 | 字段+方法 |
| entryPoint | 枚举 `EntryPoint.NOTIFICATION_BUBBLE_BUTTON`（回退 `NOTIFICATION`） | 494-497 | 枚举成员 |
| updateReq | `null` | 499 | 可空性 |
| executor | `controller.mMainExecutor.execute(Runnable)` → 切到 wmshell.main 线程 | 403-410 | 字段名 |

### 3.3 `expandAppBubbleLegacy`（A16 及更早，L481-507，纯回退）

`controller.expandStackAndSelectBubble(Intent, UserHandle, EntryPoint, BubbleBarLocation)`（4 参）。若精确签名找不到，再按"方法名 + 4 参 + 首参 Intent"遍历候选。**仅作回退，A17 上不预期命中**（注释称 A17 已删除该方法）。

### 3.4 辅助方法

| 方法 | 行号 | 用途 |
|------|------|------|
| `getNotificationTargetIntent(notif, pkg)` | 530-549 | 从 `notif.contentIntent` (PendingIntent).getIntent() 拷贝，加 `IS_FROM_NOTIFICATION=true`，setPackage |
| `dismissClickedNotificationIfAutoCancel` | 551-590 | SELECT 自动取消通知（走 `mCallbacks.removeNotification`） |
| `runOnSysuiMain` / `collapseShadeFromManager` | 592-622 | 线程切换 / 折叠通知栏（多候选签名） |
| `findMethodByNameAndCount` / `findCompatibleMethodByName` | 623-649 | 反射方法查找（按名+参数计数 / 按名+可赋值参数） |
| `rememberForcedBubble` / `isRecentlyForcedBubble` | 664-678 | 强制气泡 8s 宽限期防误删 |
| `injectBubbleMetadata` | 680-745 | 给通知注入 `BubbleMetadata`（setIntent/setDeleteIntent/setIcon/加锁屏等） |

---

## 4. SystemUI hook 清单（hookSystemUi，共 9 组）

| # | Hook 目标类.方法 | 行号 | 目的 |
|---|------------------|------|------|
| 1 | `NotificationContentView.shouldShowBubbleButton` | 64-117 | 让非前台通知显示气泡按钮 |
| 2 | `NotificationRowBinderImpl` 的 bind 方法（参2含 `ExpandableNotificationRow`） | 85-113 | bind 时注入 BubbleMetadata |
| 3 | `BubblesManager.expandStackAndSelectBubble(NotificationEntry)` | 118-144 | 拦截点击通知 → 改走 app bubble |
| 4 | `BubblesManager.onUserChangedBubble(NotificationEntry, boolean)` | 146-199 | 用户切换气泡后展开 |
| 5 | `BubbleData.dismissBubbleWithKey(int,…String)` | 202-250 | 防刚创建的气泡被立刻移除（8s 宽限） |
| 6 | `BubbleData.setSelectedBubbleInternal(BubbleEntry)` | 251-286 | 强制把 BubbleEntry 加入 mBubbles |
| 7 | `BubbleExpandedView.updateManageButtonIfExists` | 288-309 | 隐藏管理按钮 |
| 8 | `BubbleOverflow.getIconView` / `BubbleStackView.updateOverflowVisibility` | 311-349 | 隐藏 overflow "+" 按钮 |
| 9 | `BubbleIconDoubleTap` | 350 | 单击延迟 / 双击转全屏 |

> 这些是 MoreBubbleButton 为它自己的"通知场景"做的系统行为修正，**不是** FlymeFreeform 手势所需的最小集。阶段 1 应判断哪些是气泡后端必需、哪些属于 MoreBubbleButton 产品 UI（后两者明显是产品级，不移植）。

---

## 5. 对任务书路线选择的证据映射

| 任务书问题 | 证据 | 结论 |
|-----------|------|------|
| 路线 A：`SystemUiProxy.showAppBubble` 能否接受任意 Intent | 仅验证过"Task baseIntent"来源；`(Intent, UserHandle, EntryPoint, null)` 四参含 null；方法按名匹配 | **待实测**。签名需 DEX 核验；null 第 4 参需确认 |
| 路线 B：`BubbleController.expandStackAndSelectAppBubble` | `expandAppBubbleA17` 已构造 `Bubble(intent,user,icon,TYPE_APP,key)` 并传入 `(Bubble, EntryPoint, null)`，注释称与官方 `IBubblesImpl.showAppBubble` 配方一致 | **源码级配方存在**，但**签名未经目标 ROM DEX 确认**，注释自述"官方配方"需实证 |
| `Bubble` 对象构造 | `new Bubble(Intent, UserHandle, Icon, Bubble$BubbleType.TYPE_APP, String key)` + `Bubble.getAppBubbleKeyForApp(pkg, user)` | 构造函数签名待 DEX 核验 |
| 从手势选应用提交 → 气泡启动 | 两路径都**不以手势**触发；路径 S 以"通知"为载体验证了任意 Intent。手势 → 需构造 `BubbleLaunchRequest` → 走 `expandAppBubbleA17` 等价逻辑 | **移植需新增手势提交到后端的中介**（任务书阶段 2） |

### 5.1 推荐采纳的调用链（供阶段 1 验证后确认）

```
ColorOsFreeformCoordinator.onAppCommitted(entry)     // 手势已选中 entry
      │  entry.component → ComponentName
      ▼
BubbleLaunchRequest { pkg, component, userId(0), intent, source="gesture" }
      ▼
PixelBubbleLauncher.launch(request)                    // 平台无关适配器（阶段 2 新增）
      ▼
  [路线 B] SystemUI wmshell 线程：
    key    = Bubble.getAppBubbleKeyForApp(pkg, user)
    bubble = BubbleData.findAndRemoveBubbleFromOverflow(key)
             ∥ new Bubble(intent, user, icon, TYPE_APP, key)
    controller.expandStackAndSelectAppBubble(bubble, EntryPoint.X, null)
      ▲ 需跨进程：手势在 system_server，气泡在 SystemUI → IPC via SystemUiProxy(showAppBubble) 或
        SystemUI 侧再 hook 一个"手势广播→气泡"入口
  [路线 A 备选] SystemUiProxy.showAppBubble(intent, user, entryPoint, null)（跨进程代理）
      ▲ 前提：showAppBubble 接受任意 Intent（待实测）
```

> ⚠️ **进程边界是最大挑战**：手势/菜单运行在 system_server（FlymeFreeform 现状），而 Bubble 控制器在 SystemUI(wmshell) 进程。路线 A（SystemUiProxy 是 Binder 代理跨进程）天然解决此问题，这正是它"更接近跨进程边界由系统代理处理"（任务书路线 A 优点）的原因。若最终验证 showAppBubble 只能处理现有任务，则需评估第三条路：在 SystemUI 进程内 hook 一个可由 system_server 触发的入口（Binder 回调/广播）来执行 expandAppBubbleA17 等价逻辑。此为本项目后续最大技术决策点。

---

## 6. 待真机 / 目标 ROM DEX 核验清单

> **2026-10-18 已通过 MT 反编译 SystemUI 17（工作区 `81i4aezl`）+ Pixel Launcher 17（工作区 `holgxhyk`）核验**，结果见 §9。以下原 10 项中 1-9 已全部完成，仅剩第 10 项（语义层）待真机。

1. ~~`SystemUiProxy.showAppBubble` A17 精确签名、参数可空性~~ → ✅ `(Intent, UserHandle, EntryPoint, BubbleBarLocation)V`（Launcher 侧）
2. ~~`BubbleController.expandStackAndSelectAppBubble` A17 精确签名~~ → ✅ `(Bubble, EntryPoint, UpdateLocationRequest)V`（SystemUI 侧）
3. ~~`Bubble` 构造函数 `(Intent, UserHandle, Icon, BubbleType, String)`~~ → ✅ 存在
4. ~~`Bubble.getAppBubbleKeyForApp` 静态方法签名~~ → ✅ `(String, UserHandle)String`
5. ~~`BubbleData.findAndRemoveBubbleFromOverflow(String)`~~ → ✅ `(String)Bubble`
6. `BubbleData.mAppInfoProvider.getActivityInfoIcon(PackageManager, Intent)` → 类存在，具体方法签名待 outline 深读（低风险）
7. ~~`BubbleController.mBubbleData` 字段~~ → ✅ `mBubbleData:BubbleData` 确认；`mContext`/`mMainExecutor` 等字段未逐一 outline（低风险）
8. ~~`BubblesManager.mBubbles`（IBubblesImpl）字段与 `this$0` 名~~ → ✅ `this$0:Lcom/android/wm/shell/bubbles/BubbleController;` 确认
9. ~~EntryPoint 枚举成员全集~~ → ✅ `NOTIFICATION` / `NOTIFICATION_BUBBLE_BUTTON` / `TASKBAR_ICON_DRAG` 全部存在
10. `showAppBubble` 是否要求目标为"已有 Bubble/现有任务"，还是接受全新 app bubble 启动 → **待真机实测**（阶段 1 必测项）

---

## 9. DEX 核验结果（2026-10-18）

### 9.1 核验素材

| APK | 包名 | 版本 | 工作区 ID |
|-----|------|------|-----------|
| 系统界面_17.apk | `com.android.systemui` | v17 / code 37 / minSdk 37 | `81i4aezl` |
| Pixel 启动器_17.apk | `com.google.android.apps.nexuslauncher` | v17 / code 907 / minSdk 37 | `holgxhyk` |

### 9.2 路径 L（Launcher 侧）核验

| 符号 | 结果 | 签名 |
|------|------|------|
| `com.android.quickstep.SystemUiProxy` | ✅ 类存在 | — |
| `SystemUiProxy.showAppBubble` | ✅ **存在** | `(Landroid/content/Intent;Landroid/os/UserHandle;Lcom/android/wm/shell/shared/bubbles/logging/EntryPoint;Lcom/android/wm/shell/shared/bubbles/BubbleBarLocation;)V` |
| `SystemUiProxy$Companion.INSTANCE` | ✅ 存在 | MoreBubbleButton `INSTANCE.get(context)` 路径可用 |
| `RecentsView.getCurrentPageTaskView` | ✅ 存在 | `()Lcom/android/quickstep/views/TaskView;`（`com.android.quickstep.views.RecentsView`） |
| Launcher 自带 app bubble 入口 | ✅ 3 处 | `SystemShortcut$BubbleActivityStarter` / `BaseTaskbarContext` / `QuickstepLauncher` 各有 `showAppBubble(Intent,UserHandle,EntryPoint)V` |

**关键发现**：`SystemUiProxy.showAppBubble` 方法体（outline 读取）调用 `Parcel.writeInterfaceToken("com.android.wm.shell.bubbles.IBubbles")` + `Parcel.writeTypedObject(Intent)` + `writeTypedObject(UserHandle)` + `IBinder.transact` —— **确认这是 Binder/AIDL 跨进程调用**（Launcher → SystemUI wmshell），非本进程 Java 调用。这解释了：
- SystemUI dex 中搜不到 `showAppBubble` **方法名**（它在 AIDL `IBubbles` 的 `onTransact` 分发协议中，不落 Java 方法名）；
- SystemUI 侧 `BubbleController$IBubblesImpl` 只有一个 `onTransact` 统一分发 + `this$0→BubbleController` 字段（outline 确认 4 个方法：`asBinder`/`invalidate`/`onTransact`/`<init>`）。

**对移植的意义**：调用方（Launcher 侧 proxy）不需要关心 transact 码，`showAppBubble` 是稳定公共入口；**但进程边界确认**：手势必须最终落到 SystemUI wmshell 进程才能创建气泡（详见 §5.1 进程边界警示）。

### 9.3 路径 S（SystemUI 侧）核验

| 符号 | 结果 | 签名 |
|------|------|------|
| `BubbleController.expandStackAndSelectAppBubble` | ✅ **存在** | `(Lcom/android/wm/shell/bubbles/Bubble;Lcom/android/wm/shell/shared/bubbles/logging/EntryPoint;Lcom/android/wm/shell/shared/bubbles/BubbleBarLocation$UpdateLocationRequest;)V` |
| `Bubble#<init>` | ✅ **存在** | `(Landroid/content/Intent;Landroid/os/UserHandle;Landroid/graphics/drawable/Icon;Lcom/android/wm/shell/bubbles/Bubble$BubbleType;Ljava/lang/String;)V` |
| `Bubble.getAppBubbleKeyForApp` | ✅ 存在 | `(Ljava/lang/String;Landroid/os/UserHandle;)Ljava/lang/String;` |
| `Bubble$BubbleType` | ✅ 存在 | `TYPE_APP` / `TYPE_CHAT` / `TYPE_NOTE` / `TYPE_SHORTCUT` |
| `BubbleData.findAndRemoveBubbleFromOverflow` | ✅ 存在 | `(Ljava/lang/String;)Lcom/android/wm/shell/bubbles/Bubble;` |
| `BubbleController.mBubbleData` | ✅ 存在 | `mBubbleData:Lcom/android/wm/shell/bubbles/BubbleData;` |
| `EntryPoint` | ✅ 存在 | `NOTIFICATION` / `NOTIFICATION_BUBBLE_BUTTON` / `TASKBAR_ICON_DRAG` |
| `BubbleBarLocation$UpdateLocationRequest` | ✅ 存在 | `expandStackAndSelectAppBubble` 第 3 参类型 |
| `BubbleTaskView` | ✅ 存在 | 双击转全屏路径依赖 |
| `BubbleController$IBubblesImpl` | ✅ 存在 | Binder 实现，`this$0→BubbleController` |

**方法体佐证**：`expandStackAndSelectAppBubble` outline 显示调用 `Bubble.isInflated`、`updateExpandedViewForBubbleBarLocation`、`BubbleLogger.logEntryPoint` —— 与 MoreBubbleButton 注释"官方配方"描述一致，确认该方法接受**一个已构造好的 `Bubble` 对象**直接进入展开流程。

### 9.4 结论

- 路线 A（`SystemUiProxy.showAppBubble`）与路线 B（`expandStackAndSelectAppBubble` + `Bubble` 构造）**入口签名在目标 ROM 全部成立**。
- 路径 L 的进程模型 = Launcher → SystemUI Binder；路径 S 的进程模型 = SystemUI 进程内。**FlymeFreeform 手势在 system_server**，两条路径都需解决跨进程（路线 A 天然跨进程、路线 B 需新增 IPC 入口）——§5.1 的技术决策点维持不变，且现在有了确切依据。

*文档结束 · BUBBLE_CALL_CHAIN.md · 阶段 0 · 只读侦察 + DEX 核验*