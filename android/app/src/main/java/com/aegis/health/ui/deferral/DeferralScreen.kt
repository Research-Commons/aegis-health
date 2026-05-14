package com.aegis.health.ui.deferral

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aegis.health.inference.ToolDispatcher
import com.aegis.health.inference.ToolDispatcher.ProgressEvent
import com.aegis.health.models.AegisResponse
import com.aegis.health.ui.common.GhostButton
import com.aegis.health.ui.common.LoadingPanel
import com.aegis.health.ui.common.PrimaryButton
import com.aegis.health.ui.common.ScreenHeader
import com.aegis.health.ui.common.SectionLabel
import com.aegis.health.ui.reportreader.AegisResponseBuilder
import com.aegis.health.ui.theme.AegisSpacing
import com.aegis.health.ui.theme.LocalAegisColors

/**
 * Full-screen route shown when [AegisResponse.defer_to_professional] is true
 * AND any flag's severity is ≥ 4. Replaces the inline DeferralCard for the
 * critical case — the inline DeferralCard is still used as a summary block.
 *
 * Pass an optional [response] for a clinician-ready summary; when null, the
 * screen falls back to a generic deferral message OR — for the Phase 4 D-06
 * ReportReader path — checks DeferralStore.pendingReport for a synthesis-
 * pending marker and runs ToolDispatcher.runReportReaderFastPath in a
 * LaunchedEffect, rendering a LoadingPanel + preview chips while the
 * synthesis turn runs.
 *
 * D-05 fallback: if synthesis throws (native crash, JSON parse failure, prose
 * parse null, sanitization rejection), the catch block builds the Phase-3-shape
 * AegisResponse via AegisResponseBuilder.build(report) and renders a muted
 * banner — `"On-device summary unavailable for this report."` — above the
 * header. Phase 5 REGULATORY.md audits this copy verbatim.
 *
 * DrugSafe/HealthPartner producers continue to pass `response` directly OR set
 * DeferralStore.pending before navigation — the legacy path is preserved.
 */
@Composable
fun DeferralScreen(
    onBack: () -> Unit,
    response: AegisResponse? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAegisColors.current

    // Snapshot the synthesis-pending marker ONCE at entry. Recomposition must
    // not re-trigger inference, and the LaunchedEffect's key must be a stable
    // value tied to this screen entry — re-keying mid-run would cancel and
    // restart synthesis on every recomposition.
    val initialPendingReport = remember { DeferralStore.pendingReport }
    var resolvedResponse by remember {
        mutableStateOf<AegisResponse?>(response ?: DeferralStore.pending)
    }
    var synthesisRunning by remember {
        mutableStateOf(initialPendingReport != null && DeferralStore.pending == null)
    }
    var bannerVisible by remember {
        mutableStateOf(!DeferralStore.synthesisAvailable)
    }
    val stepsState = remember { mutableStateListOf<String>("Reading report") }
    val flagPreviews = remember { mutableStateListOf<ProgressEvent.FlagPreview>() }

    LaunchedEffect(initialPendingReport) {
        val report = initialPendingReport ?: return@LaunchedEffect
        try {
            val result = ToolDispatcher.runReportReaderFastPath(report) { ev ->
                ev.applyTo(stepsState)
                if (ev is ProgressEvent.FlagPreview) flagPreviews.add(ev)
            }
            DeferralStore.pending = result
            DeferralStore.pendingReport = null
            DeferralStore.synthesisAvailable = true
            resolvedResponse = result
            bannerVisible = false
            synthesisRunning = false
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // Honour structured-concurrency cancellation. The user pressed
            // back during synthesis; DisposableEffect-style cleanup below
            // clears the pendingReport marker so re-entering DeferralScreen
            // doesn't kick off another inference run.
            DeferralStore.pendingReport = null
            throw ce
        } catch (t: Throwable) {
            // D-05 fallback path. Visible-but-non-blocking. Synthesis failure
            // (inference native crash, JsonDecodingException, ProseParser null,
            // sanitizer reject) collapses to the Phase-3-shape envelope plus
            // a muted banner. See 04-CONTEXT.md D-05 lines 181-206.
            Log.e("DeferralScreen", "ReportReader synthesis failed; falling back to Phase-3 envelope", t)
            val fallback = AegisResponseBuilder.build(report)
            DeferralStore.pending = fallback
            DeferralStore.pendingReport = null
            DeferralStore.synthesisAvailable = false
            resolvedResponse = fallback
            bannerVisible = true
            synthesisRunning = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AegisSpacing.xl, vertical = AegisSpacing.xl),
    ) {
        // D-05 muted banner — visible only on the synthesis-fallback path.
        // Style matches NotADiagnosisPanel (the plan's named visual analog):
        // surfaceAlt background + onSurfaceMuted text. No icon — maintains
        // the "non-blocking" feel mandated by D-05 line 198.
        //
        // Banner text is the EXACT string Phase 5 REGULATORY.md will audit.
        // Do not edit without updating the regulatory audit checklist.
        if (bannerVisible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceAlt, RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = "On-device summary unavailable for this report.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceMuted,
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        if (synthesisRunning) {
            // Pending-synthesis branch (D-06). Preview chips above the
            // LoadingPanel show tentative flags as the model emits them —
            // these will be overwritten by Kotlin-computed flags after
            // enforceModeContract, but they give the user something to watch
            // during the 60-180s synthesis turn.
            ScreenHeader(
                title = "Composing lab summary",
                subtitle = "Aegis is summarizing the flagged values from this report.",
                onBack = onBack,
            )
            Spacer(Modifier.height(20.dp))

            if (flagPreviews.isNotEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    flagPreviews.forEach { preview ->
                        val (chipBg, chipFg) = when {
                            preview.severity >= 4 -> colors.sevCritBg to colors.sevCritFg
                            preview.severity >= 3 -> colors.sevModBg to colors.sevModFg
                            else -> colors.sevLowBg to colors.sevLowFg
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(chipBg, RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = preview.description.take(96),
                                style = MaterialTheme.typography.bodySmall,
                                color = chipFg,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            LoadingPanel(
                label = "Composing lab summary…",
                steps = stepsState,
                autoAdvance = false,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            ScreenHeader(
                title = "Talk to a clinician",
                subtitle = "Aegis flagged this assessment as needing professional review.",
                onBack = onBack,
            )
            Spacer(Modifier.height(20.dp))

            // ── Critical card ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.sevCritBg, RoundedCornerShape(18.dp))
                    .let { if (colors.isDark) it.border(1.dp, colors.hairline, RoundedCornerShape(18.dp)) else it }
                    .padding(20.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Default.Error, null, tint = colors.sevCritFg, modifier = Modifier.size(20.dp))
                    Text(
                        "DEFERRAL · DO NOT ACT ALONE",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.sevCritFg,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Your medication combination crosses a high-severity threshold.",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (colors.isDark) colors.onSurface else Color(0xFF1A1816),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    resolvedResponse?.explanation?.takeIf { it.isNotBlank() }
                        ?: "Aegis is conservative on purpose. The flagged interaction requires a clinician's review before any change. Aegis will not give you a course of action here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (colors.isDark) colors.onSurfaceMuted else Color(0xFF3B3733),
                )
            }

            Spacer(Modifier.height(18.dp))
            SectionLabel("Bring this to your appointment")
            Spacer(Modifier.height(10.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface, RoundedCornerShape(16.dp))
                    .border(1.dp, colors.hairline, RoundedCornerShape(16.dp))
                    .padding(16.dp),
            ) {
                Text(
                    "Summary for your clinician",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                )
                Spacer(Modifier.height(10.dp))
                val summary = resolvedResponse?.flags?.sortedByDescending { it.severity }?.take(4)
                if (!summary.isNullOrEmpty()) {
                    summary.forEach { f ->
                        BulletLine("${f.description}  ·  ${f.citation}")
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Confidence ${((resolvedResponse?.confidence ?: 0.0) * 100).toInt()}%.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceMuted,
                    )
                } else {
                    BulletLine("Patient medication list and current symptoms.")
                    BulletLine("Triggering interaction or finding flagged by Aegis.")
                    BulletLine("Source citation from RxNorm / DrugBank / USPSTF.")
                    BulletLine("Severity rating and confidence reported by Aegis.")
                }
            }

            Spacer(Modifier.height(22.dp))
            PrimaryButton(
                text = "Save summary as PDF",
                onClick = { /* TODO: hook to export */ },
                leading = Icons.Default.Download,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            GhostButton(
                text = "Find an urgent-care clinic",
                onClick = { /* TODO: deep link to maps */ },
                leading = Icons.Default.LocalHospital,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun BulletLine(text: String) {
    val colors = LocalAegisColors.current
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("·", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceMuted, modifier = Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceMuted,
            modifier = Modifier.weight(1f),
        )
    }
}
