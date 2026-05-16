package com.aegis.health.ui.home

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aegis.health.AegisApp
import com.aegis.health.StartupState
import com.aegis.health.db.history.HistoryEntity
import com.aegis.health.db.history.formatRelative
import com.aegis.health.ui.common.IconHeaderButton
import com.aegis.health.ui.common.ValuePropChip
import com.aegis.health.ui.common.SectionLabel
import com.aegis.health.ui.common.ShieldMark
import com.aegis.health.ui.theme.AegisSpacing
import com.aegis.health.ui.theme.LocalAegisColors
import java.time.LocalTime

@Composable
fun HomeScreen(
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAegisColors.current
    // Phase 9 HOME-03 D-03b — single top-of-Composable StateFlow subscription
    // (P9-C recomposition discipline). The brand-row pill below reads this
    // value; placing the collectAsState() inside the brand-row Column would
    // re-register the subscription on every brand-row recomposition.
    val startupState by AegisApp.instance.startup.collectAsState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AegisSpacing.xl, vertical = AegisSpacing.xl),
    ) {
        // ── Brand row ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShieldMark(size = 36.dp)
            Spacer(Modifier.size(width = 12.dp, height = 0.dp))
            Column {
                Text(
                    "Aegis Health",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurfaceMuted,
                )
                // Phase 9 HOME-03 D-03a/b/c/d/e — model-ready confirmation pill.
                // Strict predicate `is StartupState.Ready` is defense-in-depth
                // against any future StartupGate refactor that might demote the
                // pill to "always-green" (D-03b). Calm muted tokens — surfaceAlt
                // bg + onSurfaceMuted ink — same pair Phase 8 LabRow IN_RANGE
                // affirmation uses (D-03c, WCAG AA pre-cleared, no green per
                // Phase 3 D-01 carry-over). ✓ is Unicode U+2713 baked into the
                // text string, no Icon Composable (D-03d). v0.4 build-version
                // subtitle intentionally dropped (D-03e).
                if (startupState is StartupState.Ready) {
                    StatusPill(text = "Gemma 4 ✓")
                }
            }
            Box(modifier = Modifier.weight(1f))
            IconHeaderButton(
                icon = Icons.Default.Settings,
                contentDescription = "Settings",
                onClick = onSettings,
                bordered = true,
            )
        }

        Spacer(Modifier.height(22.dp))

        // ── Greeting ──
        // Serif headline pair: regular weight intro line, italic accent
        // close — mirrors the design's "Good afternoon, *Sara.*" moment.
        Text(
            greetingForNow() + ",",
            style = MaterialTheme.typography.headlineLarge,
            color = colors.onSurface,
        )
        Text(
            "What can I check for you?",
            style = MaterialTheme.typography.headlineLarge.copy(
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            ),
            color = colors.accent,
        )

        Spacer(Modifier.height(18.dp))

        // ── Value-prop strip ──
        ValuePropChip(modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(24.dp))

        // ── Feature cards ──
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FeatureCard(
                icon = Icons.Default.Medication,
                title = "DrugSafe",
                tag = "INTERACTIONS",
                body = "Scan a pill bottle or type drug names. Get severity-flagged interactions with citations.",
                accent = colors.accent,
                accentSoft = colors.accentSoft,
                onClick = { onOpen("drugsafe") },
            )
            FeatureCard(
                icon = Icons.Default.Description,
                title = "ConsentReader",
                tag = "PLAIN LANGUAGE",
                body = "Photograph any medical consent form. Aegis returns a 5th-grade summary with binding clauses surfaced.",
                accent = colors.secondary,
                accentSoft = colors.secondarySoft,
                onClick = { onOpen("consent") },
            )
            FeatureCard(
                icon = Icons.Default.Favorite,
                title = "HealthPartner",
                tag = "USPSTF",
                body = "Build a personalized prevention checklist grounded in current screening guidelines.",
                accent = colors.accent,
                accentSoft = colors.accentSoft,
                onClick = { onOpen("partner") },
            )
            FeatureCard(
                icon = Icons.Default.Science,
                title = "ReportReader",
                tag = "LAB REPORTS",
                body = "Pick a lab report PDF. We flag values outside the printed range and explain what each test measures.",
                accent = colors.secondary,
                accentSoft = colors.secondarySoft,
                onClick = {
                    // Phase 4 D-07 — fire-and-forget engine warm-up on tile tap.
                    // Hides cold-start cost behind LandingState + SAF picker.
                    // Phase 9 D-05a — full warm-up rationale + idempotency notes
                    // live on AegisApp.warmUpEngine() KDoc; HomeScreen reaches
                    // engine state only via that wrapper (HOME-05 grep gate).
                    AegisApp.instance.warmUpEngine()
                    onOpen("reportreader")
                },
            )
        }

        Spacer(Modifier.height(20.dp))

        // ── Recent ──
        val recent by AegisApp.instance.historyDb.history()
            .getRecent(2)
            .collectAsStateWithLifecycle(initialValue = emptyList())
        if (recent.isNotEmpty()) {
            SectionLabel("Recent")
            Spacer(Modifier.height(10.dp))
            val now = System.currentTimeMillis()
            recent.forEach { entry ->
                RecentRow(
                    icon = iconForKind(entry.kind),
                    title = entry.title,
                    sub = "${entry.sub} · ${formatRelative(entry.createdAt, now)}",
                )
            }
        }
    }
}

private fun iconForKind(kind: String): ImageVector = when (kind) {
    HistoryEntity.KIND_DRUGSAFE -> Icons.Default.Medication
    HistoryEntity.KIND_CONSENT -> Icons.Default.Description
    HistoryEntity.KIND_PARTNER -> Icons.Default.Favorite
    HistoryEntity.KIND_REPORTREADER -> Icons.Default.Science
    else -> Icons.Default.Medication
}

@Composable
private fun FeatureCard(
    icon: ImageVector,
    title: String,
    tag: String,
    body: String,
    accent: Color,
    accentSoft: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAegisColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            // Phase 9 HOME-02 D-02c (P9-A fix): clip BEFORE background so the
            // .clickable indication (Material ripple) is bounded by the same
            // 18.dp rounded corner as the fill. Without this, the ripple draws
            // as a rectangle and bleeds past the visual tile bounds. The
            // .border drawer below still takes an explicit shape — the clip
            // layer doesn't affect it. See PATTERNS.md §HomeScreen.kt
            // FeatureCard ripple-on-rounded fix + §Modifier order convention.
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface)
            .let {
                if (colors.isDark) it.border(1.dp, colors.hairline, RoundedCornerShape(18.dp))
                else it
            }
            .clickable { onClick() }
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Icon container 44×44
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(accentSoft, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.size(width = 12.dp, height = 0.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.onSurface,
                )
                Text(
                    tag,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceMuted,
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                null,
                tint = colors.onSurfaceMuted,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceMuted,
        )
    }
}

@Composable
private fun RecentRow(
    icon: ImageVector,
    title: String,
    sub: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAegisColors.current
    // WR-06: wrap the content Row and the hairline divider in a Column that
    // owns the caller-supplied modifier, so any modifier (padding, weight,
    // alignment) covers BOTH siblings. Previously the modifier was applied
    // only to the Row and the divider rendered outside its scope, giving
    // a future caller's modifier inconsistent coverage and a subtle bug.
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(colors.surfaceAlt, RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = colors.onSurfaceMuted, modifier = Modifier.size(16.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceMuted)
            }
            Icon(
                Icons.Default.ChevronRight,
                null,
                tint = colors.onSurfaceMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.hairline),
        )
    }
}

private fun greetingForNow(): String {
    val h = LocalTime.now().hour
    return when {
        h < 5 -> "Good evening"
        h < 12 -> "Good morning"
        h < 18 -> "Good afternoon"
        else -> "Good evening"
    }
}

/**
 * Phase 9 HOME-03 — model-ready confirmation pill rendered in the brand row
 * when `AegisApp.instance.startup.collectAsState() is StartupState.Ready`.
 *
 * Visual idiom mirrors Chips.kt PillTag (full-pill RoundedCornerShape(99.dp),
 * 10dp/5dp inset, labelMedium typography) but uses the D-03c muted token pair
 * (`surfaceAlt` bg + `onSurfaceMuted` ink) — same pair Phase 8 LabRow IN_RANGE
 * uses, WCAG AA pre-cleared. No green, no Icon Composable — the ✓ glyph is
 * Unicode U+2713 baked into the `text` string. Display-only: no onClick, no
 * Modifier.clickable (D-03d). Private + single-use, scoped to HomeScreen.kt
 * per PATTERNS.md Pattern 3 Option 2 ("purpose-built, not a generalization
 * of PillTag").
 */
@Composable
private fun StatusPill(text: String) {
    val colors = LocalAegisColors.current
    Row(
        modifier = Modifier
            .background(colors.surfaceAlt, RoundedCornerShape(99.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceMuted,
        )
    }
}
