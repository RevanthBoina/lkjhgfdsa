package com.aniob.app.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Minimal Floating Overlay Pill (AIM Phase 1.6).
 * Height 32dp, rounded pill shape, snap to screen edges.
 * Never covers center screen or blocks user tap.
 */
class AniobOverlayPill(
    private val context: Context,
    private val onCancelTask: (() -> Unit)? = null
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayContainer: LinearLayout? = null
    private var isShowing = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private var statusTextView: TextView? = null
    private var statusDotView: View? = null

    fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        ).toInt()
    }

    fun show(stepIndex: Int, statusText: String) {
        if (!Settings.canDrawOverlays(context)) return
        if (isShowing) {
            updateStep(stepIndex, statusText)
            return
        }

        val pillHeight = dpToPx(34f)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            pillHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = dpToPx(56f)
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(12f), 0, dpToPx(12f), 0)

            // Rounded pill shape with dark blur background
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(17f).toFloat()
                setColor(Color.parseColor("#E61A1C1E"))
                setStroke(dpToPx(1f), Color.parseColor("#33FFFFFF"))
            }
            elevation = dpToPx(6f).toFloat()
        }

        // Left pulsing green indicator dot
        val dot = View(context).apply {
            val dotSize = dpToPx(7f)
            layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                marginEnd = dpToPx(8f)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#00E676"))
            }
        }
        statusDotView = dot
        container.addView(dot)

        // Center Step text (11sp bold, single line)
        val stepText = TextView(context).apply {
            text = "Step $stepIndex"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
        }
        statusTextView = stepText
        container.addView(stepText)

        // Right tiny cancel 'x' button
        val closeText = TextView(context).apply {
            text = "✕"
            setTextColor(Color.parseColor("#B0BEC5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dpToPx(8f), 0, 0, 0)
            setOnClickListener {
                onCancelTask?.invoke()
                hide()
            }
        }
        container.addView(closeText)

        // Drag to reposition & snap to edge
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        container.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (e: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // Snap to left or right screen edge
                    val screenWidth = context.resources.displayMetrics.widthWidth()
                    params.x = if (params.x < screenWidth / 2) dpToPx(12f) else (screenWidth - view.width - dpToPx(12f))
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (e: Exception) {}
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(container, params)
            overlayContainer = container
            isShowing = true
        } catch (e: Exception) {
            // Overlay permission or window manager error
        }
    }

    private fun android.util.DisplayMetrics.widthWidth(): Int = widthPixels

    fun updateStep(stepIndex: Int, statusText: String) {
        statusTextView?.text = "Step $stepIndex"
    }

    fun showDoneAndDismiss() {
        if (!isShowing || overlayContainer == null) return
        statusTextView?.text = "Done ✓"
        (statusDotView?.background as? GradientDrawable)?.setColor(Color.parseColor("#00E676"))

        mainHandler.postDelayed({
            overlayContainer?.animate()
                ?.alpha(0f)
                ?.scaleX(0.8f)
                ?.scaleY(0.8f)
                ?.setDuration(300)
                ?.setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        hide()
                    }
                })?.start()
        }, 1200)
    }

    fun hide() {
        if (isShowing && overlayContainer != null) {
            try {
                windowManager.removeView(overlayContainer)
            } catch (e: Exception) {
                // View detached
            }
            overlayContainer = null
            isShowing = false
        }
    }
}
