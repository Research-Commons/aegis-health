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
import com.aegis.health.ui.theme.statusLabel
import com.aegis.health.ui.theme.tokenForStatus

/**
 * Phase 3 D-01 — three-state status chip with a fourth review state for
 * unknown rows (defer_reason != null).
 *
 * UI-03 calm-by-default mandate is honored by RESERVING chip color for
 * rows that are actually outside the printed range. IN_RANGE rows use
 * the neutral recessed-surface tone + muted ink — no color at all. Only
 * flagged rows warm up.
 *
 * Status mapping is delegated to `tokenForStatus` + `statusLabel` in
 * `ui/theme/Theme.kt` (Phase 8 D-02; single source of mapping).
 */
@Composable
fun StatusBadge(
    status: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAegisColors.current
    val (bg, fg) = tokenForStatus(status, colors)
    val label = statusLabel(status)
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
