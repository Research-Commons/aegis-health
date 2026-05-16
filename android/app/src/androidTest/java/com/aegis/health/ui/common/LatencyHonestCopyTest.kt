package com.aegis.health.ui.common

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aegis.health.ui.theme.AegisHealthTheme
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 7 Plan 07-02 Compose UI instrumented test for the SKEL-04
 * latency-honest subline. D-05 pins this literal as the SINGLE source of
 * truth inside [ToolStepper]; all three stepper-bearing screens (DrugSafe,
 * ReportReader, HealthPartner) inherit the copy via composable inclusion
 * (07-RESEARCH.md Open Q #1 resolution).
 *
 * Pinned assertions:
 *  - When ToolStepper is mounted with a real step list, the literal
 *    substring "running on your phone" is reachable via Compose semantics.
 *  - When ToolStepper is mounted with an empty steps list (pre-first-Step
 *    shimmer window — D-03a), the literal is still present — D-05 says
 *    "always rendered while ToolStepper is on screen".
 *
 * Ships `@Ignore`'d. See class-level `@Ignore` message for the
 * TEST-FRAMEWORK-01 carry-over from Phase 5.
 *
 * Memory pin (feedback_gradle_connected_androidtest_filter): selective run
 * uses `-Pandroid.testInstrumentationRunnerArguments.class=com.aegis.health.\
 * ui.common.LatencyHonestCopyTest`, NEVER `--tests "*X*"`.
 */
@RunWith(AndroidJUnit4::class)
@Ignore(
    "TEST-FRAMEWORK-01: BOM 2026.05.00 regressed Compose UI test framework " +
        "on SM-S918B — Phase 5 carry-over, migration deferred to Phase 10 P1 " +
        "stretch. Test lights up automatically when Phase 10 lands " +
        "androidx.compose.ui.test.junit4.v2.createAndroidComposeRule migration. " +
        "Verifier accepts JVM-only automated coverage + manual on-device " +
        "screenshot in 07-02-SUMMARY.md.",
)
class LatencyHonestCopyTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun toolStepper_renders_latency_honest_subline() {
        composeRule.setContent {
            AegisHealthTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                ) {
                    ToolStepper(
                        label = "Analyzing 2 medications…",
                        steps = listOf(
                            "Checking warfarin + aspirin for a 72-year-old",
                        ),
                    )
                }
            }
        }
        // Substring match — D-05 single source of truth in ToolStepper.kt.
        // All 3 stepper-bearing screens inherit via composable inclusion.
        composeRule.onNodeWithText("running on your phone", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun toolStepper_renders_subline_even_in_empty_steps_state() {
        composeRule.setContent {
            AegisHealthTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                ) {
                    // Pre-first-Step shimmer window — steps is empty. D-05
                    // says the latency-honest subline is ALWAYS rendered
                    // while ToolStepper is on screen, not just during the
                    // real-rows render path.
                    ToolStepper(label = "Test", steps = emptyList())
                }
            }
        }
        composeRule.onNodeWithText("running on your phone", substring = true)
            .assertIsDisplayed()
    }
}
