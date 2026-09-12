package com.readassist.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CropAreaCalculatorTest {
    @Test
    fun addsPaddingAroundSelection() {
        val area = CropAreaCalculator.calculate(100, 200, 300, 500, 1000, 1200, 20)

        assertEquals(CropArea(80, 180, 320, 520), area)
    }

    @Test
    fun clipsSelectionAndPaddingToImageBounds() {
        val area = CropAreaCalculator.calculate(-10, 5, 995, 1210, 1000, 1200, 20)

        assertEquals(CropArea(0, 0, 1000, 1200), area)
    }

    @Test
    fun rejectsEmptyArea() {
        val area = CropAreaCalculator.calculate(300, 300, 200, 200, 1000, 1200, 0)

        assertNull(area)
    }
}
