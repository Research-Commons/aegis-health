package com.aegis.health.ui.drugsafe

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
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aegis.health.AegisApp
import com.aegis.health.camera.CameraPreviewWithCapture
import com.aegis.health.camera.DrugNameExtractor
import com.aegis.health.inference.ToolDispatcher
import com.aegis.health.models.AegisResponse
import com.aegis.health.render.AegisResponseView
import com.aegis.health.ui.theme.AegisTeal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DrugSafeScreen(modifier: Modifier = Modifier) {
    var drugInput by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var response by remember { mutableStateOf<AegisResponse?>(null) }
    var showCamera by remember { mutableStateOf(false) }
    var cameraPermissionGranted by remember { mutableStateOf(false) }
    var scanHint by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraPermissionGranted = granted
        if (granted) showCamera = true
    }

    if (showCamera && cameraPermissionGranted) {
        CameraPreviewWithCapture(
            onTextExtracted = { rawText ->
                showCamera = false
                if (rawText.isBlank()) {
                    scanHint = "No text detected. Try again with better lighting."
                    return@CameraPreviewWithCapture
                }
                scope.launch {
                    val db = AegisApp.instance.database
                    val result = withContext(Dispatchers.IO) {
                        DrugNameExtractor.extract(rawText, db)
                    }
                    if (result.canonical.isNotEmpty()) {
                        drugInput = result.canonical.joinToString(", ")
                        scanHint = null
                    } else {
                        scanHint = "Couldn't recognize a drug name on the label. " +
                            "Try a closer shot of the active-ingredient line, or type the name manually."
                    }
                }
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
                Icons.Default.MedicalServices,
                contentDescription = null,
                tint = AegisTeal,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "DrugSafe",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = AegisTeal,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Check drug interactions, warnings, and safety flags",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))

        // Input
        OutlinedTextField(
            value = drugInput,
            onValueChange = {
                drugInput = it
                if (scanHint != null) scanHint = null
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Drug names (comma-separated)") },
            placeholder = { Text("e.g. ibuprofen, lisinopril, metformin") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            minLines = 2,
            maxLines = 5,
        )

        scanHint?.let { hint ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Scan Label")
            }

            Button(
                onClick = {
                    if (drugInput.isNotBlank()) {
                        scope.launch {
                            isLoading = true
                            response = null
                            response = ToolDispatcher.runAgenticLoop(
                                userInput = "Check these drugs for interactions and safety: $drugInput",
                                mode = "drugsafe",
                            )
                            isLoading = false
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = drugInput.isNotBlank() && !isLoading,
            ) {
                Icon(Icons.Default.Search, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Check Safety")
            }
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
                        color = AegisTeal,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Analyzing drug safety…",
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
            response?.let { resp ->
                AegisResponseView(
                    response = resp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
