package com.aniob.app.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val AniobCyan = Color(0xFF0EA5E9)
val AniobNavy = Color(0xFF0F172A)
val AniobSlate = Color(0xFF1E293B)
val AniobLightBg = Color(0xFFF8FAFC)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF38BDF8),
    onPrimary = Color(0xFF082F49),
    primaryContainer = Color(0xFF0C4A6E),
    onPrimaryContainer = Color(0xFFBAE6FD),
    secondary = Color(0xFF818CF8),
    background = Color(0xFF0F172A),
    surface = Color(0xFF1E293B),
    onBackground = Color(0xFFF1F5F9),
    onSurface = Color(0xFFF1F5F9)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0284C7),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF0369A1),
    secondary = Color(0xFF4F46E5),
    background = AniobLightBg,
    surface = Color.White,
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF0F172A)
)

@Composable
fun AniobTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

/**
 * Motion tokens (UX-7 §1). Restrained: 200ms nav transitions, and a static path that honours the
 * platform reduce-motion setting.
 */
object AniobMotion {
    const val NAV_DURATION_MS = 200
    fun <T> navSpec(): androidx.compose.animation.core.FiniteAnimationSpec<T> = tween(NAV_DURATION_MS)
    fun <T> instant(): androidx.compose.animation.core.FiniteAnimationSpec<T> = tween(0)

    val NAV_SPEC: androidx.compose.animation.core.FiniteAnimationSpec<Int> get() = tween(NAV_DURATION_MS)

    /** Reads `Settings.Global.ANIMATOR_DURATION_SCALE`; 0 means animations are disabled. */
    fun isReducedMotionEnabled(context: Context): Boolean = try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (_: Exception) {
        false
    }
}
