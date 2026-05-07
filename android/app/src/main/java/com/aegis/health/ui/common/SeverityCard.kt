package com.aegis.health.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.aegis.health.ui.theme.LocalAegisColors
import com.aegis.health.ui.theme.severityBackgroundColor
import com.aegis.health.ui.theme.severityColor
import com.aegis.health.ui.theme.severityLabel

/**
 * Severity card per spec:
 *   - 28×28 colored icon chip + uppercase label pill in the top row
 *   - Bold title (or first-sentence of description), muted body
 *   - Tap to expand → italic citation slides in below a 1px hairline
 *   - In dark mode the soft-bg cards get a hairline border for definition.
 */
@Composable
fun SeverityCard(
    severity: Int,
    description: String,
    citation: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    initiallyExpanded: Boolean = false,
) {
    val colors = LocalAegisColors.current
    var expanded by remember { mutableStateOf(initiallyExpanded) }

    val fg = severityColor(severity, colors)
    val bg = severityBackgroundColor(severity, colors)
    val icon = severityIcon(severity)

    val (resolvedTitle, resolvedBody) = remember(title, description) {
        splitTitleBody(title, description)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(16.dp))
            .let { if (colors.isDark) it.border(1.dp, colors.hairline, RoundedCornerShape(16.dp)) else it }
            .clickable { expanded = !expanded }
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 28dp icon chip
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(fg, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(10.dp))
            // Severity label pill
            SeverityLabelPill(severity = severity, fg = fg, isDark = colors.isDark)
            Box(Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = fg,
                modifier = Modifier.size(18.dp),
            )
        }

        if (resolvedTitle != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                resolvedTitle,
                style = MaterialTheme.typography.titleLarge,
                color = if (colors.isDark) colors.onSurface else Color(0xFF1A1816),
            )
        }
        if (resolvedBody.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                resolvedBody,
                style = MaterialTheme.typography.bodyMedium,
                color = if (colors.isDark) colors.onSurfaceMuted else Color(0xFF3B3733),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column {
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(if (colors.isDark) colors.hairline else Color(0x140F1F1D)),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    citation,
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = if (colors.isDark) colors.onSurfaceMuted else Color(0xFF5F5A52),
                )
            }
        }
    }
}

@Composable
private fun SeverityLabelPill(severity: Int, fg: Color, isDark: Boolean) {
    val bg = if (isDark) Color.Transparent else Color.White
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(6.dp))
            .let { if (isDark) it.border(1.dp, fg, RoundedCornerShape(6.dp)) else it }
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            severityLabel(severity),
            style = MaterialTheme.typography.labelSmall,
            color = fg,
        )
    }
}

private fun severityIcon(severity: Int): ImageVector = when (severity) {
    in 4..5 -> Icons.Default.Error
    3 -> Icons.Default.Warning
    2 -> Icons.Default.Info
    else -> Icons.Default.CheckCircle
}

/**
 * Splits a description into (title, body) by first sentence boundary.
 * If [explicitTitle] is provided, returns that and the full description.
 * If no period is found, the whole text becomes the title and body is empty.
 */
internal fun splitTitleBody(explicitTitle: String?, description: String): Pair<String?, String> {
    if (!explicitTitle.isNullOrBlank()) return explicitTitle to description
    val text = description.trim()
    if (text.isEmpty()) return null to ""
    val periodIdx = text.indexOfFirst { it == '.' || it == '?' || it == '!' }
    return if (periodIdx == -1 || periodIdx > 80) {
        // Long single sentence — use it as title only, no body.
        text to ""
    } else {
        val head = text.substring(0, periodIdx + 1).trim()
        val tail = text.substring(periodIdx + 1).trim()
        head to tail
    }
}
