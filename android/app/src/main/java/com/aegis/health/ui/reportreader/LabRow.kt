package com.aegis.health.ui.reportreader

import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.aegis.health.models.EvaluatedRow
import com.aegis.health.ui.common.GhostButton
import com.aegis.health.ui.theme.AegisSpacing
import com.aegis.health.ui.theme.LocalAegisColors
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Phase 3 D-05 — per-row composable with tap-to-expand definition + Discuss
 * CTA on flagged rows only.
 *
 * UI-02 rows-table mandate: renders canonical_name, value+units, range,
 * StatusBadge, RangeBar in the collapsed (default) state.
 *
 * UI-04 per-row deferral mandate: the Discuss button appears ONLY when
 * status is one of OUTSIDE_RANGE / BORDERLINE / unknown. IN_RANGE rows
 * expand to show the definition with no CTA — they don't need clinician
 * follow-up.
 *
 * Accessibility: collapsed row has a contentDescription that includes the
 * status verbatim so TalkBack reads the status before the value, per
 * CONTEXT.md Claude's Discretion. Per Phase 5 SAFETY-04 anchor guidance,
 * NEVER prepend urgency descriptors — the status string itself is enough.
 */
@Composable
fun LabRow(
    row: EvaluatedRow,
    onDiscuss: (EvaluatedRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAegisColors.current
    var expanded by remember { mutableStateOf(false) }

    // Display value (JsonPrimitive content; em-dash if null / non-numeric / JsonNull).
    val valueDisplay = (row.value as? JsonPrimitive)?.contentOrNull ?: "—"
    val unitsDisplay = row.units?.takeIf { it.isNotBlank() } ?: ""
    val valueText = if (unitsDisplay.isEmpty()) valueDisplay else "$valueDisplay $unitsDisplay"

    // Range text (e.g. "70–100", "above 60", "below 200").
    val lo = (row.ref_low as? JsonPrimitive)?.doubleOrNull
    val hi = (row.ref_high as? JsonPrimitive)?.doubleOrNull
    val rangeText: String = when {
        lo != null && hi != null -> "${formatNum(lo)}–${formatNum(hi)}"
        lo != null -> "above ${formatNum(lo)}"
        hi != null -> "below ${formatNum(hi)}"
        else -> ""
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(16.dp))
            .let { if (colors.isDark) it.border(1.dp, colors.hairline, RoundedCornerShape(16.dp)) else it }
            .clickable { expanded = !expanded }
            .semantics {
                contentDescription = "${row.canonical_name}, value $valueText, status ${row.status}"
            }
            .padding(16.dp),
    ) {
        // ── Row 1: test name + StatusBadge (right-aligned) ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.canonical_name,
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(AegisSpacing.xs))
            StatusBadge(status = row.status)
        }

        Spacer(Modifier.height(AegisSpacing.xs))

        // ── Row 2: value text + range text ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AegisSpacing.md),
        ) {
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
            )
            if (rangeText.isNotEmpty()) {
                Text(
                    text = "ref: $rangeText",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceMuted,
                )
            }
        }

        Spacer(Modifier.height(AegisSpacing.sm))

        // ── Row 3: RangeBar ──
        RangeBar(
            value = row.value,
            refLow = row.ref_low,
            refHigh = row.ref_high,
            status = row.status,
            deferReason = row.defer_reason,
        )

        // ── Expansion block: definition + citation + (conditional) Discuss CTA ──
        androidx.compose.animation.AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column {
                Spacer(Modifier.height(AegisSpacing.md))
                // Hairline separator
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.hairline),
                )
                Spacer(Modifier.height(AegisSpacing.md))

                // Definition (from DefinitionDb via EvaluatedRow.definition;
                // null/empty when Phase 2 did not bind a definition).
                val definitionText = row.definition?.takeIf { it.isNotBlank() }
                if (definitionText != null) {
                    Text(
                        text = definitionText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurface,
                    )
                }

                // Unknown-row caption (D-12 short-code human copy via DeferReasonCopy).
                // Renders BELOW the definition (if both are present); useful when an
                // OUTSIDE_RANGE row also has a defer_reason context note. For unknown
                // rows without a definition, this is the only block users see.
                val deferReason = row.defer_reason
                if (deferReason != null) {
                    if (definitionText != null) Spacer(Modifier.height(AegisSpacing.sm))
                    Text(
                        text = DeferReasonCopy.lookup(deferReason),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceMuted,
                    )
                }

                // Citation (always rendered when present — italic muted attribution).
                val citation = row.definition_citation?.takeIf { it.isNotBlank() }
                if (citation != null) {
                    Spacer(Modifier.height(AegisSpacing.xs))
                    Text(
                        text = "Source: $citation",
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = colors.onSurfaceMuted,
                    )
                }

                // D-05 Discuss CTA — ONLY on flagged rows (status != IN_RANGE).
                // Phase 8 D-01d: per-row subordinate variant (GhostButton); SummaryCard CTA stays PrimaryButton (loud, card-level).
                if (row.status != "IN_RANGE") {
                    Spacer(Modifier.height(AegisSpacing.md))
                    GhostButton(
                        text = "Discuss with your doctor",
                        onClick = { onDiscuss(row) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** Light formatter: strips trailing ".0" so integer-looking ranges render as "70" not "70.0". */
private fun formatNum(d: Double): String =
    if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
