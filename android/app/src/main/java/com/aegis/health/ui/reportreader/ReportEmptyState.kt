package com.aegis.health.ui.reportreader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.ImageNotSupported
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.aegis.health.ui.common.GhostButton
import com.aegis.health.ui.common.PrimaryButton
import com.aegis.health.ui.theme.AegisSpacing
import com.aegis.health.ui.theme.LocalAegisColors

/**
 * Phase 3 D-06 — dedicated empty-state per non-OK report_status.code.
 *
 * Renders icon glyph + headline + body copy + two CTAs:
 *   - Ghost: re-open SAF picker → invokes [onPickAnother]
 *   - Primary: clinician handoff → invokes [onDiscuss]; owner builds a
 *     defer-shaped AegisResponse via AegisResponseBuilder and stages it
 *     in DeferralStore.
 *
 * The headline prefers [statusMessage] when present (Phase 2 D-10 supplies
 * a per-code message string), falling back to a planner-owned default.
 * Body copy is always planner-owned (Phase 2's message is one sentence; we
 * add a second line of explanatory copy).
 *
 * NotADiagnosisPanel remains visible above this composable on the screen
 * (per D-07 "always-on collapsible" + 03-CONTEXT.md:181-183).
 */
@Composable
fun ReportEmptyState(
    statusCode: String,
    statusMessage: String?,
    onPickAnother: () -> Unit,
    onDiscuss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAegisColors.current
    val meta = metaFor(statusCode)
    val effectiveHeadline = statusMessage?.takeIf { it.isNotBlank() } ?: meta.headline

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = AegisSpacing.lg),
    ) {
        Icon(
            meta.icon,
            contentDescription = null,
            tint = colors.onSurfaceMuted,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(AegisSpacing.md))
        Text(
            text = effectiveHeadline,
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
        )
        Spacer(Modifier.height(AegisSpacing.xs))
        Text(
            text = meta.body,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceMuted,
        )
        Spacer(Modifier.height(AegisSpacing.lg))

        // Action row — mirrors DrugSafeScreen.kt:170-229 shape.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AegisSpacing.sm),
        ) {
            GhostButton(
                text = "Try another file",
                onClick = onPickAnother,
                modifier = Modifier.weight(1f),
            )
            PrimaryButton(
                text = "Discuss with your doctor",
                onClick = onDiscuss,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Per-code icon + fallback copy. Planner-owned per CONTEXT.md Claude's Discretion. */
private data class EmptyStateMeta(
    val icon: ImageVector,
    val headline: String,
    val body: String,
)

private fun metaFor(statusCode: String): EmptyStateMeta = when (statusCode) {
    "IMAGE_ONLY" -> EmptyStateMeta(
        icon = Icons.Default.ImageNotSupported,
        headline = "This looks like a scanned image.",
        body = "ReportReader works on text-based PDFs. Try downloading the digital report from your lab portal.",
    )
    "UNKNOWN_VENDOR" -> EmptyStateMeta(
        icon = Icons.AutoMirrored.Filled.HelpOutline,
        headline = "We don't recognise this lab format yet.",
        body = "ReportReader supports a set of common vendor formats. A clinician can still interpret the report directly.",
    )
    "TOO_MANY_ANALYTES" -> EmptyStateMeta(
        icon = Icons.Default.GridOn,
        headline = "This report has too many tests for ReportReader.",
        body = "Reports with more than 25 tests are best reviewed directly with a clinician.",
    )
    else -> EmptyStateMeta(
        icon = Icons.AutoMirrored.Filled.HelpOutline,
        headline = "We could not read this report.",
        body = "Try another PDF, or discuss the report directly with a clinician.",
    )
}
