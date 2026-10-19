package io.github.mangi.flymefreeform.hook

import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.mangi.flymefreeform.config.SharedStateProtocol
import io.github.mangi.flymefreeform.config.ToolCatalogCodec
import io.github.mangi.flymefreeform.platform.coloros.ColorOsAllAppsContent
import io.github.mangi.flymefreeform.platform.coloros.ColorOsAllAppsEndpoint
import io.github.mangi.flymefreeform.platform.coloros.ColorOsAlphabetGrouper
import io.github.mangi.flymefreeform.platform.coloros.ColorOsSidebarTarget
import io.github.mangi.flymefreeform.platform.coloros.ColorOsToolCatalog

/** 只注入侧边栏 UI 进程；服务方法照常执行一次，应用/工具的启动仍交给原厂路由。 */
internal class SidebarHookInstaller(private val module: XposedModule, private val configuration: ProcessConfiguration) {
    private var endpoint: ColorOsAllAppsEndpoint? = null

    fun install(loader: ClassLoader) {
        val handles = mutableListOf<XposedInterface.HookHandle>()
        try {
            val serviceClass = loader.loadClass(ColorOsSidebarTarget.SERVICE_CLASS)
            require(Service::class.java.isAssignableFrom(serviceClass))
            val bind = serviceClass.getDeclaredMethod("onBind", Intent::class.java)
            val unbind = serviceClass.getDeclaredMethod("onUnbind", Intent::class.java)
            val destroy = serviceClass.getDeclaredMethod("onDestroy")
            require(bind.returnType == IBinder::class.java)
            val allClass = loader.loadClass(ColorOsAllAppsContent.ALL_CLASS)
            val adapter = allClass.getDeclaredMethod("getAllAppAdapter")
            require(adapter.returnType.name == ColorOsAllAppsContent.ADAPTER_CLASS)
            val mainClass = loader.loadClass(ColorOsAllAppsContent.MAIN_CLASS)
            val changeState = mainClass.getDeclaredMethod("changeState", loader.loadClass("${ColorOsAllAppsContent.MAIN_CLASS}\$State"))

            handles += module.hook(bind).setId("flymefreeform.allapps.bind")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    val original = chain.proceed()
                    val service = chain.thisObject as? Service
                    val intent = chain.getArg(0) as? Intent
                    if (service != null && intent?.action == ColorOsSidebarTarget.BIND_ACTION) {
                        if (endpoint == null && ColorOsSidebarTarget.supportedUid(service) == Process.myUid()) {
                            endpoint = ColorOsAllAppsEndpoint(service, loader, configuration, ::log)
                            // @author bomo 进程首次绑定补刷一次浮标窗口参数（开关见 installNativeBarSuppression）。
                            runCatching { findPanelMainView(loader)?.let { refreshNativeBarWindow(it) } }
                        }
                        endpoint?.takeIf { it.service === service }?.binder ?: original
                    } else original
                }
            handles += module.hook(unbind).setId("flymefreeform.allapps.unbind")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    val original = chain.proceed()
                    val intent = chain.getArg(0) as? Intent
                    if (intent?.action == ColorOsSidebarTarget.BIND_ACTION) endpoint?.onUnbound(intent.identifier)
                    original
                }
            handles += module.hook(destroy).setId("flymefreeform.allapps.destroy")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    if (endpoint?.service === chain.thisObject) {
                        val previous = endpoint
                        endpoint = null
                        previous?.dispose()
                    }
                    chain.proceed()
                }
            handles += module.hook(adapter).setId("flymefreeform.allapps.adapter")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    val original = chain.proceed()
                    endpoint?.onAdapterCreated(chain.thisObject, original)
                    original
                }
            handles += module.hook(changeState).setId("flymefreeform.allapps.native_state")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    val original = chain.proceed()
                    endpoint?.onNativeSidebarState((chain.getArg(0) as? Enum<*>)?.name)
                    original
                }
            installNativeBarSuppression(loader, handles)
            log(Log.INFO, "ALL_APPS_BRIDGE_INSTALLED", null)
            installAlphabetGrouping(loader, handles)
            installDragDiagnostics(handles)
            // @author bomo 0.2.18 取证：划边缘的震动究竟来自模块扇形还是系统侧边栏，需要调用栈定案。
            DiagnosticTracer.installHapticTrace(module, ::log)
            // 工具目录与面板会话无关：进程就绪即发布一次，设置界面的「添加应用」立刻能列出工具。
            publishToolCatalog(loader)
        } catch (exception: ReflectiveOperationException) {
            handles.forEach { it.unhook() }
            log(Log.WARN, "ALL_APPS_SIGNATURE_UNAVAILABLE", exception)
        } catch (exception: RuntimeException) {
            handles.forEach { it.unhook() }
            log(Log.WARN, "ALL_APPS_HOOK_INSTALL_FAILED", exception)
        } catch (error: LinkageError) {
            handles.forEach { it.unhook() }
            log(Log.WARN, "ALL_APPS_LINKAGE_FAILED", error)
        }
    }

    /**
     * @author bomo 「全部」面板字母分组（Flyme 风格 A-Z 分排）的两个钩子。
     *
     * 1. `SearchHelper.buildAllList` after-proceed：仅当 thisObject 是模块自建面板注册的实例时，
     *    把它持有的四个数据列表原地改写为字母分组结构（工具区不动）。原厂面板不注册，不受影响。
     * 2. `AllAppRecyclerAdapter.onBindViewHolder` after-proceed：原厂 `LabelViewHolder` 不绑定数据
     *    （标题布局文案是 XML 写死的），这里按标记写字母文本、并还原同回收池的原厂标题文案。
     *
     * 方法解析按「名字 + 参数个数」扫描而不按参数类型名：17.9.8 的 androidx 内部类名被混淆
     * （如 ViewHolder → d0），写死类型名会在未来版本解析失败。任何一步失败只打诊断码，
     * 不影响已装好的面板桥接 hook。
     */
    private fun installAlphabetGrouping(loader: ClassLoader, handles: MutableList<XposedInterface.HookHandle>) {
        try {
            val grouper = ColorOsAlphabetGrouper(loader, ::log)
            val searchClass = loader.loadClass(ColorOsAllAppsContent.SEARCH_CLASS)
            val buildAllList = searchClass.declaredMethods.first {
                it.name == "buildAllList" && it.parameterCount == 1
            }
            val adapterClass = loader.loadClass(ColorOsAllAppsContent.ADAPTER_CLASS)
            val onBind = adapterClass.declaredMethods.first {
                it.name == "onBindViewHolder" && it.parameterCount == 2 &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType
            }
            val getMData = adapterClass.getMethod("getMData")
            handles += module.hook(buildAllList).setId("flymefreeform.allapps.group")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    val original = chain.proceed()
                    val helper = chain.thisObject
                    if (helper != null && ColorOsAlphabetGrouper.isRegistered(helper)) {
                        runCatching { grouper.regroupAll(helper) }
                            .onFailure { log(Log.WARN, "ALPHABET_GROUP_FAILED", it) }
                    }
                    original
                }
            handles += module.hook(onBind).setId("flymefreeform.allapps.header_bind")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    val original = chain.proceed()
                    runCatching {
                        val adapter = chain.thisObject ?: return@runCatching
                        val data = getMData.invoke(adapter) as? List<*> ?: return@runCatching
                        val holder = chain.getArg(0) ?: return@runCatching
                        val position = chain.getArg(1) as? Int ?: return@runCatching
                        grouper.bindHeader(data, holder, position)
                    }.onFailure { log(Log.WARN, "ALPHABET_HEADER_BIND_FAILED", it) }
                    original
                }
            log(Log.INFO, "ALPHABET_GROUP_HOOK_INSTALLED", null)
        } catch (exception: Exception) {
            log(Log.WARN, "ALPHABET_GROUP_HOOK_UNAVAILABLE", exception)
        } catch (error: LinkageError) {
            log(Log.WARN, "ALPHABET_GROUP_HOOK_UNAVAILABLE", error)
        }
    }

    /**
     * 枚举侧边栏工具并广播给模块 App 落盘（Hook 进程的远端配置只读，App 才能写）。
     *
     * 时机：本 Hook 安装在 `onPackageReady` 阶段，此时 `ActivityThread.currentApplication()`
     * 可能尚未就绪（实测该情况下会静默拿不到上下文），因此按秒重试若干次；
     * 仍失败则留诊断码，面板打开时 [ColorOsAllAppsEndpoint] 还会再发一次。
     */
    private fun publishToolCatalog(loader: ClassLoader, attempt: Int = 0) {
        try {
            val context = currentApplication()
            if (context == null) {
                if (attempt < CONTEXT_RETRY_LIMIT) {
                    Handler(Looper.getMainLooper()).postDelayed(
                        { publishToolCatalog(loader, attempt + 1) },
                        CONTEXT_RETRY_INTERVAL_MS,
                    )
                } else {
                    log(Log.WARN, "TOOL_CATALOG_CONTEXT_UNAVAILABLE", null)
                }
                return
            }
            val records = ColorOsToolCatalog(context, loader, ::log).build()
            if (records.isEmpty()) {
                log(Log.WARN, "TOOL_CATALOG_EMPTY", null)
                return
            }
            context.sendBroadcast(
                Intent(SharedStateProtocol.ACTION)
                    .setPackage(SharedStateProtocol.MODULE_PACKAGE)
                    // App 装完处于 stopped 状态时普通广播不会唤醒它，必须显式包含。
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    .putExtra(SharedStateProtocol.EXTRA_KIND, SharedStateProtocol.KIND_TOOL_CATALOG)
                    .putExtra(SharedStateProtocol.EXTRA_CATALOG, ToolCatalogCodec.encode(records)),
            )
            log(Log.INFO, "TOOL_CATALOG_PUBLISHED count=${records.size}", null)
        } catch (exception: Exception) {
            log(Log.WARN, "TOOL_CATALOG_PUBLISH_FAILED", exception)
        }
    }

    /**
     * @author bomo 隐藏系统侧边栏浮标（0.2.16 重做，0.2.15 的 GONE 方案实测失败）。
     *
     * **旧做法为什么不行**：对浮标 `setVisibility(GONE)` 是打地鼠 —— 日志铁证
     * `NATIVE_BAR_HIDDEN class=PanelMainView` 打了 7 次，说明每次 `changeState` 后我都藏一次，
     * 而原厂在自己的收起/展开动画里又置回 VISIBLE，于是表现为「划出 → 立刻被藏 → 2 秒后浮标又出现」
     * 的循环。
     *
     * **新做法：改窗口参数，不和原厂动画赛跑。**
     * 拦截 `WindowManagerImpl.addView/updateViewLayout`，只对 `PanelMainView` 的窗口动手：
     * - `FLAG_NOT_TOUCHABLE` —— 窗口不接收任何触摸，边缘手势直接穿透到下层应用，
     *   系统侧边栏**根本不会被触发**（不是"展开了再藏"，是从源头没有输入）；
     * - `alpha = 0f` —— 不可见。原厂内部怎么 setVisibility 都无所谓，窗口整体是透明的。
     *
     * 值写在窗口参数上，原厂后续任何一次 `updateViewLayout` 都会被这里重新改写，无需抢时序。
     *
     * **绝不动状态机**：状态仍是 `FLOAT_BAR_SHOWING`（模块自己的环境闸门依赖它，见
     * `ColorOsAllAppsContent.sidebarHidden`），被去掉的只是"看得见"和"摸得到"这两件事。
     */
    private fun installNativeBarSuppression(loader: ClassLoader, handles: MutableList<XposedInterface.HookHandle>) {
        val wmImpl = runCatching { Class.forName("android.view.WindowManagerImpl") }.getOrNull()
        val addView = wmImpl?.let {
            runCatching { it.getDeclaredMethod("addView", View::class.java, ViewGroup.LayoutParams::class.java) }.getOrNull()
        }
        val update = wmImpl?.let {
            runCatching { it.getDeclaredMethod("updateViewLayout", View::class.java, ViewGroup.LayoutParams::class.java) }.getOrNull()
        }
        if (addView == null || update == null) {
            log(Log.WARN, "NATIVE_BAR_HOOK_UNAVAILABLE", null)
            return
        }
        handles += module.hook(addView).setId("flymefreeform.allapps.bar_add")
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain -> interceptBarWindow(chain) }
        handles += module.hook(update).setId("flymefreeform.allapps.bar_update")
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain -> interceptBarWindow(chain) }
        // 浮标窗口通常早于本 hook 安装就已经 addView 过，补刷一次才能立刻生效。
        runCatching { findPanelMainView(loader)?.let { refreshNativeBarWindow(it) } }
    }

    /** 改写浮标窗口参数；[view] 为 `PanelMainView` 时生效，其余窗口原样放行。 */
    private fun interceptBarWindow(chain: XposedInterface.Chain): Any? {
        runCatching {
            val view = chain.getArg(0) as? View
            val params = chain.getArg(1) as? WindowManager.LayoutParams
            if (view != null && params != null) {
                inventoryWindow(view, params)
                if (view.javaClass.name == ColorOsAllAppsContent.MAIN_CLASS) applyBarParams(view, params)
            }
        }
        return chain.proceed()
    }

    /**
     * @author bomo 0.2.18 取证：把本进程 addView 过的窗口逐一打点（只在新类名首次出现时打一次）。
     *
     * **为什么需要它**：我只改写了 `PanelMainView` 的窗口参数。如果系统侧边栏另有一个
     * 专门接收边缘滑动的手势窗口（这类"透明条"通常不是浮标本身），那么划边缘时
     * 系统侧边栏仍会被唤醒 —— 表现就是"看不见浮标，但震动还在"。清单能证伪或坐实这一点。
     */
    private val seenWindowClasses = HashSet<String>()

    private fun inventoryWindow(view: View, params: WindowManager.LayoutParams) {
        if (!DiagnosticTracer.traceEnabled(view.context)) return
        val name = view.javaClass.name
        if (!seenWindowClasses.add(name)) return
        log(
            Log.INFO,
            "WINDOW_ADD cls=$name type=${params.type} flags=0x${params.flags.toString(16)} " +
                "w=${params.width}h=${params.height} gravity=${params.gravity}",
            null,
        )
    }

    /**
     * @author bomo 「最近文件」拖拽诊断：打印原厂实际发出的 ClipData（flags / MIME / Uri）。
     *
     * 现象：独立窗口里从最近文件拖图到 QQ 发不出去，原厂面板可以。
     * 原厂自己的日志走 `DebugLog.d("DragAndDropUtil", ...)`，release 包大概率不输出 → 只能自己埋。
     * 判据：`flags` 是否含 `DRAG_FLAG_GLOBAL`（跨应用必需）、MIME 是否为图片、Uri 是否非空且可授权。
     */
    private fun installDragDiagnostics(handles: MutableList<XposedInterface.HookHandle>) {
        val start = runCatching {
            View::class.java.getDeclaredMethod(
                "startDragAndDrop",
                ClipData::class.java,
                View.DragShadowBuilder::class.java,
                Any::class.java,
                Int::class.javaPrimitiveType,
            )
        }.getOrNull()
        if (start == null) {
            log(Log.WARN, "DRAG_DIAG_UNAVAILABLE", null)
            return
        }
        handles += module.hook(start).setId("flymefreeform.drag.diag")
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                val clip = chain.getArg(0) as? ClipData
                val raw = chain.getArg(3) as? Int ?: -1
                val view = chain.thisObject as? View
                // @author bomo 0.2.18：0.2.17 装机实测日志是 `raw=771 flags=771`（补丁压根没生效），
                // 纯读代码推不出原因 → 把 Settings 的原始读值一起打出来定案。同时去掉缓存：
                // 拖拽是低频操作，不值得为省一次 Settings 查询而承担"读到陈旧值"的代价。
                val cfg = readDragFlagSetting(view?.context)
                val patch = cfg == 1
                // 只在开着修复时改写 flags；关掉即完全还原原厂行为（对照用）。
                val flags = if (patch) patchDragFlags(raw) else raw
                val desc = clip?.description
                val types = if (desc == null) {
                    ""
                } else {
                    (0 until desc.mimeTypeCount).joinToString(",") { desc.getMimeType(it) }
                }
                log(
                    Log.INFO,
                    "DRAG_START raw=$raw flags=$flags cfg=$cfg patch=$patch changed=${flags != raw} " +
                        "global=${flags and View.DRAG_FLAG_GLOBAL != 0} uriRead=${flags and View.DRAG_FLAG_GLOBAL_URI_READ != 0} " +
                        "items=${clip?.itemCount ?: -1} types=[$types] " +
                        "uri=${clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri} " +
                        "view=${view?.javaClass?.simpleName} attached=${view?.isAttachedToWindow}",
                    null,
                )
                // 现代 Xposed 的 Chain 没有 setArg，改不了入参 → 需要改写时自己发起一次拖拽、跳过原调用。
                // 防递归：自己发起的那次 flags 已等于 patch(flags)，再次进来必然走 proceed 分支。
                val result =
                    if (flags == raw || view == null || clip == null) {
                        chain.proceed()
                    } else {
                        val shadow = chain.getArg(1) as? View.DragShadowBuilder
                        if (shadow == null) {
                            chain.proceed()
                        } else {
                            view.startDragAndDrop(clip, shadow, chain.getArg(2), flags)
                        }
                    }
                log(Log.INFO, "DRAG_RESULT started=$result", null)
                result
            }
    }

    /**
     * @author bomo 2026-10-17 核验更正：下方为前序排查记录，数值解释已被 SDK 37 与实机日志证伪。
     * URI_READ=0x1、URI_WRITE=0x2、GLOBAL=0x100、OPAQUE=0x200、SAME_APPLICATION=0x1000。
     * 原厂 771(0x303) 已含跨应用读写权限，本函数对此返回原值是正常的幂等结果；
     * cfg=1 / patch=true / changed=false 不表示补丁未执行。仅保留此规范化逻辑供既有对照开关使用。
     *
     * @author bomo 补上跨应用拖拽所缺的权限标志。
     *
     * 实测（0.2.16 埋点）原厂发起的是 `flags=771`，即
     * `OPAQUE(1) | 2 | GLOBAL(0x100) | GLOBAL_SAME_APPLICATION(0x200)`：
     * - **缺 `DRAG_FLAG_GLOBAL_URI_READ(0x400)`** —— 没有它，系统不会给 drop 接收方（QQ）
     *   授予 ClipData 里 `content://` Uri 的读权限，QQ 拿到 drop 也读不出内容 → 不发送；
     * - `GLOBAL_SAME_APPLICATION(0x200)` 会把派发范围限制在同 UID 窗口内，一并去掉。
     *
     * 开关：`adb shell settings put global flymefreeform_drag_flags 0`（还原原厂 flags 做对照）。
     */
    private fun patchDragFlags(raw: Int): Int =
        (raw or View.DRAG_FLAG_GLOBAL_URI_READ) and View.DRAG_FLAG_GLOBAL_SAME_APPLICATION.inv()

    /**
     * 读「跨应用拖拽 flags 修复」开关的原始值（0.2.18 起不再缓存）。
     *
     * @author bomo 0.2.17 用的是带 10s 缓存的布尔版本，装机后实测 `raw=771 flags=771`——
     * 补丁没生效，但缓存让"究竟读到了什么"变得不可观测。这里返回原始 Int 并打进日志，
     * 取不到上下文时按默认 1（修复开启）处理，保证"没读到配置"不会退化成"功能关闭"。
     */
    private fun readDragFlagSetting(context: Context?): Int {
        if (context == null) return 1
        return runCatching {
            Settings.Global.getInt(context.contentResolver, DRAG_FLAGS_KEY, 1)
        }.getOrDefault(1)
    }

    private fun findPanelMainView(loader: ClassLoader): View? =
        runCatching {
            val handlerClass = loader.loadClass(ColorOsAllAppsContent.HANDLER_CLASS)
            val handler = handlerClass.getField("INSTANCE").get(null)
            if (handler == null) null else handlerClass.getMethod("getMPanelMainView").invoke(handler) as? View
        }.getOrNull()

    private var lastBarView: View? = null

    private fun applyBarParams(view: View, params: WindowManager.LayoutParams) {
        lastBarView = view
        val hide = shouldHideNativeBar(view.context)
        val notTouchable = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val flags = if (hide) params.flags or notTouchable else params.flags and notTouchable.inv()
        val alpha = if (hide) 0f else 1f
        if (params.flags == flags && params.alpha == alpha) return
        params.flags = flags
        params.alpha = alpha
        log(Log.INFO, "NATIVE_BAR_WINDOW hide=$hide alpha=$alpha", null)
    }

    /** 主动刷新浮标窗口参数：开关翻转后原厂未必再更新窗口，不刷会一直保持旧状态。 */
    private fun refreshNativeBarWindow(view: View) {
        if (!view.isAttachedToWindow) return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        applyBarParams(view, params)
        runCatching { view.context.getSystemService(WindowManager::class.java)?.updateViewLayout(view, params) }
    }

    private var hideBarValue = false
    private var hideBarReadAt = 0L

    /** 读「隐藏系统侧边栏浮标」开关；带缓存，避免在状态机回调里高频读 Settings。 */
    private fun shouldHideNativeBar(context: Context): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - hideBarReadAt < HIDE_BAR_CACHE_MS) return hideBarValue
        hideBarReadAt = now
        val value = runCatching {
            Settings.Global.getInt(context.contentResolver, HIDE_NATIVE_BAR_KEY, 0)
        }.getOrDefault(0) == 1
        if (value != hideBarValue) {
            hideBarValue = value
            // 开关翻转：原厂不一定还会更新窗口参数，不主动刷一次会长时间保持旧状态。
            runCatching { lastBarView?.let { refreshNativeBarWindow(it) } }
        }
        return hideBarValue
    }

    private fun currentApplication(): android.content.Context? =
        runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? android.content.Context
        }.getOrNull()

    private fun log(priority: Int, code: String, throwable: Throwable?) = module.log(priority, "FlymeFreeform", code, throwable)

    private companion object {
        const val CONTEXT_RETRY_LIMIT = 8
        const val CONTEXT_RETRY_INTERVAL_MS = 1_000L

        /** @author bomo 隐藏系统侧边栏浮标的开关键：
         * `adb shell settings put global flymefreeform_hide_native_bar 1`（0 恢复显示）。 */
        const val HIDE_NATIVE_BAR_KEY = "flymefreeform_hide_native_bar"

        /** 开关读取缓存时长；侧边栏状态变化较频繁，不缓存会反复查 Settings。 */
        const val HIDE_BAR_CACHE_MS = 10_000L

        /**
         * @author bomo 跨应用拖拽 flags 修复开关：`adb shell settings put global flymefreeform_drag_flags 0`
         * 即还原原厂 flags（771）用于对照；默认 1（补 URI 读权限、解除同应用限制）。
         */
        const val DRAG_FLAGS_KEY = "flymefreeform_drag_flags"
    }
}
