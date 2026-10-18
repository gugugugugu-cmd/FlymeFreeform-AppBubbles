package io.github.mangi.flymefreeform.platform.coloros

import android.content.Context
import android.graphics.PixelFormat
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.DragEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.animation.ValueAnimator
import android.widget.FrameLayout
import io.github.mangi.flymefreeform.window.AllAppsPanelMotion
import java.lang.reflect.InvocationTargetException

/**
 * 「最近文件」（文件袋）的**模块自建独立窗口**。
 *
 * ## 为什么不用原厂链路
 * 原厂入口是 `UserPanelView.userPanelRecentFiles()`，它只负责在**已展开的侧边栏面板内**
 * 挂上 `RecentFilePanel` 子面板；扇形路径下面板通常是收起的，于是必须
 * `toShowUserPanel()` 先把侧边栏唤起来，还要等它 spring 展开动画跑完
 * （子面板按父容器中间态测量 → 挂早了就"只展开一半"）。用户看到的就是
 * 「先滑出侧边栏，再滑出最近文件」两步走 + 一次额外的等待。
 *
 * ## 反编译实证（侧边栏 17.9.2 / 17.9.8 一致）
 * - 原厂自己就是 `new RecentFilePanel(App.sContext)` **单参构造**（`UserListPanel:717`），
 *   布局 `layout_file_bag_recent_panel` 由此 inflate，不依赖面板容器；
 * - 列表数据是页面（[PAGE_IMAGE]/[PAGE_DOC]）构造时经单例 `FileBagController.F()`
 *   自行加载的，**不依赖面板会话或面板状态**；
 * - 面板返回键 `recent_file_back` 的原厂逻辑要回调 `mUserListPanel`，独立窗口下会空转，
 *   所以这里接管它（见 [bindNativeBack]）。
 *
 * 因此本窗口可以完全绕开侧边栏状态机，一步直达，动画由模块自己控制，
 * 与「全部应用」面板观感一致（同一套 [AllAppsPanelMotion] 与平台玻璃）。
 *
 * ## 已知降级
 * 面板内「编辑/多选」弹窗（RecentFilePanel 内部 h 类）会检查原厂面板状态是否为
 * `EDGE_PANEL_SHOWING`，独立窗口下状态仍是 `FLOAT_BAR_SHOWING` → 该弹窗不弹出。
 * 浏览、打开、长按预览等主流程不受影响；后续如确需可再挂钩子补。
 *
 * @author bomo
 * @param context 侧边栏进程的服务 Context（用于 WindowManager / 资源）
 * @param loader 侧边栏 ClassLoader
 * @param panelScalePercent 面板缩放百分比（与「全部应用」共用设置滑条）
 * @param log 诊断日志
 */
internal class ColorOsRecentFilesWindow(
    private val context: Context,
    private val loader: ClassLoader,
    private val panelScalePercent: Int,
    private val log: (Int, String, Throwable?) -> Unit,
) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val panelScale = coerceScale(panelScalePercent) / 100f
    private var attached = false
    private var closed = false
    private var exiting = false
    private var entering = true
    private var animationStart = 0L
    private var durationScale = 1f
    private var dragging = false
    private val animation = Runnable { animateFrame() }
    private val timeout = Runnable { dismiss() }

    /** 原厂面板视图；构造失败时整个功能不可用（调用方回退旧链路）。 */
    private val panel: View? = runCatching { constructPanel() }.onFailure {
        log(Log.WARN, "RECENT_FILES_PANEL_CONSTRUCT_FAILED", unwrap(it))
    }.getOrNull()

    /** 承载面板的卡片（COUICardView，与原厂面板同源的圆角/材质）。 */
    private var card: FrameLayout? = null

    private val root = object : FrameLayout(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (exiting || entering) return true
            // @author bomo 非模态窗口通过一次 ACTION_OUTSIDE 收起，事件本身仍由系统投递给下层应用。
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                if (!dragging) dismiss()
                return true
            }
            resetTimeout()
            return super.dispatchTouchEvent(event)
        }

        /**
         * @author bomo 观察本窗口发起的拖拽；返回 true 注册开始/结束通知，窗口内放下视为取消。
         * @param event 系统拖拽事件；其他窗口发起的拖拽保持父类行为。
         * @return 本窗口拖拽是否由根视图接收；不主动发送文件或授予额外权限。
         */
        override fun onDragEvent(event: DragEvent): Boolean {
            if (event.action == DragEvent.ACTION_DRAG_STARTED) {
                val source = event.localState as? View
                if (source?.rootView !== this) return super.onDragEvent(event)
                dragging = true
                removeCallbacks(timeout)
                log(Log.INFO, "RECENT_FILES_DRAG_STARTED windowFlags=0x${(layoutParams as? WindowManager.LayoutParams)?.flags?.toString(16)}", null)
                return true
            }
            if (!dragging) return super.onDragEvent(event)
            if (event.action == DragEvent.ACTION_DRAG_ENDED) {
                dragging = false
                log(Log.INFO, "RECENT_FILES_DRAG_ENDED accepted=${event.result}", null)
                resetTimeout()
            }
            return true
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) dismiss()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    /**
     * 构造窗口内容并挂载。
     *
     * @param leftSide 靠屏幕哪一侧；由扇形点击坐标推导（右半屏 → 靠右）
     * @return 是否成功显示；失败时调用方应回退原厂两步链路
     */
    fun show(leftSide: Boolean): Boolean {
        val content = panel ?: return false
        if (attached || closed) return false
        val metrics = runCatching { manager.currentWindowMetrics }.getOrNull() ?: return false
        val displayWidth = metrics.bounds.width()
        val displayHeight = metrics.bounds.height()
        val safe = metrics.windowInsets.getInsets(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
        )
        val size = runCatching { panelSize(displayHeight) }
            .onFailure { log(Log.WARN, "RECENT_FILES_SIZE_FAILED", unwrap(it)) }
            .getOrNull() ?: return false
        val scaledWidth = (size.first * panelScale).toInt().coerceAtLeast(1)
        val scaledHeight = (size.second * panelScale).toInt().coerceAtLeast(1)
        val left = if (leftSide) {
            safe.left + SIDE_MARGIN_PX
        } else {
            displayWidth - safe.right - SIDE_MARGIN_PX - scaledWidth
        }
        val top = safe.top + ((displayHeight - safe.top - safe.bottom - scaledHeight) / 2)
        val cardView = runCatching { createCard(content, size.first, size.second) }
            .onFailure { log(Log.WARN, "RECENT_FILES_CARD_FAILED", unwrap(it)) }
            .getOrNull() ?: return false
        card = cardView
        root.setBackgroundColor(0)
        root.isFocusableInTouchMode = true
        root.clipChildren = false
        cardView.alpha = 0f
        content.alpha = 0f
        root.addView(cardView, FrameLayout.LayoutParams(size.first, size.second))
        root.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) dismiss()
            true
        }
        val params = WindowManager.LayoutParams(
            scaledWidth, scaledHeight, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                // @author bomo 原先的模态窗口会在边界外继续成为拖拽落点，阻断下层 QQ 接收。
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = left.coerceIn(0, (displayWidth - scaledWidth).coerceAtLeast(0))
            y = top.coerceIn(0, (displayHeight - scaledHeight).coerceAtLeast(0))
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            setFitInsetsTypes(0)
            title = "FlymeFreeformRecentFiles"
        }
        runCatching { manager.addView(root, params) }
            .onFailure {
                log(Log.WARN, "RECENT_FILES_ADD_VIEW_FAILED", unwrap(it))
                return false
            }
        attached = true
        bindNativeBack(content)
        startEntrance(scaledWidth, scaledHeight)
        return true
    }

    fun dismiss() {
        if (closed || exiting) return
        exiting = true
        entering = false
        root.removeCallbacks(timeout)
        startAnimation()
    }

    /** 彻底释放窗口与面板（面板 onDetachedFromWindow 会取消页面订阅）。 */
    fun dispose() {
        if (closed) return
        closed = true
        dragging = false
        root.removeCallbacks(animation)
        root.removeCallbacks(timeout)
        if (attached) {
            attached = false
            runCatching { manager.removeViewImmediate(root) }
                .onFailure { log(Log.WARN, "RECENT_FILES_REMOVE_FAILED", unwrap(it)) }
        }
        root.removeAllViews()
        card = null
    }

    // ── 内部实现 ──

    /** 单参构造原厂面板（与原厂 UserListPanel 同款，用侧边栏的 App.sContext）。 */
    private fun constructPanel(): View {
        val themed = runCatching {
            loader.loadClass(APP_CLASS).getField(APP_CONTEXT_FIELD).get(null) as? Context
        }.getOrNull() ?: context
        val type = loader.loadClass(PANEL_CLASS)
        return type.getConstructor(Context::class.java).newInstance(themed) as View
    }

    /**
     * 卡片容器：用原厂 COUICardView，圆角/材质参数从原厂 UserPanelView 抄，
     * 尺寸用原厂 dual 档宽度 × max 高度（与原生子面板展开后的尺寸一致）。
     */
    private fun createCard(content: View, width: Int, height: Int): FrameLayout {
        val cardClass = loader.loadClass(CARD_CLASS)
        val surface = cardClass.getConstructor(Context::class.java).newInstance(context) as FrameLayout
        val original = runCatching { nativeUserPanel() }.getOrNull()
        if (original != null) {
            runCatching {
                val radius = original.javaClass.getMethod(CARD_GET_RADIUS).invoke(original) as? Float
                val weight = original.javaClass.getMethod(CARD_GET_WEIGHT).invoke(original) as? Float
                if (radius != null) cardClass.getMethod("setRadius", Float::class.javaPrimitiveType).invoke(surface, radius)
                if (weight != null) cardClass.getMethod("setWeight", Float::class.javaPrimitiveType).invoke(surface, weight)
            }
        }
        cardClass.getMethod("setPreventCornerOverlap", Boolean::class.javaPrimitiveType).invoke(surface, false)
        cardClass.getMethod("setUseCompatPadding", Boolean::class.javaPrimitiveType).invoke(surface, false)
        cardClass.getMethod(
            "setCardBackgroundColor",
            Int::class.javaPrimitiveType,
        ).invoke(surface, glassSurfaceColor())
        surface.clipToOutline = true
        surface.isForceDarkAllowed = false
        surface.elevation = 1f
        surface.addView(content, FrameLayout.LayoutParams(width, height))
        attachPlatformGlass(surface)
        return surface
    }

    /**
     * 面板尺寸：宽取原厂 dual 档（与最近文件子面板一致），高取原厂 max 面板高。
     * 拿不到就抛异常 → 调用方回退旧链路，不猜像素值。
     */
    private fun panelSize(displayHeight: Int): Pair<Int, Int> {
        val width = loader.loadClass(SIZE_HELPER_CLASS)
            .getMethod(
                GET_USER_PANEL_WIDTH,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            ).invoke(null, true, false) as? Int ?: 0
        val height = runCatching {
            nativeUserPanel()?.javaClass?.getMethod(GET_MAX_PANEL_HEIGHT)?.invoke(nativeUserPanel()) as? Int
        }.getOrNull() ?: 0
        if (width <= 0 || height <= 0) throw IllegalStateException("RECENT_FILES_SIZE_UNAVAILABLE w=$width h=$height")
        return width to height.coerceAtMost(displayHeight)
    }

    /** 原厂 UserPanelView 实例（取圆角/高度用）；取不到返回 null 走降级。 */
    private fun nativeUserPanel(): Any? = runCatching {
        val handlerClass = loader.loadClass(VIEW_DATA_HANDLER_CLASS)
        val handler = handlerClass.getField(VIEW_DATA_HANDLER_INSTANCE).get(null) ?: return null
        val main = handlerClass.getMethod(GET_PANEL_MAIN_VIEW).invoke(handler) ?: return null
        main.javaClass.getMethod(GET_PANEL).invoke(main)
    }.getOrNull()

    /**
     * @author bomo 接管面板自带的返回按钮：原厂 F() 会去回调 mUserListPanel，
     * 独立窗口下它是空转（点了没反应），这里改成关自己的窗口。
     */
    private fun bindNativeBack(content: View) {
        val button = findByIdName(content, BACK_ID_NAME)
        if (button == null) {
            log(Log.WARN, "RECENT_FILES_BACK_NOT_FOUND", null)
            return
        }
        button.setOnClickListener { dismiss() }
        log(Log.INFO, "RECENT_FILES_BACK_BOUND class=${button.javaClass.simpleName}", null)
    }

    private fun findByIdName(node: View, name: String): View? {
        if (node.id != View.NO_ID) {
            val entry = runCatching { context.resources.getResourceEntryName(node.id) }.getOrNull()
            if (entry == name) return node
        }
        if (node is ViewGroup) {
            for (index in 0 until node.childCount) {
                findByIdName(node.getChildAt(index), name)?.let { return it }
            }
        }
        return null
    }

    /** 玻璃表面色：优先原厂资源，失败退回「全部应用」面板同款实测值。 */
    private fun glassSurfaceColor(): Int {
        val id = runCatching {
            context.resources.getIdentifier(GLASS_SURFACE_COLOR_RES, "color", ColorOsSidebarTarget.PACKAGE_NAME)
        }.getOrDefault(0)
        if (id != 0) {
            val color = runCatching { context.resources.getColor(id, context.theme) }.getOrNull()
            if (color != null) return color
        }
        return GLASS_SURFACE_COLOR
    }

    /**
     * 复用原厂 SidebarPlatformBlurHelper 给卡片贴 posteffect 背景模糊，
     * 与「全部应用」面板 1:1 观感；任何失败都保留卡片底色（不抛）。
     */
    private fun attachPlatformGlass(surface: FrameLayout) {
        val helperClass = runCatching { loader.loadClass(GLASS_HELPER_CLASS) }.getOrNull() ?: return
        val apply = runCatching {
            helperClass.getMethod(
                "r",
                View::class.java,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            )
        }.getOrNull() ?: runCatching {
            helperClass.getMethod(
                "r",
                View::class.java,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
        }.getOrNull() ?: return
        val enabled = runCatching {
            Settings.Global.getInt(context.contentResolver, ColorOsAllAppsContent.PLATFORM_GLASS_KEY, 1)
        }.getOrDefault(1)
        if (enabled == 0) return
        runCatching {
            val helper = helperClass.getConstructor().newInstance()
            val applied = if (apply.parameterCount >= 4) {
                apply.invoke(helper, surface, true, true, 0)
            } else {
                apply.invoke(helper, surface, true, true)
            }
            if (applied == true) {
                // 模糊 Drawable 即视图背景，卡片底色必须透明，否则挡住玻璃。
                loader.loadClass(CARD_CLASS)
                    .getMethod("setCardBackgroundColor", Int::class.javaPrimitiveType)
                    .invoke(surface, 0)
                surface.elevation = 0f
                log(Log.INFO, "RECENT_FILES_GLASS_ATTACHED", null)
            }
        }.onFailure { log(Log.WARN, "RECENT_FILES_GLASS_FAILED", unwrap(it)) }
    }

    /** 入场：卡片按原生尺寸布局后围绕中心缩放，铺满缩小后的窗口。 */
    private fun startEntrance(scaledWidth: Int, scaledHeight: Int) {
        val surface = card ?: return
        val content = panel ?: return
        surface.pivotX = surface.width / 2f
        surface.pivotY = surface.height / 2f
        surface.translationX = -surface.width * (1f - panelScale) / 2f
        surface.translationY = -surface.height * (1f - panelScale) / 2f
        log(
            Log.INFO,
            "RECENT_FILES_SHOWN ${scaledWidth}x$scaledHeight native=${surface.width}x${surface.height} scale=$panelScale",
            null,
        )
        val initial = AllAppsPanelMotion.enter(0f)
        surface.scaleX = initial.scale * panelScale
        surface.scaleY = initial.scale * panelScale
        content.alpha = initial.alpha
        surface.alpha = 1f
        startAnimation()
        resetTimeout()
    }

    private fun startAnimation() {
        root.removeCallbacks(animation)
        durationScale = ValueAnimator.getDurationScale()
        animationStart = SystemClock.uptimeMillis()
        if (!ValueAnimator.areAnimatorsEnabled() || durationScale <= 0f) {
            if (exiting) finishExit() else finishEnter()
            return
        }
        root.postOnAnimation(animation)
    }

    private fun animateFrame() {
        if (closed) return
        val elapsed = (SystemClock.uptimeMillis() - animationStart) / durationScale
        val frame =
            if (exiting) AllAppsPanelMotion.exit(elapsed / 1_000f)
            else AllAppsPanelMotion.enter(elapsed / 1_000f)
        val surface = card
        val content = panel
        if (surface != null && content != null) {
            surface.alpha = if (exiting) frame.alpha else 1f
            content.alpha = if (exiting) 1f else frame.alpha
            surface.scaleX = frame.scale * panelScale
            surface.scaleY = frame.scale * panelScale
        }
        when {
            exiting && (frame.alpha <= AllAppsPanelMotion.EXIT_ALPHA_THRESHOLD ||
                elapsed >= AllAppsPanelMotion.MAX_DURATION_MS) -> finishExit()
            !exiting && ((frame.alpha >= 0.997f && frame.scale >= 0.997f) ||
                elapsed >= AllAppsPanelMotion.MAX_DURATION_MS) -> finishEnter()
            else -> root.postOnAnimation(animation)
        }
    }

    private fun finishEnter() {
        entering = false
        card?.alpha = 1f
        panel?.alpha = 1f
        card?.scaleX = panelScale
        card?.scaleY = panelScale
    }

    private fun finishExit() {
        card?.alpha = 0f
        panel?.alpha = 0f
        dispose()
    }

    private fun resetTimeout() {
        root.removeCallbacks(timeout)
        if (dragging || closed || exiting) return
        root.postDelayed(timeout, AUTO_DISMISS_MS)
    }

    private fun coerceScale(value: Int): Int = value.coerceIn(MIN_SCALE_PERCENT, 100)

    private fun unwrap(throwable: Throwable): Throwable =
        if (throwable is InvocationTargetException) throwable.cause ?: throwable else throwable

    private companion object {
        /** 原厂「最近文件」面板视图（单参构造）。 */
        const val PANEL_CLASS = "com.oplus.smartsidebar.panelview.filebag.view.RecentFilePanel"

        /** 侧边栏全局 Context 宿主（原厂构造面板就用它）。 */
        const val APP_CLASS = "com.coloros.common.App"
        const val APP_CONTEXT_FIELD = "sContext"

        /** 卡片容器与圆角取值。 */
        const val CARD_CLASS = "com.coui.appcompat.cardview.COUICardView"
        const val CARD_GET_RADIUS = "getRadius"
        const val CARD_GET_WEIGHT = "getWeight"

        /** 面板实例链与尺寸来源。 */
        const val VIEW_DATA_HANDLER_CLASS =
            "com.oplus.smartsidebar.panelview.edgepanel.data.viewdatahandlers.ViewDataHandlerImpl"
        const val VIEW_DATA_HANDLER_INSTANCE = "INSTANCE"
        const val GET_PANEL_MAIN_VIEW = "getMPanelMainView"
        const val GET_PANEL = "getMPanel"
        const val GET_MAX_PANEL_HEIGHT = "getMaxPanelHeight"
        const val SIZE_HELPER_CLASS =
            "com.oplus.smartsidebar.panelview.edgepanel.utils.MainPanelSizeHelper"
        const val GET_USER_PANEL_WIDTH = "getUserPanelWidth"

        /** 玻璃：与「全部应用」面板同源。 */
        const val GLASS_HELPER_CLASS = "com.oplus.smartsidebar.utils.SidebarPlatformBlurHelper"
        const val GLASS_SURFACE_COLOR_RES = "coui_popup_list_blend_blur_light"
        const val GLASS_SURFACE_COLOR = 0xBFEEEEEE.toInt()

        /** 布局 layout_file_bag_recent_panel 中的返回按钮 id 名。 */
        const val BACK_ID_NAME = "recent_file_back"

        /** 页面类型（数据由页面经 FileBagController 自行加载，此处仅作文档索引）。 */
        const val PAGE_IMAGE = "RecentImagePage"
        const val PAGE_DOC = "RecentDocPage"

        /** 面板贴边留白（px）；原厂侧边栏也是贴边距展开。 */
        const val SIDE_MARGIN_PX = 8

        /** 缩放范围；与设置滑条一致的下限保护。 */
        const val MIN_SCALE_PERCENT = 50

        /** 无操作自动收起。 */
        const val AUTO_DISMISS_MS = 120_000L
    }
}
