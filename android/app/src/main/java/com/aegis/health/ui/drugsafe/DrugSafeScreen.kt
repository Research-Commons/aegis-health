package com.aegis.health.ui.drugsafe

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import com.aegis.health.AegisApp
import com.aegis.health.camera.CameraPreviewWithCapture
import com.aegis.health.camera.DrugNameExtractor
import com.aegis.health.db.history.HistoryEntity
import com.aegis.health.db.history.severityKeyFor
import com.aegis.health.inference.ToolDispatcher
import com.aegis.health.models.AegisResponse
import com.aegis.health.ui.common.AegisChip
import com.aegis.health.ui.common.AegisTextField
import com.aegis.health.ui.common.ConfidenceDot
import com.aegis.health.ui.common.DeferralBanner
import com.aegis.health.ui.common.FailureInfo
import com.aegis.health.ui.common.GhostButton
import com.aegis.health.ui.common.OcrFailBanner
import com.aegis.health.ui.common.PrimaryButton
import com.aegis.health.ui.common.ScreenHeader
import com.aegis.health.ui.common.SectionLabel
import com.aegis.health.ui.common.SeverityCard
import com.aegis.health.ui.common.SummaryPill
import com.aegis.health.ui.common.ToolStepper
import com.aegis.health.ui.deferral.DeferralStore
import com.aegis.health.ui.theme.AegisSpacing
import com.aegis.health.ui.theme.LocalAegisColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Composable
fun DrugSafeScreen(
    onBack: () -> Unit,
    onDefer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAegisColors.current
    var drugInput by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var response by remember { mutableStateOf<AegisResponse?>(null) }
    var showCamera by remember { mutableStateOf(false) }
    var cameraGranted by remember { mutableStateOf(false) }
    var ocrFailed by remember { mutableStateOf(false) }
    val progress = remember { mutableStateListOf<String>() }
    // FlagPreview events from ToolDispatcher land here as the synthesis turn
    // streams. Cleared on each new "Check" click; replaced by the real
    // SeverityCards once `response` is populated.
    val flagPreviews = remember { mutableStateListOf<ToolDispatcher.ProgressEvent.FlagPreview>() }
    // Phase 7 D-04c — typed side channel for ProgressEvent.StepFailure events.
    // ToolStepper renders the calm-tone ⚠ chip when an entry exists at the step index.
    val failures = remember { mutableStateMapOf<Int, FailureInfo>() }
    val scope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraGranted = granted
        if (granted) showCamera = true
    }

    if (showCamera && cameraGranted) {
        CameraPreviewWithCapture(
            hint = "Aim at the active-ingredient line",
            onCancel = { showCamera = false },
            onTextExtracted = { rawText ->
                showCamera = false
                if (rawText.isBlank()) {
                    ocrFailed = true
                    return@CameraPreviewWithCapture
                }
                scope.launch {
                    val db = AegisApp.instance.database
                    val result = withContext(Dispatchers.IO) {
                        DrugNameExtractor.extract(rawText, db)
                    }
                    if (result.canonical.isNotEmpty()) {
                        drugInput = result.canonical.joinToString(", ")
                        ocrFailed = false
                    } else {
                        ocrFailed = true
                    }
                }
            },
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AegisSpacing.xl, vertical = AegisSpacing.xl),
    ) {
        ScreenHeader(
            title = "DrugSafe",
            subtitle = "Check interactions, warnings, and safety flags.",
            onBack = onBack,
        )
        Spacer(Modifier.height(22.dp))

        if (ocrFailed) {
            OcrFailBanner(onRetry = {
                ocrFailed = false
                permissionLauncher.launch(Manifest.permission.CAMERA)
            })
            Spacer(Modifier.height(12.dp))
        }

        AegisTextField(
            value = drugInput,
            onValueChange = { drugInput = it },
            label = "Medications",
            placeholder = "e.g. ibuprofen, lisinopril, metformin",
            leading = Icons.Default.Search,
            multiline = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // Parsed drug chips — derived from the comma-separated input.
        val drugs = drugInput.split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (drugs.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                drugs.take(6).forEach { d -> AegisChip(text = d, selected = true) }
            }
        }

        Spacer(Modifier.height(18.dp))

        // ── Action row ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GhostButton(
                text = "Scan label",
                leading = Icons.Default.CameraAlt,
                onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.weight(1f),
            )
            PrimaryButton(
                text = "Check",
                leading = Icons.Default.AutoAwesome,
                onClick = {
                    if (drugInput.isNotBlank()) {
                        scope.launch {
                            isLoading = true
                            response = null
                            progress.clear()
                            flagPreviews.clear()
                            failures.clear()
                            try {
                                val r = ToolDispatcher.runDrugSafeFastPath(
                                    userInput = drugInput,
                                    onProgress = { event ->
                                        // Phase 7 D-04c — typed `when` with explicit
                                        // StepFailure branch routing through the
                                        // screen-scope `failures` side channel.
                                        // FlagPreview retains its Phase 6 inline
                                        // dedup filter (DrugSafe's heuristic; the
                                        // STREAM-02 wiring-parity matcher relaxes
                                        // for DrugSafe — see 06-03 close-out). The
                                        // `else` branch covers Step + Update via
                                        // the shared `applyTo` contract.
                                        when (event) {
                                            is ToolDispatcher.ProgressEvent.FlagPreview -> {
                                                if (flagPreviews.none { it.description == event.description && it.citation == event.citation }) {
                                                    flagPreviews.add(event)
                                                }
                                            }
                                            is ToolDispatcher.ProgressEvent.StepFailure -> {
                                                val idx = (progress.size - 1).coerceAtLeast(0)
                                                failures[idx] = FailureInfo(event.label, event.reason)
                                            }
                                            else -> event.applyTo(progress)
                                        }
                                    },
                                )
                                response = r
                                withContext(Dispatchers.IO) {
                                    AegisApp.instance.historyDb.history().insert(
                                        HistoryEntity(
                                            kind = HistoryEntity.KIND_DRUGSAFE,
                                            title = drugInput.trim(),
                                            sub = if (r.flags.isEmpty()) "No flags"
                                                  else "${r.flags.size} flag${if (r.flags.size == 1) "" else "s"}",
                                            severityKey = severityKeyFor(r),
                                            createdAt = System.currentTimeMillis(),
                                            payloadJson = Json.encodeToString(r),
                                        ),
                                    )
                                }
                            } catch (ce: kotlinx.coroutines.CancellationException) {
                                // Honour structured-concurrency cancellation (Phase 7
                                // CR-02; ReportReaderScreen.kt:417-420 reference).
                                throw ce
                            } catch (t: Throwable) {
                                // Plan 07-07 CR-02 — secondary guard for OOM / JNI
                                // crash / withContext IO failure / history-insert
                                // exception. Plan 07-06's catch-and-emit-StepFailure
                                // path covers the tool-throws case; this catch is
                                // the screen-side belt-and-suspenders so the user is
                                // NEVER stranded on an infinite spinner.
                                android.util.Log.e("DrugSafeScreen", "fast-path crashed", t)
                                // Populate the failures map at the latest step index
                                // so STEP-06's calm-tone ⚠ chip renders on the row
                                // that was running when the exception fired.
                                val idx = (progress.size - 1).coerceAtLeast(0)
                                failures[idx] = FailureInfo(
                                    label = progress.getOrNull(idx) ?: "Drug safety check",
                                    reason = t.message ?: "On-device check failed",
                                )
                            } finally {
                                isLoading = false
                            }
                        }
                    }
                },
                enabled = drugInput.isNotBlank() && !isLoading,
                modifier = Modifier.weight(1f),
            )
        }

        if (isLoading) {
            Spacer(Modifier.height(24.dp))
            ToolStepper(
                label = "Analyzing ${drugs.size} medications…",
                steps = progress,
                modifier = Modifier.fillMaxWidth(),
                failures = failures,
            )

            // Phase B: streaming preview cards. Appear as ToolDispatcher's
            // FlagsStreamParser emits each completed flag during the
            // synthesis decode (~30s after Check is tapped on SD8G2,
            // vs ~155s for the full response). The cards vanish when
            // `isLoading` flips to false; the real Findings section then
            // takes over via the AnimatedVisibility block below. Render
            // in arrival order — sorting them on the fly would rearrange
            // visible cards each time a new one streams in.
            if (flagPreviews.isNotEmpty()) {
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

        AnimatedVisibility(visible = response != null && !isLoading, enter = fadeIn()) {
            response?.let { resp ->
                val needsDefer = resp.defer_to_professional && resp.flags.any { it.severity >= 4 }
                Column {
                    Spacer(Modifier.height(24.dp))
                    if (needsDefer) {
                        DeferralBanner(
                            title = "This combination needs a clinician's review.",
                            body = "Aegis flagged a high-severity interaction below. Read the findings, then bring a summary to your provider.",
                            onClick = {
                                DeferralStore.pending = resp
                                onDefer()
                            },
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                    ResultSummaryCard(resp)
                    if (resp.flags.isNotEmpty()) {
                        Spacer(Modifier.height(18.dp))
                        SectionLabel("Findings · ${resp.flags.size}")
                        Spacer(Modifier.height(10.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            resp.flags
                                .sortedByDescending { it.severity }
                                .forEach { flag ->
                                    SeverityCard(
                                        severity = flag.severity,
                                        description = flag.description,
                                        citation = flag.citation,
                                    )
                                }
                        }
                    }
                    if (resp.citations.isNotEmpty()) {
                        Spacer(Modifier.height(20.dp))
                        SectionLabel("Sources")
                        Spacer(Modifier.height(10.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            resp.citations.forEach { c ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(colors.surfaceAlt, RoundedCornerShape(12.dp))
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Book,
                                        null,
                                        tint = colors.accent,
                                        modifier = Modifier
                                            .size(14.dp)
                                            .padding(top = 3.dp),
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            c.source,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = colors.onSurface,
                                        )
                                        if (c.text.isNotBlank()) {
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                c.text,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = colors.onSurfaceMuted,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultSummaryCard(resp: AegisResponse) {
    val colors = LocalAegisColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(18.dp))
            .border(1.dp, colors.hairline, RoundedCornerShape(18.dp))
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SummaryPill()
            Box(modifier = Modifier.weight(1f))
            ConfidenceDot(confidence = resp.confidence)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            resp.explanation.ifBlank { fallbackSummary(resp) },
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurface,
        )
    }
}

private fun fallbackSummary(resp: AegisResponse): String {
    val highSeverity = resp.flags.any { it.severity >= 4 }
    return when {
        resp.flags.isEmpty() && resp.defer_to_professional ->
            "No interactions identified, but a clinician should review this combination before you proceed."
        resp.flags.isEmpty() ->
            "No safety issues found in the local knowledge base for the medications you entered."
        highSeverity ->
            "Found ${resp.flags.size} safety concern${if (resp.flags.size == 1) "" else "s"}, including a high-severity issue. Review the findings below."
        else ->
            "Found ${resp.flags.size} safety concern${if (resp.flags.size == 1) "" else "s"}. Review the findings below."
    }
}
