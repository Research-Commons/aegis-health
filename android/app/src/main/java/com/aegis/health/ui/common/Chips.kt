package com.aegis.health.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.aegis.health.ui.theme.LocalAegisColors

/**
 * Selectable pill chip used in DrugSafe's parsed-drug row, etc.
 * `selected = true` paints with `tint`; otherwise a hairline outline.
 */
@Composable
fun AegisChip(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    tint: Color? = null,
    onClick: (() -> Unit)? = null,
    leading: ImageVector? = null,
) {
    val colors = LocalAegisColors.current
    val accent = tint ?: colors.accent
    val bg = if (selected) accent else Color.Transparent
    val fg = if (selected) colors.accentInk else colors.onSurface
    val borderColor = if (selected) accent else colors.hairline

    Row(
        modifier = modifier
            .background(bg, RoundedCornerShape(99.dp))
            .border(1.dp, borderColor, RoundedCornerShape(99.dp))
            .let { if (onClick != null) it.clickable { onClick() } else it }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (leading != null) Icon(leading, null, tint = fg, modifier = Modifier.size(13.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

/**
 * Dashed "+ add" chip — used at the end of a chip row to signal more entries.
 */
@Composable
fun AddChip(
    text: String = "add",
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAegisColors.current
    Row(
        modifier = modifier
            .border(1.dp, colors.hairline, RoundedCornerShape(99.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("+", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceMuted)
        Text(text, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceMuted)
    }
}

/**
 * Tiny grey pill used inside summary cards ("5 recs", "USPSTF 2024").
 */
@Composable
fun Tag(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAegisColors.current
    Text(
        text = text,
        modifier = modifier
            .background(colors.surfaceAlt, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelMedium,
        color = colors.onSurfaceMuted,
    )
}

/**
 * Hero value-prop strip on HomeScreen.
 *
 * Phase 9 HOME-01 (D-01b + D-01c + D-01g): renamed in place from the v1.0
 * privacy chip + label swap to the SC-1 verbatim phrase. Everything else
 * (icon, accent tint, padding, shape, dark-mode-only hairline border) is
 * byte-equivalent to the prior chip — only the Composable name and the
 * Text literal changed.
 *
 * The chip body uses middle-dot separators (`·`, U+00B7) and a trailing
 * period after each pillar, matching SC-1 verbatim. The wording is N1-safe.
 * Permanent regression: `HomeScreenStructureTest.homeScreenHasNoForbiddenN1WordsOrBenchTile`.
 */
@Composable
fun ValuePropChip(modifier: Modifier = Modifier) {
    val colors = LocalAegisColors.current
    val tint = colors.accent
    val tintBg = if (colors.isDark) colors.accentSoft else colors.chip

    Row(
        modifier = modifier
            .background(tintBg, RoundedCornerShape(12.dp))
            .let { if (colors.isDark) it.border(1.dp, colors.hairline, RoundedCornerShape(12.dp)) else it }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Default.WifiOff, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Text(
            "Offline · KB-grounded · Cite-or-defer.",
            style = MaterialTheme.typography.labelLarge,
            color = tint,
        )
    }
}

/**
 * Small rounded chip used in screen headers ("ON-DEVICE" tag).
 */
@Composable
fun PillTag(
    text: String,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
) {
    val colors = LocalAegisColors.current
    val tintBg = if (colors.isDark) colors.accentSoft else colors.chip
    Row(
        modifier = modifier
            .background(tintBg, RoundedCornerShape(99.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (leading != null) Icon(leading, null, tint = colors.accent, modifier = Modifier.size(12.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = colors.accent)
    }
}
