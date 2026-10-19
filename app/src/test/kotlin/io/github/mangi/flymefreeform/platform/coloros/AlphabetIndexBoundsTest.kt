package io.github.mangi.flymefreeform.platform.coloros

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** @author bomo 索引布局回归：避开搜索框、容纳完整索引，并处理短屏及极端输入。 */
class AlphabetIndexBoundsTest {
    /** @author bomo 常规高度下整体从搜索框底部加间隔起排，保留原有字母条长度。 */
    @Test fun normalPanelStartsBelowSearch() {
        assertEquals(AlphabetIndexBounds(128, 440), AlphabetIndexBounds.calculate(600, 120, 440, 8, 8))
    }

    /** @author bomo 不足时压缩可用高度，不能把顶部反向推回搜索区域。 */
    @Test fun shortPanelShrinksInsteadOfOverlappingSearch() {
        val result = AlphabetIndexBounds.calculate(450, 120, 440, 8, 8)
        assertEquals(128, result.top)
        assertEquals(314, result.height)
        assertTrue(result.top + result.height <= 450 - 8)
    }

    /** @author bomo 搜索框超出卡片或尚未布局时隐藏，不产生负高度。 */
    @Test fun exhaustedOrUnmeasuredPanelHidesIndex() {
        assertEquals(0, AlphabetIndexBounds.calculate(80, 120, 440, 8, 8).height)
        assertEquals(AlphabetIndexBounds(0, 0), AlphabetIndexBounds.calculate(0, 0, 440, 8, 8))
    }

    /** @author bomo 防止像素坐标相加溢出导致顶部变为负数并重新遮挡搜索框。 */
    @Test fun extremeCoordinatesRemainInsideCard() {
        assertEquals(AlphabetIndexBounds(600, 0), AlphabetIndexBounds.calculate(600, Int.MAX_VALUE, 440, 8, 8))
    }
}
