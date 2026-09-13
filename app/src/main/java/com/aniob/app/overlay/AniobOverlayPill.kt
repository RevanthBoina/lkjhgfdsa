package com.aniob.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.aniob.app.R

/**
 * Floating overlay pill displayed strictly while an autonomous task is actively running.
 */
class AniobOverlayPill(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private var isShowing = false

    fun show(statusText: String) {
        if (!Settings.canDrawOverlays(context)) return
        if (isShowing) {
            updateText(statusText)
            return
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 100
        }

        val textView = TextView(context).apply {
            text = "⚡ Aniob: $statusText"
            setBackgroundColor(0xCC111827.toInt())
            setTextColor(0xFF38BDF8.toInt())
            setPadding(32, 16, 32, 16)
            textSize = 14f
            elevation = 16f
        }

        try {
            windowManager.addView(textView, params)
            overlayView = textView
            isShowing = true
        } catch (e: Exception) {
            // Permission or window error fallback
        }
    }

    fun updateText(statusText: String) {
        (overlayView as? TextView)?.text = "⚡ Aniob: $statusText"
    }

    fun hide() {
        if (isShowing && overlayView != null) {
            try {
                windowManager.removeView(overlayView)
            } catch (e: Exception) {
                // View already detached
            }
            overlayView = null
            isShowing = false
        }
    }
}
