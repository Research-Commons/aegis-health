package com.aegis.health.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aegis.health.ui.theme.AegisSpacing
import com.aegis.health.ui.theme.LocalAegisColors

private val ButtonHeight: Dp = 52.dp
private val ButtonRadius: Dp = 18.dp // theme radius (16) + 2

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
    enabled: Boolean = true,
) {
    val colors = LocalAegisColors.current
    val bg = if (enabled) colors.accent else colors.surfaceAlt
    val fg = if (enabled) colors.accentInk else colors.onSurfaceMuted
    Row(
        modifier = modifier
            .height(ButtonHeight)
            .background(bg, RoundedCornerShape(ButtonRadius))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = AegisSpacing.xl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AegisSpacing.sm, Alignment.CenterHorizontally),
    ) {
        CompositionLocalProvider(LocalContentColor provides fg) {
            if (leading != null) {
                Icon(leading, contentDescription = null, modifier = Modifier.size(18.dp), tint = fg)
            }
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                color = fg,
            )
        }
    }
}

@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
    enabled: Boolean = true,
) {
    val colors = LocalAegisColors.current
    Row(
        modifier = modifier
            .height(ButtonHeight)
            .background(colors.surface, RoundedCornerShape(ButtonRadius))
            .border(1.dp, colors.hairline, RoundedCornerShape(ButtonRadius))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = AegisSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AegisSpacing.sm, Alignment.CenterHorizontally),
    ) {
        if (leading != null) {
            Icon(leading, contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.onSurface)
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
        )
    }
}

/**
 * Used for save / find-clinic style links — full-width borderless action with an icon.
 */
@Composable
fun TextActionRow(
    text: String,
    onClick: () -> Unit,
    leading: ImageVector,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAegisColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .border(1.dp, colors.hairline, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = AegisSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AegisSpacing.sm, Alignment.CenterHorizontally),
    ) {
        Icon(leading, contentDescription = null, modifier = Modifier.size(15.dp), tint = colors.accent)
        Text(text, style = MaterialTheme.typography.labelLarge, color = colors.accent)
    }
}

/**
 * Tiny circular icon-only button used in screen headers (back, settings).
 * Hit target 44dp; visual 36-38dp.
 */
@Composable
fun IconHeaderButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    bordered: Boolean = false,
) {
    val colors = LocalAegisColors.current
    Box(
        modifier = modifier
            .size(44.dp)
            .let { if (bordered) it.border(1.dp, colors.hairline, RoundedCornerShape(12.dp)) else it }
            .clickable { onClick() }
            .padding(PaddingValues(0.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = colors.onSurfaceMuted,
            modifier = Modifier.size(20.dp),
        )
    }
}
