package com.aegis.health.ui.onboarding

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.aegis.health.ui.common.PrimaryButton
import com.aegis.health.ui.common.ShieldMark
import com.aegis.health.ui.theme.LocalAegisColors

@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAegisColors.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .padding(horizontal = 28.dp, vertical = 56.dp),
    ) {
        ShieldMark(size = 64.dp)
        Spacer(Modifier.height(28.dp))

        Text(
            "A medical safety",
            style = MaterialTheme.typography.displayLarge,
            color = colors.onSurface,
        )
        Text(
            "second opinion,",
            style = MaterialTheme.typography.displayLarge,
            color = colors.onSurface,
        )
        Text(
            "on your device.",
            // Italic-serif accent line — matches the design's italicized
            // closing phrase on the cream/white canvas.
            style = MaterialTheme.typography.displayLarge.copy(
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            ),
            color = colors.accent,
        )

        Spacer(Modifier.height(18.dp))
        Text(
            "Aegis runs the Gemma 4 model entirely offline. Your photos, drug names, and health profile never leave this phone.",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceMuted,
        )

        Spacer(Modifier.height(36.dp))

        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            FeatureRow(
                icon = Icons.Default.Lock,
                title = "On-device only",
                sub = "No network calls. Works in airplane mode.",
            )
            FeatureRow(
                icon = Icons.AutoMirrored.Filled.MenuBook,
                title = "Cited, not invented",
                sub = "Every flag links to USPSTF, RxNorm, FDA, or DrugBank.",
            )
            FeatureRow(
                icon = Icons.Default.Flag,
                title = "Defers when uncertain",
                sub = "Aegis tells you when to talk to a clinician.",
            )
        }

        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(32.dp))

        PrimaryButton(
            text = "Get started",
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Powered by Gemma 4 E4B · 4.1 GB on disk",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceMuted,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, sub: String) {
    val colors = LocalAegisColors.current
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(colors.accentSoft, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = colors.accent, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceMuted)
        }
    }
}
