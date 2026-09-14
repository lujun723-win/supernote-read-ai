package com.readassist.utils

object HorizontalInkSegmenter {
    fun segment(inkColumns: BooleanArray, minimumBlankColumns: Int): List<IntRange> {
        require(minimumBlankColumns > 0) { "空白间隔必须大于零" }

        val firstInk = inkColumns.indexOfFirst { it }
        if (firstInk < 0) return emptyList()

        val segments = mutableListOf<IntRange>()
        var segmentStart = firstInk
        var lastInk = firstInk

        for (column in firstInk + 1 until inkColumns.size) {
            if (!inkColumns[column]) continue
            if (column - lastInk - 1 >= minimumBlankColumns) {
                segments += segmentStart..lastInk
                segmentStart = column
            }
            lastInk = column
        }
        segments += segmentStart..lastInk
        return segments
    }
}
