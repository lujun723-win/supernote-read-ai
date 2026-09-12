package com.readassist.utils

data class CropArea(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

object CropAreaCalculator {
    fun calculate(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        imageWidth: Int,
        imageHeight: Int,
        padding: Int
    ): CropArea? {
        if (imageWidth <= 0 || imageHeight <= 0) return null

        val cropLeft = (left - padding).coerceIn(0, imageWidth)
        val cropTop = (top - padding).coerceIn(0, imageHeight)
        val cropRight = (right + padding).coerceIn(0, imageWidth)
        val cropBottom = (bottom + padding).coerceIn(0, imageHeight)

        if (cropRight <= cropLeft || cropBottom <= cropTop) return null
        return CropArea(cropLeft, cropTop, cropRight, cropBottom)
    }
}
