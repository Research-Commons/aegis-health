package com.aegis.health.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Brand colors ────────────────────────────────────────────────────────

val AegisTeal = Color(0xFF0D7377)
val AegisTealLight = Color(0xFF14A3A8)
val AegisTealDark = Color(0xFF095456)
val AegisBlue = Color(0xFF1565C0)
val AegisBlueLight = Color(0xFF42A5F5)
val AegisSurface = Color(0xFFF5F9FA)
val AegisSurfaceDark = Color(0xFF121820)

// Severity colors
val SeverityRed = Color(0xFFD32F2F)
val SeverityRedLight = Color(0xFFFFCDD2)
val SeverityAmber = Color(0xFFF57F17)
val SeverityAmberLight = Color(0xFFFFF9C4)
val SeverityGreen = Color(0xFF2E7D32)
val SeverityGreenLight = Color(0xFFC8E6C9)
val SeverityBlue = Color(0xFF1565C0)
val SeverityBlueLight = Color(0xFFBBDEFB)

// ── Color schemes ───────────────────────────────────────────────────────

private val LightColorScheme = lightColorScheme(
    primary = AegisTeal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB2DFDB),
    onPrimaryContainer = AegisTealDark,
    secondary = AegisBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFBBDEFB),
    onSecondaryContainer = Color(0xFF0D47A1),
    tertiary = Color(0xFF6D4C41),
    tertiaryContainer = Color(0xFFD7CCC8),
    surface = AegisSurface,
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE8EFF0),
    onSurfaceVariant = Color(0xFF49454F),
    error = SeverityRed,
    onError = Color.White,
    background = Color.White,
    onBackground = Color(0xFF1C1B1F),
)

private val DarkColorScheme = darkColorScheme(
    primary = AegisTealLight,
    onPrimary = Color(0xFF003739),
    primaryContainer = AegisTealDark,
    onPrimaryContainer = Color(0xFFB2DFDB),
    secondary = AegisBlueLight,
    onSecondary = Color(0xFF0D47A1),
    secondaryContainer = Color(0xFF1565C0),
    onSecondaryContainer = Color(0xFFBBDEFB),
    tertiary = Color(0xFFBCAAA4),
    tertiaryContainer = Color(0xFF4E342E),
    surface = AegisSurfaceDark,
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF1E2A30),
    onSurfaceVariant = Color(0xFFCAC4D0),
    error = Color(0xFFEF9A9A),
    onError = Color(0xFF601010),
    background = Color(0xFF0E1419),
    onBackground = Color(0xFFE6E1E5),
)

// ── Typography ──────────────────────────────────────────────────────────

val AegisTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
    ),
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
    ),
)

// ── Shapes ──────────────────────────────────────────────────────────────

val AegisShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

// ── Theme composable ────────────────────────────────────────────────────

@Composable
fun AegisHealthTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AegisTypography,
        shapes = AegisShapes,
        content = content,
    )
}

// ── Severity helpers ────────────────────────────────────────────────────

fun severityColor(severity: Int): Color = when (severity) {
    5 -> SeverityRed
    4 -> SeverityRed
    3 -> SeverityAmber
    2 -> SeverityBlue
    else -> SeverityGreen
}

fun severityBackgroundColor(severity: Int): Color = when (severity) {
    5 -> SeverityRedLight
    4 -> SeverityRedLight
    3 -> SeverityAmberLight
    2 -> SeverityBlueLight
    else -> SeverityGreenLight
}

fun severityLabel(severity: Int): String = when (severity) {
    5 -> "CRITICAL"
    4 -> "HIGH"
    3 -> "MODERATE"
    2 -> "LOW"
    else -> "INFO"
}
