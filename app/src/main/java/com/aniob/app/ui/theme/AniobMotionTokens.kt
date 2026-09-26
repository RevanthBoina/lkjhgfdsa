package com.aniob.app.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.IntOffset

/*
 * ANIOB MOTION
 * ------------
 * The old build had exactly one motion value: tween(200) for nav, plus one infinite
 * alpha pulse on a hardcoded green dot. Everything else snapped. That is the single
 * biggest reason the app "feels like heavy software" — nothing acknowledges input.
 *
 * Material 3 Expressive replaces duration-based easing with springs, for one concrete
 * reason: springs are RETARGETABLE. If a user scrolls while a bubble is animating in,
 * a tween fights the gesture; a spring re-solves to the new target. In an agent app
 * where state changes arrive asynchronously from a background service, that matters
 * more than it does in a normal app.
 *
 * Two families, per the M3 spec:
 *   SPATIAL — position / size / shape. Allowed to overshoot. Feels alive.
 *   EFFECT  — colour / alpha. Must NOT overshoot (an overshooting colour flashes).
 *
 * All of it is routed through [AniobMotionScheme] so the reduce-motion path is a
 * single swap, not 40 `if (reducedMotion)` branches sprinkled through screens.
 */
@Immutable
data class AniobMotionScheme(
    val enabled: Boolean = true,

    /** Default spatial spring: bubbles entering, cards expanding, sheets settling. */
    val spatialDefault: FiniteAnimationSpec<Float>,
    /** Fast spatial: chip presses, icon swaps. */
    val spatialFast: FiniteAnimationSpec<Float>,
    /** Slow spatial with visible overshoot: the task-complete proof card. */
    val spatialExpressive: FiniteAnimationSpec<Float>,

    /** Colour and alpha. Critically damped — never bounces. */
    val effectDefault: FiniteAnimationSpec<Float>,
    val effectFast: FiniteAnimationSpec<Float>,

    /** Screen-level nav transition offset spring. */
    val navOffset: FiniteAnimationSpec<IntOffset>,
    val navAlpha: FiniteAnimationSpec<Float>
) {
    companion object {
        fun expressive() = AniobMotionScheme(
            enabled = true,
            spatialDefault = spring(
                dampingRatio = 0.75f,
                stiffness = Spring.StiffnessMediumLow
            ),
            spatialFast = spring(
                dampingRatio = 0.85f,
                stiffness = Spring.StiffnessMedium
            ),
            spatialExpressive = spring(
                dampingRatio = 0.55f,           // visible, satisfying overshoot
                stiffness = Spring.StiffnessLow
            ),
            effectDefault = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            effectFast = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            navOffset = spring(
                dampingRatio = 0.9f,
                stiffness = Spring.StiffnessMediumLow,
                visibilityThreshold = IntOffset(1, 1)
            ),
            navAlpha = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
        )

        /**
         * Honours the platform reduce-motion setting. Not "no animation" — zero-duration
         * animation, so state still lands correctly and tests stay deterministic.
         */
        fun reduced() = AniobMotionScheme(
            enabled = false,
            spatialDefault = tween(0),
            spatialFast = tween(0),
            spatialExpressive = tween(0),
            effectDefault = tween(0),
            effectFast = tween(0),
            navOffset = tween(0),
            navAlpha = tween(0)
        )
    }
}

/** Durations for the few things that genuinely are duration-based (shimmer, ticker cycling). */
object AniobDurations {
    const val INSTANT = 0
    const val FAST = 120
    const val MEDIUM = 240
    const val SLOW = 400
    /** One full breath of the "working" pulse. Deliberately slow — a fast pulse reads as panic. */
    const val PULSE_CYCLE = 1600
    /** Copy-confirmation checkmark dwell. */
    const val CONFIRM_DWELL = 1400
}

/**
 * Back-compat shim. The old `AniobMotion` object is referenced from AniobMainScreen;
 * keeping it means the redesign can land without touching navigation in the same commit.
 *
 * Migrate call sites to `AniobTheme.motion` then delete this.
 */
@Deprecated("Use AniobTheme.motion (AniobMotionScheme)")
object AniobMotion {
    const val NAV_DURATION_MS = 200

    fun <T> navSpec(): FiniteAnimationSpec<T> = tween(NAV_DURATION_MS)
    fun <T> instant(): FiniteAnimationSpec<T> = tween(0)
    val NAV_SPEC: FiniteAnimationSpec<Int> get() = tween(NAV_DURATION_MS)

    /**
     * Reads `Settings.Global.ANIMATOR_DURATION_SCALE`; 0 means animations are disabled.
     *
     * NOTE: the old implementation called this once in a `LaunchedEffect(Unit)`, so
     * toggling the accessibility setting while Aniob was in the background never took
     * effect until a process restart. [rememberAniobMotionScheme] re-reads on resume.
     */
    fun isReducedMotionEnabled(context: android.content.Context): Boolean = try {
        android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    } catch (_: Exception) {
        false
    }
}
