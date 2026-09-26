package com.aniob.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * How Aniob picks its colours.
 *
 * The old build hardcoded `isSystemInDarkTheme()` with no user override and no dynamic
 * colour, on a `minSdk = 30` app where ~everyone is on Android 12+. Users who keep their
 * phone in light mode but want a dark agent surface (the common case — you watch Aniob
 * work at night) had no way to get it.
 */
enum class AniobThemeMode { SYSTEM, LIGHT, DARK }

@androidx.compose.runtime.Immutable
data class AniobThemeConfig(
    val mode: AniobThemeMode = AniobThemeMode.SYSTEM,
    /**
     * Material You. Defaults ON because it makes Aniob feel native to the user's phone —
     * which is exactly the trust signal an app that *drives* their phone needs.
     * Users who want brand colours can turn it off in Settings.
     */
    val useDynamicColor: Boolean = true,
    /** Pure-black surfaces for OLED. Saves real battery during long agent runs. */
    val useOledBlack: Boolean = false
)

private val LocalAniobShapes = staticCompositionLocalOf { AniobComponentShapes() }
private val LocalAniobSpacing = staticCompositionLocalOf { AniobSpacing() }
private val LocalAniobSizing = staticCompositionLocalOf { AniobSizing() }
private val LocalAniobMotion = staticCompositionLocalOf { AniobMotionScheme.expressive() }

/**
 * Accessor object so screens write `AniobTheme.spacing.m` instead of `16.dp`, and
 * `AniobTheme.state.working.accent` instead of `Color(0xFF00C853)`.
 */
object AniobTheme {
    val state: AniobStateColors
        @Composable @ReadOnlyComposable get() = LocalAniobStateColors.current

    val shapes: AniobComponentShapes
        @Composable @ReadOnlyComposable get() = LocalAniobShapes.current

    val spacing: AniobSpacing
        @Composable @ReadOnlyComposable get() = LocalAniobSpacing.current

    val sizing: AniobSizing
        @Composable @ReadOnlyComposable get() = LocalAniobSizing.current

    val motion: AniobMotionScheme
        @Composable @ReadOnlyComposable get() = LocalAniobMotion.current

    val type: AniobTextStyles get() = AniobTextStyles
}

/**
 * Re-reads the platform reduce-motion setting on every RESUME.
 *
 * The old code read it once in `LaunchedEffect(Unit)`, so a user who turned animations
 * off in Developer Options while Aniob was backgrounded kept getting animations until
 * the process died.
 */
@Composable
private fun rememberAniobMotionScheme(): AniobMotionScheme {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var reduced by remember { mutableStateOf(AniobMotion.isReducedMotionEnabled(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                reduced = AniobMotion.isReducedMotionEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return remember(reduced) {
        if (reduced) AniobMotionScheme.reduced() else AniobMotionScheme.expressive()
    }
}

@Composable
fun AniobTheme(
    config: AniobThemeConfig = AniobThemeConfig(),
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (config.mode) {
        AniobThemeMode.SYSTEM -> systemDark
        AniobThemeMode.LIGHT -> false
        AniobThemeMode.DARK -> true
    }

    val context = LocalContext.current
    val baseScheme: ColorScheme = when {
        config.useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> AniobDarkScheme
        else -> AniobLightScheme
    }

    val colorScheme = if (darkTheme && config.useOledBlack) {
        baseScheme.copy(
            background = androidx.compose.ui.graphics.Color.Black,
            surface = androidx.compose.ui.graphics.Color.Black,
            surfaceContainerLowest = androidx.compose.ui.graphics.Color.Black
        )
    } else {
        baseScheme
    }

    // Agent-state colours are NEVER dynamic. "Needs your approval" must be amber on
    // every device, whatever wallpaper the user set — it is a safety signal, not decor.
    val stateColors = if (darkTheme) AniobDarkStateColors else AniobLightStateColors

    // Status-bar / nav-bar icon polarity. The old build set the bars transparent in
    // themes.xml but never flipped the icon colour, so in light mode the status-bar
    // icons were white-on-near-white and effectively invisible.
    val view = LocalView.current
    if (!view.isInEditMode) {
        DisposableEffect(darkTheme) {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val insets = WindowCompat.getInsetsController(window, view)
                insets.isAppearanceLightStatusBars = !darkTheme
                insets.isAppearanceLightNavigationBars = !darkTheme
            }
            onDispose { }
        }
    }

    CompositionLocalProvider(
        LocalAniobStateColors provides stateColors,
        LocalAniobShapes provides AniobComponentShapes(),
        LocalAniobSpacing provides AniobSpacing(),
        LocalAniobSizing provides AniobSizing(),
        LocalAniobMotion provides rememberAniobMotionScheme()
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AniobTypography,
            shapes = AniobShapes,
            content = content
        )
    }
}

/**
 * Backward-compatible overload for call sites that force a mode explicitly.
 *
 * NOTE: `darkTheme` has no default on purpose — giving it one would make the bare
 * call `AniobTheme { ... }` ambiguous against the config overload above.
 */
@Deprecated(
    "Pass an AniobThemeConfig instead so the user's theme preference is honoured.",
    ReplaceWith("AniobTheme(AniobThemeConfig(mode = if (darkTheme) AniobThemeMode.DARK else AniobThemeMode.LIGHT), content)")
)
@Composable
fun AniobTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit
) = AniobTheme(
    config = AniobThemeConfig(
        mode = if (darkTheme) AniobThemeMode.DARK else AniobThemeMode.LIGHT
    ),
    content = content
)
