package com.aegis.health.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

// ── Material3 schemes — derived from the AegisColors token sheet ────────
// Direction A · Clinical Calm: terracotta accent on white canvas.

private val LightColorScheme = lightColorScheme(
    primary = AegisCoral,
    onPrimary = AegisCoralInk,
    primaryContainer = AegisCoralSoft,
    onPrimaryContainer = AegisCoral,
    secondary = AegisSecondary,
    onSecondary = Color.White,
    secondaryContainer = AegisSecondarySoft,
    onSecondaryContainer = AegisSecondary,
    tertiary = SevMod,
    tertiaryContainer = SevModBg,
    onTertiaryContainer = SevMod,
    background = AegisCanvas,
    onBackground = AegisOnSurfaceLight,
    surface = AegisSurfaceLight,
    onSurface = AegisOnSurfaceLight,
    surfaceVariant = AegisSurfaceAltLight,
    onSurfaceVariant = AegisOnSurfaceMutedLight,
    outline = AegisHairlineLight,
    error = SevCrit,
    onError = Color.White,
    errorContainer = SevCritBg,
    onErrorContainer = SevCrit,
)

private val DarkColorScheme = darkColorScheme(
    primary = AegisCoralDarkAccent,
    onPrimary = AegisCanvasDark,
    primaryContainer = AegisCoralDarkSoft,
    onPrimaryContainer = AegisCoralDarkAccent,
    secondary = AegisSecondaryDark,
    onSecondary = AegisCanvasDark,
    secondaryContainer = AegisSecondarySoftDark,
    onSecondaryContainer = AegisSecondaryDark,
    tertiary = SevModDark,
    tertiaryContainer = SevModBgDark,
    onTertiaryContainer = SevModDark,
    background = AegisCanvasDark,
    onBackground = AegisOnSurfaceDark,
    surface = AegisSurfaceDark,
    onSurface = AegisOnSurfaceDark,
    surfaceVariant = AegisSurfaceAltDark,
    onSurfaceVariant = AegisOnSurfaceMutedDark,
    outline = AegisHairlineDark,
    error = SevCritDark,
    onError = AegisCanvasDark,
    errorContainer = SevCritBgDark,
    onErrorContainer = SevCritDark,
)

/**
 * Aegis terracotta + warm-clay theme — the calm-by-default palette uniform
 * across the demo cohort.
 *
 * The `dynamicColor` parameter is a structural enforcement point for
 * PITFALLS N3 (HOME-02 D-02f): Material You is permanently rejected for
 * v1.1 (REQUIREMENTS.md §Out of Scope) because wallpaper-derived colors
 * are incompatible with the carefully-tuned LocalAegisColors severity
 * tokens (sevCrit / sevMod / sevLow / sevInfo) and the brand colour
 * consistency required for a medical-safety app — a wallpaper-randomized
 * "Outside range" badge defeats the calibrated risk-signal palette.
 *
 * The parameter is intentionally NOT consumed in the body. Future Material
 * You enablement requires either (a) flipping the default to `true`
 * (visible in code review) or (b) wiring a `dynamicColor()` branch into
 * `colorScheme` — both are loud explicit changes that cannot occur via
 * dependency upgrade or transitive Compose-BOM bump.
 *
 * @param darkTheme When true, use [DarkColorScheme] + [DarkAegisColors].
 *   Defaults to the system dark-mode setting via [isSystemInDarkTheme].
 * @param dynamicColor When true, allows Material You to override the calm
 *   palette with wallpaper-derived colors on Android 12+. Locked at `false`
 *   per PITFALLS N3 (HOME-02 D-02f) — Material You is incompatible with the
 *   carefully-tuned [LocalAegisColors] severity tokens and brand colour
 *   consistency for a medical-safety app. v2 candidate to revisit; do NOT
 *   flip without a follow-up RFC.
 * @param content The themed content slot — trailing-lambda so callers can
 *   use the `AegisHealthTheme { ... }` syntax.
 */
@Composable
fun AegisHealthTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,   // ← D-02f / PITFALLS N3 structural lock
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val aegisColors = if (darkTheme) DarkAegisColors else LightAegisColors

    CompositionLocalProvider(LocalAegisColors provides aegisColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AegisTypography,
            shapes = AegisShapes,
            content = content,
        )
    }
}

// ── Severity helpers ────────────────────────────────────────────────────
// Two flavors: legacy fixed-token (callable from non-Composable code) and
// theme-aware (preferred — picks light/dark variant from AegisColors).

fun severityColor(severity: Int): Color = when (severity) {
    in 4..5 -> SevCrit
    3 -> SevMod
    2 -> SevLow
    else -> SevInfo
}

fun severityBackgroundColor(severity: Int): Color = when (severity) {
    in 4..5 -> SevCritBg
    3 -> SevModBg
    2 -> SevLowBg
    else -> SevInfoBg
}

fun severityColor(severity: Int, colors: AegisColors): Color = when (severity) {
    in 4..5 -> colors.sevCritFg
    3 -> colors.sevModFg
    2 -> colors.sevLowFg
    else -> colors.sevInfoFg
}

fun severityBackgroundColor(severity: Int, colors: AegisColors): Color = when (severity) {
    in 4..5 -> colors.sevCritBg
    3 -> colors.sevModBg
    2 -> colors.sevLowBg
    else -> colors.sevInfoBg
}

fun severityLabel(severity: Int): String = when (severity) {
    5 -> "CRITICAL"
    4 -> "HIGH"
    3 -> "MODERATE"
    2 -> "LOW"
    else -> "INFO"
}

// ── ReportReader status helpers (Phase 8 D-02) ──────────────────────────
// Single source of mapping for the four canonical EvaluatedRow.status codes
// (IN_RANGE / BORDERLINE / OUTSIDE_RANGE / unknown — note: "unknown" is
// intentionally lowercase per the Phase 3 schema). Strict-case match — no
// case-normalization (D-02d). Unrecognized strings fall back to
// IN_RANGE tokens (calm-by-default) per D-02c. Mirrors the
// severityColor(severity, colors) / severityBackgroundColor(severity, colors)
// split above; consumers destructure as `val (bg, fg) = tokenForStatus(...)`.

fun tokenForStatus(status: String, colors: AegisColors): Pair<Color, Color> = when (status) {
    "OUTSIDE_RANGE" -> colors.sevCritBg to colors.sevCritFg
    "BORDERLINE"    -> colors.sevModBg  to colors.sevModFg
    "unknown"       -> colors.sevLowBg  to colors.sevLowFg
    else            -> colors.surfaceAlt to colors.onSurfaceMuted
}

fun statusLabel(status: String): String = when (status) {
    "OUTSIDE_RANGE" -> "Outside range"
    "BORDERLINE"    -> "Borderline"
    "unknown"       -> "Review"
    else            -> "In range"
}
