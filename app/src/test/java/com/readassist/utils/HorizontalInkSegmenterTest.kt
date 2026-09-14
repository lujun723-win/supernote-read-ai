package com.readassist.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class HorizontalInkSegmenterTest {
    @Test
    fun `splits edge fragments from center word at full whitespace gaps`() {
        val columns = BooleanArray(30)
        (0..2).forEach { columns[it] = true }
        (8..20).forEach { columns[it] = true }
        (27..29).forEach { columns[it] = true }

        assertEquals(
            listOf(0..2, 8..20, 27..29),
            HorizontalInkSegmenter.segment(columns, minimumBlankColumns = 4)
        )
    }

    @Test
    fun `keeps letter gaps inside one word`() {
        val columns = booleanArrayOf(true, true, false, false, true, true)

        assertEquals(
            listOf(0..5),
            HorizontalInkSegmenter.segment(columns, minimumBlankColumns = 3)
        )
    }

    @Test
    fun `returns no segments for blank image`() {
        assertEquals(
            emptyList<IntRange>(),
            HorizontalInkSegmenter.segment(BooleanArray(8), minimumBlankColumns = 3)
        )
    }
}
