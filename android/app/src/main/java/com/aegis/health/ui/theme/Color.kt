package com.aegis.health.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ── Direction A · Clinical Calm — light tokens ──────────────────────────
// Source: design handoff bundle 2026-05-12 (Aegis Health Revamp.html · `calm`
// theme in data.jsx). White canvas, near-black text, terracotta accent, warm
// clay secondary for ConsentReader. Severity palette is warm-aligned so it
// reads on the cream/white surface.

val AegisCoral = Color(0xFFCC785C)          // accent · primary, CTAs
val AegisCoralInk = Color(0xFFFFFFFF)        // on-accent text
val AegisCoralSoft = Color(0xFFF7E8E1)       // soft chip / icon container
val AegisChip = Color(0x1ACC785C)            // 0.10 alpha terracotta — privacy strip / on-device pill
val AegisSecondary = Color(0xFF8A6A4A)       // ConsentReader accent — warm clay
val AegisSecondarySoft = Color(0xFFF0E8DC)
val AegisCanvas = Color(0xFFFFFFFF)          // app background — pure white
val AegisSurfaceLight = Color(0xFFFFFFFF)    // cards, sheets
val AegisSurfaceAltLight = Color(0xFFF4F4F2) // recessed surfaces, tags
val AegisOnSurfaceLight = Color(0xFF0A0A0A)  // near-black text
val AegisOnSurfaceMutedLight = Color(0xFF5A5A5A)
val AegisHairlineLight = Color(0x1A0A0A0A)   // 0.10 alpha black

val SevCrit = Color(0xFFB3503E)
val SevCritBg = Color(0xFFF7E3DA)
val SevMod = Color(0xFF8A5A13)
val SevModBg = Color(0xFFF5E8C8)
val SevLow = Color(0xFF3A5A82)
val SevLowBg = Color(0xFFE2EAF2)
val SevInfo = Color(0xFF3B6240)
val SevInfoBg = Color(0xFFE2EBDA)

// ── Direction A · Clinical Calm — dark tokens ───────────────────────────
// Dark variant kept for system-dark fallback. Keeps the terracotta accent
// at full chroma against an ink canvas — the design canvas only shipped a
// light spec, so this is an internal extension.

val AegisCoralDarkAccent = Color(0xFFE89A82)
val AegisCoralDarkSoft = Color(0x24E89A82)   // 0.14 alpha
val AegisChipDark = Color(0x33E89A82)
val AegisSecondaryDark = Color(0xFFC9AC86)
val AegisSecondarySoftDark = Color(0x24C9AC86)
val AegisCanvasDark = Color(0xFF131110)
val AegisSurfaceDark = Color(0xFF1C1A18)
val AegisSurfaceAltDark = Color(0xFF26231F)
val AegisOnSurfaceDark = Color(0xFFEDEAE4)
val AegisOnSurfaceMutedDark = Color(0xFF9A938A)
val AegisHairlineDark = Color(0x1FEDEAE4)    // 0.12 alpha

val SevCritDark = Color(0xFFE89180)
val SevCritBgDark = Color(0x1FE89180)
val SevModDark = Color(0xFFE2B86A)
val SevModBgDark = Color(0x1FE2B86A)
val SevLowDark = Color(0xFF8FAFD3)
val SevLowBgDark = Color(0x1F8FAFD3)
val SevInfoDark = Color(0xFF9BC59E)
val SevInfoBgDark = Color(0x1F9BC59E)

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
    val chip: Color,
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
    accent = AegisCoral,
    accentSoft = AegisCoralSoft,
    accentInk = AegisCoralInk,
    chip = AegisChip,
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
    accent = AegisCoralDarkAccent,
    accentSoft = AegisCoralDarkSoft,
    accentInk = AegisCanvasDark,
    chip = AegisChipDark,
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

// ── Legacy aliases — kept so existing screens compile during the revamp ──
// New code should reach for LocalAegisColors.current.* instead.
@Deprecated("Use LocalAegisColors.current.accent", ReplaceWith("LocalAegisColors.current.accent"))
val AegisTeal = AegisCoral
@Deprecated("Use LocalAegisColors.current.accentInk", ReplaceWith("LocalAegisColors.current.accentInk"))
val AegisTealInk = AegisCoralInk
@Deprecated("Use LocalAegisColors.current.accentSoft", ReplaceWith("LocalAegisColors.current.accentSoft"))
val AegisTealSoft = AegisCoralSoft
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
