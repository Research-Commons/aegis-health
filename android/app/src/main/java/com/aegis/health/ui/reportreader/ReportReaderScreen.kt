package com.aegis.health.ui.reportreader

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aegis.health.AegisApp
import com.aegis.health.db.history.HistoryEntity
import com.aegis.health.models.PreparsedReport
import com.aegis.health.reportreader.ReportReaderPipeline
import com.aegis.health.ui.common.PrimaryButton
import com.aegis.health.ui.common.ScreenHeader
import com.aegis.health.ui.deferral.DeferralStore
import com.aegis.health.ui.theme.AegisSpacing
import com.aegis.health.ui.theme.LocalAegisColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 3 — ReportReader screen, full composition (Plan 03-06).
 *
 * Replaces the Plan 03-01 skeleton with the full LazyColumn-root composition.
 * Three rendering branches keyed off the parsed PreparsedReport:
 *
 *   • report == null && !isLoading: landing state (SAF picker CTA).
 *   • isLoading: inline "Reading your report…" caption.
 *   • report != null && report.report_status.code == "OK": populated state
 *       (LazyColumn root: NotADiagnosisPanel → SummaryCard → LabRow items).
 *   • report != null && report.report_status.code != "OK": empty-state
 *       (NotADiagnosisPanel → ReportEmptyState).
 *
 * NotADiagnosisPanel is always rendered at the top per D-07 — both populated
 * and empty branches show it above the rest.
 *
 * Three deferral CTAs all route through AegisResponseBuilder + DeferralStore
 * + the parent's defer callback for navigation:
 *
 *   1. SummaryCard "Bring this to your clinician" → builder.build(report)
 *   2. Per-row Discuss button (D-05)           → builder.buildForRow(report, row)
 *   3. ReportEmptyState Discuss button (D-06)   → builder.buildForStatus(report, code, msg)
 *
 * SummaryCard chip-strip taps animate-scroll the LazyColumn to the matching
 * row (D-03 mandate: animateScrollToItem with default spec). The LazyColumn
 * is the screen root so this works — NotADiagnosisPanel and SummaryCard live
 * in `item { ... }` slots above the `items(rows) { ... }` block, and the
 * chip-tap callback offsets the OUTSIDE_RANGE index by the header-slot count
 * to land on the correct row.
 *
 * History (D-08): a HistoryEntity row with KIND_REPORTREADER is inserted on
 * successful OK parse only — non-OK parses do NOT insert history (per
 * PATTERNS.md §S-5).
 *
 * Memory pin (project_kbdatabase_startup_race): this screen ALWAYS reads
 * AegisApp.instance.database; it never instantiates a parallel KBDatabase.
 *
 * Phase 3 hackathon de-risking guarantee: the model is NEVER loaded — no
 * engine, dispatcher, runtime, or synthesis-fast-path is invoked anywhere
 * in this file. Phase 4 wires the synthesis turn.
 */
@Composable
fun ReportReaderScreen(
    onBack: () -> Unit,
    onDefer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAegisColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()

    var isLoading by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<PreparsedReport?>(null) }

    // SAF launcher — ACTION_OPEN_DOCUMENT(application/pdf). No manifest
    // permission required (SAFETY-05; verified by Plan 03-08 PermissionAuditTest).
    val pickPdf = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isLoading = true
            report = null
            val parsed = withContext(Dispatchers.IO) {
                ReportReaderPipeline.parseFromUri(uri, context, AegisApp.instance.database)
            }
            // History insertion on successful OK parse only (PATTERNS.md §S-5).
            // Non-OK parses do NOT insert history.
            if (parsed.report_status.code == "OK") {
                val flaggedCount = parsed.rows.count { it.status != "IN_RANGE" }
                val sevKey =
                    if (parsed.has_outside_range) HistoryEntity.SEV_CRIT
                    else if (parsed.has_unknown) HistoryEntity.SEV_LOW
                    else if (flaggedCount > 0) HistoryEntity.SEV_MOD
                    else HistoryEntity.SEV_INFO
                withContext(Dispatchers.IO) {
                    AegisApp.instance.historyDb.history().insert(
                        HistoryEntity(
                            kind = HistoryEntity.KIND_REPORTREADER,
                            title = "Lab Report",
                            sub = "$flaggedCount flagged of ${parsed.rows.size} values",
                            severityKey = sevKey,
                            createdAt = System.currentTimeMillis(),
                            payloadJson = "",
                        ),
                    )
                }
            }
            report = parsed
            isLoading = false
        }
    }

    // LazyColumn-root layout — mandatory per D-03 chip-tap smooth-scroll.
    // ScreenHeader, NotADiagnosisPanel, SummaryCard, and ReportEmptyState
    // all live in `item { ... }` slots; LabRow lives in `items(rows) { ... }`.
    // Compose forbids nesting LazyColumn inside a scroll-modified Column;
    // by making LazyColumn the root, we satisfy both the smooth-scroll
    // requirement and Compose's nested-scrollable rule.
    LazyColumn(
        state = lazyListState,
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .padding(horizontal = AegisSpacing.xl, vertical = AegisSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(AegisSpacing.md),
    ) {
        item {
            ScreenHeader(
                title = "ReportReader",
                subtitle = "Flag lab values outside the printed range.",
                onBack = onBack,
            )
            Spacer(Modifier.height(22.dp))
        }

        // D-07: always-on collapsible disclaimer, above everything else.
        item {
            NotADiagnosisPanel(modifier = Modifier.fillMaxWidth())
        }

        when {
            isLoading -> {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = AegisSpacing.lg),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Reading your report…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceMuted,
                        )
                    }
                }
            }

            report == null -> {
                item {
                    LandingState(onPick = { pickPdf.launch(arrayOf("application/pdf")) })
                }
            }

            report!!.report_status.code == "OK" -> {
                val currentReport = report!!
                val outsideRows = currentReport.rows.filter { it.status == "OUTSIDE_RANGE" }

                // SummaryCard sits in a header slot — count it when offsetting
                // chip-tap targets. The lazy-slot layout above SummaryCard:
                //   [0] ScreenHeader item
                //   [1] NotADiagnosisPanel item
                //   [2] SummaryCard item
                //   [3..] LabRow items (one per row in currentReport.rows)
                // chip-tap target index = 3 + currentReport.rows.indexOf(targetRow)
                val headerSlotCount = 3

                item {
                    SummaryCard(
                        outsideRows = outsideRows,
                        totalCount = currentReport.rows.size,
                        onChipTap = { rowIndexInOutsideList ->
                            val targetRow = outsideRows.getOrNull(rowIndexInOutsideList)
                                ?: return@SummaryCard
                            val rowIndex = currentReport.rows.indexOf(targetRow).coerceAtLeast(0)
                            val globalIndex = headerSlotCount + rowIndex
                            scope.launch {
                                lazyListState.animateScrollToItem(globalIndex)
                            }
                        },
                        onClinicianCta = {
                            DeferralStore.pending = AegisResponseBuilder.build(currentReport)
                            onDefer()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                items(currentReport.rows) { row ->
                    LabRow(
                        row = row,
                        onDiscuss = { tappedRow ->
                            DeferralStore.pending = AegisResponseBuilder.buildForRow(
                                currentReport,
                                tappedRow,
                            )
                            onDefer()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Citations footer (muted attribution; only when present).
                if (currentReport.citations.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(AegisSpacing.sm))
                        Text(
                            "Sources",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.onSurfaceMuted,
                        )
                    }
                    items(currentReport.citations) { c ->
                        Text(
                            text = "• ${c.label} — ${c.url}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceMuted,
                        )
                    }
                }
            }

            else -> {
                val currentReport = report!!
                item {
                    ReportEmptyState(
                        statusCode = currentReport.report_status.code,
                        statusMessage = currentReport.report_status.message,
                        onPickAnother = { pickPdf.launch(arrayOf("application/pdf")) },
                        onDiscuss = {
                            DeferralStore.pending = AegisResponseBuilder.buildForStatus(
                                report = currentReport,
                                statusCode = currentReport.report_status.code,
                                statusMessage = currentReport.report_status.message,
                            )
                            onDefer()
                        },
                    )
                }
            }
        }
    }
}

// ── Subcomposables (file-private) ─────────────────────────────────────────

@Composable
private fun LandingState(onPick: () -> Unit) {
    val colors = LocalAegisColors.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Pick a lab report PDF",
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
        )
        Spacer(Modifier.height(AegisSpacing.sm))
        Text(
            "We'll flag values outside the printed reference range and explain what each test measures. Nothing leaves your device.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceMuted,
        )
        Spacer(Modifier.height(AegisSpacing.lg))
        PrimaryButton(
            text = "Pick a lab report PDF",
            leading = Icons.Default.Description,
            onClick = onPick,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
