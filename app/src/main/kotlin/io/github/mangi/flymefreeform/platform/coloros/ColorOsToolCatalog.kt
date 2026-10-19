package io.github.mangi.flymefreeform.platform.coloros

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import io.github.mangi.flymefreeform.config.ToolCatalogCodec
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationTargetException

/**
 * 侧边栏工具的枚举与执行（只在侧边栏进程内使用）。
 *
 * 实测结论（ColorOS 17 / 侧边栏 17.9.2）：工具不是独立 App，而是
 * `com.oplus.smartsidebar.panelview.edgepanel.data.entrybeans.models.tools.AbsTool` 的子类
 * （如 `BreenoScreenIdentifyTool` = 小布识屏、`GlobalTranslationTool` = 屏幕翻译），
 * 由 `ToolEntryHelper` 统一持有，执行入口是各自的 `handle()`。
 * 因此：
 * - 枚举必须在侧边栏进程做，导出的别名/名称/图标经框架远端配置给 App 与 system_server；
 * - 执行也必须回到侧边栏进程（扇形侧通过 [SidebarProtocol.RUN_TOOL] 转交）。
 *
 * @author bomo
 * @param context 侧边栏主题 Context
 * @param loader 侧边栏 ClassLoader
 * @param log 诊断日志
 */
internal class ColorOsToolCatalog(
    private val context: Context,
    private val loader: ClassLoader,
    private val log: (Int, String, Throwable?) -> Unit,
    /**
     * @author bomo 面板缩放百分比（来自设置界面滑条，经远端配置下发）；
     * 仅「最近文件」独立窗口使用，与「全部应用」面板保持同一观感。
     */
    private val panelScalePercent: Int = 100,
) {
    private var helper: Any? = null
    private var helperClass: Class<*>? = null

    /** @author bomo 当前打开的「最近文件」独立窗口；持引用避免被 GC，关闭时置空。 */
    private var recentFilesWindow: ColorOsRecentFilesWindow? = null

    /** 原厂工具路由与条目类型（按需加载，失败在调用处降级）。 */
    private val pageRoutClass: Class<*> by lazy { loader.loadClass(PAGE_ROUT_CLASS) }
    private val entryBeanClass: Class<*> by lazy { loader.loadClass(ENTRY_BEAN_CLASS) }

    /**
     * 枚举当前设备上实际可用的工具。
     * 任何反射失败都退化为空列表（不抛异常），由调用方决定是否重试。
     */
    fun build(): List<ToolCatalogCodec.Record> {
        val instance = ensureHelper() ?: return emptyList()
        val tools = readTools(instance) ?: return emptyList()
        cacheEntryBeans()
        return tools.mapNotNull { tool -> toRecord(tool) }
    }

    /** alias → 原厂 EntryBean 缓存；执行时优先走 startSysTool 原厂路径。 */
    private val entryBeansByAlias = mutableMapOf<String, Any>()

    /** 把原厂工具条目列表按别名建立索引（工具条目的 activity 字段恒为空串，alias 才是工具别名）。 */
    private fun cacheEntryBeans() {
        try {
            val helperClass = loader.loadClass(ENTRY_HELPER_CLASS)
            val helper = helperClass.getMethod("getActiveInstance").invoke(null) ?: return
            listOf("getShownTools", "getAllAppListForTool").forEach { name ->
                val list =
                    runCatching { helperClass.getMethod(name).invoke(helper) as? List<*> }.getOrNull()
                        ?: return@forEach
                list.filterNotNull().forEach { bean ->
                    runCatching {
                        val alias = bean.javaClass.getMethod("getAlias").invoke(bean) as? String
                        if (!alias.isNullOrEmpty()) entryBeansByAlias[alias] = bean
                    }
                }
            }
        } catch (exception: Exception) {
            log(Log.WARN, "TOOL_BEAN_CACHE_FAILED", unwrap(exception))
        }
    }

    /**
     * 执行指定别名的工具。
     *
     * 优先走原厂点击路径 `PageRoutUtils.startSysTool(entryBean)` —— 反编译证实原厂点工具
     * 不是裸调 `AbsTool.handle()`，还会做 PageRout 记账（点击坐标等）；
     * 小布识屏这类依赖坐标的工具缺了它就会"有时没反应"（截屏不依赖所以一直正常）。
     * 找不到条目时退回 `handle()` 兜底。
     *
     * **例外**：`recent_files`（最近文件）压根不在执行链上，走 [openRecentFilesWindow]
     * 用模块独立窗口直接承载原厂 `RecentFilePanel`（一步直达，首选）；
     * 独立窗口不可用时回退 [openRecentFilesPanel] 的旧两步链路；都失败才继续下面的普通路径。
     *
     * @param alias [build] 导出的别名
     * @return 是否成功调用到执行入口
     */
    fun run(alias: String, clickX: Float, clickY: Float): Boolean {
        val dispatchAt = SystemClock.uptimeMillis()
        val instance = ensureHelper() ?: return false
        // 0) PageRout 坐标记账：识屏等工具依赖点击坐标，扇形触发时传手势位置。
        try {
            pageRoutClass.getMethod("setENTRY_CLICK_X", Float::class.javaPrimitiveType).invoke(null, clickX)
            pageRoutClass.getMethod("setENTRY_CLICK_Y", Float::class.javaPrimitiveType).invoke(null, clickY)
        } catch (exception: Exception) {
            log(Log.WARN, "TOOL_PAGE_ROUT_COORDS_FAILED", unwrap(exception))
        }
        // 0.5) 「最近文件」不在工具执行链上：原厂由 UserPanelView.userPanelRecentFiles()
        //      在侧边栏内展开 RecentFilePanel。调 startSysTool 只会落到 AbsTool.handle()
        //      的空实现（只打一行日志），表现为"点了没反应"。详见 openRecentFilesPanel。
        if (alias == RECENT_FILES_ALIAS) {
            // 首选新版：模块自建独立窗口直接承载原厂 RecentFilePanel，
            // 不再「先展开侧边栏再挂子面板」两步走（B2 方案）。失败自动回退旧链路。
            if (openRecentFilesWindow(clickX)) {
                log(Log.INFO, "TOOL_INVOKED $alias via=moduleWindow", null)
                return true
            }
            log(Log.WARN, "RECENT_FILES_WINDOW_UNAVAILABLE", null)
            val opened =
                runCatching { openRecentFilesPanel() }
                    .onFailure { log(Log.WARN, "RECENT_FILES_PANEL_FAILED", unwrap(it)) }
                    .getOrDefault(false)
            if (opened) {
                log(Log.INFO, "TOOL_INVOKED $alias via=userPanelRecentFiles", null)
                return true
            }
            // 拿不到面板实例时回退到原路径，避免行为倒退。
            log(Log.WARN, "RECENT_FILES_PANEL_UNAVAILABLE", null)
        }
        // 1) 原厂路径：用缓存的 EntryBean 交给 PageRoutUtils.startSysTool。
        val bean = entryBeansByAlias[alias] ?: findToolBean(instance, alias)
        if (bean != null) {
            try {
                pageRoutClass
                    .getMethod(PAGE_ROUT_METHOD, entryBeanClass)
                    .invoke(null, bean)
                log(Log.INFO, "TOOL_INVOKED $alias via=${PAGE_ROUT_METHOD} dispatchMs=${SystemClock.uptimeMillis() - dispatchAt}", null)
                return true
            } catch (exception: Exception) {
                log(Log.WARN, "TOOL_PAGE_ROUT_FAILED $alias", unwrap(exception))
            }
        }
        // 2) 兜底：直接 handle()（截屏类不依赖 PageRout 记账，仍然可用）。
        return try {
            val tool =
                helperClass?.getMethod("getToolByAlias", String::class.java)
                    ?.invoke(instance, alias)
                    ?: return false
            tool.javaClass.getMethod("handle").invoke(tool)
            log(Log.INFO, "TOOL_INVOKED $alias via=handle", null)
            true
        } catch (exception: Exception) {
            log(Log.WARN, "TOOL_INVOKE_FAILED $alias", unwrap(exception))
            false
        }
    }

    /**
     * @author bomo 用模块自建独立窗口打开「最近文件」（B2 方案，首选路径）。
     *
     * 绕开侧边栏状态机：直接单参构造原厂 `RecentFilePanel` 塞进模块自己的 overlay 窗口，
     * 一步直达、动画自管，消除「先滑出侧边栏再滑出子面板」的两步观感与那段等待。
     * 任何一步失败都返回 false，由 [run] 回退 [openRecentFilesPanel] 旧链路。
     *
     * 呼出方位按点击坐标推导（左半屏靠左、右半屏靠右），与扇形手势方向一致。
     *
     * @param clickX 扇形里该工具条目的点击 X 坐标（px）
     * @return 窗口是否成功显示
     */
    private fun openRecentFilesWindow(clickX: Float): Boolean {
        // 上一个窗口还没关就先收掉，避免叠窗口。
        recentFilesWindow?.dismiss()
        recentFilesWindow = null
        val metrics = runCatching {
            context.getSystemService(WindowManager::class.java)?.currentWindowMetrics
        }.getOrNull()
        val width = metrics?.bounds?.width() ?: 0
        val leftSide = width > 0 && clickX <= width / 2f
        val window = ColorOsRecentFilesWindow(context, loader, panelScalePercent, log)
        val shown = runCatching { window.show(leftSide) }
            .onFailure { log(Log.WARN, "RECENT_FILES_WINDOW_FAILED", unwrap(it)) }
            .getOrDefault(false)
        if (!shown) {
            runCatching { window.dispose() }
            return false
        }
        recentFilesWindow = window
        log(Log.INFO, "RECENT_FILES_WINDOW_OPEN left=$leftSide", null)
        return true
    }

    /** @author bomo 会话结束/进程清理时收起独立窗口（无窗口时为空操作）。 */
    fun closeRecentFilesWindow() {
        recentFilesWindow?.dismiss()
        recentFilesWindow = null
    }

    /**
     * 打开原厂「最近文件」（文件袋）面板。
     *
     * 反编译实证（侧边栏 17.9.2 与 17.9.8 一致）：`RecentFilesTool` 只定义 6 个 getter，
     * **不 override `handle()`**，其 `getPkgs()` 也返回空数组 —— 它根本不属于
     * "靠 handle() 启动外部 App"那类工具。真正的入口是
     * `UserPanelView.userPanelRecentFiles(boolean)`，由它在侧边栏内部展开
     * `RecentFilePanel`（`panelview.filebag.view`）。原厂两个调用点：
     * 主面板 `UserPanelView.onItemClick` 传 `true`、「全部」面板 `AllAppPanelView` 传 `false`。
     * 这里用「全部」面板同款参数 `false`（`true` 走的是主面板的收起动画分支）。
     *
     * **两步**：原厂两个调用点都发生在面板**已显示**时，而扇形路径下面板通常是收起的。
     * 所以面板未展开时先调 `ViewDataHandlerImpl.toShowUserPanel(PANEL_SHOW_STATE)`
     * 请原厂唤起面板，**等它连展开动画一起跑完**再挂子面板（见 [isPanelReady]）——
     * 只等状态不等动画，子面板会按动画中间态测量，表现就是面板「只展开一半」。
     *
     * 调用必须发生在主线程（面板是 UI 对象）；[run] 由 Messenger 的 main Looper 处理，满足。
     *
     * @author bomo
     * @return 是否成功把调用交给原厂；拿不到面板实例时返回 false，由调用方回退
     */
    private fun openRecentFilesPanel(): Boolean {
        val handlerClass = loader.loadClass(VIEW_DATA_HANDLER_CLASS)
        // Kotlin object 的单例字段，侧边栏进程内常驻。
        val handler = handlerClass.getField(VIEW_DATA_HANDLER_INSTANCE).get(null) ?: return false
        val panelMainView =
            handlerClass.getMethod(GET_PANEL_MAIN_VIEW).invoke(handler) ?: return false
        val panel =
            panelMainView.javaClass.getMethod(GET_PANEL).invoke(panelMainView) ?: return false
        if (isPanelShowing(panelMainView)) {
            enterRecentPanel(panel)
            return true
        }
        // 侧边栏面板本身没显示（扇形路径的常态）：userPanelRecentFiles 只负责展开
        // 「最近文件」子面板，不负责把面板显示出来 —— 那是 PanelMainView 的职责。
        // 先请原厂把面板唤起来，等它进入 EDGE_PANEL_SHOWING 再展开子面板。
        val stateType = loader.loadClass(STATE_TYPE_CLASS)
        val showState = stateType.getField(STATE_SHOW).get(null)
        handlerClass.getMethod(SHOW_USER_PANEL, stateType).invoke(handler, showState)
        awaitPanelThenEnterRecent(panelMainView, panel, PANEL_SHOW_POLLS)
        return true
    }

    /**
     * @author bomo 轮询等待侧边栏面板进入 EDGE_PANEL_SHOWING 后再展开「最近文件」子面板。
     * 用主线程 postDelayed 递归，不阻塞；轮询次数用尽时只记诊断码。
     */
    private fun awaitPanelThenEnterRecent(panelMainView: Any, panel: Any, remaining: Int) {
        Handler(Looper.getMainLooper()).postDelayed(
            {
                val ready = isPanelReady(panelMainView)
                if (!ready && remaining > 1) {
                    awaitPanelThenEnterRecent(panelMainView, panel, remaining - 1)
                    return@postDelayed
                }
                // 轮询用尽仍没等到稳定态时尽力试一次，不把功能卡死。
                if (!ready) {
                    log(
                        Log.WARN,
                        "RECENT_FILES_PANEL_TIMEOUT showing=${isPanelShowing(panelMainView)}" +
                            " anim=${flag(panelMainView, IS_PANEL_EXCESS_ANIM_RUNNING)}",
                        null,
                    )
                }
                runCatching {
                    // 先把面板撑到正常侧边栏宽度，再挂子面板 —— 顺序对齐原厂 transfer 面板的做法。
                    applyPanelWidth(panel)
                    enterRecentPanel(panel)
                }.onFailure { log(Log.WARN, "RECENT_FILES_ENTER_FAILED", unwrap(it)) }
            },
            PANEL_SHOW_POLL_MS,
        )
    }

    /**
     * @author bomo 面板是否「已展开**且展开 spring 动画已结束**」。
     *
     * `EDGE_PANEL_SHOWING` 是展开动画**开始时**就翻转的状态，动画还要再跑几百毫秒；
     * 只等状态就挂子面板，子面板会按动画中间态的宽度测量 —— 表现为面板「只展开一半」。
     *
     * **别用 `PanelMainView.isUserPanelAnimToShowing()` 当"动画中"判据**：实测
     * （17.9.2 反编译 `PanelMainView:2839`）它在 `EDGE_PANEL_SHOWING` 稳态下**恒为 true** ——
     * 它的语义是「面板处于展开态」，不是「动画正在跑」。用它会把等待一路拖到超时，
     * 反而在更差的时机挂子面板（实测 0.2.8：超时后挂载，面板宽度掉到 266）。
     * 真正表示动画在跑的是 [IS_PANEL_EXCESS_ANIM_RUNNING]（内部就是 spring 动画状态）。
     */
    private fun isPanelReady(panelMainView: Any): Boolean =
        isPanelShowing(panelMainView) && !flag(panelMainView, IS_PANEL_EXCESS_ANIM_RUNNING)

    /** 反射读取原厂的无参 boolean 判据；取不到时按 false 处理（宁可早试也不卡死）。 */
    private fun flag(target: Any, method: String): Boolean =
        runCatching { target.javaClass.getMethod(method).invoke(target) as? Boolean ?: false }
            .getOrDefault(false)

    /** 侧边栏面板是否已展开（比枚举 name，避免额外加载原厂 State 类）。 */
    private fun isPanelShowing(panelMainView: Any): Boolean =
        runCatching {
            panelMainView.javaClass.getMethod(GET_M_STATE).invoke(panelMainView)?.toString() ==
                STATE_EDGE_PANEL_SHOWING
        }.getOrDefault(false)

    /** 交给原厂展开「最近文件」子面板。 */
    private fun enterRecentPanel(panel: Any) {
        panel.javaClass
            .getMethod(RECENT_FILES_ENTER, Boolean::class.javaPrimitiveType)
            .invoke(panel, false)
    }

    /**
     * @author bomo 把面板从「悬浮条宽度」撑到能容纳「最近文件」的宽度。
     *
     * 唤起路径 `toShowUserPanel` 只做状态迁移、**不改宽度** —— 面板会顶着 `FLOAT_BAR`
     * 的宽度展开（本机 266px）。而 `RecentFilePanel.onMeasure` 是照搬父容器宽度的
     * （`setMeasuredDimension(getSize(widthSpec), ...)`），里面的缩略图卡片上限又是
     * `user_panel_dual_img_max_width`(108dp ≈ 378px) —— 塞进 266px 里右侧直接被裁掉，
     * 就是用户看到的「只显示一半」。
     *
     * 宽度取 **dual 档**（与用户单/双列设置无关）：原厂给「需要更宽容器的子面板」
     * （文件中转站）也是这么取的 —— 见 `PanelMainView.changeWidthAtInitForTransferPanel`
     * 调 `getUserPanelWidth$default(false, true, 1, null)`，第 2 个实参使
     * `(z10 || z11)` 恒为真，即强制 dual。这里不写死像素值，仍走原厂资源。
     *
     * 应用方式照抄原厂：`changePanelWidth(true, w, false, false)` + 显式 `measure(0, 0)`
     * （原厂那处同样是"不 requestLayout、手动 measure"）。
     */
    private fun applyPanelWidth(panel: Any) {
        val width =
            loader
                .loadClass(SIZE_HELPER_CLASS)
                .getMethod(
                    GET_USER_PANEL_WIDTH,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                )
                .invoke(null, true, false) as? Int
                ?: 0
        val before =
            runCatching { panel.javaClass.getMethod(GET_M_PANEL_WIDTH).invoke(panel) as? Int }
                .getOrNull()
        log(Log.INFO, "RECENT_FILES_PANEL_WIDTH before=$before target=$width", null)
        if (width <= 0) {
            log(Log.WARN, "RECENT_FILES_PANEL_WIDTH_UNAVAILABLE", null)
            return
        }
        panel.javaClass
            .getMethod(
                CHANGE_PANEL_WIDTH,
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            )
            .invoke(panel, true, width, false, false)
        panel.javaClass
            .getMethod(MEASURE, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(panel, 0, 0)
    }

    /** 在原厂工具条目列表里按别名找 EntryBean（工具条目的 activity 字段恒为空串，alias 才是工具别名）。 */
    private fun findToolBean(instance: Any, alias: String): Any? =
        try {
            val helperClass = loader.loadClass(ENTRY_HELPER_CLASS)
            val helper = helperClass.getMethod("getActiveInstance").invoke(null) ?: return null
            val lists =
                listOf("getShownTools", "getAllAppListForTool").mapNotNull { name ->
                    runCatching { helperClass.getMethod(name).invoke(helper) as? List<*> }.getOrNull()
                }
            lists
                .flatten()
                .filterNotNull()
                .firstOrNull { bean ->
                    runCatching {
                        bean.javaClass.getMethod("getAlias").invoke(bean) == alias ||
                            bean.javaClass.getMethod("getIntentString").invoke(bean) == alias
                    }.getOrDefault(false)
                }
        } catch (exception: Exception) {
            log(Log.WARN, "TOOL_BEAN_LOOKUP_FAILED", unwrap(exception))
            null
        }

    private fun ensureHelper(): Any? {
        helper?.let { return it }
        return try {
            val type = loader.loadClass(TOOL_HELPER_CLASS)
            val instance =
                runCatching { type.getMethod("getActiveInstance").invoke(null) }.getOrNull()
                    ?: type.getMethod("createAndGetInstance").invoke(null)
                    ?: return null
            // init(Context) 幂等：重复调用只是刷新内部表；失败仍保留实例供 getToolByAlias 使用。
            runCatching { type.getMethod("init", Context::class.java).invoke(instance, context) }
                .onFailure { exception -> log(Log.WARN, "TOOL_HELPER_INIT_FAILED", unwrap(exception)) }
            helperClass = type
            helper = instance
            instance
        } catch (exception: Exception) {
            log(Log.WARN, "TOOL_HELPER_UNAVAILABLE", unwrap(exception))
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun readTools(instance: Any): List<Any>? =
        try {
            val field = (helperClass ?: return null).getDeclaredField(TOOLS_FIELD)
            field.isAccessible = true
            (field.get(instance) as? Map<*, *>)?.values?.filterNotNull()
        } catch (exception: Exception) {
            log(Log.WARN, "TOOL_TABLE_UNAVAILABLE", unwrap(exception))
            null
        }

    private fun toRecord(tool: Any): ToolCatalogCodec.Record? =
        try {
            val type = tool.javaClass
            val alias = type.getMethod("getAlias").invoke(tool) as? String ?: return null
            if (alias.isEmpty()) return null
            val label =
                (type.getMethod("getLabel").invoke(tool) as? String)?.takeIf { it.isNotBlank() }
                    ?: (type.getMethod("getChineseName").invoke(tool) as? String)
                    ?: return null
            val available = type.getMethod("isToolAvailable").invoke(tool) as? Boolean ?: false
            ToolCatalogCodec.Record(
                alias = alias,
                label = label.trim(),
                available = available,
                iconPng = loadIcon(tool, type),
            )
        } catch (exception: Exception) {
            log(Log.WARN, "TOOL_ENTRY_SKIPPED", unwrap(exception))
            null
        }

    /**
     * 优先用工具自带图标资源，缺失时回退到它依赖的应用图标；再失败则不带图标。
     * 任一环节失败都打诊断码（此前 runCatching 静默，目录曾出现 22 条全无图标且无线索）。
     */
    private fun loadIcon(tool: Any, type: Class<*>): ByteArray? {
        val drawable =
            runCatching {
                val iconRes = type.getMethod("getIconRes").invoke(tool) as? Int ?: 0
                if (iconRes != 0) context.resources.getDrawable(iconRes, context.theme) else null
            }.recoverCatching { exception ->
                log(Log.WARN, "TOOL_ICON_RES_FAILED ${type.simpleName}", unwrap(exception))
                null
            }.getOrNull() ?: fallbackIcon(tool, type)
        if (drawable == null) {
            log(Log.WARN, "TOOL_ICON_LOAD_FAILED ${type.simpleName}", null)
            return null
        }
        return encodePng(drawable) ?: run {
            log(Log.WARN, "TOOL_ICON_ENCODE_FAILED ${type.simpleName}", null)
            null
        }
    }

    private fun fallbackIcon(tool: Any, type: Class<*>): Drawable? =
        runCatching {
            val pkg = type.getMethod("getValidPkg").invoke(tool) as? String ?: return null
            context.packageManager.getApplicationIcon(pkg)
        }.getOrNull()

    private fun encodePng(drawable: Drawable): ByteArray? =
        runCatching {
            val size = TOOL_ICON_PX
            val bitmap =
                if (drawable is BitmapDrawable && drawable.bitmap != null) {
                    Bitmap.createScaledBitmap(drawable.bitmap, size, size, true)
                } else {
                    Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { target ->
                        val canvas = Canvas(target)
                        drawable.setBounds(0, 0, canvas.width, canvas.height)
                        drawable.draw(canvas)
                    }
                }
            ByteArrayOutputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                stream.toByteArray()
            }
        }.getOrNull()

    private fun unwrap(exception: Throwable): Throwable =
        (exception as? InvocationTargetException)?.cause ?: exception

    private companion object {
        const val PAGE_ROUT_CLASS =
            "com.oplus.smartsidebar.panelview.edgepanel.utils.PageRoutUtils"
        const val PAGE_ROUT_METHOD = "startSysTool"
        const val ENTRY_BEAN_CLASS =
            "com.oplus.smartsidebar.panelview.edgepanel.data.entrybeans.models.beans.EntryBean"
        const val ENTRY_HELPER_CLASS =
            "com.oplus.smartsidebar.panelview.edgepanel.data.entrybeans.EntryBeanHelper"
        const val TOOL_HELPER_CLASS =
            "com.oplus.smartsidebar.panelview.edgepanel.data.entrybeans.ToolEntryHelper"

        /** 原厂 ToolEntryHelper 内部持有全部工具的 Map 字段名。 */
        const val TOOLS_FIELD = "mTools"

        /** 原厂「最近文件」（文件袋）别名；该工具不在执行链上，见 [openRecentFilesPanel]。 */
        const val RECENT_FILES_ALIAS = "recent_files"

        /** 原厂面板实例的取用链：ViewDataHandlerImpl.INSTANCE → PanelMainView → UserPanelView。 */
        const val VIEW_DATA_HANDLER_CLASS =
            "com.oplus.smartsidebar.panelview.edgepanel.data.viewdatahandlers.ViewDataHandlerImpl"
        const val VIEW_DATA_HANDLER_INSTANCE = "INSTANCE"
        const val GET_PANEL_MAIN_VIEW = "getMPanelMainView"
        const val GET_PANEL = "getMPanel"

        /** 原厂展开「最近文件」面板的真实入口方法。 */
        const val RECENT_FILES_ENTER = "userPanelRecentFiles"

        /** 面板宽度：取原厂尺寸工具，再交给 UserPanelView.changePanelWidth 应用（见 [applyPanelWidth]）。 */
        const val SIZE_HELPER_CLASS =
            "com.oplus.smartsidebar.panelview.edgepanel.utils.MainPanelSizeHelper"
        const val GET_USER_PANEL_WIDTH = "getUserPanelWidth"
        const val CHANGE_PANEL_WIDTH = "changePanelWidth"
        const val GET_M_PANEL_WIDTH = "getMPanelWidth"
        const val MEASURE = "measure"

        /** 面板未展开时先唤起的入口：ViewDataHandlerImpl.toShowUserPanel(SingleStateType)。 */
        const val STATE_TYPE_CLASS = "com.oplus.smartsidebar.aidl.SingleStateType"
        const val STATE_SHOW = "PANEL_SHOW_STATE"
        const val SHOW_USER_PANEL = "toShowUserPanel"

        /** PanelMainView 状态读取；[STATE_EDGE_PANEL_SHOWING] 是枚举 name，用字符串比较。 */
        const val GET_M_STATE = "getMState"
        const val STATE_EDGE_PANEL_SHOWING = "EDGE_PANEL_SHOWING"

        /**
         * 展开 spring 动画是否在跑（`PanelMainView.isUserPanelExcessAnimRunning()`，
         * 内部即 `isPanelRevealSpringRunning()`）。为 false = 动画结束、面板尺寸已定型。
         * 注意**不能**用同名的 `isUserPanelAnimToShowing()` 代替，见 [isPanelReady]。
         */
        const val IS_PANEL_EXCESS_ANIM_RUNNING = "isUserPanelExcessAnimRunning"

        /** 面板唤起的轮询参数：100ms × 12 ≈ 1.2s 上限（满足即提前退出）。 */
        const val PANEL_SHOW_POLL_MS = 100L
        const val PANEL_SHOW_POLLS = 12

        /** 图标上限；目录要经框架配置搬运，过大会拖慢写入与读取。 */
        const val TOOL_ICON_PX = 72
    }
}
