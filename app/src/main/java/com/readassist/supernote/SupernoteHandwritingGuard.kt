package com.readassist.supernote

import android.content.Context
import android.graphics.Rect
import android.util.Log
import com.readassist.utils.DeviceUtils

/** 统一维护 ReadAssist 悬浮控件对应的 Supernote 硬件手写禁区。 */
class SupernoteHandwritingGuard(context: Context) {
    private companion object {
        const val TAG = "SupernoteHandwritingGuard"
    }

    private val enabled = DeviceUtils.isSupernoteDevice()
    private val screenWidth = context.resources.displayMetrics.widthPixels
    private val screenHeight = context.resources.displayMetrics.heightPixels
    private val fullScreenOwners = linkedSetOf<String>()
    private var floatingButtonBounds: Rect? = null
    private var lastSent: List<Rect>? = null

    fun setFloatingButtonBounds(bounds: Rect?) {
        floatingButtonBounds = bounds?.clippedToScreen()
        publish()
    }

    fun setFullScreenOverlayVisible(owner: String, visible: Boolean) {
        if (visible) fullScreenOwners.add(owner) else fullScreenOwners.remove(owner)
        publish()
    }

    fun clear() {
        fullScreenOwners.clear()
        floatingButtonBounds = null
        publish(force = true)
    }

    private fun publish(force: Boolean = false) {
        if (!enabled) return

        val rects = when {
            fullScreenOwners.isNotEmpty() -> listOf(Rect(0, 0, screenWidth, screenHeight))
            floatingButtonBounds != null -> listOf(Rect(floatingButtonBounds))
            // Document 应用本身用零面积矩形表达“当前没有禁写区域”。
            else -> listOf(Rect(0, 0, 0, 0))
        }
        if (!force && rects == lastSent) return

        if (SupernoteDrawPathClient.setDisabledAreas(rects)) {
            lastSent = rects.map(::Rect)
            Log.d(TAG, "已发布手写禁区，overlay=$fullScreenOwners, rects=$rects")
        }
    }

    private fun Rect.clippedToScreen(): Rect? {
        val clipped = Rect(
            left.coerceIn(0, screenWidth),
            top.coerceIn(0, screenHeight),
            right.coerceIn(0, screenWidth),
            bottom.coerceIn(0, screenHeight)
        )
        return clipped.takeIf { it.width() > 0 && it.height() > 0 }
    }
}
