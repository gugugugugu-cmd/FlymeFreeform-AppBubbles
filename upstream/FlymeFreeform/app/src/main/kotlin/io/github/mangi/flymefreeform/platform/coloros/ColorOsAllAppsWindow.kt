package io.github.mangi.flymefreeform.platform.coloros

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import io.github.mangi.flymefreeform.config.ModulePreferences
import io.github.mangi.flymefreeform.window.AllAppsPanelGeometry
import io.github.mangi.flymefreeform.window.AllAppsPanelMotion
import java.lang.reflect.Method

/** 独立窗口只承载本次新建的全部内容；不挂接原侧栏父视图，也不改变其状态机。 */
internal class ColorOsAllAppsWindow(
    private val context: Context,
    val content: ColorOsAllAppsContent,
    /**
     * @author bomo 面板整体缩放百分比，来自设置界面滑条（经 [ModulePreferences] 下发）。
     */
    panelScalePercent: Int,
    private val onShown: () -> Unit,
    private val onClosed: () -> Unit,
    private val onExitStarted: () -> Unit,
    private val beforeTool: (() -> Unit) -> Unit,
    private val onFailure: (Exception) -> Unit,
    /** @author bomo 诊断日志出口（默认丢弃，便于单测与旧调用点不受影响）。 */
    private val log: (Int, String, Throwable?) -> Unit = { _, _, _ -> },
) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val card = content.createCard()
    private var attached = false
    private var closed = false
    private var exiting = false
    private var entering = true
    private var animationStart = 0L
    private var durationScale = 1f
    private var notified = false
    private var hiddenAction: (() -> Unit)? = null
    private val dimensions = content.dimensions()
    private val mode = content.mode()
    private val leftSide = content.leftSide()

    /** @author bomo 设置界面滑条写入的缩放百分比。 */
    private val preferredScalePercent =
        ModulePreferences.coercePanelScalePercent(panelScalePercent)

    /**
     * @author bomo 「全部」面板整体缩放比例（默认取滑条值）。
     * 内容按原生尺寸布局后等比缩放，网格间距随之等比缩小。
     * 仍可用 `adb shell settings put global flymefreeform_panel_scale 60` 覆盖（优先级更高），
     * 范围 [ModulePreferences.MIN_PANEL_SCALE_PERCENT]~100，下次打开面板即生效。
     */
    private val panelScale =
        ModulePreferences.coercePanelScalePercent(
            tunedInt(PANEL_SCALE_KEY, preferredScalePercent),
        ) / 100f
    private var insets: WindowInsets? = null
    private var displayWidth = 0
    private var displayHeight = 0
    private var displaySafe = IntArray(4)
    private var windowParams: WindowManager.LayoutParams? = null

    // @author bomo 首帧门闩诊断：记录 addView 时刻、被拦下的帧数与原因，
    // 放行时一次性打印。用于判定「面板空白/白屏」发生在门闩期间还是入场动画期间。
    private var showAt = 0L
    private var gateFrames = 0
    private val gateReasons = LinkedHashSet<String>()

    // @author bomo 首帧耗时分段：定位 addView→首帧 这几百毫秒究竟花在哪一段。
    // addView=同步 addView 调用（ViewRootImpl 创建 + 首次 relayout 请求）
    // attach=onAttachedToWindow / measure=首次 onMeasure / layout=首次 onLayout
    private var addViewDoneAt = 0L
    private var attachedAt = 0L
    private var measuredAt = 0L
    private var laidOutAt = 0L

    /**
     * @author bomo 本进程内第几个面板窗口（从 1 开始）。
     * 用来判定 `addView→onAttachedToWindow` 那几百毫秒是**每次 addView 都付**的固有开销，
     * 还是只有首次（WMS 为进程建立窗口/Surface）才付 —— 前者只能靠复用同一个窗口消除，
     * 后者可以靠预热空窗口消除。判据：连续开两次面板，比较两次的 seq 与 attach 耗时。
     */
    private val seq = ++windowSeq

    /** 分段耗时（相对 addView 时刻）；该阶段未发生返回 -1。 */
    private fun elapsed(at: Long): Long = if (at == 0L) -1L else at - showAt

    /**
     * @author bomo 是否等首屏数据到位再放行首帧（默认关闭）。
     * 数据由 SearchHelper 异步加载，放行过早会先出现一张空壳面板再填入条目 —— 表现为"闪一下白/空"。
     * 开启后门闩会额外等数据，最多 [WAIT_DATA_TIMEOUT_MS] 毫秒（超时照常放行，绝不卡住面板）。
     * 调参：`adb shell settings put global flymefreeform_wait_data 1`（下次打开面板生效）。
     */
    private val waitData =
        runCatching {
            Settings.Global.getInt(context.contentResolver, WAIT_DATA_KEY, 0)
        }.getOrDefault(0) == 1

    /** `FLAG_BLUR_BEHIND` 与 `setBlurBehindRadius` 未在公开 SDK 导出，按名字反射获取。 */
    private val blurBehindFlag: Int? =
        runCatching {
            WindowManager.LayoutParams::class.java.getField("FLAG_BLUR_BEHIND").getInt(null)
        }.getOrNull()
    private val blurRadiusSetter: Method? =
        runCatching {
            WindowManager.LayoutParams::class.java
                .getMethod("setBlurBehindRadius", Int::class.javaPrimitiveType)
        }.getOrNull()
    private var backDispatcher: OnBackInvokedDispatcher? = null
    private val backCallback = OnBackInvokedCallback { back() }
    private val animation = Runnable { animateFrame() }
    private val timeout = Runnable { dismiss() }
    /** @author bomo 透明帧未提交时仅清理，不让取屏工具截到模块内容。 */
    private val hiddenFrameTimeout = Runnable { dispose() }
    private var toolSelectedAt = 0L
    private val firstFrame = ViewTreeObserver.OnPreDrawListener { prepareFirstFrame() }
    private var waitingForFirstFrame = true
    private val root = object : FrameLayout(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (exiting || entering) return true
            resetTimeout()
            return super.dispatchTouchEvent(event)
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) back()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (attachedAt == 0L) attachedAt = SystemClock.uptimeMillis()
            backDispatcher = findOnBackInvokedDispatcher()?.also {
                it.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback)
            }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (measuredAt == 0L) measuredAt = SystemClock.uptimeMillis()
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            if (laidOutAt == 0L && right > left && bottom > top) laidOutAt = SystemClock.uptimeMillis()
        }

        override fun onDetachedFromWindow() {
            backDispatcher?.unregisterOnBackInvokedCallback(backCallback)
            backDispatcher = null
            super.onDetachedFromWindow()
            if (!closed) dispose()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            placeCard()
        }
    }

    init {
        root.setBackgroundColor(0)
        root.isFocusableInTouchMode = true
        root.clipChildren = false
        card.alpha = 0f
        card.visibility = View.INVISIBLE
        content.view.alpha = 0f
        card.isClickable = true
        val metrics = manager.currentWindowMetrics
        displayWidth = metrics.bounds.width()
        displayHeight = metrics.bounds.height()
        val safe =
            metrics.windowInsets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
        displaySafe = intArrayOf(safe.left, safe.top, safe.right, safe.bottom)
        val initial = AllAppsPanelGeometry.calculate(
            displayWidth, displayHeight, dimensions, mode, leftSide,
            safe.left, safe.top, safe.right, safe.bottom,
        )
        val scaled = scaledBounds(initial)
        // 窗口收缩成面板大小：窗口模糊才会只作用于面板背后，而不是整屏。
        // 面板入场/退场缩放范围为 0.3~1.0，不会超出窗口被裁切。
        windowParams = WindowManager.LayoutParams(
            scaled.width, scaled.height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = scaled.left
            y = scaled.top
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            setFitInsetsTypes(0)
            title = "FlymeFreeformAllApps"
            // @author bomo 不再在 addView 时预设窗口级模糊。
            // 实测（0.2.14 埋点）：窗口 addView 到首帧绘制完成有约 467ms 空窗期，
            // 期间 FLAG_BLUR_BEHIND 已生效而内容为空 → 合成器把空窗口画成白底，即「唤起闪白」。
            // 改为首帧放行、内容就位后，仅在平台玻璃附着失败时才补开窗口模糊（见 enableWindowBlur）。
        }
        // @author bomo 面板整体缩小：内容必须按原生尺寸布局（网格条目是固定 dp，直接缩窗会挤压变形），
        // 卡片保持原生 LayoutParams，再由 applyBaseTransform() 等比缩放贴满缩小后的窗口。
        root.addView(card, FrameLayout.LayoutParams(initial.width, initial.height))
        root.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) dismiss()
            true
        }
        root.setOnApplyWindowInsetsListener { _, value ->
            insets = value
            placeCard()
            value
        }
        content.bindClose { dismiss() }
    }

    fun show() {
        check(!attached && !closed)
        val params = windowParams ?: error("ALL_APPS_WINDOW_PARAMS_UNAVAILABLE")
        showAt = SystemClock.uptimeMillis()
        gateFrames = 0
        gateReasons.clear()
        manager.addView(root, params)
        addViewDoneAt = SystemClock.uptimeMillis()
        attached = true
        root.viewTreeObserver.addOnPreDrawListener(firstFrame)
        root.requestApplyInsets()
        root.requestFocus()
    }

    private fun prepareFirstFrame(): Boolean {
        if (closed) return true
        if (!waitingForFirstFrame) return true
        gateFrames++
        // @author bomo 每帧重新评估当前阻塞条件，已恢复的布局/数据不能永久卡住首帧。
        gateReasons.clear()
        return try {
            // 卡片按原生面板尺寸（自身 LayoutParams）布局完成即就绪；窗口已缩小，不能再与窗口尺寸比较。
            val cardParams = card.layoutParams as FrameLayout.LayoutParams
            // @author bomo 逐项收集未就绪原因：放行时一次打印，
            // 即可区分「窗口布局没好」「数据没到」「模糊没附着」三类空白。
            if (root.width <= 0 || root.height <= 0) gateReasons += "rootSize"
            if (insets == null) gateReasons += "insets"
            if (card.isLayoutRequested || card.width != cardParams.width || card.height != cardParams.height) {
                gateReasons += "cardLayout"
            }
            val items = content.itemCount()
            val dataTimedOut = SystemClock.uptimeMillis() - showAt >= WAIT_DATA_TIMEOUT_MS
            if (waitData && items <= 0 && !dataTimedOut) gateReasons += "noData"
            if (!content.attachBlur()) gateReasons += "blur"
            if (gateReasons.isNotEmpty()) return false
            // 平台玻璃附着失败才补开窗口级模糊；此时内容已就位，不会出现空窗口白底。
            if (!content.platformGlass) enableWindowBlur()
            // @author bomo 首帧放行判据：frames=1 说明窗口首帧就位；ms 即 addView→首帧的空窗期，
            // 是面板唤起延迟的主要构成。windowBlur 指放行后是否会启用窗口级模糊。
            log(
                Log.INFO,
                "FIRST_FRAME_RELEASED seq=$seq ms=${elapsed(SystemClock.uptimeMillis())} addView=${elapsed(addViewDoneAt)} " +
                    "attach=${elapsed(attachedAt)} measure=${elapsed(measuredAt)} layout=${elapsed(laidOutAt)} " +
                    "frames=$gateFrames glass=${content.platformGlass} items=$items waitData=$waitData",
                null,
            )
            applyBaseTransform()
            val initial = AllAppsPanelMotion.enter(0f)
            card.scaleX = initial.scale * panelScale
            card.scaleY = initial.scale * panelScale
            content.view.alpha = initial.alpha
            card.alpha = 1f
            card.visibility = View.VISIBLE
            waitingForFirstFrame = false
            root.viewTreeObserver.removeOnPreDrawListener(firstFrame)
            startAnimation()
            resetTimeout()
            true
        } catch (exception: Exception) {
            onFailure(exception)
            dispose()
            true
        }
    }

    fun isShown(): Boolean = attached && !closed && !exiting && !entering &&
        root.isShown && card.width > 0 && content.view.childCount > 0

    fun beginItemClick(afterHidden: (() -> Unit)? = null): Boolean {
        if (!isShown()) return false
        hiddenAction = afterHidden
        if (afterHidden != null) toolSelectedAt = SystemClock.uptimeMillis()
        dismiss()
        return true
    }

    fun dismiss() {
        if (closed || exiting) return
        exiting = true
        entering = false
        onExitStarted()
        root.removeCallbacks(timeout)
        if (waitingForFirstFrame) {
            dispose()
            return
        }
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(root.windowToken, 0)
        // @author bomo 工具交接直接提交不可见帧；普通关闭仍保留原退场动画。
        if (hiddenAction != null) finishExit() else startAnimation()
    }

    fun dispose() {
        if (closed) return
        closed = true
        if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(firstFrame)
        hiddenAction = null
        root.removeCallbacks(animation)
        root.removeCallbacks(timeout)
        root.removeCallbacks(hiddenFrameTimeout)
        try {
            content.close()
        } catch (exception: Exception) {
            onFailure(exception)
        } finally {
            if (attached) {
                attached = false
                try {
                    manager.removeViewImmediate(root)
                } catch (_: IllegalArgumentException) {
                    // 系统已移除窗口。
                }
            }
            root.removeAllViews()
            onClosed()
        }
    }

    private fun back() = safely {
        if (!exiting && !content.leaveSearch()) dismiss()
    }

    /** 窗口本身即面板，尺寸/位置直接写在窗口参数上；卡片始终按原生面板尺寸布局。 */
    private fun placeCard() {
        if (closed || displayWidth <= 0 || displayHeight <= 0) return
        val ime = insets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
        val native = AllAppsPanelGeometry.calculate(
            displayWidth, displayHeight, dimensions, mode, leftSide,
            displaySafe[0], displaySafe[1], displaySafe[2], maxOf(displaySafe[3], ime),
        )
        val bounds = scaledBounds(native)
        // @author bomo 锚定与缩放的唯一可观测证据：几何算错时窗口位置会明显不对，
        // 打这一行就能区分「方位没传到」还是「贴边距算错」。
        // ⚠️ 必须放在下面的「参数未变化就提前 return」之前 —— 0.1.18 曾放在其后，
        // 结果一次都没打出来（参数与 init 算出的相同 → 提前 return）。
        log(
            Log.INFO,
            "PANEL_BOUNDS left=${bounds.left} top=${bounds.top} " +
                "${bounds.width}x${bounds.height} leftSide=$leftSide scale=$panelScale",
            null,
        )
        val cardParams = card.layoutParams as? FrameLayout.LayoutParams
        if (cardParams != null && (cardParams.width != native.width || cardParams.height != native.height)) {
            cardParams.width = native.width
            cardParams.height = native.height
            card.layoutParams = cardParams
        }
        val params = windowParams ?: return
        if (params.width == bounds.width && params.height == bounds.height &&
            params.x == bounds.left && params.y == bounds.top
        ) return
        params.width = bounds.width
        params.height = bounds.height
        params.x = bounds.left
        params.y = bounds.top
        if (attached) manager.updateViewLayout(root, params)
    }

    /**
     * @author bomo 「全部」面板整体缩小的边界换算。
     *
     * **关键：缩放后必须保留「被锚定那一侧」的贴边距，而不是固定左边缘坐标。**
     * 原生面板宽度几乎满屏（PLZ110 上 1104 / 屏宽 1216 = 90.8%，恰好等于
     * `屏宽 − 2×minMargin`），于是 `left = if (leftSide) gap else 屏宽 − gap − 面板宽`
     * 两种算法**算出的 left 几乎相同**（都 ≈ 56），原生面板本来就是左右对称居中的。
     * 因此缩小后若沿用 `native.left`，无论从哪侧呼出都会落到左边
     * （0.1.16 / 0.1.17 的实际表现）。
     *
     * 修法：左侧呼出对齐原生盒的左边缘，右侧呼出对齐原生盒的**右边缘**。
     *
     * 垂直方向：竖屏在原生盒内居中，其余模式保持原顶部锚定。
     */
    private fun scaledBounds(native: AllAppsPanelGeometry.Bounds): AllAppsPanelGeometry.Bounds {
        val width = (native.width * panelScale).toInt().coerceAtLeast(1)
        val height = (native.height * panelScale).toInt().coerceAtLeast(1)
        val left =
            if (leftSide) {
                native.left
            } else {
                native.left + native.width - width
            }
        val top =
            if (mode == AllAppsPanelGeometry.Mode.Portrait) {
                native.top + (native.height - height) / 2
            } else {
                native.top
            }
        return AllAppsPanelGeometry.Bounds(left, top, width, height)
    }

    /**
     * @author bomo 基础缩放变换：卡片按原生尺寸布局，围绕中心缩放并做平移补偿，
     * 使缩放后的可视区域恰好铺满缩小后的窗口（入场/退场动画在此基础上继续乘动画比例）。
     */
    private fun applyBaseTransform() {
        card.pivotX = card.width / 2f
        card.pivotY = card.height / 2f
        card.translationX = -card.width * (1f - panelScale) / 2f
        card.translationY = -card.height * (1f - panelScale) / 2f
    }

    private var windowBlurEnabled = false

    /**
     * @author bomo 仅在平台玻璃附着失败时才启用窗口级模糊，且**必须在首帧内容绘制完成后**再开：
     * FLAG_BLUR_BEHIND 若在 addView 时就带在窗口参数里，窗口会先于内容进入合成器，
     * 空窗期（实测 addView→首帧约 467ms）被合成器画成白底 —— 这正是「面板唤起闪白」。
     * 首帧放行后内容已就位，此时补开模糊不会出现空白帧（只执行一次）。
     */
    private fun enableWindowBlur() {
        if (windowBlurEnabled) return
        windowBlurEnabled = true
        val flag = blurBehindFlag ?: return
        val params = windowParams ?: return
        if (params.flags and flag != 0) return
        params.flags = params.flags or flag
        blurRadiusSetter?.let { setter ->
            runCatching { setter.invoke(params, tunedInt(BLUR_RADIUS_KEY, BLUR_BEHIND_RADIUS_PX)) }
        }
        if (attached) runCatching { manager.updateViewLayout(root, params) }
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

    private fun animateFrame(): Unit = safely {
        if (closed) return@safely
        val elapsed = (SystemClock.uptimeMillis() - animationStart) / durationScale
        val frame = if (exiting) AllAppsPanelMotion.exit(elapsed / 1_000f) else AllAppsPanelMotion.enter(elapsed / 1_000f)
        // 材质底色不参与入场渐变，只有内容淡入；避免暗色桌面透入后再变亮。
        card.alpha = if (exiting) frame.alpha else 1f
        content.view.alpha = if (exiting) 1f else frame.alpha
        applyBaseTransform()
        card.scaleX = frame.scale * panelScale
        card.scaleY = frame.scale * panelScale
        content.updateBlur(if (exiting) frame.alpha else 1f)
        if (exiting && (frame.alpha <= AllAppsPanelMotion.EXIT_ALPHA_THRESHOLD || elapsed >= AllAppsPanelMotion.MAX_DURATION_MS)) {
            finishExit()
        } else if (!exiting && ((frame.alpha >= 0.997f && frame.scale >= 0.997f) || elapsed >= AllAppsPanelMotion.MAX_DURATION_MS)) {
            finishEnter()
        } else {
            root.postOnAnimation(animation)
        }
    }

    private fun finishEnter() {
        entering = false
        card.alpha = 1f
        content.view.alpha = 1f
        card.scaleX = panelScale
        card.scaleY = panelScale
        content.updateBlur(1f)
        // @author bomo 入场动画收尾时刻：与 FIRST_FRAME_RELEASED 的 ms 相减即入场动画实际耗时，
        // 用于判断"白/空"是被门闩拖长还是被动画拖长。
        log(Log.INFO, "PANEL_ENTER_DONE ms=${SystemClock.uptimeMillis() - showAt}", null)
        if (!notified) {
            notified = true
            onShown()
        }
    }

    private fun finishExit() {
        card.alpha = 0f
        root.setBackgroundColor(0)
        content.updateBlur(0f)
        val action = hiddenAction
        hiddenAction = null
        if (action == null) {
            dispose()
            return
        }
        // 先提交不可见帧，再执行原厂工具；短暂保留窗口所有权供系统判断前台发起者。
        root.postDelayed(hiddenFrameTimeout, SidebarProtocol.CLEANUP_TIMEOUT_MS)
        val dispatch = Runnable {
            safely {
                root.removeCallbacks(hiddenFrameTimeout)
                if (closed) return@safely
                beforeTool {
                    safely {
                        if (closed) return@safely
                        log(Log.INFO, "TOOL_PANEL_HIDDEN ms=${SystemClock.uptimeMillis() - toolSelectedAt}", null)
                        action()
                        root.postOnAnimation { if (!closed) dispose() }
                    }
                }
            }
        }
        // @author bomo postOnAnimation 发生于绘制前；硬件渲染必须确认提交后才调用工具。
        if (root.isHardwareAccelerated) {
            root.viewTreeObserver.registerFrameCommitCallback { root.post(dispatch) }
            root.invalidate()
        } else {
            root.postOnAnimation { root.postOnAnimation(dispatch) }
        }
    }

    private fun resetTimeout() {
        root.removeCallbacks(timeout)
        root.postDelayed(timeout, 120_000L)
    }

    private inline fun safely(action: () -> Unit) {
        try {
            action()
        } catch (exception: Exception) {
            onFailure(exception)
            dispose()
        }
    }

    /**
     * 读调参覆盖值。改 `Settings.Global` 后下次打开面板即生效，无需重编译或重启：
     * `adb shell settings put global flymefreeform_blur_radius 200`
     */
    private fun tunedInt(key: String, fallback: Int): Int =
        runCatching {
            Settings.Global.getInt(context.contentResolver, key, fallback)
        }.getOrDefault(fallback)

    private companion object {
        /** @author bomo 本进程已创建的面板窗口数；仅供首帧埋点打序号用。 */
        var windowSeq = 0

        /** 窗口模糊回退路径的模糊半径（像素）默认值；对齐原生 integer/edit_panel_platform_blur_radius=300。 */
        const val BLUR_BEHIND_RADIUS_PX = 300
        const val BLUR_RADIUS_KEY = "flymefreeform_blur_radius"

        /**
         * @author bomo 覆盖键（调试用）：`adb shell settings put global flymefreeform_panel_scale 60`。
         * 取值下限与范围统一见 [ModulePreferences.MIN_PANEL_SCALE_PERCENT] /
         * [ModulePreferences.MAX_PANEL_SCALE_PERCENT]，不再在本类重复定义。
         */
        const val PANEL_SCALE_KEY = "flymefreeform_panel_scale"

        /** @author bomo 首帧门闩是否等首屏数据（1=等）；诊断/对照用。 */
        const val WAIT_DATA_KEY = "flymefreeform_wait_data"

        /** 等数据的最长时间；超时照常放行，面板绝不会被数据加载卡住。 */
        const val WAIT_DATA_TIMEOUT_MS = 400L
    }
}
