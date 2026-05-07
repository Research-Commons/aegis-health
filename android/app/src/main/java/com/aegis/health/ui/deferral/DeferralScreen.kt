package com.aegis.health.ui.deferral

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aegis.health.models.AegisResponse
import com.aegis.health.ui.common.GhostButton
import com.aegis.health.ui.common.PrimaryButton
import com.aegis.health.ui.common.ScreenHeader
import com.aegis.health.ui.common.SectionLabel
import com.aegis.health.ui.theme.AegisSpacing
import com.aegis.health.ui.theme.LocalAegisColors

/**
 * Full-screen route shown when [AegisResponse.defer_to_professional] is true
 * AND any flag's severity is ≥ 4. Replaces the inline DeferralCard for the
 * critical case — the inline DeferralCard is still used as a summary block.
 *
 * Pass an optional [response] for a clinician-ready summary; when null, the
 * screen falls back to a generic deferral message.
 */
@Composable
fun DeferralScreen(
    onBack: () -> Unit,
    response: AegisResponse? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAegisColors.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AegisSpacing.xl, vertical = AegisSpacing.xl),
    ) {
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
                response?.explanation?.takeIf { it.isNotBlank() }
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
            val summary = response?.flags?.sortedByDescending { it.severity }?.take(4)
            if (!summary.isNullOrEmpty()) {
                summary.forEach { f ->
                    BulletLine("${f.description}  ·  ${f.citation}")
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Confidence ${(response.confidence * 100).toInt()}%.",
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
