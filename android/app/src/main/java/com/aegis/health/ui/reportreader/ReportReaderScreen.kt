package com.aegis.health.ui.reportreader

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
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
import com.aegis.health.inference.ToolDispatcher
import com.aegis.health.models.PreparsedReport
import com.aegis.health.models.ReportStatus
import com.aegis.health.reportreader.ReportReaderPipeline
import com.aegis.health.ui.common.FailureInfo
import com.aegis.health.ui.common.MonotonicFlagList
import com.aegis.health.ui.common.PrimaryButton
import com.aegis.health.ui.common.ScreenHeader
import com.aegis.health.ui.common.SectionLabel
import com.aegis.health.ui.common.SeverityCard
import com.aegis.health.ui.common.ToolStepper
import com.aegis.health.ui.deferral.DeferralStore
import com.aegis.health.ui.theme.AegisSpacing
import com.aegis.health.ui.theme.LocalAegisColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 4.1 D-08 — centralized "this report has user-visible rows below the
 * banner" predicate. True for both `"OK"` (named-vendor extraction succeeded)
 * and `"GENERIC_FALLBACK"` (catch-all GenericExtractor matched + aggregate-
 * floor >= 3 rows survived alias-map normalization).
 *
 * Used as the render-branch gate for the rows-present surface in
 * [ReportReaderScreen]. Keeps the OK ↔ GENERIC_FALLBACK widening in a single
 * place so future status-code additions don't scatter conditionals across the
 * LazyColumn cascade.
 */
private val PreparsedReport.hasRows: Boolean
    get() = this.report_status.code == "OK" || this.report_status.code == "GENERIC_FALLBACK"

/**
 * Phase 3 — ReportReader screen, full composition (Plan 03-06).
 *
 * Replaces the Plan 03-01 skeleton with the full LazyColumn-root composition.
 * Three rendering branches keyed off the parsed PreparsedReport:
 *
 *   • report == null && !isLoading: landing state (SAF picker CTA).
 *   • isLoading: ToolStepper live-tools surface (Plan 07-04 D-02b) — mounted
 *       INSIDE this branch's `item { ... }` slot so the non-loading arms'
 *       `headerSlotCount` math (3 or 4 with GenericFallbackBanner) stays
 *       valid. Receives ProgressEvent.Step / FlagPreview / StepFailure from
 *       the new onClinicianCta scope.launch invocation (D-02 Path B —
 *       closes Phase 6 STREAM-01-followup).
 *   • report != null && report.hasRows (OK or GENERIC_FALLBACK): populated state
 *       (LazyColumn root: NotADiagnosisPanel → [GenericFallbackBanner if
 *       GENERIC_FALLBACK] → SummaryCard → LabRow items). Phase 4.1 D-08
 *       widens the OK-only branch to also include GENERIC_FALLBACK so the
 *       catch-all extractor's rows surface to the user.
 *   • report != null && !report.hasRows: empty-state
 *       (NotADiagnosisPanel → ReportEmptyState).
 *
 * NotADiagnosisPanel is always rendered at the top per D-07 — both populated
 * and empty branches show it above the rest. GenericFallbackBanner (Phase
 * 4.1 D-06) sits at slot [2] above SummaryCard when GENERIC_FALLBACK.
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
 * History (D-08 + Phase 4.1 R-03): a HistoryEntity row with KIND_REPORTREADER
 * is inserted on successful OK or GENERIC_FALLBACK parse — non-rows-present
 * parses do NOT insert history (per PATTERNS.md §S-5). GENERIC_FALLBACK
 * provenance is encoded in the persisted report_status.code if/when read back.
 *
 * Memory pin (project_kbdatabase_startup_race): this screen ALWAYS reads
 * AegisApp.instance.database; it never instantiates a parallel KBDatabase.
 *
 * Phase 7 update (Plan 07-04 D-02 Path B): the SummaryCard `onClinicianCta`
 * now runs `ToolDispatcher.runReportReaderFastPath` directly via
 * `scope.launch` (mirroring DrugSafeScreen.kt:184-207) instead of staging
 * the parsed report for DeferralScreen's prior LaunchedEffect to consume.
 * DeferralScreen reverts to deferral-only (D-02a); the synthesis fallback
 * (`AegisResponseBuilder.build`) is now owned by this screen's catch block.
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

    // Plan 06-02 — STREAM-01 + STREAM-02 + ROADMAP SC-1.
    //
    // FlagPreview events from ToolDispatcher.runReportReaderFastPath land
    // here as the synthesis turn streams. Cleared on each new synthesis
    // invocation; replaced by the real SeverityCards once the settled
    // AegisResponse / flag list renders downstream. Dedup is routed through
    // MonotonicFlagList.appendIfNew (ROADMAP SC-5 / M2 mitigation —
    // never-shrink invariant). D-13 single-buffer-owner invariant: this
    // list holds typed `ToolDispatcher.ProgressEvent.FlagPreview` events
    // only — never any reference to the dispatcher's engine-internal decode
    // buffer (the SC-4 grep gate enforces this structurally).
    //
    // Plan 07-04 (D-02 Path B) closed the prior STREAM-01-followup TODO —
    // the synthesis invocation now lives directly inside onClinicianCta
    // below (mirroring DrugSafeScreen.kt:184-207). This makes the
    // flagPreviews state reachable in production for the first time.
    val flagPreviews = remember { mutableStateListOf<ToolDispatcher.ProgressEvent.FlagPreview>() }

    // Plan 07-04 (D-04c) — typed side channel for ProgressEvent.StepFailure
    // events. ToolStepper renders the calm-tone ⚠ chip when an entry exists
    // at the step index. SnapshotStateMap → Compose recomposes only the
    // affected step row on insertion.
    val failures = remember { mutableStateMapOf<Int, FailureInfo>() }

    // Plan 07-04 — progress label list consumed by ToolStepper inside the
    // isLoading LazyColumn branch. Receives ProgressEvent.Step.applyTo
    // appends; cleared on each new synthesis invocation. Phase 5 D-09
    // consumer pattern (no Flow, no ViewModel, no typed StepItem).
    val progress = remember { mutableStateListOf<String>() }

    // SAF launcher — ACTION_OPEN_DOCUMENT(application/pdf). No manifest
    // permission required (SAFETY-05; verified by Plan 03-08 PermissionAuditTest).
    val pickPdf = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isLoading = true
            report = null
            try {
                val parsed = withContext(Dispatchers.IO) {
                    ReportReaderPipeline.parseFromUri(uri, context, AegisApp.instance.database)
                }
                // Phase 4.1 R-03: GENERIC_FALLBACK reports also persist to
                // HistoryEntity (same path as OK; supplements D-08). No new
                // entity column — provenance is encoded in the persisted
                // `report_status.code` if/when read back, and the in-app
                // safety surface (banner + always-defer + 0.4 confidence)
                // is the durable signal on every read.
                // Non-OK / non-GENERIC_FALLBACK parses do NOT insert history.
                if (parsed.report_status.code in setOf("OK", "GENERIC_FALLBACK")) {
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
            } catch (ce: kotlinx.coroutines.CancellationException) {
                // Honour structured-concurrency cancellation: do not swallow it
                // into a UI state. Re-throw so the launching coroutine
                // terminates as the parent scope expects.
                throw ce
            } catch (t: Throwable) {
                // CR-01: post-extract pipeline stages (LabValueParser, RangeEvaluator,
                // KBDatabase queries, vendor extractors, ReportAssembler) are not
                // exception-free. Without this catch, any unexpected throw cancels
                // the coroutine while isLoading is still true and the screen sticks
                // on "Reading your report…" with no recovery path.
                //
                // Surface the failure as a non-OK empty state so the user can retry
                // or hand off to a clinician. Mirrors PdfTextExtractor's own
                // defer-on-crash contract from PdfTextExtractor.kt.
                android.util.Log.e("ReportReaderScreen", "parseFromUri crashed", t)
                report = PreparsedReport(
                    rows = emptyList(),
                    report_status = ReportStatus(
                        code = "UNKNOWN_VENDOR",
                        message = "We could not read this report. Try another PDF or discuss it with a clinician.",
                    ),
                )
            } finally {
                // Load-bearing: even if the catch path's assignment to `report`
                // throws (OOM, etc.), `isLoading` must reset or the UI freezes.
                isLoading = false
            }
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

        // Phase 4.1 D-06 + R-01: generic-fallback banner at slot [2].
        // Renders only when the report was extracted via the catch-all
        // GenericExtractor (i.e. unknown vendor, slot-7 catch-all matched +
        // aggregate-floor >= 3 rows survived). Sits between the always-on
        // NotADiagnosisPanel ([1]) and the SummaryCard / LandingState /
        // Loading caption ([3]) so the "best-effort extraction" framing is
        // read BEFORE the row summary.
        report?.let { current ->
            if (current.report_status.code == "GENERIC_FALLBACK") {
                item {
                    GenericFallbackBanner(modifier = Modifier.fillMaxWidth())
                }
            }
        }

        when {
            isLoading -> {
                // Plan 07-04 (D-02b + Pitfall 2) — ToolStepper mounts INSIDE
                // the isLoading branch so headerSlotCount math at the
                // `report!!.hasRows` arm below stays unchanged (3 or 4 with
                // GenericFallbackBanner). A sibling `if (isLoading) item { }`
                // outside the `when` would silently bump that count by one
                // and re-surface the Phase 4.1 Pitfall 1 chip-tap drift bug.
                item {
                    ToolStepper(
                        label = "Composing lab summary…",
                        steps = progress,
                        modifier = Modifier.fillMaxWidth(),
                        failures = failures,
                    )
                }

                // Plan 06-02 streaming preview rail (STREAM-01 / ROADMAP SC-1).
                // Mirrors DrugSafeScreen.kt:248-262 verbatim, adapted only by
                // virtue of being inside an `item { Column { ... } }` slot
                // (LazyColumn requires composable slots, not direct Column
                // children). Renders only when `flagPreviews.isNotEmpty()` —
                // empty state is "no card" per 06-RESEARCH.md §Open Q #5.
                // No per-item placement animation modifier per RESEARCH
                // §Anti-Patterns (M2 flicker-becomes-visible-slide). The cards
                // disappear when `isLoading` flips to false; the settled
                // AegisResponse SeverityCards take over downstream.
                //
                // Plan 07-04 Pitfall 7 — this rail MUST render BELOW
                // ToolStepper above. Do not reorder.
                if (flagPreviews.isNotEmpty()) {
                    item {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Spacer(Modifier.height(18.dp))
                            val flagWord = if (flagPreviews.size == 1) "flag" else "flags"
                            SectionLabel("Streaming · ${flagPreviews.size} $flagWord so far")
                            Spacer(Modifier.height(10.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                flagPreviews.forEach { preview ->
                                    SeverityCard(
                                        severity = preview.severity,
                                        description = preview.description,
                                        citation = preview.citation,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            report == null -> {
                item {
                    LandingState(onPick = { pickPdf.launch(arrayOf("application/pdf")) })
                }
            }

            report!!.hasRows -> {
                val currentReport = report!!
                val outsideRows = currentReport.rows.filter { it.status == "OUTSIDE_RANGE" }

                // SummaryCard sits in a header slot — count it when offsetting
                // chip-tap targets. Lazy-slot layout differs based on whether
                // the Phase 4.1 GenericFallbackBanner is rendered:
                //
                // OK (banner absent, headerSlotCount = 3):
                //   [0] ScreenHeader item
                //   [1] NotADiagnosisPanel item
                //   [2] SummaryCard item
                //   [3..] LabRow items
                //
                // GENERIC_FALLBACK (banner present, headerSlotCount = 4):
                //   [0] ScreenHeader item
                //   [1] NotADiagnosisPanel item
                //   [2] GenericFallbackBanner item
                //   [3] SummaryCard item
                //   [4..] LabRow items
                //
                // Phase 4.1 Pitfall 1 fix — without this dynamic count the
                // chip-tap animateScrollToItem target would be off-by-one on
                // generic-fallback reports and scroll to the row ABOVE the
                // intended OUTSIDE_RANGE row.
                val headerSlotCount =
                    if (currentReport.report_status.code == "GENERIC_FALLBACK") 4 else 3

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
                            // Plan 07-04 (D-02 Path B) — synthesis invocation
                            // moved from DeferralScreen.kt:98 into this screen
                            // body (closes Phase 6 STREAM-01-followup). Mirrors
                            // DrugSafeScreen.kt:184-207 shape verbatim. The
                            // fallback path (D-05) is preserved by porting
                            // DeferralScreen.kt:115-128 into the catch block
                            // below — AegisResponseBuilder.build(currentReport)
                            // is the Phase-3-shape envelope safety net.
                            scope.launch {
                                isLoading = true
                                progress.clear()
                                flagPreviews.clear()
                                failures.clear()
                                try {
                                    val r = ToolDispatcher.runReportReaderFastPath(
                                        report = currentReport,
                                        onProgress = { event ->
                                            when (event) {
                                                is ToolDispatcher.ProgressEvent.FlagPreview -> {
                                                    val next = MonotonicFlagList.appendIfNew(
                                                        flagPreviews.toList(),
                                                        event,
                                                    )
                                                    if (next.size > flagPreviews.size) {
                                                        flagPreviews.add(event)
                                                    }
                                                }
                                                is ToolDispatcher.ProgressEvent.StepFailure -> {
                                                    // D-04c — typed side channel; ToolStepper
                                                    // renders the calm-tone ⚠ chip at this index.
                                                    val idx = (progress.size - 1).coerceAtLeast(0)
                                                    failures[idx] = FailureInfo(event.label, event.reason)
                                                }
                                                else -> event.applyTo(progress)
                                            }
                                        },
                                    )
                                    DeferralStore.pending = r
                                    onDefer()
                                } catch (ce: kotlinx.coroutines.CancellationException) {
                                    // Honour structured-concurrency cancellation;
                                    // ported verbatim from DeferralScreen.kt:108-114.
                                    throw ce
                                } catch (t: Throwable) {
                                    // D-05 fallback path — visible-but-non-blocking.
                                    // Mirrors DeferralScreen.kt:115-128 (synthesis
                                    // failure collapses to Phase-3-shape envelope).
                                    android.util.Log.e(
                                        "ReportReaderScreen",
                                        "synthesis failed; falling back to Phase-3 envelope",
                                        t,
                                    )
                                    DeferralStore.pending = AegisResponseBuilder.build(currentReport)
                                    onDefer()
                                } finally {
                                    isLoading = false
                                }
                            }
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
private fun LandingState(
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAegisColors.current
    Column(
        modifier = modifier.fillMaxWidth(),
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
