package com.aegis.health.ui.healthpartner

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.aegis.health.AegisApp
import com.aegis.health.db.history.HistoryEntity
import com.aegis.health.inference.ToolDispatcher
import com.aegis.health.models.AegisResponse
import com.aegis.health.models.GuidelineRecommendation
import com.aegis.health.models.HealthProfile
import com.aegis.health.ui.deferral.DeferralStore
import com.aegis.health.ui.profile.ProfileStore
import com.aegis.health.ui.common.AegisTextField
import com.aegis.health.ui.common.DeferralBanner
import com.aegis.health.ui.common.FailureInfo
import com.aegis.health.ui.common.GhostButton
import com.aegis.health.ui.common.GradePill
import com.aegis.health.ui.common.MonotonicFlagList
import com.aegis.health.ui.common.PrimaryButton
import com.aegis.health.ui.common.ScreenHeader
import com.aegis.health.ui.common.SectionLabel
import com.aegis.health.ui.common.SeverityCard
import com.aegis.health.ui.common.Tag
import com.aegis.health.ui.common.ToolStepper
import com.aegis.health.ui.theme.AegisSpacing
import com.aegis.health.ui.theme.LocalAegisColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Composable
fun HealthPartnerScreen(
    onBack: () -> Unit,
    onDefer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAegisColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Form state — pre-fill from ProfileStore on first composition.
    val seed = remember { ProfileStore.current() }
    var ageText by remember { mutableStateOf(seed?.age?.toString().orEmpty()) }
    var sex by remember {
        mutableStateOf(seed?.sex?.replaceFirstChar { it.uppercaseChar() }.orEmpty())
    }
    var conditions by remember { mutableStateOf(seed?.conditions?.joinToString(", ").orEmpty()) }
    var medications by remember { mutableStateOf(seed?.medications?.joinToString(", ").orEmpty()) }
    var familyHistory by remember {
        mutableStateOf(seed?.familyHistory?.joinToString(", ").orEmpty())
    }

    // Output state.
    var isLoading by remember { mutableStateOf(false) }
    var response by remember { mutableStateOf<AegisResponse?>(null) }
    var recommendations by remember { mutableStateOf<List<GuidelineRecommendation>>(emptyList()) }
    var gaps by remember { mutableStateOf<List<String>>(emptyList()) }
    val checked = remember { mutableStateMapOf<Int, Boolean>() }
    val progress = remember { mutableStateListOf<String>() }

    // Plan 06-03 — STREAM-01 + STREAM-02 + ROADMAP SC-1 / SC-2.
    //
    // FlagPreview events from ToolDispatcher.runHealthPartnerFastPath land
    // here as the synthesis turn streams. Cleared on each new "Build my
    // plan" tap; replaced by the real per-row guidance once `response`
    // populates downstream. Dedup is routed through
    // MonotonicFlagList.appendIfNew (ROADMAP SC-5 / M2 mitigation —
    // never-shrink invariant) — the SAME helper Plan 06-02 wires into
    // ReportReaderScreen, so neither screen forks the dedup heuristic
    // (the JVM wiring-parity test in
    // `FlagPreviewWiringParityTest.kt` structurally enforces this on
    // every test run).
    //
    // HealthPartner's domain emits zero FlagPreview events on the
    // typical "45yo male preventive" fixture (06-RESEARCH.md §Pitfall
    // #2) — the rail below is hidden behind `flagPreviews.isNotEmpty()`
    // so this is a data-driven absence, not a UI-conditional absence
    // (Open Q #2 resolution from planning_context).
    //
    // D-13 single-buffer-owner invariant: this list holds typed
    // `ToolDispatcher.ProgressEvent.FlagPreview` events only — never any
    // reference to the dispatcher's engine-internal decode buffer (the
    // SC-4 grep gate enforces this structurally).
    val flagPreviews = remember { mutableStateListOf<ToolDispatcher.ProgressEvent.FlagPreview>() }
    // Phase 7 D-04c — typed side channel for ProgressEvent.StepFailure events.
    // ToolStepper renders the calm-tone ⚠ chip when an entry exists at the step index.
    // Mirrors DrugSafeScreen.kt's `failures` state declaration verbatim — STREAM-02
    // wiring-parity invariant (Phase 6 Plan 06-03 FlagPreviewWiringParityTest).
    val failures = remember { mutableStateMapOf<Int, FailureInfo>() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AegisSpacing.xl, vertical = AegisSpacing.xl),
    ) {
        ScreenHeader(
            title = "HealthPartner",
            subtitle = "A prevention checklist grounded in USPSTF guidance.",
            onBack = onBack,
        )
        Spacer(Modifier.height(22.dp))

        if (response == null && !isLoading) {
            ProfileForm(
                ageText = ageText, onAgeChange = { ageText = it.filter { c -> c.isDigit() } },
                sex = sex, onSexChange = { sex = it },
                conditions = conditions, onConditionsChange = { conditions = it },
                medications = medications, onMedicationsChange = { medications = it },
                familyHistory = familyHistory, onFamilyHistoryChange = { familyHistory = it },
            )
            Spacer(Modifier.height(20.dp))
            PrimaryButton(
                text = "Build my plan",
                onClick = {
                    val age = ageText.toIntOrNull()
                    if (age != null && sex.isNotBlank()) {
                        saveProfile(context, ageText, sex, conditions, medications, familyHistory)
                        scope.launch {
                            isLoading = true
                            response = null
                            recommendations = emptyList()
                            gaps = emptyList()
                            checked.clear()
                            progress.clear()
                            flagPreviews.clear()
                            failures.clear()

                            val condList = conditions.splitTrim()
                            val medList = medications.splitTrim()
                            val profileDesc = buildString {
                                append("Age: $age, Sex: $sex")
                                if (condList.isNotEmpty()) append(", Conditions: ${condList.joinToString()}")
                                if (medList.isNotEmpty()) append(", Medications: ${medList.joinToString()}")
                                if (familyHistory.isNotBlank()) append(", Family history: $familyHistory")
                            }

                            try {
                                val r = ToolDispatcher.runHealthPartnerFastPath(
                                    age = age,
                                    sex = sex,
                                    conditions = condList,
                                    userInput = profileDesc,
                                    onProgress = { event ->
                                        // Phase 7 D-04c — typed `when` with explicit
                                        // StepFailure branch routing through the
                                        // screen-scope `failures` side channel.
                                        // FlagPreview retains its Phase 6 Plan 06-03
                                        // `MonotonicFlagList.appendIfNew` consumer
                                        // (STREAM-02 wiring-parity invariant — the
                                        // JVM `FlagPreviewWiringParityTest.bothScreens_
                                        // useMonotonicFlagList_appendIfNew_noPerModeForks`
                                        // structurally enforces this shape). The
                                        // `else` branch covers Step + Update via the
                                        // shared `applyTo` contract.
                                        when (event) {
                                            is ToolDispatcher.ProgressEvent.FlagPreview -> {
                                                val next = MonotonicFlagList.appendIfNew(
                                                    flagPreviews.toList(),
                                                    event,
                                                )
                                                if (next.size > flagPreviews.size) flagPreviews.add(event)
                                            }
                                            is ToolDispatcher.ProgressEvent.StepFailure -> {
                                                val idx = (progress.size - 1).coerceAtLeast(0)
                                                failures[idx] = FailureInfo(event.label, event.reason)
                                            }
                                            else -> event.applyTo(progress)
                                        }
                                    },
                                )
                                response = r.response
                                recommendations = r.guidelines.recommendations
                                gaps = buildList {
                                    addAll(r.guidelines.gaps)
                                    if (familyHistory.isBlank()) add("No family history provided — genetic risk factors not assessed.")
                                }
                                withContext(Dispatchers.IO) {
                                    AegisApp.instance.historyDb.history().insert(
                                        HistoryEntity(
                                            kind = HistoryEntity.KIND_PARTNER,
                                            title = "Prevention plan · age $age",
                                            sub = "${recommendations.size} rec${if (recommendations.size == 1) "" else "s"}",
                                            severityKey = HistoryEntity.SEV_INFO,
                                            createdAt = System.currentTimeMillis(),
                                            payloadJson = Json.encodeToString(r.response),
                                        ),
                                    )
                                }
                            } catch (ce: kotlinx.coroutines.CancellationException) {
                                // Honour structured-concurrency cancellation (Phase 7
                                // CR-02; ReportReaderScreen.kt:417-420 reference).
                                throw ce
                            } catch (t: Throwable) {
                                // Plan 07-07 CR-02 — secondary guard for OOM / JNI
                                // crash / withContext IO failure / history-insert
                                // exception. Plan 07-06's catch-and-emit-StepFailure
                                // path covers the tool-throws case; this catch is
                                // the screen-side belt-and-suspenders so the user is
                                // NEVER stranded on an infinite spinner.
                                android.util.Log.e("HealthPartnerScreen", "fast-path crashed", t)
                                // Populate the failures map at the latest step index
                                // so STEP-06's calm-tone ⚠ chip renders on the row
                                // that was running when the exception fired.
                                val idx = (progress.size - 1).coerceAtLeast(0)
                                failures[idx] = FailureInfo(
                                    label = progress.getOrNull(idx) ?: "Prevention plan check",
                                    reason = t.message ?: "On-device check failed",
                                )
                            } finally {
                                isLoading = false
                            }
                        }
                    }
                },
                leading = Icons.Default.AutoAwesome,
                enabled = ageText.isNotBlank() && sex.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (ageText.isBlank() || sex.isBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Age and sex required for guideline matching.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceMuted,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }

        if (isLoading) {
            ToolStepper(
                label = "Building your prevention plan…",
                steps = progress,
                modifier = Modifier.fillMaxWidth(),
                failures = failures,
            )

            // Plan 06-03 streaming preview rail (STREAM-01 / STREAM-02 /
            // ROADMAP SC-1, SC-2). Mirrors DrugSafeScreen.kt:248-262 and the
            // ReportReaderScreen.kt rail from Plan 06-02 verbatim — the rail
            // shape is shared so any future change must be made in all three
            // sites simultaneously. Renders only when
            // `flagPreviews.isNotEmpty()`; HealthPartner's domain typically
            // emits zero flags on common preventive fixtures (06-RESEARCH.md
            // §Pitfall #2), so the empty branch is the typical case — not a
            // failure mode. Cards disappear when `isLoading` flips to false;
            // the settled checklist + DeferralBanner take over via the
            // AnimatedVisibility block below. No per-item placement animation
            // modifier per 06-RESEARCH.md §Anti-Patterns (M2 flicker).
            if (flagPreviews.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                val flagWord = if (flagPreviews.size == 1) "flag" else "flags"
                SectionLabel("Streaming · ${flagPreviews.size} $flagWord so far")
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    flagPreviews.forEach { preview ->
                        SeverityCard(
                            severity = preview.severity,
                            description = preview.description,
                            citation = preview.citation,
                        )
                    }
                }
            }
        }

        AnimatedVisibility(visible = response != null && !isLoading, enter = fadeIn()) {
            response?.let { resp ->
                val needsDefer = resp.defer_to_professional && resp.flags.any { it.severity >= 4 }
                Column {
                    if (needsDefer) {
                        DeferralBanner(
                            title = "Your profile flags a finding that needs clinician review.",
                            body = "Aegis built the prevention checklist below, but one or more items cross a threshold that requires a provider's input.",
                            onClick = {
                                DeferralStore.pending = resp
                                onDefer()
                            },
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                    PlanSummaryCard(
                        age = ageText,
                        sex = sex,
                        conditions = conditions,
                        recCount = recommendations.size,
                        gradeACount = recommendations.count { it.grade.equals("A", ignoreCase = true) },
                    )
                    Spacer(Modifier.height(18.dp))
                    SectionLabel("Your checklist · ${recommendations.size} items")
                    Spacer(Modifier.height(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        recommendations.forEachIndexed { i, rec ->
                            ChecklistRow(
                                rec = rec,
                                checked = checked[i] == true,
                                onCheck = { checked[i] = !(checked[i] ?: false) },
                            )
                        }
                    }
                    if (gaps.isNotEmpty()) {
                        Spacer(Modifier.height(22.dp))
                        GapsCard(items = gaps)
                    }
                    Spacer(Modifier.height(16.dp))
                    GhostButton(
                        text = "Edit profile",
                        onClick = {
                            response = null
                            recommendations = emptyList()
                            gaps = emptyList()
                            checked.clear()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileForm(
    ageText: String, onAgeChange: (String) -> Unit,
    sex: String, onSexChange: (String) -> Unit,
    conditions: String, onConditionsChange: (String) -> Unit,
    medications: String, onMedicationsChange: (String) -> Unit,
    familyHistory: String, onFamilyHistoryChange: (String) -> Unit,
) {
    SectionLabel("Profile")
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AegisTextField(
            value = ageText,
            onValueChange = onAgeChange,
            label = "Age",
            placeholder = "45",
            keyboardType = KeyboardType.Number,
            modifier = Modifier.weight(1f),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "SEX",
                style = MaterialTheme.typography.labelMedium,
                color = LocalAegisColors.current.onSurfaceMuted,
            )
            Spacer(Modifier.height(8.dp))
            SexSegmented(value = sex, onChange = onSexChange)
        }
    }
    Spacer(Modifier.height(14.dp))
    AegisTextField(
        value = conditions,
        onValueChange = onConditionsChange,
        label = "Conditions",
        placeholder = "diabetes, hypertension",
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(14.dp))
    AegisTextField(
        value = medications,
        onValueChange = onMedicationsChange,
        label = "Current medications",
        placeholder = "metformin, lisinopril",
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(14.dp))
    AegisTextField(
        value = familyHistory,
        onValueChange = onFamilyHistoryChange,
        label = "Family history",
        placeholder = "heart disease, breast cancer",
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SexSegmented(value: String, onChange: (String) -> Unit) {
    val colors = LocalAegisColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf("Female", "Male").forEach { option ->
            val selected = value == option
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .background(
                        color = if (selected) colors.accent else colors.surface,
                        shape = RoundedCornerShape(16.dp),
                    )
                    .border(
                        width = 1.dp,
                        color = if (selected) colors.accent else colors.hairline,
                        shape = RoundedCornerShape(16.dp),
                    )
                    .clickable { onChange(option) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    option,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) colors.accentInk else colors.onSurface,
                )
            }
        }
    }
}

@Composable
private fun PlanSummaryCard(
    age: String,
    sex: String,
    conditions: String,
    recCount: Int,
    gradeACount: Int,
) {
    val colors = LocalAegisColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(18.dp))
            .border(1.dp, colors.hairline, RoundedCornerShape(18.dp))
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(colors.accent, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                age.ifBlank { "—" },
                style = MaterialTheme.typography.headlineMedium,
                color = colors.accentInk,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "${sex.ifBlank { "—" }} · age ${age.ifBlank { "—" }}",
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
            )
            Text(
                conditions.ifBlank { "no conditions provided" }.take(48),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceMuted,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag("$recCount recs")
                Tag("$gradeACount grade A")
                Tag("USPSTF 2024")
            }
        }
    }
}

@Composable
private fun ChecklistRow(
    rec: GuidelineRecommendation,
    checked: Boolean,
    onCheck: () -> Unit,
) {
    val colors = LocalAegisColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(16.dp))
            .border(1.dp, colors.hairline, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 22dp checkbox
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(22.dp)
                .background(
                    color = if (checked) colors.accent else androidx.compose.ui.graphics.Color.Transparent,
                    shape = RoundedCornerShape(6.dp),
                )
                .border(
                    width = 1.5.dp,
                    color = if (checked) colors.accent else colors.hairline,
                    shape = RoundedCornerShape(6.dp),
                )
                .clickable { onCheck() },
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(Icons.Default.Check, null, tint = colors.accentInk, modifier = Modifier.size(13.dp))
            }
        }
        Column(modifier = Modifier.weight(1f).alpha(if (checked) 0.55f else 1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    rec.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        textDecoration = if (checked) TextDecoration.LineThrough else null,
                    ),
                    color = colors.onSurface,
                )
                GradePill(grade = rec.grade)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                rec.description,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceMuted,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                rec.citation,
                style = MaterialTheme.typography.labelMedium,
                color = colors.accent,
            )
        }
    }
}

@Composable
private fun GapsCard(items: List<String>) {
    val colors = LocalAegisColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.sevModBg, RoundedCornerShape(16.dp))
            .let { if (colors.isDark) it.border(1.dp, colors.hairline, RoundedCornerShape(16.dp)) else it }
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.AutoMirrored.Filled.HelpOutline, null, tint = colors.sevModFg, modifier = Modifier.size(16.dp))
            Text(
                "WHAT WE DON'T KNOW",
                style = MaterialTheme.typography.labelMedium,
                color = colors.sevModFg,
            )
        }
        Spacer(Modifier.height(8.dp))
        items.forEachIndexed { i, gap ->
            Text(
                "· $gap",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onWarmSurfaceMuted,
                modifier = Modifier.padding(top = if (i == 0) 0.dp else 4.dp),
            )
        }
    }
}

private fun String.splitTrim(): List<String> =
    split(",").map { it.trim() }.filter { it.isNotBlank() }

private fun saveProfile(
    context: Context,
    age: String,
    sex: String,
    conditions: String,
    medications: String,
    familyHistory: String,
) {
    val existing = ProfileStore.current()
    val profile = HealthProfile(
        name = existing?.name,
        age = age.toIntOrNull(),
        sex = sex.lowercase(),
        conditions = conditions.splitTrim(),
        medications = medications.splitTrim(),
        familyHistory = familyHistory.splitTrim(),
    )
    ProfileStore.save(context, profile)
}
