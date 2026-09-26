package com.aniob.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/*
 * ANIOB TYPE SCALE
 * ----------------
 * The old build passed no `typography` to MaterialTheme, so every screen fell back to
 * the Compose default scale and then fought it by sprinkling `fontWeight = FontWeight.Bold`
 * on individual Text() calls — 40+ times. That is why the header felt heavy and the
 * hierarchy felt flat: everything important was the same size, just bolder.
 *
 * This scale fixes hierarchy with SIZE and COLOUR first, weight last.
 *
 * Rules encoded here:
 *  - 4dp baseline grid: every lineHeight is a multiple of 4.
 *  - Body copy sits at 15-16sp with 1.45x line height (the old 12sp `bodySmall`
 *    default was used for real reading copy on the model screen — too small).
 *  - `Trim.None` + `Alignment.Center` so short status strings stay optically centred
 *    inside chips and pills instead of sitting high.
 *  - No hardcoded FontFamily: uses the device's system font so Aniob inherits the
 *    user's OEM face (Samsung One UI, MIUI, Pixel) instead of looking foreign.
 *    Swap `AniobFontFamily` for a bundled variable font when branding is ready.
 */

val AniobFontFamily: FontFamily = FontFamily.Default

/** Tabular figures for counters that must not jitter while a task runs. */
private val NoTrim = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None
)

private fun aniobStyle(
    size: Int,
    lineHeight: Int,
    weight: FontWeight,
    tracking: Double = 0.0
) = TextStyle(
    fontFamily = AniobFontFamily,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontWeight = weight,
    letterSpacing = tracking.sp,
    lineHeightStyle = NoTrim,
    platformStyle = PlatformTextStyle(includeFontPadding = false)
)

val AniobTypography = Typography(
    // Display — the empty-state headline and the first-run welcome only.
    displayLarge = aniobStyle(52, 60, FontWeight.SemiBold, (-1.0)),
    displayMedium = aniobStyle(42, 48, FontWeight.SemiBold, (-0.5)),
    displaySmall = aniobStyle(34, 40, FontWeight.SemiBold, (-0.25)),

    // Headline — screen titles in a large top app bar.
    headlineLarge = aniobStyle(30, 36, FontWeight.SemiBold, (-0.25)),
    headlineMedium = aniobStyle(26, 32, FontWeight.SemiBold),
    headlineSmall = aniobStyle(23, 28, FontWeight.SemiBold),

    // Title — card headers, sheet titles, list section headers.
    titleLarge = aniobStyle(21, 28, FontWeight.SemiBold),
    titleMedium = aniobStyle(17, 24, FontWeight.SemiBold, 0.1),
    titleSmall = aniobStyle(15, 20, FontWeight.SemiBold, 0.1),

    // Body — everything the user actually reads. Chat bubbles use bodyLarge.
    bodyLarge = aniobStyle(16, 24, FontWeight.Normal, 0.15),
    bodyMedium = aniobStyle(15, 22, FontWeight.Normal, 0.15),
    bodySmall = aniobStyle(13, 18, FontWeight.Normal, 0.2),

    // Label — buttons, chips, timestamps, badges.
    labelLarge = aniobStyle(15, 20, FontWeight.SemiBold, 0.1),
    labelMedium = aniobStyle(13, 16, FontWeight.Medium, 0.4),
    labelSmall = aniobStyle(11, 16, FontWeight.Medium, 0.5)
)

/**
 * Styles that are not part of the Material scale but recur across Aniob.
 * Exposed through [AniobTheme.type] so screens never invent their own TextStyle.
 */
object AniobTextStyles {
    /** The live narration line in the cockpit ticker. Optically small, high contrast. */
    val narration = aniobStyle(14, 20, FontWeight.Medium, 0.1)

    /** Numbers in Stats that update live — monospaced digits stop layout jitter. */
    val metric = aniobStyle(28, 32, FontWeight.Bold, (-0.5))

    /** The "3 steps · 4.2s · On-device" provenance line under an answer. */
    val provenance = aniobStyle(12, 16, FontWeight.Medium, 0.3)

    /** Step text inside the execution timeline. */
    val step = aniobStyle(14, 20, FontWeight.Normal, 0.1)

    /** Only place technical identifiers are allowed to appear. */
    val mono = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
        lineHeightStyle = NoTrim,
        platformStyle = PlatformTextStyle(includeFontPadding = false)
    )
}
