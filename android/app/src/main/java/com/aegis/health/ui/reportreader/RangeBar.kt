package com.aegis.health.ui.reportreader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.aegis.health.ui.theme.AegisSpacing
import com.aegis.health.ui.theme.LocalAegisColors
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Phase 3 D-02 — visual range bar. Calm-by-default per UI-03: the dot color
 * matches the status chip, the track is the muted hairline tone. NEVER uses
 * red/amber/green.
 *
 * Three render branches:
 *   1. Two-sided range (ref_low + ref_high both non-null): thin track from
 *      ref_low to ref_high, dot positioned by linear interpolation of value
 *      between the two bounds. If value lies outside, the dot clamps to a
 *      slight off-end position (left of track for value < ref_low, right
 *      for value > ref_high) using an offset proportional to the overshoot.
 *   2. One-sided range (exactly one bound non-null): track with one bound
 *      rendered + an ArrowUpward (when only ref_low is set, i.e. "above X")
 *      or ArrowDownward (when only ref_high is set, i.e. "below X") glyph.
 *   3. Unknown row (defer_reason != null OR status == unknown with no
 *      numeric value): NO track. Italic caption in the muted ink tone.
 *
 * Dot color mapping (mirrors StatusBadge per D-02 final paragraph):
 *   OUTSIDE_RANGE -> sevCritFg
 *   BORDERLINE    -> sevModFg
 *   unknown       -> sevLowFg
 *   IN_RANGE      -> onSurfaceMuted
 */
@Composable
fun RangeBar(
    value: kotlinx.serialization.json.JsonElement,
    refLow: kotlinx.serialization.json.JsonElement,
    refHigh: kotlinx.serialization.json.JsonElement,
    status: String,
    deferReason: String?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAegisColors.current
    val dotColor: Color = when (status) {
        "OUTSIDE_RANGE" -> colors.sevCritFg
        "BORDERLINE"    -> colors.sevModFg
        "unknown"       -> colors.sevLowFg
        else            -> colors.onSurfaceMuted
    }

    val v = (value as? JsonPrimitive)?.doubleOrNull
    val lo = (refLow as? JsonPrimitive)?.doubleOrNull
    val hi = (refHigh as? JsonPrimitive)?.doubleOrNull

    // Branch 3: unknown / range-not-printed
    if (deferReason != null || (lo == null && hi == null)) {
        Text(
            text = "range not printed",
            style = MaterialTheme.typography.labelSmall,
            fontStyle = FontStyle.Italic,
            color = colors.onSurfaceMuted,
            modifier = modifier,
        )
        return
    }

    // Branch 2: one-sided ranges — render one bound + arrow glyph.
    if (lo == null || hi == null) {
        val arrow = if (lo != null) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward
        val description = if (lo != null) "above $lo" else "below $hi"
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                arrow,
                contentDescription = description,
                tint = colors.onSurfaceMuted,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(AegisSpacing.xs))
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceMuted,
            )
        }
        return
    }

    // Branch 1: two-sided range — track + positioned dot.
    // Layout: a Box that fills max width with the track as a child filling
    // the same width and a custom Layout overlay placing the dot by a
    // fractional offset measured against the actual track width.
    Box(modifier = modifier.fillMaxWidth().padding(vertical = AegisSpacing.xs)) {
        // Track
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .align(Alignment.CenterStart)
                .background(colors.hairline, RoundedCornerShape(1.dp)),
        )
        // Dot — placed via a custom Layout so we can compute fractional offset
        // against the actual measured track width.
        // WR-01: Layout must align CenterStart inside the Box so the 8.dp dot
        // sits ON the 2.dp track, not 3.dp above it. Without this modifier the
        // Layout defaults to TopStart and the dot floats above the centered
        // track — silently subverting the D-02 "dot on the track" mandate.
        Layout(
            content = {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(dotColor, CircleShape),
                )
            },
            modifier = Modifier.align(Alignment.CenterStart),
        ) { measurables, constraints ->
            val placeable = measurables[0].measure(constraints.copy(minWidth = 0, minHeight = 0))
            val width = constraints.maxWidth
            val height = placeable.height
            layout(width, height) {
                if (v == null || hi == lo) {
                    // Defensive: missing value or zero-width range, center the dot.
                    placeable.place(x = (width / 2) - (placeable.width / 2), y = 0)
                    return@layout
                }
                // Clamp fraction to [-0.05, 1.05] so out-of-bounds dots sit off the
                // appropriate end of the track but don't fly off screen.
                val raw = (v - lo) / (hi - lo)
                val clamped = raw.coerceIn(-0.05, 1.05)
                val xPos = (clamped * width - placeable.width / 2.0).toInt()
                placeable.place(x = xPos, y = 0)
            }
        }
    }
}
