package com.aegis.health.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ── Direction A · Clinical Calm — light tokens ──────────────────────────

val AegisTeal = Color(0xFF0D7377)
val AegisTealInk = Color(0xFF073A3C)
val AegisTealSoft = Color(0xFFD6EBE9)
val AegisSecondary = Color(0xFF1F5D92)
val AegisSecondarySoft = Color(0xFFDEE9F5)
val AegisCanvas = Color(0xFFF6FAF8)
val AegisSurfaceLight = Color(0xFFFFFFFF)
val AegisSurfaceAltLight = Color(0xFFEEF4F1)
val AegisOnSurfaceLight = Color(0xFF0F1F1D)
val AegisOnSurfaceMutedLight = Color(0xFF5E6F6A)
val AegisHairlineLight = Color(0x140F1F1D) // 0.08 alpha black-teal

val SevCrit = Color(0xFFA3262A)
val SevCritBg = Color(0xFFFBE6E6)
val SevMod = Color(0xFF9E6604)
val SevModBg = Color(0xFFFBF0D6)
val SevLow = Color(0xFF1F5D92)
val SevLowBg = Color(0xFFDEE9F5)
val SevInfo = Color(0xFF22683A)
val SevInfoBg = Color(0xFFDDE9D8)

// ── Direction A · Clinical Calm — dark tokens ───────────────────────────

val AegisTealDarkAccent = Color(0xFF5FC7C2)
val AegisTealDarkSoft = Color(0x245FC7C2) // 0.14 alpha
val AegisSecondaryDark = Color(0xFF7EB1E0)
val AegisSecondarySoftDark = Color(0x247EB1E0)
val AegisCanvasDark = Color(0xFF0C1413)
val AegisSurfaceDark = Color(0xFF141D1C)
val AegisSurfaceAltDark = Color(0xFF1C2625)
val AegisOnSurfaceDark = Color(0xFFE8EFED)
val AegisOnSurfaceMutedDark = Color(0xFF8A9994)
val AegisHairlineDark = Color(0x14E8EFED) // 0.08 alpha

val SevCritDark = Color(0xFFFF8585)
val SevCritBgDark = Color(0x1FFF8585)
val SevModDark = Color(0xFFF0BB6F)
val SevModBgDark = Color(0x1FF0BB6F)
val SevLowDark = Color(0xFF7EB1E0)
val SevLowBgDark = Color(0x1F7EB1E0)
val SevInfoDark = Color(0xFF7ED29A)
val SevInfoBgDark = Color(0x1F7ED29A)

// ── Extended palette container — surfaces every token to screens ────────

@Immutable
data class AegisColors(
    val canvas: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val onSurface: Color,
    val onSurfaceMuted: Color,
    val hairline: Color,
    val accent: Color,
    val accentSoft: Color,
    val accentInk: Color,
    val secondary: Color,
    val secondarySoft: Color,
    val sevCritFg: Color,
    val sevCritBg: Color,
    val sevModFg: Color,
    val sevModBg: Color,
    val sevLowFg: Color,
    val sevLowBg: Color,
    val sevInfoFg: Color,
    val sevInfoBg: Color,
    val isDark: Boolean,
)

val LightAegisColors = AegisColors(
    canvas = AegisCanvas,
    surface = AegisSurfaceLight,
    surfaceAlt = AegisSurfaceAltLight,
    onSurface = AegisOnSurfaceLight,
    onSurfaceMuted = AegisOnSurfaceMutedLight,
    hairline = AegisHairlineLight,
    accent = AegisTeal,
    accentSoft = AegisTealSoft,
    accentInk = Color.White,
    secondary = AegisSecondary,
    secondarySoft = AegisSecondarySoft,
    sevCritFg = SevCrit,
    sevCritBg = SevCritBg,
    sevModFg = SevMod,
    sevModBg = SevModBg,
    sevLowFg = SevLow,
    sevLowBg = SevLowBg,
    sevInfoFg = SevInfo,
    sevInfoBg = SevInfoBg,
    isDark = false,
)

val DarkAegisColors = AegisColors(
    canvas = AegisCanvasDark,
    surface = AegisSurfaceDark,
    surfaceAlt = AegisSurfaceAltDark,
    onSurface = AegisOnSurfaceDark,
    onSurfaceMuted = AegisOnSurfaceMutedDark,
    hairline = AegisHairlineDark,
    accent = AegisTealDarkAccent,
    accentSoft = AegisTealDarkSoft,
    accentInk = AegisCanvasDark,
    secondary = AegisSecondaryDark,
    secondarySoft = AegisSecondarySoftDark,
    sevCritFg = SevCritDark,
    sevCritBg = SevCritBgDark,
    sevModFg = SevModDark,
    sevModBg = SevModBgDark,
    sevLowFg = SevLowDark,
    sevLowBg = SevLowBgDark,
    sevInfoFg = SevInfoDark,
    sevInfoBg = SevInfoBgDark,
    isDark = true,
)

val LocalAegisColors = staticCompositionLocalOf { LightAegisColors }

// ── Legacy aliases — kept so screens compile during the staged revamp ───
// These are removed once all screens are migrated to LocalAegisColors.
@Deprecated("Use LocalAegisColors.current.secondary", ReplaceWith("LocalAegisColors.current.secondary"))
val AegisBlue = AegisSecondary
@Deprecated("Use LocalAegisColors.current.canvas", ReplaceWith("LocalAegisColors.current.canvas"))
val AegisSurface = AegisCanvas
@Deprecated("Use LocalAegisColors.current.sevCritFg", ReplaceWith("LocalAegisColors.current.sevCritFg"))
val SeverityRed = SevCrit
@Deprecated("Use LocalAegisColors.current.sevCritBg", ReplaceWith("LocalAegisColors.current.sevCritBg"))
val SeverityRedLight = SevCritBg
@Deprecated("Use LocalAegisColors.current.sevModFg", ReplaceWith("LocalAegisColors.current.sevModFg"))
val SeverityAmber = SevMod
@Deprecated("Use LocalAegisColors.current.sevModBg", ReplaceWith("LocalAegisColors.current.sevModBg"))
val SeverityAmberLight = SevModBg
@Deprecated("Use LocalAegisColors.current.sevInfoFg", ReplaceWith("LocalAegisColors.current.sevInfoFg"))
val SeverityGreen = SevInfo
@Deprecated("Use LocalAegisColors.current.sevInfoBg", ReplaceWith("LocalAegisColors.current.sevInfoBg"))
val SeverityGreenLight = SevInfoBg
@Deprecated("Use LocalAegisColors.current.sevLowFg", ReplaceWith("LocalAegisColors.current.sevLowFg"))
val SeverityBlue = SevLow
@Deprecated("Use LocalAegisColors.current.sevLowBg", ReplaceWith("LocalAegisColors.current.sevLowBg"))
val SeverityBlueLight = SevLowBg
