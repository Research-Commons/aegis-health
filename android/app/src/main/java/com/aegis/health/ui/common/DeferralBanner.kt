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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aegis.health.ui.theme.LocalAegisColors

/**
 * Critical-severity deferral CTA shown above the findings when a result has
 * `defer_to_professional = true` AND a flag with severity ≥ 4.
 *
 * Screens used to auto-navigate to DeferralScreen on this condition, which
 * hid the actual interaction details. The banner replaces that: details
 * stay visible inline, and the user explicitly opts into the clinician path.
 */
@Composable
fun DeferralBanner(
    title: String,
    body: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAegisColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.sevCritBg, RoundedCornerShape(16.dp))
            .let { if (colors.isDark) it.border(1.dp, colors.hairline, RoundedCornerShape(16.dp)) else it }
            .clickable { onClick() }
            .padding(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Default.Error,
                contentDescription = null,
                tint = colors.sevCritFg,
                modifier = Modifier.size(20.dp),
            )
            Text(
                "DEFERRAL · CLINICIAN REVIEW",
                style = MaterialTheme.typography.labelMedium,
                color = colors.sevCritFg,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = if (colors.isDark) colors.onSurface else Color(0xFF1A1816),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = if (colors.isDark) colors.onSurfaceMuted else Color(0xFF3B3733),
        )
        Spacer(Modifier.height(10.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Talk to a clinician",
                style = MaterialTheme.typography.labelLarge,
                color = colors.sevCritFg,
            )
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = colors.sevCritFg,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
