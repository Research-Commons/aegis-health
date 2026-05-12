package com.aegis.health.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aegis.health.ui.theme.LocalAegisColors

/**
 * Uppercase muted section label used between content blocks.
 *   "FINDINGS · 3", "SOURCES", "MODEL".
 */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAegisColors.current
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = colors.onSurfaceMuted,
    )
}

/**
 * Small SUMMARY chip used in the result-summary card header.
 */
@Composable
fun SummaryPill(
    text: String = "SUMMARY",
    modifier: Modifier = Modifier,
) {
    val colors = LocalAegisColors.current
    Text(
        text = text,
        modifier = modifier
            .background(
                color = if (colors.isDark) colors.accentSoft else colors.chip,
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 9.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = colors.accent,
    )
}

/**
 * `ConfidenceDot` — colored dot + percent, color picks from severity tokens.
 *   ≥80%  → info green
 *   ≥50%  → moderate amber
 *   else  → critical red
 */
@Composable
fun ConfidenceDot(
    confidence: Double,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAegisColors.current
    val pct = (confidence * 100).toInt()
    val dotColor = when {
        confidence >= 0.8 -> colors.sevInfoFg
        confidence >= 0.5 -> colors.sevModFg
        else -> colors.sevCritFg
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
    ) {
        Box(modifier = Modifier
            .size(8.dp)
            .background(dotColor, CircleShape))
        Text(
            "$pct% confident",
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceMuted,
        )
    }
}

/**
 * GradePill — small label used in HealthPartner checklist items.
 *   A → info green
 *   B → accent (terracotta)
 */
@Composable
fun GradePill(
    grade: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAegisColors.current
    val bg = if (grade.equals("A", ignoreCase = true)) colors.sevInfoFg else colors.accent
    val fg = if (grade.equals("A", ignoreCase = true)) Color.White else colors.accentInk
    Text(
        text = "GRADE ${grade.uppercase()}",
        modifier = modifier
            .background(bg, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = fg,
    )
}
