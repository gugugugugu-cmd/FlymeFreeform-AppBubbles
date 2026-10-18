package io.github.mangi.flymefreeform.platform.coloros

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import java.lang.reflect.Method

/**
 * 「全部」面板右侧的 A-Z 字母索引条。
 * @author bomo 0.2.23：按实际呼出侧镜像布局；以下右缘/左侧气泡说明为右侧呼出的默认路径，左侧呼出时相反。
 *
 * 交互对齐 Flyme 原生实现：细条贴右缘、常驻完整 A-Z + '#'（无应用的字母置灰、
 * 触摸仍浮出气泡但不跳转），滑动时列表即时跳到对应字母分组开头，
 * 同时在索引条左侧浮出圆形浅灰大字母气泡，当前字母在条内带圆形高亮底。
 *
 * 数据来自原生 `SearchHelper.getCurrentDataList()`：列表经 [ColorOsAlphabetGrouper]
 * 改写为字母分组结构后，索引直接读 `ff_letter_` 标记项的位置；分组不可用（应用太少 /
 * 钩子缺失）时回退到旧的拼音首字母扫描，行为与 0.2.11 一致。
 *
 * 不直接依赖 androidx.recyclerview 编译期符号；RecyclerView 相关调用走反射，
 * 因为本模块的 compile classpath 不含 RecyclerView，但 hook 侧 `smartsidebar:ui`
 * 进程的运行期类加载器有该类。
 *
 * @author bomo
 * @param context 侧边栏主题 Context
 * @param loader 侧边栏 ClassLoader
 * @param log 诊断日志出口
 */
internal class ColorOsAlphabetIndex(
    private val context: Context,
    private val loader: ClassLoader,
    private val log: (Int, String, Throwable?) -> Unit,
) {
    private val dataClass = runCatching { loader.loadClass(DATA_CLASS) }.getOrNull()
    private val getEntry: Method? = runCatching { dataClass?.getMethod("getEntryBean") }.getOrNull()
    private val getLabel: Method? = runCatching {
        getEntry?.returnType?.getMethod("getLabel")
    }.getOrNull()
    private val getType: Method? = runCatching {
        getEntry?.returnType?.getMethod("getType")
    }.getOrNull()

    // @author bomo 正常分组路径直接读取标题标记，只有拼音回退路径才创建 ICU 转换器。
    private val transliterator by lazy {
        runCatching { android.icu.text.Transliterator.getInstance("Han-Latin") }.getOrNull()
    }

    /** RecyclerView 类引用（延迟加载），用于 isInstance 判断以覆盖子类。 */
    private val recyclerViewClass: Class<*>? by lazy {
        runCatching { loader.loadClass("androidx.recyclerview.widget.RecyclerView") }.getOrNull()
    }

    /** RecyclerView 的 `getLayoutManager()` 反射方法；延迟初始化。 */
    private val getLayoutManagerMethod: Method? by lazy {
        runCatching { recyclerViewClass?.getMethod("getLayoutManager") }.getOrNull()
    }

    /** RecyclerView 的 `scrollToPosition(Int)` 反射方法（即时跳转回退）；延迟初始化。 */
    private val scrollToMethod: Method? by lazy {
        runCatching {
            recyclerViewClass?.getMethod("scrollToPosition", Int::class.javaPrimitiveType)
        }.getOrNull()
    }

    /** RecyclerView 的 `smoothScrollToPosition(Int)` 反射方法（最终回退）；延迟初始化。 */
    private val smoothScrollMethod: Method? by lazy {
        runCatching {
            recyclerViewClass?.getMethod("smoothScrollToPosition", Int::class.javaPrimitiveType)
        }.getOrNull()
    }

    private var bar: AlphabetIndexBar? = null
    private var popup: TextView? = null
    private var recyclerView: View? = null
    private var attached = false
    private var leftSide = false
    private var originalListPadding: Rect? = null
    private var hostCard: FrameLayout? = null
    private var searchAnchor: View? = null
    private val placementListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePlacement() }

    /** 应用条目数少于此阈值时不显示索引条（与分组器阈值一致）。 */
    private val minAppCount = ColorOsAlphabetGrouper.MIN_GROUP_APP_COUNT

    /**
     * 把索引条挂载到卡片容器。
     *
     * @param card 模块自建面板卡片（FrameLayout）
     * @param panelView 原厂 AllAppPanelView 根视图
     * @param dataList 当前数据列表（来自 SearchHelper.getCurrentDataList()）
     * @param leftSide 实际从左下角呼出时为 true；使用物理左右，不随文字排版方向翻转。
     * @return 是否成功挂载
     */
    fun attach(card: FrameLayout, panelView: ViewGroup, dataList: List<*>, leftSide: Boolean): Boolean {
        if (attached) return true
        val recycler = findRecyclerView(panelView) ?: run {
            log(Log.WARN, "ALPHABET_INDEX_NO_RECYCLER", null)
            return false
        }
        recyclerView = recycler
        val letterMap = buildLetterMap(dataList)
        if (letterMap.size < 2) {
            log(Log.INFO, "ALPHABET_INDEX_TOO_FEW letters=${letterMap.size}", null)
            return false
        }
        this.leftSide = leftSide
        val barView = AlphabetIndexBar(context).apply {
            // Flyme 风格：常驻完整字母表，无应用的字母置灰。
            this.letters = ALL_LETTERS
            this.enabledLetters = letterMap.keys
            this.onLetterTouch = cb@{ letter, slotCenterY ->
                val p = popup ?: return@cb
                showPopup(p, letter, card, this, slotCenterY)
                scrollToLetter(letter, letterMap)
            }
            this.onTouchEnd = { popup?.visibility = View.GONE }
        }
        val params = FrameLayout.LayoutParams(dp(BAR_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = (if (leftSide) Gravity.LEFT else Gravity.RIGHT) or Gravity.TOP
            leftMargin = if (leftSide) dp(BAR_MARGIN_DP) else 0
            rightMargin = if (leftSide) 0 else dp(BAR_MARGIN_DP)
        }
        barView.visibility = View.INVISIBLE
        card.addView(barView, params)
        bar = barView
        hostCard = card
        searchAnchor = findByIdName(panelView, SEARCH_VIEW_ID)
        card.addOnLayoutChangeListener(placementListener)
        searchAnchor?.addOnLayoutChangeListener(placementListener)

        // Flyme 风格圆形大字气泡：初始隐藏，触摸时在触点字母高度的左侧浮出。
        val popupView = TextView(context).apply {
            visibility = View.GONE
            setTextColor(POPUP_TEXT_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, POPUP_TEXT_SP)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(POPUP_BG_COLOR)
            }
            val size = dp(POPUP_SIZE_DP)
            val popupParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.LEFT or Gravity.TOP
            }
            card.addView(this, popupParams)
        }
        popup = popupView
        // @author bomo 左缘原本紧邻标题和第一列；用内容留白避让，保留原厂相对marginStart及其他布局参数。
        // @author bomo 左右索引使用相同的内容边缘间距，按当前物理侧扣除原厂margin和padding后补齐。
        originalListPadding = Rect(
            recycler.paddingLeft,
            recycler.paddingTop,
            recycler.paddingRight,
            recycler.paddingBottom,
        )
        val listMargins = recycler.layoutParams as? ViewGroup.MarginLayoutParams
        val sideMargin = (if (leftSide) listMargins?.leftMargin else listMargins?.rightMargin)
            ?.coerceAtLeast(0) ?: 0
        val sidePadding = if (leftSide) recycler.paddingLeft else recycler.paddingRight
        val addedInset = (dp(INDEX_CONTENT_EDGE_INSET_DP) - sideMargin - sidePadding)
            .coerceAtLeast(0)
        if (leftSide) {
            recycler.setPadding(
                recycler.paddingLeft + addedInset,
                recycler.paddingTop,
                recycler.paddingRight,
                recycler.paddingBottom,
            )
        } else {
            recycler.setPadding(
                recycler.paddingLeft,
                recycler.paddingTop,
                recycler.paddingRight + addedInset,
                recycler.paddingBottom,
            )
        }
        attached = true
        updatePlacement()
        log(Log.INFO, "ALPHABET_INDEX_ATTACHED letters=${letterMap.size} side=${if (leftSide) "left" else "right"}", null)
        return true
    }

    /** 面板关闭时清理。 */
    fun detach() {
        hostCard?.removeOnLayoutChangeListener(placementListener)
        searchAnchor?.removeOnLayoutChangeListener(placementListener)
        bar?.let { (it.parent as? ViewGroup)?.removeView(it) }
        popup?.let { (it.parent as? ViewGroup)?.removeView(it) }
        bar = null
        popup = null
        // @author bomo 注销/重新挂载时归还原厂内边距，防止反复挂载叠加左侧留白。
        originalListPadding?.let { original ->
            recyclerView?.setPadding(original.left, original.top, original.right, original.bottom)
        }
        originalListPadding = null
        recyclerView = null
        attached = false
        hostCard = null
        searchAnchor = null
    }

    /** 数据刷新后重建索引（包安装/卸载触发）。 */
    fun rebuild(dataList: List<*>) {
        if (!attached) return
        detach()
        // 调用方会重新 attach
    }

    // ── 内部实现 ──

    /** @author bomo 按实际搜索框底部定位索引；空间不足收缩字母槽位，锚点缺失时不覆盖搜索区域。 */
    private fun updatePlacement() {
        val card = hostCard ?: return
        val barView = bar ?: return
        val search = searchAnchor
        if (card.height <= 0 || search == null || !search.isLaidOut) {
            barView.visibility = View.INVISIBLE
            return
        }
        val bounds = Rect()
        search.getDrawingRect(bounds)
        card.offsetDescendantRectToMyCoords(search, bounds)
        val placement = AlphabetIndexBounds.calculate(
            card.height, bounds.bottom, barView.preferredHeight(), dp(SEARCH_GAP_DP), dp(BOTTOM_MARGIN_DP),
        )
        val params = barView.layoutParams as FrameLayout.LayoutParams
        if (params.topMargin != placement.top || params.height != placement.height) {
            params.topMargin = placement.top
            params.height = placement.height
            barView.layoutParams = params
            log(Log.INFO, "ALPHABET_INDEX_BOUNDS searchBottom=${bounds.bottom} top=${placement.top} height=${placement.height} cardHeight=${card.height}", null)
        }
        barView.visibility = if (placement.height > 0) View.VISIBLE else View.INVISIBLE
    }

    private fun findRecyclerView(panelView: ViewGroup): View? {
        // 优先按资源 id 直查（与 ColorOsRecentSection 同一模式）。
        val id = resourceId(APP_LIST_ID, "id")
        if (id != 0) {
            val byId = panelView.findViewById<View>(id)
            if (byId != null) {
                log(Log.INFO, "ALPHABET_INDEX_LIST_FOUND class=${byId.javaClass.name}", null)
                if (isRecyclerView(byId)) return byId
            }
        }
        // 兜底：按 id 名递归匹配，再 BFS 找第一个 RecyclerView。
        val byName = findByIdName(panelView, APP_LIST_ID)
        if (byName != null) {
            log(Log.INFO, "ALPHABET_INDEX_LIST_FOUND_BY_NAME class=${byName.javaClass.name}", null)
            if (isRecyclerView(byName)) return byName
        }
        return findFirstRecyclerView(panelView)
    }

    private fun isRecyclerView(view: View): Boolean =
        recyclerViewClass?.isInstance(view) == true

    @Suppress("DiscouragedApi")
    private fun resourceId(name: String, type: String): Int =
        runCatching {
            context.resources.getIdentifier(name, type, ColorOsSidebarTarget.PACKAGE_NAME)
        }.getOrDefault(0)

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

    private fun findFirstRecyclerView(node: View): View? {
        if (isRecyclerView(node)) return node
        if (node is ViewGroup) {
            for (index in 0 until node.childCount) {
                findFirstRecyclerView(node.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    /**
     * 构建 字母 → 跳转目标 adapter 位置 的映射。
     *
     * 优先读 [ColorOsAlphabetGrouper] 插入的 `ff_letter_` 分组标题标记
     * （位置即分组开头，跳转后标题正好顶在列表上沿）；
     * 列表未被分组（应用太少 / 分组钩子不可用）时回退到拼音首字母扫描。
     * 只统计应用条目（type != 0），跳过工具与区块头。
     */
    private fun buildLetterMap(dataList: List<*>): Map<Char, Int> {
        val markerMap = sortedMapOf<Char, Int>()
        var appCount = 0
        val dc = dataClass
        for ((index, item) in dataList.withIndex()) {
            if (item == null) continue
            if (dc?.isInstance(item) == true) appCount++
            // 标记项是 TitleLabelData 精确类型（AppLabelData 是其 final 子类，按类名区分）。
            if (item.javaClass.name == TITLE_CLASS) {
                val key = runCatching {
                    item.javaClass.getMethod("getKey").invoke(item) as? String
                }.getOrNull() ?: continue
                if (key.startsWith(ColorOsAlphabetGrouper.HEADER_KEY_PREFIX)) {
                    val letter = key.removePrefix(ColorOsAlphabetGrouper.HEADER_KEY_PREFIX).firstOrNull()
                        ?: continue
                    markerMap.putIfAbsent(letter, index)
                }
            }
        }
        if (markerMap.isNotEmpty()) {
            return if (appCount >= minAppCount) markerMap else emptyMap()
        }
        return buildLetterMapByPinyin(dataList)
    }

    /** 回退路径：列表未分组时按拼音首字母扫描首个应用位置（0.2.11 行为）。 */
    private fun buildLetterMapByPinyin(dataList: List<*>): Map<Char, Int> {
        val map = sortedMapOf<Char, Int>()
        val dc = dataClass ?: return map
        val ge = getEntry ?: return map
        val gl = getLabel ?: return map
        val gt = getType ?: return map
        var appCount = 0
        for ((index, item) in dataList.withIndex()) {
            if (item == null || !dc.isInstance(item)) continue
            val entry = runCatching { ge.invoke(item) }.getOrNull() ?: continue
            val type = runCatching { gt.invoke(entry) as? Int }.getOrNull() ?: continue
            if (type == 0) continue // 工具
            appCount++
            val label = runCatching { gl.invoke(entry) as? String }.getOrNull() ?: continue
            val letter = firstLetter(label)
            map.putIfAbsent(letter, index)
        }
        if (appCount < minAppCount) return emptyMap()
        return map
    }

    /** 取名称的首字母：英文直取，中文经 ICU 转拼音取首字母，其余归 '#'。 */
    private fun firstLetter(label: String): Char {
        val first = label.firstOrNull() ?: return '#'
        if (first in 'A'..'Z') return first
        if (first in 'a'..'z') return first.uppercaseChar()
        if (first.code in 0x4E00..0x9FFF || first.code in 0x3400..0x4DBF) {
            val pinyin = transliterator?.transliterate(first.toString()) ?: return '#'
            val c = pinyin.firstOrNull() ?: return '#'
            return if (c in 'a'..'z') c.uppercaseChar() else if (c in 'A'..'Z') c else '#'
        }
        return '#'
    }

    /**
     * 跳到目标位置：优先 `LayoutManager.scrollToPositionWithOffset(pos, 0)` 即时定位
     * （Flyme 滑索引条时列表跟随手指即时跳动，smoothScroll 会明显拖尾）；
     * 方法缺失时依次回退 scrollToPosition / smoothScrollToPosition。
     */
    private fun scrollToLetter(letter: Char, letterMap: Map<Char, Int>) {
        val position = letterMap[letter] ?: return
        val recycler = recyclerView ?: return
        runCatching {
            val layoutManager = getLayoutManagerMethod?.invoke(recycler)
            val withOffset = layoutManager?.javaClass?.let { clazz ->
                runCatching {
                    clazz.getMethod(
                        "scrollToPositionWithOffset",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    )
                }.getOrNull()
            }
            when {
                withOffset != null -> withOffset.invoke(layoutManager, position, 0)
                else -> {
                    val direct = scrollToMethod
                    val smooth = smoothScrollMethod
                    when {
                        direct != null -> direct.invoke(recycler, position)
                        smooth != null -> smooth.invoke(recycler, position)
                    }
                }
            }
        }
    }

    /**
     * 在触点字母高度、索引条左侧浮出圆形气泡。
     *
     * @param slotCenterY 命中字母槽位中心在**索引条视图**内的 Y 坐标；
     * 气泡是 card 的子视图，须换算成 card 坐标（修正 0.2.11 直接用条内坐标的错位）。
     */
    private fun showPopup(popup: TextView, letter: Char, card: FrameLayout, barView: View, slotCenterY: Float) {
        popup.text = letter.toString()
        popup.visibility = View.VISIBLE
        val size = dp(POPUP_SIZE_DP)
        val params = popup.layoutParams as FrameLayout.LayoutParams
        val cardY = barView.top + slotCenterY
        params.topMargin = (cardY - size / 2).toInt().coerceIn(0, (card.height - size).coerceAtLeast(0))
        // @author bomo 气泡永远朝卡片内侧浮出，按索引真实位置计算，短卡片也不越界。
        params.leftMargin = (if (leftSide) barView.right + dp(POPUP_GAP_DP)
            else barView.left - size - dp(POPUP_GAP_DP))
            .coerceIn(0, (card.width - size).coerceAtLeast(0))
        params.rightMargin = 0
        popup.layoutParams = params
    }

    private fun dp(value: Float): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun dp(value: Int): Int = dp(value.toFloat())

    // ── 索引条视图 ──

    private class AlphabetIndexBar(context: Context) : View(context) {
        var letters: List<Char> = emptyList()
            set(value) { field = value; requestLayout(); invalidate() }

        /** 有应用分组的字母；其余置灰、触摸不跳转（气泡仍浮出，与 Flyme 一致）。 */
        var enabledLetters: Set<Char> = emptySet()
            set(value) { field = value; invalidate() }

        /** 回调参数：字母 + 槽位中心在本视图内的 Y 坐标。 */
        var onLetterTouch: ((Char, Float) -> Unit)? = null
        var onTouchEnd: (() -> Unit)? = null

        private var activeIndex = -1
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT
        }
        private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ACTIVE_BG_COLOR
            style = Paint.Style.FILL
        }
        private val textBounds = Rect()

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = dp(BAR_WIDTH_DP)
            val perLetter = dp(PER_LETTER_DP)
            val h = (letters.size * perLetter + dp(VERTICAL_PADDING_DP) * 2)
                .coerceAtLeast(perLetter)
            setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
        }

        /** @author bomo 返回正常字母间距下的理想高度；外部布局可约束到搜索框下方的剩余空间。 */
        fun preferredHeight(): Int = letters.size * dp(PER_LETTER_DP) + dp(VERTICAL_PADDING_DP) * 2

        /** @author bomo 小高度时缩小上下内边距，为所有字母保留正的槽位高度。 */
        private fun verticalPadding(): Float = dp(VERTICAL_PADDING_DP).toFloat().coerceAtMost(height / 4f)

        /** @author bomo 绘制与命中检测共用实际槽位高度，避免收缩后触点和字母错位。 */
        private fun slotHeight(): Float = if (letters.isEmpty()) 0f else (height - 2f * verticalPadding()) / letters.size

        /** 槽位中心在本视图内的 Y 坐标。 */
        private fun slotCenter(index: Int): Float =
            verticalPadding() + (index + 0.5f) * slotHeight()

        override fun onDraw(canvas: Canvas) {
            if (letters.isEmpty()) return
            val centerX = width / 2f
            for ((index, letter) in letters.withIndex()) {
                val y = slotCenter(index)
                val active = index == activeIndex
                val enabled = enabledLetters.contains(letter)
                if (active) {
                    // Flyme 风格：当前字母带圆形高亮底。
                    canvas.drawCircle(centerX, y, slotHeight() * ACTIVE_BG_RATIO, highlightPaint)
                }
                paint.textSize = (if (active) sp(ACTIVE_TEXT_SP) else sp(TEXT_SP)).coerceAtMost(slotHeight() * 0.8f)
                paint.color = when {
                    active -> ACTIVE_COLOR
                    enabled -> NORMAL_COLOR
                    else -> DISABLED_COLOR
                }
                paint.isFakeBoldText = active
                paint.getTextBounds(letter.toString(), 0, 1, textBounds)
                val baseline = y + textBounds.height() / 2f
                canvas.drawText(letter.toString(), centerX, baseline, paint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (letters.isEmpty() || slotHeight() <= 0f) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val perLetter = slotHeight()
                    val startY = verticalPadding()
                    val index = ((event.y - startY) / perLetter).toInt()
                        .coerceIn(0, letters.size - 1)
                    if (index != activeIndex && index in letters.indices) {
                        activeIndex = index
                        // @author bomo 每次进入新字母轻震一次；遵循系统触感设置，同槽移动和抬手不重复震动。
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        invalidate()
                        onLetterTouch?.invoke(letters[index], slotCenter(index))
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    activeIndex = -1
                    invalidate()
                    onTouchEnd?.invoke()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun dp(value: Float): Int =
            (value * resources.displayMetrics.density).toInt()
        private fun dp(value: Int): Int = dp(value.toFloat())
        private fun sp(value: Float): Float =
            value * resources.displayMetrics.scaledDensity

        companion object {
            const val TEXT_SP = 10f
            const val ACTIVE_TEXT_SP = 12f
            const val PER_LETTER_DP = 16f
            const val VERTICAL_PADDING_DP = 4f

            /** 当前字母圆形高亮底的半径（占槽位高度比例）。 */
            const val ACTIVE_BG_RATIO = 0.44f
            val NORMAL_COLOR = Color.parseColor("#99000000")
            val DISABLED_COLOR = Color.parseColor("#40000000")
            val ACTIVE_COLOR = Color.parseColor("#DE000000")
            val ACTIVE_BG_COLOR = Color.parseColor("#1F000000")
        }
    }

    companion object {
        const val DATA_CLASS = "com.oplus.smartsidebar.panelview.edgepanel.data.AppLabelData"

        /** @author bomo TitleLabelData 全限定名；分组标题标记项按精确类名识别。 */
        const val TITLE_CLASS = "com.oplus.smartsidebar.panelview.edgepanel.data.TitleLabelData"

        const val APP_LIST_ID = "app_list"
        /** @author bomo 原厂搜索框资源名；索引布局以其底部为上界，随实际尺寸变化。 */
        const val SEARCH_VIEW_ID = "search_view"
        const val SEARCH_GAP_DP = 8
        const val BOTTOM_MARGIN_DP = 8
        const val BAR_WIDTH_DP = 28
        const val BAR_MARGIN_DP = 2

        /** @author bomo 索引与应用列表内容之间的可见安全间隔，按右侧原有边缘留白统一布局。 */
        const val INDEX_CONTENT_GAP_DP = 4

        /** @author bomo 内容区为索引条预留的右侧宽度，包含索引宽度、贴边距和安全间隔；现作为两侧共用边距。 */
        const val INDEX_CONTENT_EDGE_INSET_DP = BAR_WIDTH_DP + BAR_MARGIN_DP + INDEX_CONTENT_GAP_DP

        /** @author bomo Flyme 风格圆形气泡直径与字号。 */
        const val POPUP_SIZE_DP = 56
        const val POPUP_TEXT_SP = 22f
        const val POPUP_GAP_DP = 4

        /** @author bomo 常驻完整字母表：A-Z + '#'（与 Flyme 一致；无应用的字母置灰）。 */
        val ALL_LETTERS: List<Char> = ('A'..'Z').toList() + '#'

        /** @author bomo 最近小窗行右侧预留宽度，保持与下方网格接近的可见范围；左右呼出共用该值。 */
        const val RECENT_SECTION_INDEX_INSET_DP = 24

        /** Flyme 风格气泡：浅灰圆底 + 深灰字母。 */
        val POPUP_BG_COLOR = Color.parseColor("#EDF2F2F2")
        val POPUP_TEXT_COLOR = Color.parseColor("#333333")
    }
}
