package com.aegis.health.ui.reportreader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aegis.health.ui.theme.AegisSpacing
import com.aegis.health.ui.theme.LocalAegisColors

/**
 * Phase 3 D-07 — always-on collapsible "What this is — and what it isn't"
 * panel. UI-07 success criterion.
 *
 * NOT a first-launch-only experience — visible on every ReportReader visit.
 * Default state: collapsed (one-line bar with chevron).
 *
 * CRITICAL — SAFETY-04 anchor strings (per 03-CONTEXT.md:225-227): the
 * expanded text MUST contain the literal phrases for the Phase 5 REGULATORY.md
 * language audit grep. Changing the wording in the rendered Text below
 * requires updating REGULATORY.md's audit checklist.
 */
@Composable
fun NotADiagnosisPanel(modifier: Modifier = Modifier) {
    val colors = LocalAegisColors.current
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surfaceAlt, RoundedCornerShape(12.dp))
            .clickable { expanded = !expanded }
            .padding(AegisSpacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = colors.onSurfaceMuted,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(AegisSpacing.sm))
            Text(
                text = "What this is — and what it isn't",
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceMuted,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = colors.onSurfaceMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column {
                Spacer(Modifier.height(AegisSpacing.sm))
                // SAFETY-04 GREP ANCHOR — DO NOT CHANGE the three literal phrases
                // in the rendered Text below without updating REGULATORY.md.
                Text(
                    text = "ReportReader is not a diagnosis. It does NOT replace medical advice. " +
                        "It does NOT recommend treatment. It flags values outside the printed lab ranges.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceMuted,
                )
            }
        }
    }
}
