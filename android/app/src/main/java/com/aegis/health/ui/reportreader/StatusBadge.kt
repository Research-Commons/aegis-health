package com.aegis.health.ui.reportreader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aegis.health.ui.theme.LocalAegisColors

/**
 * Phase 3 D-01 — three-state status chip with a fourth review state for
 * unknown rows (defer_reason != null).
 *
 * UI-03 calm-by-default mandate is honored by RESERVING chip color for
 * rows that are actually outside the printed range. IN_RANGE rows use
 * the neutral recessed-surface tone + muted ink — no color at all. Only
 * flagged rows warm up.
 *
 * Status string must come from `EvaluatedRow.status` verbatim. Unknown
 * status values default to the IN_RANGE styling defensively (never crash
 * the LazyColumn for an unrecognized wire-format value).
 */
@Composable
fun StatusBadge(
    status: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAegisColors.current
    val (bg, fg, label) = when (status) {
        "OUTSIDE_RANGE" -> Triple(colors.sevCritBg, colors.sevCritFg, "Outside range")
        "BORDERLINE"    -> Triple(colors.sevModBg,  colors.sevModFg,  "Borderline")
        "unknown"       -> Triple(colors.sevLowBg,  colors.sevLowFg,  "Review")
        else            -> Triple(colors.surfaceAlt, colors.onSurfaceMuted, "In range")
    }
    Text(
        text = label,
        modifier = modifier
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .semantics { contentDescription = "Status: $label" },
        style = MaterialTheme.typography.labelMedium,
        color = fg,
    )
}
