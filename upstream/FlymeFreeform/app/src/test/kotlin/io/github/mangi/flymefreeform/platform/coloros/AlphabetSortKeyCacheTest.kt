package io.github.mangi.flymefreeform.platform.coloros

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** @author bomo 验证排序转换次数不随比较器调用或四列表重复而增长。 */
class AlphabetSortKeyCacheTest {
    /** @author bomo 模拟四个列表及多轮比较；相同标签只允许转换一次，并保留原始标签用于排序回退。 */
    @Test fun repeatedLabelsAcrossListsAreConvertedOnce() {
        var letters = 0
        var keys = 0
        val cache = AlphabetSortKeyCache({ letters++; 'W' }, { keys++; "weixin" })
        val first = cache.get("微信")
        repeat(4) { repeat(100) { assertSame(first, cache.get("微信")) } }
        assertEquals(1, letters)
        assertEquals(1, keys)
        assertEquals("微信", first.label)
        assertEquals('W', first.letter)
        assertEquals("weixin", first.sortKey)
    }

    /** @author bomo 标签更新应使用新键；不以包名或原厂条目对象缓存，避免重命名后排序陈旧。 */
    @Test fun distinctLabelsKeepIndependentKeys() {
        val cache = AlphabetSortKeyCache({ it.firstOrNull()?.uppercaseChar() ?: '#' }, { it.lowercase() })
        assertEquals('A', cache.get("Alpha").letter)
        assertEquals("beta", cache.get("Beta").sortKey)
        assertEquals('#', cache.get("").letter)
        assertEquals(3, cache.size)
    }

    /** @author bomo 转换失败不缓存半成品，后续重试仍能得到完整有效键。 */
    @Test fun failedConversionCanBeRetried() {
        var calls = 0
        val cache = AlphabetSortKeyCache({ 'A' }, { if (++calls == 1) error("conversion failed") else "alpha" })
        try { cache.get("Alpha") } catch (_: IllegalStateException) { /* 预期首次转换失败。 */ }
        assertEquals(0, cache.size)
        assertEquals("alpha", cache.get("Alpha").sortKey)
        assertEquals(2, calls)
    }
}
