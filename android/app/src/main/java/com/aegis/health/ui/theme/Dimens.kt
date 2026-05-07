package com.aegis.health.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Spacing scale — design tokens 4 / 8 / 12 / 16 / 20 / 24 / 32 dp.
object AegisSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
}

// Radius scale — 6 / 10 / 12 / 16 / 22.
// Default surface radius is 16; cards/buttons add +2 for a softer feel.
object AegisRadius {
    val xs = 6.dp
    val sm = 10.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 22.dp
}

val AegisShapes = Shapes(
    extraSmall = RoundedCornerShape(AegisRadius.xs),
    small = RoundedCornerShape(AegisRadius.sm),
    medium = RoundedCornerShape(AegisRadius.md),
    large = RoundedCornerShape(AegisRadius.lg),
    extraLarge = RoundedCornerShape(AegisRadius.xl),
)
