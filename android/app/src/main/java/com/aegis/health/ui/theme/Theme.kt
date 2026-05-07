package com.aegis.health.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

// ── Material3 schemes — derived from the AegisColors token sheet ────────

private val LightColorScheme = lightColorScheme(
    primary = AegisTeal,
    onPrimary = Color.White,
    primaryContainer = AegisTealSoft,
    onPrimaryContainer = AegisTealInk,
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
    primary = AegisTealDarkAccent,
    onPrimary = AegisCanvasDark,
    primaryContainer = AegisTealDarkSoft,
    onPrimaryContainer = AegisTealDarkAccent,
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

@Composable
fun AegisHealthTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
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
