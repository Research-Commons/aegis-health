package com.aegis.health.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aegis.health.ui.theme.LocalAegisColors

/**
 * Geometric shield mark used in onboarding, home, and splash. Replaces
 * Material's `Icons.Default.Shield`. Two-tone fill + checkmark inside.
 */
@Composable
fun ShieldMark(
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    fillColor: Color = LocalAegisColors.current.accent,
    strokeColor: Color = LocalAegisColors.current.accentInk,
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height

        fun p(xRatio: Float, yRatio: Float) = Offset(w * xRatio, h * yRatio)

        // Outer shield silhouette (50/56 reference path).
        val outline = Path().apply {
            moveTo(w * (28f / 56f), h * (4f / 56f))
            lineTo(w * (48f / 56f), h * (11f / 56f))
            lineTo(w * (48f / 56f), h * (28f / 56f))
            // Curve down to bottom point (approximated with two bezier segments).
            cubicTo(
                w * (48f / 56f), h * (40f / 56f),
                w * (39f / 56f), h * (49f / 56f),
                w * (28f / 56f), h * (52f / 56f),
            )
            cubicTo(
                w * (17f / 56f), h * (49f / 56f),
                w * (8f / 56f), h * (40f / 56f),
                w * (8f / 56f), h * (28f / 56f),
            )
            lineTo(w * (8f / 56f), h * (11f / 56f))
            close()
        }
        drawPath(outline, color = fillColor)

        // Right-half overlay for the slight depth shading.
        val overlay = Path().apply {
            moveTo(w * (28f / 56f), h * (4f / 56f))
            lineTo(w * (48f / 56f), h * (11f / 56f))
            lineTo(w * (48f / 56f), h * (28f / 56f))
            cubicTo(
                w * (48f / 56f), h * (40f / 56f),
                w * (39f / 56f), h * (49f / 56f),
                w * (28f / 56f), h * (52f / 56f),
            )
            close()
        }
        drawPath(overlay, color = fillColor.copy(alpha = 0.78f))

        // Checkmark.
        val check = Path().apply {
            moveTo(w * (19f / 56f), h * (28f / 56f))
            lineTo(w * (25f / 56f), h * (34f / 56f))
            lineTo(w * (37f / 56f), h * (20f / 56f))
        }
        drawPath(
            path = check,
            color = strokeColor,
            style = Stroke(
                width = w * (3f / 56f),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
                pathEffect = PathEffect.cornerPathEffect(2f),
            ),
        )
    }
}
