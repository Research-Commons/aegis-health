package com.aegis.health.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PersonAddAlt1
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aegis.health.AegisApp
import com.aegis.health.models.HealthProfile
import com.aegis.health.ui.common.SectionLabel
import com.aegis.health.ui.theme.AegisSpacing
import com.aegis.health.ui.theme.LocalAegisColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen(
    onOpenBench: () -> Unit,
    onEditProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAegisColors.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val profile by ProfileStore.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AegisSpacing.xl, vertical = AegisSpacing.xl),
    ) {
        Text("Profile", style = MaterialTheme.typography.headlineLarge, color = colors.onSurface)
        Spacer(Modifier.height(6.dp))
        Text(
            "Health profile, model status, and privacy.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceMuted,
        )

        Spacer(Modifier.height(22.dp))

        // ── Profile card ──
        if (profile == null) {
            ProfileCtaCard(onSetUp = onEditProfile)
        } else {
            ProfileSummaryCard(profile!!, onEdit = onEditProfile)
        }

        Spacer(Modifier.height(18.dp))

        // ── Model section ──
        SectionLabel("Model")
        Spacer(Modifier.height(10.dp))
        SettingsGroup {
            SettingRow(
                icon = Icons.Default.Memory,
                title = "Gemma 4 E4B",
                sub = "4.1 GB · Loaded · Last verified today",
                onClick = {},
                divider = true,
            )
            SettingRow(
                icon = Icons.Default.Science,
                title = "Battery Bench",
                sub = "Run anchor cases · diagnostic",
                onClick = onOpenBench,
                divider = true,
            )
            SettingRow(
                icon = Icons.Default.Download,
                title = "Knowledge base",
                sub = "USPSTF, RxNorm, DrugBank · 2024-Q4",
                onClick = {},
                divider = false,
            )
        }

        Spacer(Modifier.height(18.dp))

        // ── Privacy section ──
        SectionLabel("Privacy")
        Spacer(Modifier.height(10.dp))
        SettingsGroup {
            SettingRow(
                icon = Icons.Default.Lock,
                title = "Network access",
                sub = "Blocked · always offline",
                onClick = {},
                divider = true,
            )
            SettingRow(
                icon = Icons.Default.Visibility,
                title = "Camera & OCR",
                sub = "On-device · text never stored",
                onClick = {},
                divider = true,
            )
            SettingRow(
                icon = Icons.Default.History,
                title = "Clear history",
                sub = "Delete all checks and summaries",
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        AegisApp.instance.historyDb.history().deleteAll()
                    }
                },
                divider = profile != null,
            )
            if (profile != null) {
                SettingRow(
                    icon = Icons.Default.PersonOff,
                    title = "Delete profile",
                    sub = "Remove your name, age, and conditions",
                    onClick = { ProfileStore.delete(context) },
                    divider = false,
                )
            }
        }
    }
}

@Composable
private fun ProfileSummaryCard(profile: HealthProfile, onEdit: () -> Unit) {
    val colors = LocalAegisColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(18.dp))
            .border(1.dp, colors.hairline, RoundedCornerShape(18.dp))
            .clickable { onEdit() }
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(colors.accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                initialsFor(profile.name),
                style = MaterialTheme.typography.titleLarge,
                color = colors.accentInk,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                profile.name?.takeIf { it.isNotBlank() } ?: "Unnamed profile",
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface,
            )
            Text(
                summaryLine(profile),
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
}

@Composable
private fun ProfileCtaCard(onSetUp: () -> Unit) {
    val colors = LocalAegisColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(18.dp))
            .border(1.dp, colors.hairline, RoundedCornerShape(18.dp))
            .clickable { onSetUp() }
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(colors.surfaceAlt, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.PersonAddAlt1,
                null,
                tint = colors.accent,
                modifier = Modifier.size(24.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Set up your profile",
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface,
            )
            Text(
                "Tailors prevention plans and severity flags to you.",
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
}

private fun initialsFor(name: String?): String {
    val parts = name.orEmpty().trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (parts.isEmpty()) return "?"
    return parts.take(2).joinToString("") { it.first().uppercaseChar().toString() }
}

private fun summaryLine(profile: HealthProfile): String {
    val parts = mutableListOf<String>()
    profile.age?.let { parts += it.toString() }
    profile.sex?.takeIf { it.isNotBlank() }?.let {
        parts += it.replaceFirstChar { c -> c.uppercaseChar() }
    }
    val condCount = profile.conditions.size
    if (condCount > 0) {
        parts += "$condCount condition${if (condCount == 1) "" else "s"}"
    }
    return if (parts.isEmpty()) "Tap to add details" else parts.joinToString(" · ")
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    val colors = LocalAegisColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(16.dp))
            .border(1.dp, colors.hairline, RoundedCornerShape(16.dp)),
    ) {
        content()
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    sub: String,
    onClick: () -> Unit,
    divider: Boolean,
) {
    val colors = LocalAegisColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = AegisSpacing.lg, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(colors.surfaceAlt, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = colors.accent, modifier = Modifier.size(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted)
        }
        Icon(
            Icons.Default.ChevronRight,
            null,
            tint = colors.onSurfaceMuted,
            modifier = Modifier.size(16.dp),
        )
    }
    if (divider) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 60.dp)
                .height(1.dp)
                .background(colors.hairline),
        )
    }
}
