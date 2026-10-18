package io.github.mangi.flymefreeform.platform.coloros

/** @author bomo 索引条在卡片内的布局范围；height 为零时隐藏，不侵入搜索框或越过卡片。 */
internal data class AlphabetIndexBounds(val top: Int, val height: Int) {
    companion object {
        /**
         * @author bomo 将索引放在搜索框底部之后，并在空间不足时收缩索引高度。
         * @param cardHeight 卡片像素高度；负值按零处理。
         * @param searchBottom 搜索框底部在卡片内的像素坐标。
         * @param preferredHeight 完整字母条理想高度；负值按零处理。
         * @param gap 与搜索框的像素间隔；负值按零处理。
         * @param bottomPadding 卡片底部像素留白；负值按零处理。
         * @return 不超出卡片的范围；无剩余空间时高度为零。
         */
        fun calculate(cardHeight: Int, searchBottom: Int, preferredHeight: Int, gap: Int, bottomPadding: Int): AlphabetIndexBounds {
            val limit = cardHeight.coerceAtLeast(0)
            val top = (searchBottom.coerceAtLeast(0).toLong() + gap.coerceAtLeast(0))
                .coerceAtMost(limit.toLong()).toInt()
            val available = (limit.toLong() - top - bottomPadding.coerceAtLeast(0)).coerceAtLeast(0).toInt()
            return AlphabetIndexBounds(top, preferredHeight.coerceIn(0, available))
        }
    }
}
