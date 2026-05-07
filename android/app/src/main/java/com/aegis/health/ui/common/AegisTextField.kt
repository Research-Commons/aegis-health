package com.aegis.health.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aegis.health.ui.theme.AegisSpacing
import com.aegis.health.ui.theme.LocalAegisColors

/**
 * Replaces Material3 OutlinedTextField. Visual contract from the spec:
 *   - Surface bg (#FFF light / surface dark), 16dp radius, hairline border.
 *   - Border tweens to accent over 120ms on focus.
 *   - Min height 52dp single, 96dp multiline.
 *   - Optional uppercase label, leading icon.
 */
@Composable
fun AegisTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    leading: ImageVector? = null,
    multiline: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    enabled: Boolean = true,
) {
    val colors = LocalAegisColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val borderColor by animateColorAsState(
        targetValue = if (isFocused) colors.accent else colors.hairline,
        label = "AegisTextFieldBorder",
    )

    Column(modifier = modifier) {
        if (label != null) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceMuted,
            )
            Spacer(Modifier.height(AegisSpacing.sm))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = if (multiline) 96.dp else 52.dp)
                .background(colors.surface, RoundedCornerShape(16.dp))
                .border(1.dp, borderColor, RoundedCornerShape(16.dp))
                .padding(
                    horizontal = AegisSpacing.md,
                    vertical = if (multiline) AegisSpacing.md else 0.dp,
                ),
            contentAlignment = if (multiline) Alignment.TopStart else Alignment.CenterStart,
        ) {
            Row(
                verticalAlignment = if (multiline) Alignment.Top else Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AegisSpacing.sm),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (leading != null) {
                    Icon(
                        leading,
                        contentDescription = null,
                        tint = colors.onSurfaceMuted,
                        modifier = Modifier
                            .size(18.dp)
                            .padding(top = if (multiline) 3.dp else 0.dp),
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        enabled = enabled,
                        textStyle = LocalTextStyle.current.merge(
                            MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                        ),
                        cursorBrush = SolidColor(colors.accent),
                        singleLine = !multiline,
                        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                        interactionSource = interactionSource,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = if (multiline) 70.dp else 0.dp),
                    )
                    if (value.isEmpty() && placeholder != null) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurfaceMuted,
                        )
                    }
                }
            }
        }
    }
}
