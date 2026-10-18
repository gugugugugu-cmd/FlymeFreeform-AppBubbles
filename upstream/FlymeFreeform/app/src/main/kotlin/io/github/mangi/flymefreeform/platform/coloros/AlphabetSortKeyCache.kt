package io.github.mangi.flymefreeform.platform.coloros

/**
 * @author bomo 在一次四列表分组中，每个标签仅做一次首字母和全拼转换。
 * @param letterOf 首字母转换；异常由调用方原有处理路径接管，不缓存失败结果。
 * @param sortKeyOf 完整排序键转换；缓存只存字符串，不持有原厂条目或视图。
 */
internal class AlphabetSortKeyCache(
    private val letterOf: (String) -> Char,
    private val sortKeyOf: (String) -> String,
) {
    /** @author bomo 同一标签的不可变排序信息；保持原有首字母、全拼及 Collator 回退语义。 */
    data class Key(val label: String, val letter: Char, val sortKey: String)

    private val keys = mutableMapOf<String, Key>()
    val size: Int get() = keys.size

    /** @author bomo 获取标签排序信息；重复标签复用转换结果，生命周期随本次分组结束。 */
    fun get(label: String): Key = keys.getOrPut(label) { Key(label, letterOf(label), sortKeyOf(label)) }
}
