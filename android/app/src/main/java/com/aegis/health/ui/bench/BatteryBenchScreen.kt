package com.aegis.health.ui.bench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aegis.health.inference.BatteryProbe
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun BatteryBenchScreen(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val state by BatteryBenchViewModel.state.collectAsState()
    var probeEnabled by remember { mutableStateOf(BatteryProbe.enabled) }
    var cooldownText by remember { mutableStateOf("30") }
    var jobRef by remember { mutableStateOf<Job?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "Battery Bench",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Replays anchor cases through the agentic loop and writes per-call " +
                "battery + engagement records to a JSONL file. Unplug the device, " +
                "enable airplane mode, fix screen brightness before running.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Probe enabled", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Switch(
                        checked = probeEnabled,
                        onCheckedChange = {
                            probeEnabled = it
                            BatteryProbe.enabled = it
                        },
                    )
                }
                Text(
                    "Log file: ${BatteryProbe.logPath() ?: "(no external storage)"}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    "Anchor cases: ${BatteryBenchViewModel.anchorCasesPath(context)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Run", fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Cooldown (s):", modifier = Modifier.weight(1f))
                    OutlinedTextField(
                        value = cooldownText,
                        onValueChange = { cooldownText = it.filter { c -> c.isDigit() }.take(4) },
                        singleLine = true,
                        modifier = Modifier.width(96.dp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            jobRef = scope.launch {
                                BatteryBenchViewModel.run(
                                    context = context,
                                    cooldownSec = cooldownText.toIntOrNull() ?: 30,
                                )
                            }
                        },
                        enabled = !state.running && probeEnabled,
                        modifier = Modifier.weight(1f),
                    ) { Text("Run anchor cases") }
                    OutlinedButton(
                        onClick = {
                            jobRef?.cancel()
                            BatteryBenchViewModel.markStopped()
                        },
                        enabled = state.running,
                    ) { Text("Stop") }
                }
                OutlinedButton(
                    onClick = { BatteryProbe.reset() },
                    enabled = !state.running,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Clear battery_log.jsonl") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Progress", fontWeight = FontWeight.SemiBold)
                if (state.total > 0) {
                    LinearProgressIndicator(
                        progress = { state.completed.toFloat() / state.total.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(2.dp))
                }
                Text(
                    "Case ${state.completed} / ${state.total}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                state.currentCaseId?.let {
                    Text("Current: $it (${state.currentMode ?: "?"})", style = MaterialTheme.typography.bodySmall)
                }
                state.lastDurationMs?.let {
                    Text("Last loop wall-clock: ${it} ms", style = MaterialTheme.typography.bodySmall)
                }
                state.lastError?.let {
                    Text(
                        "Last error: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Text(
            "Pull results: adb pull ${BatteryProbe.logPath() ?: "<external_files_dir>/battery_log.jsonl"}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

