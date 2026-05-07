package com.aegis.health.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aegis.health.AegisApp
import com.aegis.health.db.history.HistoryEntity
import com.aegis.health.db.history.formatRelative
import com.aegis.health.db.history.isThisWeek
import com.aegis.health.ui.common.PrimaryButton
import com.aegis.health.ui.common.SectionLabel
import com.aegis.health.ui.theme.AegisSpacing
import com.aegis.health.ui.theme.LocalAegisColors

enum class SeverityKey { Crit, Mod, Low, Info }

@Composable
fun HistoryScreen(modifier: Modifier = Modifier) {
    val colors = LocalAegisColors.current
    val entries by AegisApp.instance.historyDb.history()
        .getAll()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val now = System.currentTimeMillis()
    val (thisWeek, earlier) = entries.partition { isThisWeek(it.createdAt, now) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AegisSpacing.xl, vertical = AegisSpacing.xl),
    ) {
        Text("History", style = MaterialTheme.typography.headlineLarge, color = colors.onSurface)
        Spacer(Modifier.height(6.dp))
        Text(
            "Your saved checks and summaries — stored only on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceMuted,
        )

        Spacer(Modifier.height(22.dp))

        if (entries.isEmpty()) {
            EmptyState()
        } else {
            if (thisWeek.isNotEmpty()) {
                SectionLabel("This week")
                Spacer(Modifier.height(10.dp))
                thisWeek.forEach { HistoryRow(it, now) }
            }
            if (earlier.isNotEmpty()) {
                if (thisWeek.isNotEmpty()) Spacer(Modifier.height(18.dp))
                SectionLabel("Earlier")
                Spacer(Modifier.height(10.dp))
                earlier.forEach { HistoryRow(it, now) }
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntity, now: Long) {
    val colors = LocalAegisColors.current
    val severity = severityKeyFromString(entry.severityKey)
    val (fg, bg) = when (severity) {
        SeverityKey.Crit -> colors.sevCritFg to colors.sevCritBg
        SeverityKey.Mod -> colors.sevModFg to colors.sevModBg
        SeverityKey.Low -> colors.sevLowFg to colors.sevLowBg
        SeverityKey.Info -> colors.sevInfoFg to colors.sevInfoBg
    }
    val icon = iconForKind(entry.kind)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(bg, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(17.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
            Text(
                "${entry.sub} · ${formatRelative(entry.createdAt, now)}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceMuted,
            )
        }
        Icon(
            Icons.Default.ChevronRight,
            null,
            tint = colors.onSurfaceMuted,
            modifier = Modifier.size(18.dp),
        )
    }
    Box(modifier = Modifier
        .fillMaxWidth()
        .height(1.dp)
        .background(colors.hairline))
}

private fun severityKeyFromString(s: String): SeverityKey = when (s) {
    HistoryEntity.SEV_CRIT -> SeverityKey.Crit
    HistoryEntity.SEV_MOD -> SeverityKey.Mod
    HistoryEntity.SEV_LOW -> SeverityKey.Low
    else -> SeverityKey.Info
}

private fun iconForKind(kind: String): ImageVector = when (kind) {
    HistoryEntity.KIND_DRUGSAFE -> Icons.Default.Medication
    HistoryEntity.KIND_CONSENT -> Icons.Default.Description
    HistoryEntity.KIND_PARTNER -> Icons.Default.Favorite
    else -> Icons.Default.History
}

@Composable
private fun EmptyState() {
    val colors = LocalAegisColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(18.dp))
            .border(1.dp, colors.hairline, RoundedCornerShape(18.dp))
            .padding(vertical = 40.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(colors.surfaceAlt, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.History, null, tint = colors.onSurfaceMuted, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Nothing here yet",
            style = MaterialTheme.typography.titleLarge,
            color = colors.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Run your first drug check, simplify a consent form, or build a prevention plan to get started.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceMuted,
        )
        Spacer(Modifier.height(18.dp))
        PrimaryButton(
            text = "Run a check",
            onClick = { /* nav handled by enclosing tab; stub */ },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
