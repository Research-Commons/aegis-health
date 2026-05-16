package com.aegis.health.ui.healthpartner

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aegis.health.inference.ToolDispatcher
import com.aegis.health.ui.common.MonotonicFlagList
import com.aegis.health.ui.common.SectionLabel
import com.aegis.health.ui.common.SeverityCard
import com.aegis.health.ui.theme.AegisHealthTheme
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 6 / Plan 06-03 Compose UI instrumented test for the streaming
 * preview rail wired into [HealthPartnerScreen] (STREAM-01 HealthPartner
 * half / STREAM-02 wiring parity / ROADMAP SC-1, SC-2).
 *
 * Asserts that a synthetic [ToolDispatcher.ProgressEvent.FlagPreview] pushed
 * into a `mutableStateListOf` drives a [SeverityCard] render in the same
 * shape `HealthPartnerScreen` uses inside its `if (isLoading) { ... }`
 * verticalScroll-Column arm (file lines ~ "Plan 06-03 streaming preview
 * rail"). The test composes the preview-rail block directly so it doesn't
 * depend on the full screen's profile-form / synthesis / fast-path /
 * `DeferralStore` surface (mirrors the strategy of [HealthPartnerScreenTest]
 * which hosts component atoms with synthetic data, and the strategy of
 * `ReportReaderFlagPreviewTest` from Plan 06-02).
 *
 * The harness is byte-identical to the in-screen render except the outer
 * `if (isLoading) { ... }` Column wrapping is flattened (since the harness
 * has no surrounding screen scope). The rail block itself, the
 * [MonotonicFlagList.appendIfNew] call site, the [SectionLabel] header
 * format ("Streaming · 1 flag so far"), and the [SeverityCard] render shape
 * all match `HealthPartnerScreen.kt` verbatim — which is the same shape
 * `ReportReaderScreen.kt` (Plan 06-02) and `DrugSafeScreen.kt:248-262`
 * (Phase 5 carry-forward) use.
 *
 * Ships `@Ignore`'d. See class-level `@Ignore` message for the
 * TEST-FRAMEWORK-01 carry-over from Phase 5. `:app:assembleDebugAndroidTest`
 * verifies this test compiles; `:app:connectedDebugAndroidTest` skips it via
 * `@Ignore` so SM-S918B keeps BUILD SUCCESSFUL. When Phase 10 P1 stretch
 * lands the v2-API migration (`androidx.compose.ui.test.junit4.v2`), the
 * `@Ignore` removal flips this test live with no other code change required
 * (the rail block under assertion is byte-identical to the in-screen render
 * shape).
 *
 * Memory pin (feedback_gradle_connected_androidtest_filter): selective run
 * uses `-Pandroid.testInstrumentationRunnerArguments.class=com.aegis.health.\
 * ui.healthpartner.HealthPartnerFlagPreviewTest`, NEVER `--tests "*X*"`
 * (broken on AGP).
 */
@RunWith(AndroidJUnit4::class)
@Ignore(
    "TEST-FRAMEWORK-01: BOM 2026.05.00 regressed Compose UI test framework " +
        "on SM-S918B — Phase 5 carry-over, migration deferred to Phase 10 P1 " +
        "stretch. Test lights up automatically when Phase 10 lands " +
        "androidx.compose.ui.test.junit4.v2.createAndroidComposeRule migration. " +
        "Verifier accepts JVM-only automated coverage + manual on-device " +
        "screenshot in 06-03-SUMMARY.md.",
)
class HealthPartnerFlagPreviewTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun previewRailRendersSeverityCardForSyntheticFlag() {
        composeRule.setContent {
            AegisHealthTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                ) {
                    // TEST-OWNED state list — distinct from the production
                    // HealthPartnerScreen's `flagPreviews` slot. The rail
                    // composable block below is byte-identical to the
                    // in-screen render (the outer `if (isLoading) { ... }`
                    // wrapping is flattened here because there is no
                    // surrounding screen scope in this test harness).
                    val flagPreviews = remember {
                        mutableStateListOf<ToolDispatcher.ProgressEvent.FlagPreview>()
                    }
                    // Push one synthetic event through MonotonicFlagList to
                    // exercise the same dedup path the screen uses. The
                    // helper is the SC-5 guard; calling it here keeps the
                    // test's wiring identical to production AND identical
                    // to ReportReaderFlagPreviewTest (Plan 06-02) — the
                    // wiring-parity invariant in source form.
                    val incoming = ToolDispatcher.ProgressEvent.FlagPreview(
                        severity = 3,
                        description = "test flag description",
                        citation = "FDA label",
                    )
                    val next = MonotonicFlagList.appendIfNew(flagPreviews.toList(), incoming)
                    if (next.size > flagPreviews.size) flagPreviews.add(incoming)

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
            }
        }
        // The SeverityCard renders description as the title node when the
        // description contains no sentence-terminating punctuation (per
        // splitTitleBody at SeverityCard.kt:176-188). Substring match is
        // resilient to future SeverityCard internal layout changes.
        composeRule.onNodeWithText("test flag description", substring = true)
            .assertIsDisplayed()
        // SectionLabel uppercases its text — assert the uppercased form.
        composeRule.onNodeWithText("STREAMING · 1 FLAG SO FAR", substring = true)
            .assertIsDisplayed()
    }
}
