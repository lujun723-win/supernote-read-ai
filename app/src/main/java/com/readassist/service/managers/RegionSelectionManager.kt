package com.readassist.service.managers

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.readassist.R
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class RegionSelection(
    val bounds: Rect,
    val path: Path
)

class RegionSelectionManager(
    private val context: Context,
    private val windowManager: WindowManager,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onRegionSelected(selection: RegionSelection)
        fun onRegionSelectionCancelled()
    }

    private var selectionView: RegionSelectionView? = null

    fun show() {
        if (selectionView != null) return

        val view = RegionSelectionView(
            context = context,
            onSelected = { selection ->
                dismiss()
                callbacks.onRegionSelected(selection)
            },
            onCancelled = {
                dismiss()
                callbacks.onRegionSelectionCancelled()
            }
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
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            windowManager.addView(view, params)
            selectionView = view
        } catch (_: Exception) {
            Toast.makeText(
                context,
                context.getString(R.string.region_screenshot_open_failed),
                Toast.LENGTH_LONG
            ).show()
            callbacks.onRegionSelectionCancelled()
        }
    }

    fun dismiss() {
        selectionView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {
                // The system may already have detached the overlay.
            }
        }
        selectionView = null
    }

    fun cleanup() = dismiss()
}

private class RegionSelectionView(
    context: Context,
    private val onSelected: (RegionSelection) -> Unit,
    private val onCancelled: () -> Unit
) : View(context) {
    private val density = resources.displayMetrics.density
    private val selectionPath = Path()
    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val boundsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val panelPaint = Paint().apply { color = Color.WHITE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 16f * density
    }
    private val cancelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val cancelRect = RectF()
    private val selectedBounds = RectF()
    private var drawing = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val panelHeight = 56f * density
        canvas.drawRect(0f, 0f, width.toFloat(), panelHeight, panelPaint)
        canvas.drawText(
            context.getString(R.string.region_screenshot_instruction),
            16f * density,
            35f * density,
            textPaint
        )

        cancelRect.set(width - 86f * density, 8f * density, width - 12f * density, 48f * density)
        canvas.drawRect(cancelRect, cancelPaint)
        canvas.drawText(context.getString(R.string.cancel), width - 70f * density, 35f * density, textPaint)

        if (!selectionPath.isEmpty) {
            canvas.drawPath(selectionPath, pathPaint)
            canvas.drawRect(selectedBounds, boundsPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (cancelRect.contains(event.x, event.y)) {
                    onCancelled()
                    return true
                }
                drawing = true
                selectionPath.reset()
                selectionPath.moveTo(event.x, event.y)
                selectedBounds.set(event.x, event.y, event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!drawing) return true
                for (index in 0 until event.historySize) {
                    addPoint(event.getHistoricalX(index), event.getHistoricalY(index))
                }
                addPoint(event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (!drawing) return true
                addPoint(event.x, event.y)
                drawing = false

                // 只过滤近似点击的误触；单个英文单词的圈选高度通常远小于 48dp。
                val minimumSize = 8f * density
                if (selectedBounds.width() < minimumSize || selectedBounds.height() < minimumSize) {
                    selectionPath.reset()
                    selectedBounds.setEmpty()
                    invalidate()
                    Toast.makeText(
                        context,
                        context.getString(R.string.region_screenshot_too_small),
                        Toast.LENGTH_SHORT
                    ).show()
                    return true
                }

                selectionPath.close()
                onSelected(
                    RegionSelection(
                        bounds = Rect(
                            floor(selectedBounds.left).toInt(),
                            floor(selectedBounds.top).toInt(),
                            ceil(selectedBounds.right).toInt(),
                            ceil(selectedBounds.bottom).toInt()
                        ),
                        path = Path(selectionPath)
                    )
                )
            }
            MotionEvent.ACTION_CANCEL -> {
                drawing = false
                selectionPath.reset()
                selectedBounds.setEmpty()
                invalidate()
            }
        }
        return true
    }

    private fun addPoint(x: Float, y: Float) {
        selectionPath.lineTo(x, y)
        selectedBounds.left = min(selectedBounds.left, x)
        selectedBounds.top = min(selectedBounds.top, y)
        selectedBounds.right = max(selectedBounds.right, x)
        selectedBounds.bottom = max(selectedBounds.bottom, y)
    }
}
