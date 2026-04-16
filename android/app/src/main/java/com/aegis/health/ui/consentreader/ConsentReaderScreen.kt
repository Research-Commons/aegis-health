package com.aegis.health.ui.consentreader

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aegis.health.camera.CameraPreviewWithCapture
import com.aegis.health.inference.ToolDispatcher
import com.aegis.health.models.AegisResponse
import com.aegis.health.render.AegisResponseView
import com.aegis.health.render.SimplifiedText
import com.aegis.health.ui.theme.AegisBlue
import com.aegis.health.ui.theme.SeverityAmber
import kotlinx.coroutines.launch

@Composable
fun ConsentReaderScreen(modifier: Modifier = Modifier) {
    var consentText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var response by remember { mutableStateOf<AegisResponse?>(null) }
    var simplifiedText by remember { mutableStateOf("") }
    var bindingClauses by remember { mutableStateOf<List<String>>(emptyList()) }
    var showOriginal by remember { mutableStateOf(false) }
    var showCamera by remember { mutableStateOf(false) }
    var cameraPermissionGranted by remember { mutableStateOf(false) }
    var selectedTermDef by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraPermissionGranted = granted
        if (granted) showCamera = true
    }

    if (showCamera && cameraPermissionGranted) {
        CameraPreviewWithCapture(
            onTextExtracted = { text ->
                if (text.isNotBlank()) {
                    consentText = text
                }
                showCamera = false
            },
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Description,
                contentDescription = null,
                tint = AegisBlue,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "ConsentReader",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = AegisBlue,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Simplify medical consent forms into plain language",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))

        // Camera button
        Button(
            onClick = {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.CameraAlt, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Photograph Consent Form")
        }

        Spacer(Modifier.height(12.dp))

        // Manual text input
        OutlinedTextField(
            value = consentText,
            onValueChange = { consentText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Or paste consent form text") },
            minLines = 4,
            maxLines = 10,
        )

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                if (consentText.isNotBlank()) {
                    scope.launch {
                        isLoading = true
                        response = null
                        simplifiedText = ""
                        bindingClauses = emptyList()

                        val result = ToolDispatcher.runAgenticLoop(
                            userInput = "Simplify this consent form and highlight medical terms and binding clauses:\n\n$consentText",
                            mode = "consentreader",
                        )
                        response = result
                        simplifiedText = result.explanation
                        bindingClauses = result.flags
                            .filter { it.citation.contains("BINDING", ignoreCase = true) }
                            .map { it.description }
                        isLoading = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = consentText.isNotBlank() && !isLoading,
        ) {
            Icon(Icons.Default.Description, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Analyze Consent Form")
        }

        Spacer(Modifier.height(20.dp))

        // Loading
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = AegisBlue,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Reading and simplifying…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Results
        AnimatedVisibility(
            visible = response != null && !isLoading,
            enter = fadeIn(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // View toggle
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !showOriginal,
                        onClick = { showOriginal = false },
                        label = { Text("Simplified") },
                    )
                    FilterChip(
                        selected = showOriginal,
                        onClick = { showOriginal = true },
                        label = { Text("Original") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.SwapHoriz,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                }

                if (showOriginal) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Text(
                            text = consentText,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                } else {
                    // Simplified text with term highlights
                    if (simplifiedText.isNotBlank()) {
                        SimplifiedText(
                            text = simplifiedText,
                            onTermClick = { term ->
                                selectedTermDef = term
                            },
                        )
                    }
                }

                // Binding clauses
                if (bindingClauses.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Gavel,
                            contentDescription = null,
                            tint = SeverityAmber,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Binding Clauses",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = SeverityAmber,
                        )
                    }
                    bindingClauses.forEach { clause ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = SeverityAmber.copy(alpha = 0.08f),
                            ),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Text(
                                text = clause,
                                style = MaterialTheme.typography.bodyMedium,
                                fontStyle = FontStyle.Italic,
                                modifier = Modifier.padding(14.dp),
                            )
                        }
                    }
                }

                // Full response view
                response?.let { resp ->
                    if (resp.flags.isNotEmpty() || resp.citations.isNotEmpty()) {
                        AegisResponseView(response = resp)
                    }
                }

                // Selected term definition
                selectedTermDef?.let { term ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = AegisBlue.copy(alpha = 0.08f),
                        ),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = term,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = AegisBlue,
                            )
                            Text(
                                text = "Tap to look up this medical term.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
