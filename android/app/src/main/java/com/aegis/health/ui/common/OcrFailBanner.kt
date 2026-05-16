package com.aegis.health.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aegis.health.ui.theme.AegisSpacing
import com.aegis.health.ui.theme.LocalAegisColors

/**
 * Inline banner shown above the input when OCR can't find a recognizable
 * drug name. Per spec: do NOT navigate away from the camera flow.
 */
@Composable
fun OcrFailBanner(
    message: String = "Try a closer shot of the active-ingredient line, better lighting, or type the name below.",
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAegisColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.sevModBg, RoundedCornerShape(16.dp))
            .let { if (colors.isDark) it.border(1.dp, colors.hairline, RoundedCornerShape(16.dp)) else it }
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Default.Warning, null, tint = colors.sevModFg, modifier = Modifier.size(18.dp).padding(top = 2.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Couldn't read the label",
                style = MaterialTheme.typography.titleMedium,
                color = colors.sevModFg,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onWarmSurfaceMuted,
            )
            if (onRetry != null) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .height(28.dp)
                        .border(1.dp, colors.sevModFg, RoundedCornerShape(8.dp))
                        .clickable { onRetry() }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Try scan again", style = MaterialTheme.typography.labelLarge, color = colors.sevModFg)
                }
            }
        }
    }
}
