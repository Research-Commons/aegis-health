package com.aegis.health.ui.reportreader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aegis.health.models.EvaluatedRow
import com.aegis.health.ui.common.AegisChip
import com.aegis.health.ui.common.PrimaryButton
import com.aegis.health.ui.theme.AegisSpacing
import com.aegis.health.ui.theme.LocalAegisColors

/**
 * Phase 3 D-03 / D-04 — top-of-report summary card with three zones:
 *
 *   1. Count headline: "X of N values are outside the printed range".
 *      Count framing per D-03 — never narrative prose framing.
 *
 *   2. Chip strip — one chip per OUTSIDE_RANGE row only (D-04). BORDERLINE
 *      and unknown rows do NOT promote to summary chips; they stay in the
 *      LazyColumn below with their own per-row Discuss CTAs.
 *      Tapping a chip invokes [onChipTap] with the index of the row within
 *      [outsideRows] — the parent owns the LazyListState and decides how
 *      to scroll (animateScrollToItem per CONTEXT.md Discretion).
 *
 *   3. CTA — full-width clinician-handoff PrimaryButton. Text never changes,
 *      even when X=0 (D-04 all-clear case).
 *
 * D-03 chip styling mandate: each chip wires `tint = colors.sevCritFg`
 * against [AegisChip] with `selected = true`, producing the terracotta
 * pill described in 03-CONTEXT.md:107-125.
 *
 * @param outsideRows  EvaluatedRow rows with status == "OUTSIDE_RANGE".
 *                     The screen owner (Plan 03-06) filters
 *                     PreparsedReport.rows and passes the result — NOT raw
 *                     rows.
 * @param totalCount   Total row count in the report (denominator N).
 * @param onChipTap    Callback when a chip is tapped — receives the index
 *                     of the row within [outsideRows]. The owner maps it
 *                     back to the global LazyColumn index.
 * @param onClinicianCta Bottom-of-card clinician CTA tap. Owner stages an
 *                     AegisResponse via AegisResponseBuilder (Plan 03-05)
 *                     and navigates to DeferralScreen.
 */
@Composable
fun SummaryCard(
    outsideRows: List<EvaluatedRow>,
    totalCount: Int,
    onChipTap: (Int) -> Unit,
    onClinicianCta: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAegisColors.current
    val outsideCount = outsideRows.size

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(18.dp))
            .border(1.dp, colors.hairline, RoundedCornerShape(18.dp))
            .padding(AegisSpacing.lg),
    ) {
        // ── Zone 1: Headline (count framing per D-03) ──
        Text(
            text = "$outsideCount of $totalCount values are outside the printed range",
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
        )

        Spacer(Modifier.height(AegisSpacing.md))

        // ── Zone 2: Chip strip ──
        // D-04: OUTSIDE_RANGE rows only. When empty (all-clear), render a
        // small invisible spacer to preserve vertical rhythm — do NOT
        // collapse, do NOT switch to celebratory copy.
        if (outsideCount == 0) {
            Spacer(Modifier.height(28.dp))
        } else {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(AegisSpacing.sm),
            ) {
                outsideRows.forEachIndexed { index, row ->
                    AegisChip(
                        text = row.canonical_name,
                        selected = true,
                        tint = colors.sevCritFg,
                        onClick = { onChipTap(index) },
                    )
                }
            }
        }

        Spacer(Modifier.height(AegisSpacing.md))

        // ── Zone 3: CTA ──
        // Text is fixed per D-04 all-clear case. NEVER varies on count.
        PrimaryButton(
            text = "Bring this to your clinician",
            onClick = onClinicianCta,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
