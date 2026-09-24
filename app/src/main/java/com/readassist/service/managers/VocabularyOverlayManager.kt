package com.readassist.service.managers

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import com.readassist.R
import com.readassist.dictionary.VocabularyHint
import kotlin.math.max

class VocabularyOverlayManager(
    private val context: Context,
    private val windowManager: WindowManager
) {
    private var annotationOverlay: View? = null
    private var toggleButton: Button? = null
    private var currentHints: List<VocabularyHint> = emptyList()
    private var currentSourceWidth: Int = 0
    private var currentSourceHeight: Int = 0
    private var annotationsVisible: Boolean = false

    fun show(hints: List<VocabularyHint>, sourceWidth: Int, sourceHeight: Int) {
        hide()
        if (hints.isEmpty()) return
        currentHints = hints
        currentSourceWidth = sourceWidth
        currentSourceHeight = sourceHeight
        annotationsVisible = true
        addAnnotationOverlay()
        addToggleButton()
    }

    fun hide() {
        removeAnnotationOverlay()
        toggleButton?.let { button -> runCatching { windowManager.removeView(button) } }
        toggleButton = null
        currentHints = emptyList()
        currentSourceWidth = 0
        currentSourceHeight = 0
        annotationsVisible = false
    }

    fun isShowing(): Boolean = annotationOverlay != null

    private fun addAnnotationOverlay() {
        if (annotationOverlay != null || currentHints.isEmpty()) return
        val view = VocabularyOverlayView(
            context,
            currentHints,
            currentSourceWidth,
            currentSourceHeight
        )
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        windowManager.addView(view, params)
        annotationOverlay = view
    }

    private fun removeAnnotationOverlay() {
        annotationOverlay?.let { view -> runCatching { windowManager.removeView(view) } }
        annotationOverlay = null
    }

    private fun addToggleButton() {
        if (toggleButton != null) return
        val button = Button(context).apply {
            isAllCaps = false
            minWidth = 0
            minHeight = 0
            textSize = 14f
            setTextColor(Color.BLACK)
            setPadding(dp(8), 0, dp(8), 0)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.WHITE)
                setStroke(dp(1), Color.BLACK)
                cornerRadius = dp(4).toFloat()
            }
            setOnClickListener { setAnnotationsVisible(!annotationsVisible) }
        }
        val params = WindowManager.LayoutParams(
            dp(72),
            dp(48),
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = dp(16)
            y = dp(88)
        }
        updateToggleText(button)
        windowManager.addView(button, params)
        toggleButton = button
    }

    private fun setAnnotationsVisible(visible: Boolean) {
        if (visible == annotationsVisible) return
        annotationsVisible = visible
        if (visible) addAnnotationOverlay() else removeAnnotationOverlay()
        toggleButton?.let(::updateToggleText)
    }

    private fun updateToggleText(button: Button) {
        val textRes = if (annotationsVisible) {
            R.string.vocabulary_hints_hide
        } else {
            R.string.vocabulary_hints_show
        }
        button.setText(textRes)
        button.contentDescription = context.getString(textRes)
    }

    private fun overlayWindowType(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}

private class VocabularyOverlayView(
    context: Context,
    private val hints: List<VocabularyHint>,
    private val sourceWidth: Int,
    private val sourceHeight: Int
) : View(context) {
    private val density = resources.displayMetrics.density
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        textSize = 13f * resources.displayMetrics.scaledDensity
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = max(1f, density)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (sourceWidth <= 0 || sourceHeight <= 0) return
        val scaleX = width.toFloat() / sourceWidth
        val scaleY = height.toFloat() / sourceHeight
        val paddingX = 5f * density
        val labelHeight = 22f * density
        val gap = 2f * density
        val corner = 3f * density
        val placed = mutableListOf<RectF>()

        hints.forEach { hint ->
            val word = RectF(
                hint.left * scaleX,
                hint.top * scaleY,
                hint.right * scaleX,
                hint.bottom * scaleY
            )
            val labelWidth = (textPaint.measureText(hint.gloss) + paddingX * 2)
                .coerceAtMost(width * 0.42f)
            var left = (word.centerX() - labelWidth / 2).coerceIn(0f, width - labelWidth)
            var top = word.top - labelHeight - gap
            if (top < 0f) top = word.bottom + gap
            var label = RectF(left, top, left + labelWidth, top + labelHeight)

            if (placed.any { RectF.intersects(it, label) }) {
                top = word.bottom + gap
                label = RectF(left, top, left + labelWidth, top + labelHeight)
            }
            if (placed.any { RectF.intersects(it, label) }) {
                left = (word.left - labelWidth - gap).coerceIn(0f, width - labelWidth)
                label = RectF(left, top, left + labelWidth, top + labelHeight)
            }
            if (label.bottom > height) return@forEach
            placed += label

            canvas.drawRoundRect(label, corner, corner, backgroundPaint)
            canvas.drawRoundRect(label, corner, corner, borderPaint)
            val baseline = label.centerY() - (textPaint.ascent() + textPaint.descent()) / 2
            canvas.drawText(hint.gloss, label.centerX(), baseline, textPaint)
            val lineStartY = if (label.centerY() < word.centerY()) label.bottom else label.top
            val lineEndY = if (label.centerY() < word.centerY()) word.top else word.bottom
            canvas.drawLine(label.centerX(), lineStartY, word.centerX(), lineEndY, borderPaint)
        }
    }
}
