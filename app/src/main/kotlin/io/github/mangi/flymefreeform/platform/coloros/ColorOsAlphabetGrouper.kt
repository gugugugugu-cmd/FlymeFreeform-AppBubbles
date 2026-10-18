package io.github.mangi.flymefreeform.platform.coloros

import android.util.Log
import android.os.SystemClock
import android.widget.TextView
import java.lang.reflect.Method
import java.text.Collator
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale

/**
 * 「全部」面板应用段的字母分组器（Flyme 风格 A-Z 分排）。
 *
 * 原厂 `SearchHelper.buildAllList()` 产出的列表结构是
 * `[工具标题, 工具…, (展开/折叠), 应用标题, 应用…]`，应用段虽已按拼音排序但没有分组标题。
 * 本类在数据（重）建后把应用段原地改写为
 * `[…工具区不动…, 应用标题, A, A组应用…, B, B组应用…, …, #, #组应用…]`：
 *
 * - 分组标题复用原厂 `TitleLabelData` + `ViewType.ALL_APP_TABLE_APP`（借「应用」标题的全宽行布局），
 *   key 带 [HEADER_KEY_PREFIX] 前缀作为模块标记；
 * - 排序键为 ICU `Han-Latin` 全拼（转小写、剔除非字母字符），中文多音字取 ICU 默认读音；
 *   非汉字条目按标签本身排序；无法归类进 `#` 组且排在最后；
 * - 工具折叠/展开/退出搜索三条路径都从 `toolNoFoldList` / `toolCollapseList` / `toolExpandList`
 *   拷贝重建 `currentDataList`，因此四个列表一起改写，后续原厂重建天然带着分组；
 * - 搜索态（fuzzyMatch 有词）不动：搜索结果保持扁平。
 *
 * 字母标题的**文本**由 `onBindViewHolder` 钩子按标记改写（见 [bindHeader]）：
 * 原厂 `LabelViewHolder` 不绑定数据，标题布局里的文案是 XML 写死的「应用/工具」，
 * 且同 viewType 共享回收池 —— 非标记的标题项必须显式还原原厂文案，否则回收复用后
 * 原厂「应用」标题会显示成字母。
 *
 * @author bomo
 * @param loader 侧边栏 ClassLoader
 * @param log 诊断日志出口
 */
internal class ColorOsAlphabetGrouper(
    loader: ClassLoader,
    private val log: (Int, String, Throwable?) -> Unit,
) {
    private val titleClass = loader.loadClass(TITLE_CLASS)
    private val appClass = loader.loadClass(ColorOsAlphabetIndex.DATA_CLASS)
    private val viewTypeClass = loader.loadClass(VIEW_TYPE_CLASS)
    private val getText: Method = titleClass.getMethod("getText")
    private val getKey: Method = titleClass.getMethod("getKey")
    private val getViewType: Method = titleClass.getMethod("getViewType")
    private val setViewType: Method = titleClass.getMethod("setViewType", viewTypeClass)
    private val titleCtor = titleClass.getConstructor(CharSequence::class.java, String::class.java)
    private val appTableApp: Any = viewTypeClass.enumConstants.first { (it as Enum<*>).name == "ALL_APP_TABLE_APP" }
    private val appTableTool: Any = viewTypeClass.enumConstants.first { (it as Enum<*>).name == "ALL_APP_TABLE_TOOL" }

    private val transliterator = runCatching {
        android.icu.text.Transliterator.getInstance("Han-Latin")
    }.getOrNull()
    private val collator = Collator.getInstance(Locale.CHINA)

    /** 原厂标题文案缓存（回收池还原用）；按 viewType 惰性解析一次。 */
    private var nativeAppTitle: String? = null
    private var nativeToolTitle: String? = null

    /**
     * 对 SearchHelper 持有的四个列表全部做分组改写（哪个为空跳哪个）。
     * 幂等：已含标记项的列表直接跳过。
     */
    fun regroupAll(search: Any) {
        val startedAt = SystemClock.uptimeMillis()
        // @author bomo 排序比较器不再重复反射标签、反复转拼音；四个列表共享本次转换结果。
        val keys = AlphabetSortKeyCache(::groupLetter, ::sortKey)
        var groupedApps = -1
        var letters = 0
        for (getterName in LIST_GETTERS) {
            val list = runCatching {
                search.javaClass.getMethod(getterName).invoke(search) as? MutableList<Any?>
            }.getOrNull() ?: continue
            if (list.isEmpty()) continue
            val result = regroup(list, keys)
            if (result != null && groupedApps < 0) {
                groupedApps = result.first
                letters = result.second
            }
        }
        if (groupedApps >= 0) {
            log(Log.INFO, "ALPHABET_GROUPED apps=$groupedApps letters=$letters labels=${keys.size} ms=${SystemClock.uptimeMillis() - startedAt}", null)
        }
    }

    /**
     * 分组改写单个列表。返回 (应用数, 字母组数)；跳过（已分组/无应用段/应用太少）返回 null。
     */
    private fun regroup(list: MutableList<Any?>, keys: AlphabetSortKeyCache): Pair<Int, Int>? {
        if (list.any { isLetterHeader(it) }) return null
        val headerIndex = list.indexOfFirst { isAppSectionHeader(it) }
        if (headerIndex < 0) return null
        val apps = list.subList(headerIndex + 1, list.size).filterNotNull()
        if (apps.size < MIN_GROUP_APP_COUNT) return null
        val sorted = apps.map { it to keys.get(labelOf(it)) }
            .sortedWith { left, right -> compareItems(left.second, right.second) }
        val tail = list.subList(headerIndex + 1, list.size)
        tail.clear()
        var groups = 0
        var index = 0
        while (index < sorted.size) {
            val letter = sorted[index].second.letter
            val header = titleCtor.newInstance(letter.toString(), HEADER_KEY_PREFIX + letter)
            setViewType.invoke(header, appTableApp)
            tail.add(header)
            groups++
            while (index < sorted.size && sorted[index].second.letter == letter) {
                tail.add(sorted[index].first)
                index++
            }
        }
        return apps.size to groups
    }

    /** 字母标题标记项（我们插入的 TitleLabelData）。 */
    fun isLetterHeader(item: Any?): Boolean =
        item != null && titleClass.isInstance(item) && !appClass.isInstance(item) &&
            (runCatching { getKey.invoke(item) as? String }.getOrNull()?.startsWith(HEADER_KEY_PREFIX) == true)

    /** 原厂「应用」区段标题（viewType = ALL_APP_TABLE_APP 的非标记 TitleLabelData）。 */
    private fun isAppSectionHeader(item: Any?): Boolean =
        item != null && titleClass.isInstance(item) && !appClass.isInstance(item) &&
            runCatching { getViewType.invoke(item) === appTableApp }.getOrDefault(false)

    /**
     * `AllAppRecyclerAdapter.onBindViewHolder` after-proceed 回调：
     * 标记项写字母；同回收池的原厂标题项还原原厂文案（防止回收复用后串文本）。
     */
    fun bindHeader(mData: List<*>, holder: Any, position: Int) {
        val item = mData.getOrNull(position) ?: return
        if (!titleClass.isInstance(item) || appClass.isInstance(item)) return
        val view = runCatching {
            holder.javaClass.getField("itemView").get(holder)
        }.getOrNull() as? TextView ?: return
        val key = runCatching { getKey.invoke(item) as? String }.getOrNull()
        if (key?.startsWith(HEADER_KEY_PREFIX) == true) {
            val letter = runCatching { getText.invoke(item) as? CharSequence }.getOrNull()?.toString().orEmpty()
            if (view.text.toString() != letter) view.text = letter
            return
        }
        val viewType = runCatching { getViewType.invoke(item) }.getOrNull()
        when (viewType) {
            appTableApp -> restoreNativeTitle(view, TITLE_APP_RES) { nativeAppTitle = it }
            appTableTool -> restoreNativeTitle(view, TITLE_TOOL_RES) { nativeToolTitle = it }
        }
    }

    /** 还原原厂标题文案（仅在当前文本已被改写时才触碰视图）。 */
    private inline fun restoreNativeTitle(view: TextView, resName: String, cache: (String) -> Unit) {
        val cached = if (resName == TITLE_APP_RES) nativeAppTitle else nativeToolTitle
        val native = cached ?: runCatching {
            val id = view.resources.getIdentifier(resName, "string", ColorOsSidebarTarget.PACKAGE_NAME)
            if (id != 0) view.resources.getString(id) else null
        }.getOrNull() ?: return
        cache(native)
        if (view.text.toString() != native) view.text = native
    }

    /** 条目的显示标签（TitleLabelData.getText；应用即应用名）。 */
    private fun labelOf(item: Any): String =
        runCatching { (getText.invoke(item) as? CharSequence)?.toString() }.getOrNull().orEmpty()

    /** 比较两个标签：先按首字母组（'#' 最后），组内按拼音排序键，再退回中文 Collator。 */
    private fun compareItems(left: AlphabetSortKeyCache.Key, right: AlphabetSortKeyCache.Key): Int {
        val la = left.letter
        val lb = right.letter
        if (la != lb) return letterOrder(la) - letterOrder(lb)
        val ka = left.sortKey
        val kb = right.sortKey
        if (ka.isNotEmpty() && kb.isNotEmpty() && ka != kb) return ka.compareTo(kb)
        return collator.compare(left.label, right.label)
    }

    /** '#' 排在 Z 之后。 */
    private fun letterOrder(letter: Char): Int = if (letter == '#') Int.MAX_VALUE else letter.code

    /** 拼音排序键：ICU 转全拼 → 小写 → 只留 a-z；非汉字条目退化为小写标签。 */
    private fun sortKey(label: String): String {
        if (label.isEmpty()) return ""
        val converted = transliterator?.let { runCatching { it.transliterate(label) }.getOrNull() } ?: label
        return converted.lowercase(Locale.US).filter { it in 'a'..'z' }
    }

    /** 首字母：英文直取，中文经 ICU 转拼音取首字母，其余归 '#'。 */
    private fun groupLetter(label: String): Char {
        val first = label.firstOrNull { !it.isWhitespace() } ?: return '#'
        if (first in 'A'..'Z') return first
        if (first in 'a'..'z') return first.uppercaseChar()
        if (first.code in 0x4E00..0x9FFF || first.code in 0x3400..0x4DBF) {
            val pinyin = transliterator?.let { runCatching { it.transliterate(first.toString()) }.getOrNull() }
                ?: return '#'
            val c = pinyin.firstOrNull() ?: return '#'
            return if (c in 'a'..'z') c.uppercaseChar() else if (c in 'A'..'Z') c else '#'
        }
        return '#'
    }

    companion object {
        /** @author bomo TitleLabelData 全限定名（AppLabelData 父类，区段标题的数据载体）。 */
        const val TITLE_CLASS = "com.oplus.smartsidebar.panelview.edgepanel.data.TitleLabelData"

        /** @author bomo ViewType 枚举全限定名。 */
        const val VIEW_TYPE_CLASS = "com.oplus.smartsidebar.panelview.data.ViewType"

        /** @author bomo 字母标题项的 key 前缀标记；索引条据此定位分组位置。 */
        const val HEADER_KEY_PREFIX = "ff_letter_"

        /** @author bomo 原厂「应用」标题字符串资源名（回收池还原用）。 */
        const val TITLE_APP_RES = "coloros_ep_edit_tab_app"

        /** @author bomo 原厂「工具」标题字符串资源名。 */
        const val TITLE_TOOL_RES = "coloros_ep_edit_tab_tool"

        /** @author bomo 应用少于此数量不分组（与索引条显示阈值一致）。 */
        const val MIN_GROUP_APP_COUNT = 15

        /** SearchHelper 持的四个数据列表的 getter 名。 */
        private val LIST_GETTERS = listOf(
            "getCurrentDataList", "getToolNoFoldList", "getToolCollapseList", "getToolExpandList",
        )

        /** 模块自建面板的 SearchHelper 实例注册表（身份比较）；钩子在进程级命中后按此过滤。 */
        private val registry: MutableSet<Any> =
            Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap()))

        /** 面板内容构造后注册其 SearchHelper；仅注册实例的列表会被分组改写。 */
        fun register(search: Any) = registry.add(search)

        /** 面板关闭时注销，避免影响后续原生面板与泄漏。 */
        fun unregister(search: Any) = registry.remove(search)

        fun isRegistered(search: Any): Boolean = registry.contains(search)
    }
}
