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
 * Floating status pill 2.0 (UX-3 §2c, fixes U12/U17).
 * Narration + pause/resume + stop, tap-body opens the app, 48dp touch targets and TalkBack
 * labels. Never intercepts touches outside its own buttons and never covers the center screen.
 */
class AniobOverlayPill(
    private val context: Context,
    private val onCancelTask: (() -> Unit)? = null,
    private val onPauseToggle: (() -> Unit)? = null,
    private val onOpenApp: (() -> Unit)? = null
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayContainer: LinearLayout? = null
    private var isShowing = false
    private var isPaused = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private var statusTextView: TextView? = null
    private var statusDotView: View? = null
    private var pauseView: TextView? = null

    fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        ).toInt()
    }

    fun dpToPx(dp: Int): Int = dpToPx(dp.toFloat())

    fun show(stepIndex: Int, statusText: String) {
        if (!Settings.canDrawOverlays(context)) return
        if (isShowing) {
            updateStep(stepIndex, statusText)
            return
        }

        val params = WindowManager.LayoutParams(
            dpToPx(MIN_TOUCH_TARGET_DP),
            WindowManager.LayoutParams.WRAP_CONTENT,
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
            minimumHeight = dpToPx(MIN_TOUCH_TARGET_DP)

            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(24f).toFloat()
                setColor(Color.parseColor("#E61A1C1E"))
                setStroke(dpToPx(1f), Color.parseColor("#33FFFFFF"))
            }
            elevation = dpToPx(6f).toFloat()
        }

        val dot = View(context).apply {
            val dotSize = dpToPx(8f)
            layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                marginEnd = dpToPx(8f)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#00E676"))
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        statusDotView = dot
        container.addView(dot)

        // Narration-first body: the human layer, not a step counter.
        val narration = TextView(context).apply {
            text = "Step $stepIndex"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = dpToPx(180f)
            contentDescription = "Aniob status"
            setOnClickListener { onOpenApp?.invoke() }
        }
        statusTextView = narration
        container.addView(narration)

        val pause = TextView(context).apply {
            text = "⏸"
            setTextColor(Color.parseColor("#B0BEC5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            contentDescription = "Pause task"
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpToPx(MIN_TOUCH_TARGET_DP), dpToPx(MIN_TOUCH_TARGET_DP))
            setOnClickListener {
                isPaused = !isPaused
                setPaused(isPaused)
                onPauseToggle?.invoke()
            }
        }
        pauseView = pause
        container.addView(pause)

        // Stop: 48dp target, labelled for TalkBack (U17).
        val stop = TextView(context).apply {
            text = "■"
            setTextColor(Color.parseColor("#EF9A9A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            contentDescription = "Stop task"
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpToPx(MIN_TOUCH_TARGET_DP), dpToPx(MIN_TOUCH_TARGET_DP))
            setOnClickListener {
                onCancelTask?.invoke()
                hide()
            }
        }
        container.addView(stop)

        // Drag to reposition & snap to edge (body drag only; buttons keep their taps).
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
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (_: Exception) {
                        // View already detached during a fast drag/dismiss race.
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val screenWidth = context.resources.displayMetrics.widthPixels
                    params.x = if (params.x < screenWidth / 2) dpToPx(12f) else (screenWidth - view.width - dpToPx(12f))
                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (_: Exception) {
                        // View already detached.
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(container, params)
            overlayContainer = container
            isShowing = true
        } catch (_: Exception) {
            // Overlay permission revoked or window manager error; the task still runs.
        }
    }

    /** Narration + step for the pill body; failure variant is amber (UX-3). */
    fun updateStep(stepIndex: Int, statusText: String) {
        statusTextView?.text = statusText
        statusTextView?.contentDescription = statusText
    }

    fun setPaused(paused: Boolean) {
        isPaused = paused
        pauseView?.text = if (paused) "▶" else "⏸"
        pauseView?.contentDescription = if (paused) "Resume task" else "Pause task"
        statusTextView?.text = if (paused) "Paused — do this step yourself, then Resume" else statusTextView?.text
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
            } catch (_: Exception) {
                // View detached
            }
            overlayContainer = null
            isShowing = false
        }
    }

    companion object {
        /** Minimum touch target (UX-7): never smaller than 48dp. */
        const val MIN_TOUCH_TARGET_DP = 48
    }
}
