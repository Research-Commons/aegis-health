package com.aegis.health.ui.reportreader

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aegis.health.ui.common.PrimaryButton
import com.aegis.health.ui.common.ScreenHeader
import com.aegis.health.ui.theme.AegisSpacing
import com.aegis.health.ui.theme.LocalAegisColors

/**
 * Phase 3 — ReportReader screen skeleton (Plan 03-01).
 *
 * This plan lands the empty/landing state ONLY: header + "Pick a lab report PDF"
 * CTA wired to ACTION_OPEN_DOCUMENT with PDF MIME filter. The picker callback
 * for now just discards the resulting Uri (TODO marker for Plan 03-06, which
 * wires parseFromUri / Compose composition / DeferralStore handoff / history
 * insertion).
 *
 * D-07 NotADiagnosisPanel + summary card + LazyColumn of LabRow all land in
 * Plan 03-06 once Wave 2 components are available.
 *
 * (onBack, onDefer) callback signature matches DrugSafeScreen.kt:71-74 exactly
 * so MainActivity's composable entry can mirror DrugSafe's pattern verbatim.
 */
@Composable
fun ReportReaderScreen(
    onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onDefer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAegisColors.current

    // SAF picker — ACTION_OPEN_DOCUMENT filtered to PDF MIME below at .launch().
    // Memory pin: SAFETY-05 — ACTION_OPEN_DOCUMENT requires NO manifest
    // permission entry. Verified by Plan 03-08 PermissionAuditTest.
    val pickPdf = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        // TODO(03-06): wire to ReportReaderPipeline.parseFromUri(uri, context, db)
        // For now this is a skeleton — Wave 3 lands the full parse flow.
        if (uri == null) return@rememberLauncherForActivityResult
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AegisSpacing.xl, vertical = AegisSpacing.xl),
    ) {
        ScreenHeader(
            title = "ReportReader",
            subtitle = "Flag lab values outside the printed range.",
            onBack = onBack,
        )
        Spacer(Modifier.height(22.dp))

        // Landing / empty state — centered "Pick a lab report PDF" CTA.
        // Plan 03-06 replaces this with NotADiagnosisPanel + SummaryCard +
        // LazyColumn of LabRow once a PreparsedReport is in hand. The
        // landing-state shape (this) remains visible when report == null.
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Pick a lab report PDF",
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "We'll flag values outside the printed reference range and explain what each test measures. Nothing leaves your device.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceMuted,
            )
            Spacer(Modifier.height(20.dp))
            PrimaryButton(
                text = "Pick a lab report PDF",
                leading = Icons.Default.Description,
                onClick = { pickPdf.launch(arrayOf("application/pdf")) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
