package com.aegis.health.ui.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import com.aegis.health.ui.theme.AegisSpacing
import com.aegis.health.ui.theme.LocalAegisColors
import kotlinx.coroutines.delay

/**
 * Replaces inline `CircularProgressIndicator`. Surface card with:
 *   - Pulsing accent dot (1.4s scale 1→1.6, opacity 1→0.4 loop)
 *   - Bold label
 *   - Stepped tick list — each step fills its 14dp ring + draws a check
 *     when reached.
 *
 * `autoAdvance = true` (default) animates a fixed list at 380ms intervals —
 * decorative mode for screens that don't have real progress signal.
 * `autoAdvance = false` treats `steps` as a live, growing list: the last
 * entry is the Active step, all prior entries are Done. Pair with a
 * mutableStateListOf<String>() and append to it from a real progress source
 * (e.g. ToolDispatcher.runAgenticLoop's onProgress callback).
 */
@Composable
fun LoadingPanel(
    label: String,
    steps: List<String>,
    modifier: Modifier = Modifier,
    autoAdvance: Boolean = true,
) {
    val colors = LocalAegisColors.current
    var autoStep by remember(steps) { mutableIntStateOf(0) }

    LaunchedEffect(steps, autoAdvance) {
        if (autoAdvance) {
            while (autoStep < steps.lastIndex) {
                delay(380)
                autoStep += 1
            }
        }
    }

    val step = if (autoAdvance) autoStep else steps.lastIndex.coerceAtLeast(0)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(18.dp))
            .border(1.dp, colors.hairline, RoundedCornerShape(18.dp))
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PulsingDot()
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            steps.forEachIndexed { i, s ->
                StepRow(
                    text = s,
                    state = when {
                        i < step -> StepState.Done
                        i == step -> StepState.Active
                        else -> StepState.Pending
                    },
                )
            }
        }
    }
}

@Composable
private fun PulsingDot() {
    val colors = LocalAegisColors.current
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )
    Box(
        modifier = Modifier
            .scale(scale)
            .size(10.dp)
            .background(colors.accent.copy(alpha = alpha), CircleShape),
    )
}

private enum class StepState { Pending, Active, Done }

@Composable
private fun StepRow(text: String, state: StepState) {
    val colors = LocalAegisColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .background(
                    color = if (state == StepState.Done) colors.accent else androidx.compose.ui.graphics.Color.Transparent,
                    shape = CircleShape,
                )
                .border(
                    width = 1.5.dp,
                    color = if (state == StepState.Pending) colors.hairline else colors.accent,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (state == StepState.Done) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = colors.accentInk,
                    modifier = Modifier.size(9.dp),
                )
            }
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (state == StepState.Pending) colors.onSurfaceMuted else colors.onSurface,
        )
    }
    Spacer(Modifier.height(0.dp))
}
