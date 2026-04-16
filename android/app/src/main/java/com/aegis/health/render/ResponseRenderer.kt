package com.aegis.health.render

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.aegis.health.models.AegisResponse
import com.aegis.health.models.Citation
import com.aegis.health.models.Flag
import com.aegis.health.models.GuidelineRecommendation
import com.aegis.health.ui.theme.AegisTeal
import com.aegis.health.ui.theme.SeverityGreen
import com.aegis.health.ui.theme.severityBackgroundColor
import com.aegis.health.ui.theme.severityColor
import com.aegis.health.ui.theme.severityLabel

// ── Full response renderer ──────────────────────────────────────────────

@Composable
fun AegisResponseView(
    response: AegisResponse,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (response.defer_to_professional) {
            DeferralCard()
        }

        if (response.explanation.isNotBlank()) {
            Text(
                text = response.explanation,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        response.flags.sortedByDescending { it.severity }.forEach { flag ->
            WarningCard(flag = flag)
        }

        if (response.citations.isNotEmpty()) {
            Text(
                text = "Sources",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
            )
            response.citations.forEach { citation ->
                CitationBadge(citation = citation)
            }
        }

        ConfidenceBadge(confidence = response.confidence)
    }
}

// ── Warning card ────────────────────────────────────────────────────────

@Composable
fun WarningCard(
    flag: Flag,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val bgColor = severityBackgroundColor(flag.severity)
    val fgColor = severityColor(flag.severity)
    val icon = severityIcon(flag.severity)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = fgColor,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    color = fgColor,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = severityLabel(flag.severity),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = fgColor,
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = flag.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
            ) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        text = "Citation: ${flag.citation}",
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ── Citation badge ──────────────────────────────────────────────────────

@Composable
fun CitationBadge(
    citation: Citation,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = AegisTeal,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = citation.source,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = AegisTeal,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Text(
                    text = citation.text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── Deferral card ───────────────────────────────────────────────────────

@Composable
fun DeferralCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.LocalHospital,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "Talk to Your Doctor",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = "This assessment requires review by a healthcare professional before making any decisions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

// ── Simplified text with term highlights ────────────────────────────────

@Composable
fun SimplifiedText(
    text: String,
    onTermClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val termRegex = remember { Regex("""\[([A-Z_]+)]""") }

    val annotated = remember(text) {
        buildAnnotatedString {
            var cursor = 0
            termRegex.findAll(text).forEach { match ->
                append(text.substring(cursor, match.range.first))
                val termName = match.groupValues[1]
                pushStringAnnotation(tag = "TERM", annotation = termName)
                withStyle(SpanStyle(color = AegisTeal, fontWeight = FontWeight.Bold)) {
                    append(termName)
                }
                pop()
                cursor = match.range.last + 1
            }
            if (cursor < text.length) {
                append(text.substring(cursor))
            }
        }
    }

    ClickableText(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier,
        onClick = { offset ->
            annotated.getStringAnnotations("TERM", offset, offset)
                .firstOrNull()
                ?.let { onTermClick(it.item) }
        },
    )
}

// ── Checklist item for HealthPartner ────────────────────────────────────

@Composable
fun ChecklistItem(
    recommendation: GuidelineRecommendation,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = recommendation.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(8.dp))
                    GradeBadge(grade = recommendation.grade)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = recommendation.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = recommendation.citation,
                    style = MaterialTheme.typography.labelSmall,
                    fontStyle = FontStyle.Italic,
                    color = AegisTeal,
                )
            }
        }
    }
}

// ── Grade badge ─────────────────────────────────────────────────────────

@Composable
fun GradeBadge(grade: String, modifier: Modifier = Modifier) {
    val color = if (grade == "A") SeverityGreen else AegisTeal
    Surface(
        modifier = modifier,
        color = color,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = "Grade $grade",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

// ── Confidence badge ────────────────────────────────────────────────────

@Composable
fun ConfidenceBadge(confidence: Double, modifier: Modifier = Modifier) {
    val percent = (confidence * 100).toInt()
    val color = when {
        confidence >= 0.8 -> SeverityGreen
        confidence >= 0.5 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }

    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = "Confidence: $percent%",
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────

private fun severityIcon(severity: Int): ImageVector = when (severity) {
    5, 4 -> Icons.Default.Error
    3 -> Icons.Default.Warning
    else -> Icons.Default.CheckCircle
}
