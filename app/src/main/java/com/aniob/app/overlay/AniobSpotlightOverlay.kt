package com.aniob.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager

/**
 * Non-touchable rounded-rect flash at resolved target bounds (UX-3 Cockpit).
 * Flashes for 300ms right before dispatch. Never blocks dispatch if it fails to show.
 */
class AniobSpotlightOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var spotlightView: View? = null

    fun flash(bounds: Rect?, durationMs: Long = 300L) {
        if (bounds == null || bounds.isEmpty) return
        if (!Settings.canDrawOverlays(context) || windowManager == null) return

        mainHandler.post {
            try {
                removeCurrentView()

                val view = SpotlightDrawView(context, bounds)
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                )

                windowManager.addView(view, params)
                spotlightView = view

                mainHandler.postDelayed({
                    removeCurrentView()
                }, durationMs)
            } catch (_: Exception) {
                // Non-blocking: never fails dispatch
            }
        }
    }

    fun flash(x: Float, y: Float, radius: Float = 48f, durationMs: Long = 300L) {
        val rect = Rect(
            (x - radius).toInt().coerceAtLeast(0),
            (y - radius).toInt().coerceAtLeast(0),
            (x + radius).toInt(),
            (y + radius).toInt()
        )
        flash(rect, durationMs)
    }

    private fun removeCurrentView() {
        spotlightView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
            spotlightView = null
        }
    }

    private class SpotlightDrawView(context: Context, private val targetBounds: Rect) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(160, 14, 165, 233)
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(14, 165, 233)
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val rectF = RectF(targetBounds)
            canvas.drawRoundRect(rectF, 16f, 16f, paint)
            canvas.drawRoundRect(rectF, 16f, 16f, strokePaint)
        }
    }
}
