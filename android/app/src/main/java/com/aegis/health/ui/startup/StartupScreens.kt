package com.aegis.health.ui.startup

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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aegis.health.ui.common.GhostButton
import com.aegis.health.ui.common.PrimaryButton
import com.aegis.health.ui.common.SectionLabel
import com.aegis.health.ui.common.ShieldMark
import com.aegis.health.ui.theme.LocalAegisColors

/**
 * Cold-start splash. The indeterminate LinearProgressIndicator matches the
 * lack of granular progress signal from the LiteRT-LM bundle; the latency
 * subtitle is honest per PITFALLS C4 (no fake percentage). HOME-04 D-04a/b.
 */
@Composable
fun StartupLoadingScreen(modifier: Modifier = Modifier) {
    val colors = LocalAegisColors.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ShieldMark(size = 64.dp)
            Spacer(Modifier.height(20.dp))
            Text(
                "Aegis Health",
                style = MaterialTheme.typography.headlineMedium,
                color = colors.onSurface,
            )
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(
                modifier = Modifier.width(220.dp),
                color = colors.accent,
                trackColor = colors.hairline,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "Loading on-device model — ~30s on first launch",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceMuted,
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 50.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "ON-DEVICE · NO NETWORK",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceMuted,
            )
        }
    }
}

@Composable
fun StartupErrorScreen(
    message: String,
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = LocalAegisColors.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 60.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(colors.sevCritBg, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Warning, null, tint = colors.sevCritFg, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(22.dp))
        Text(
            "Aegis can't start",
            style = MaterialTheme.typography.headlineMedium,
            color = colors.onSurface,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "The Gemma 4 model file is missing or corrupted. Aegis needs it on-device to run anything — no network fallback by design.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceMuted,
        )

        Spacer(Modifier.height(18.dp))

        // ── Mono error block ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface, RoundedCornerShape(16.dp))
                .border(1.dp, colors.hairline, RoundedCornerShape(16.dp))
                .padding(16.dp),
        ) {
            Text(
                "Error · MODEL_NOT_FOUND",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = colors.sevCritFg,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                message.ifBlank { "Expected: /Android/data/com.aegis.health/files/aegis_model.litertlm" },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = colors.onSurfaceMuted,
            )
        }

        Spacer(Modifier.height(18.dp))
        SectionLabel("Fix it")
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FixStep(1, "Connect this phone to a computer.")
            FixStep(2, "Push the LiteRT-LM bundle to the app's external files dir.")
            FixStep(3, "Force-stop and relaunch Aegis.")
        }

        Spacer(Modifier.height(24.dp))
        PrimaryButton(
            text = "Download model (~7.7 GB)",
            onClick = { /* handled by user; offline by design */ },
            leading = Icons.Default.Download,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        GhostButton(
            text = "Retry",
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FixStep(n: Int, text: String) {
    val colors = LocalAegisColors.current
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "$n.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
            modifier = Modifier.width(20.dp),
            textAlign = TextAlign.End,
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}
