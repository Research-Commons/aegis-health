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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.aegis.health.ui.common.GhostButton
import com.aegis.health.ui.common.LoadingPanel
import com.aegis.health.ui.common.OcrFailBanner
import com.aegis.health.ui.common.PrimaryButton
import com.aegis.health.ui.common.ScreenHeader
import com.aegis.health.ui.common.SectionLabel
import com.aegis.health.ui.common.SeverityCard
import com.aegis.health.ui.common.SummaryPill
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
    val scope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraGranted = granted
        if (granted) showCamera = true
    }

    // Auto-route to deferral if response triggers it.
    LaunchedEffect(response) {
        val r = response ?: return@LaunchedEffect
        if (r.defer_to_professional && r.flags.any { it.severity >= 4 }) {
            onDefer()
        }
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
                            val r = ToolDispatcher.runAgenticLoop(
                                userInput = "Check these drugs for interactions and safety: $drugInput",
                                mode = "drugsafe",
                                onProgress = { it.applyTo(progress) },
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
                            isLoading = false
                        }
                    }
                },
                enabled = drugInput.isNotBlank() && !isLoading,
                modifier = Modifier.weight(1f),
            )
        }

        if (isLoading) {
            Spacer(Modifier.height(24.dp))
            LoadingPanel(
                label = "Analyzing ${drugs.size} medications…",
                steps = progress,
                autoAdvance = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        AnimatedVisibility(visible = response != null && !isLoading, enter = fadeIn()) {
            response?.let { resp ->
                Column {
                    Spacer(Modifier.height(24.dp))
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
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(Icons.Default.Book, null, tint = colors.accent, modifier = Modifier.size(14.dp))
                                    Text(c.source, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                                    Text("· ${c.text}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted)
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
        if (resp.explanation.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                resp.explanation,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
            )
        }
    }
}
