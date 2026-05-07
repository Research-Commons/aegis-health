package com.aegis.health.ui.consentreader

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.aegis.health.AegisApp
import com.aegis.health.camera.CameraPreviewWithCapture
import com.aegis.health.db.history.HistoryEntity
import com.aegis.health.inference.ToolDispatcher
import com.aegis.health.models.AegisResponse
import com.aegis.health.models.TermDefinition
import com.aegis.health.tools.LookupTerm
import com.aegis.health.ui.common.AegisTextField
import com.aegis.health.ui.common.GhostButton
import com.aegis.health.ui.common.LoadingPanel
import com.aegis.health.ui.common.PrimaryButton
import com.aegis.health.ui.common.ScreenHeader
import com.aegis.health.ui.common.SectionLabel
import com.aegis.health.ui.common.TextActionRow
import com.aegis.health.ui.theme.AegisSpacing
import com.aegis.health.ui.theme.LocalAegisColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private enum class Tab { Simple, Original }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsentReaderScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAegisColors.current
    var consentText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var response by remember { mutableStateOf<AegisResponse?>(null) }
    var simplified by remember { mutableStateOf("") }
    var bindingClauses by remember { mutableStateOf<List<String>>(emptyList()) }
    val progress = remember { mutableStateListOf<String>() }
    var tab by remember { mutableStateOf(Tab.Simple) }
    var showCamera by remember { mutableStateOf(false) }
    var cameraGranted by remember { mutableStateOf(false) }
    var openTerm by remember { mutableStateOf<TermDefinition?>(null) }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraGranted = granted
        if (granted) showCamera = true
    }

    if (showCamera && cameraGranted) {
        CameraPreviewWithCapture(
            hint = "Frame the consent form",
            onCancel = { showCamera = false },
            onTextExtracted = { text ->
                if (text.isNotBlank()) consentText = text
                showCamera = false
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
            title = "ConsentReader",
            subtitle = "Plain-language summaries of medical consent forms.",
            onBack = onBack,
            accentColor = colors.secondary,
        )
        Spacer(Modifier.height(18.dp))

        if (response == null && !isLoading) {
            PrimaryButton(
                text = "Photograph form",
                onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                leading = Icons.Default.CameraAlt,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            // OR-divider
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(colors.hairline))
                Text(
                    "  OR PASTE TEXT  ",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceMuted,
                )
                Box(modifier = Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(colors.hairline))
            }
            Spacer(Modifier.height(14.dp))
            AegisTextField(
                value = consentText,
                onValueChange = { consentText = it },
                placeholder = "Paste consent form text…",
                multiline = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            PrimaryButton(
                text = "Simplify",
                onClick = {
                    if (consentText.isNotBlank()) {
                        scope.launch {
                            isLoading = true
                            response = null
                            simplified = ""
                            bindingClauses = emptyList()
                            progress.clear()
                            val r = ToolDispatcher.runAgenticLoop(
                                userInput = "Simplify this consent form and highlight medical terms and binding clauses:\n\n$consentText",
                                mode = "consentreader",
                                onProgress = { it.applyTo(progress) },
                            )
                            response = r
                            simplified = r.explanation
                            bindingClauses = r.flags
                                .filter { it.citation.contains("BINDING", ignoreCase = true) }
                                .map { it.description }
                            withContext(Dispatchers.IO) {
                                val firstLine = consentText.lineSequence()
                                    .firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                                AegisApp.instance.historyDb.history().insert(
                                    HistoryEntity(
                                        kind = HistoryEntity.KIND_CONSENT,
                                        title = if (firstLine.isNotEmpty()) firstLine.take(80)
                                                else "Consent form summary",
                                        sub = if (bindingClauses.isEmpty()) "Simplified"
                                              else "Simplified · ${bindingClauses.size} binding clause${if (bindingClauses.size == 1) "" else "s"}",
                                        severityKey = if (bindingClauses.isEmpty()) HistoryEntity.SEV_INFO
                                                      else HistoryEntity.SEV_MOD,
                                        createdAt = System.currentTimeMillis(),
                                        payloadJson = Json.encodeToString(r),
                                    ),
                                )
                            }
                            isLoading = false
                        }
                    }
                },
                leading = Icons.Default.AutoAwesome,
                enabled = consentText.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (isLoading) {
            Spacer(Modifier.height(20.dp))
            LoadingPanel(
                label = "Reading and simplifying…",
                steps = progress,
                autoAdvance = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        AnimatedVisibility(visible = response != null && !isLoading, enter = fadeIn()) {
            response?.let { _ ->
                Column {
                    ReadingMeta(
                        originalWords = consentText.split(Regex("\\s+")).filter { it.isNotBlank() }.size,
                        simplifiedWords = simplified.split(Regex("\\s+")).filter { it.isNotBlank() }.size,
                    )
                    Spacer(Modifier.height(14.dp))
                    SegmentedPill(active = tab, onChange = { tab = it })
                    Spacer(Modifier.height(16.dp))
                    if (tab == Tab.Simple) {
                        SimplifiedCard(
                            text = simplified,
                            onTermClick = { term ->
                                scope.launch {
                                    val def = withContext(Dispatchers.IO) {
                                        LookupTerm.lookup(
                                            term = term.replace('_', ' ').lowercase(),
                                            db = AegisApp.instance.database,
                                        ).definition ?: TermDefinition(
                                            term = term.replace('_', ' ').lowercase(),
                                            plain_language_definition =
                                                "No definition found in the bundled glossary. Tap Read more for a web search prompt to discuss with your clinician.",
                                            citation = "Bundled medical glossary",
                                        )
                                    }
                                    openTerm = def
                                }
                            },
                        )
                    } else {
                        OriginalCard(text = consentText)
                    }

                    if (bindingClauses.isNotEmpty()) {
                        Spacer(Modifier.height(22.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Flag, null, tint = colors.sevModFg, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(6.dp))
                            SectionLabel("Binding clauses · ${bindingClauses.size}")
                        }
                        Spacer(Modifier.height(10.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            bindingClauses.forEach { clause ->
                                BindingClauseCard(text = clause)
                            }
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                    TextActionRow(
                        text = "Save plain-language summary",
                        leading = Icons.Default.Download,
                        onClick = { /* TODO: save */ },
                    )
                }
            }
        }
    }

    // ── Bottom-sheet term modal ──
    if (openTerm != null) {
        ModalBottomSheet(
            onDismissRequest = { openTerm = null },
            sheetState = sheetState,
            containerColor = colors.surface,
        ) {
            TermSheetContent(
                term = openTerm!!,
                onClose = { openTerm = null },
            )
        }
    }
}

@Composable
private fun ReadingMeta(originalWords: Int, simplifiedWords: Int) {
    val colors = LocalAegisColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .background(
                    color = if (colors.isDark) colors.accentSoft else androidx.compose.ui.graphics.Color(0x140D7377),
                    shape = RoundedCornerShape(99.dp),
                )
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Text(
                "GRADE 5 · READABLE",
                style = MaterialTheme.typography.labelMedium,
                color = colors.accent,
            )
        }
        Text(
            "$originalWords → $simplifiedWords words",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceMuted,
        )
    }
}

@Composable
private fun SegmentedPill(active: Tab, onChange: (Tab) -> Unit) {
    val colors = LocalAegisColors.current
    Row(
        modifier = Modifier
            .background(colors.surfaceAlt, RoundedCornerShape(99.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        SegmentButton(text = "Plain language", selected = active == Tab.Simple, onClick = { onChange(Tab.Simple) })
        SegmentButton(text = "Original", selected = active == Tab.Original, onClick = { onChange(Tab.Original) })
    }
}

@Composable
private fun SegmentButton(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalAegisColors.current
    Box(
        modifier = Modifier
            .background(
                color = if (selected) colors.surface else androidx.compose.ui.graphics.Color.Transparent,
                shape = RoundedCornerShape(99.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) colors.onSurface else colors.onSurfaceMuted,
        )
    }
}

@Composable
private fun SimplifiedCard(text: String, onTermClick: (String) -> Unit) {
    val colors = LocalAegisColors.current
    val annotated = remember(text, colors) { annotateTerms(text, colors.accent) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(18.dp))
            .border(1.dp, colors.hairline, RoundedCornerShape(18.dp))
            .padding(18.dp),
    ) {
        androidx.compose.foundation.text.ClickableText(
            text = annotated,
            style = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface, lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.15f),
            onClick = { offset ->
                annotated.getStringAnnotations("TERM", offset, offset)
                    .firstOrNull()
                    ?.let { onTermClick(it.item) }
            },
        )
    }
}

@Composable
private fun OriginalCard(text: String) {
    val colors = LocalAegisColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceAlt, RoundedCornerShape(18.dp))
            .border(1.dp, colors.hairline, RoundedCornerShape(18.dp))
            .padding(18.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Serif),
            color = colors.onSurfaceMuted,
        )
    }
}

@Composable
private fun BindingClauseCard(text: String) {
    val colors = LocalAegisColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.sevModBg, RoundedCornerShape(16.dp))
            .let { if (colors.isDark) it.border(1.dp, colors.hairline, RoundedCornerShape(16.dp)) else it }
            .padding(14.dp),
    ) {
        Text(
            "“$text”",
            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
            color = if (colors.isDark) colors.onSurface else androidx.compose.ui.graphics.Color(0xFF3B3733),
        )
    }
}

@Composable
private fun TermSheetContent(term: TermDefinition, onClose: () -> Unit) {
    val colors = LocalAegisColors.current
    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(start = 22.dp, end = 22.dp, bottom = 28.dp)) {
        Text(
            "MEDICAL TERM",
            style = MaterialTheme.typography.labelMedium,
            color = colors.accent,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            term.term,
            style = MaterialTheme.typography.headlineMedium,
            color = colors.onSurface,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            term.plain_language_definition,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurface,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            term.citation,
            style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
            color = colors.onSurfaceMuted,
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton(text = "Close", onClick = onClose, modifier = Modifier.weight(1f))
            PrimaryButton(text = "Read more", onClick = onClose, leading = Icons.AutoMirrored.Filled.MenuBook, modifier = Modifier.weight(1f))
        }
    }
}

private fun annotateTerms(text: String, accent: androidx.compose.ui.graphics.Color): AnnotatedString {
    val regex = Regex("""\[([A-Z_]+)]""")
    return buildAnnotatedString {
        var cursor = 0
        regex.findAll(text).forEach { match ->
            append(text.substring(cursor, match.range.first))
            val raw = match.groupValues[1]
            val display = raw.replace('_', ' ').lowercase()
            pushStringAnnotation(tag = "TERM", annotation = raw)
            withStyle(
                SpanStyle(
                    color = accent,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = TextDecoration.Underline,
                ),
            ) {
                append(display)
            }
            pop()
            cursor = match.range.last + 1
        }
        if (cursor < text.length) append(text.substring(cursor))
    }
}
