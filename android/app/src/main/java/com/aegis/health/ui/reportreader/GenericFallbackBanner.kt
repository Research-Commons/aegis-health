package com.aegis.health.ui.reportreader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aegis.health.ui.theme.AegisSpacing
import com.aegis.health.ui.theme.LocalAegisColors

/**
 * Phase 4.1 D-06 — calm-slate banner rendered above the SummaryCard when a
 * lab report was extracted via the catch-all [GenericExtractor] (vendor not
 * recognized by any of the 7 named extractors). Tells the user the rows below
 * are best-effort and they should verify each one against the source PDF.
 *
 * Styling mirrors [NotADiagnosisPanel] verbatim — same `surfaceAlt` background +
 * `onSurfaceMuted` foreground (R-01 overrides the original D-06 wording that
 * called for non-existent `infoBg`/`infoFg` tokens). Undismissible (safety-
 * critical signal, not chrome). No state, no toggle, no close button.
 *
 * Anti-toggle note (RESEARCH.md Pattern 3): users cannot hide this banner.
 * If we ever add a dismiss action, the safety story of GENERIC_FALLBACK
 * regresses — the durable signal lives in `report_status.code` + the
 * `enforceReportReaderContract` override (always-defer + 0.4 confidence),
 * but the visual banner is what tells the user to verify on the page they
 * are looking at.
 *
 * The copy is hoisted to a top-level `const val` ([GENERIC_FALLBACK_BANNER_COPY])
 * for two reasons (RESEARCH.md Pitfall 4):
 *   1. Grep-able from CI audits / future REGULATORY.md language scans.
 *   2. Scannable by [com.aegis.health.ui.reportreader.DeferReasonCopyTest.banner_copy_uses_calm_vocabulary]
 *      without instantiating a Compose runtime.
 */
@Composable
fun GenericFallbackBanner(modifier: Modifier = Modifier) {
    val colors = LocalAegisColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surfaceAlt, RoundedCornerShape(12.dp))
            .padding(AegisSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Info,
            contentDescription = null,
            tint = colors.onSurfaceMuted,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(AegisSpacing.sm))
        Text(
            text = GENERIC_FALLBACK_BANNER_COPY,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceMuted,
        )
    }
}

/**
 * Phase 4.1 D-06 canonical banner copy. RESEARCH.md candidate #1, user-confirmed
 * verbatim in CONTEXT.md. 89 chars; passes UI-03 calm-vocabulary scan
 * (no good/bad/abnormal/critical/warning/alert/danger, no exclamation mark);
 * contains the literal "verify"-affordance substring required by D-06.
 *
 * Hoisted as top-level `const val` so [DeferReasonCopyTest] can scan it without
 * a Compose runtime and so future grep-based audits can locate it. DO NOT
 * inline this into the composable body without updating the test scan target.
 */
const val GENERIC_FALLBACK_BANNER_COPY: String =
    "Lab vendor not recognized -- best-effort extraction. Verify each row against your PDF."
