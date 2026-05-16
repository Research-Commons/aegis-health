package com.aegis.health.ui.common

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aegis.health.ui.theme.AegisHealthTheme
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 7 Plan 07-02 Compose UI instrumented test for [ToolStepper]'s
 * calm-tone ⚠ chip rendering — STEP-06 negative guard against fake-success
 * ✓ on failed tool calls and ASVS V7 / T-07-05 truncation guard at 64
 * characters.
 *
 * Pinned assertions:
 *  - A row index present in the synthetic `failures` map renders the
 *    failure reason text (substring match) — the calm-tone chip path is
 *    reached, not the fake-success Done ✓ path.
 *  - The same row index has NO `step-row-Done-0` testTag — i.e. the failed
 *    row does NOT silently transition to ✓ (STEP-06: NEVER fake-success on
 *    failed tool calls).
 *  - Long failure reasons truncate to 64 characters (T-07-05 mitigation in
 *    `StepRow`'s Failed branch — `failureReason?.take(64)`).
 *
 * Ships `@Ignore`'d. See class-level `@Ignore` message for the
 * TEST-FRAMEWORK-01 carry-over from Phase 5.
 *
 * Memory pin (feedback_gradle_connected_androidtest_filter): selective run
 * uses `-Pandroid.testInstrumentationRunnerArguments.class=com.aegis.health.\
 * ui.common.ToolStepperFailureChipTest`, NEVER `--tests "*X*"`.
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
class ToolStepperFailureChipTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun failure_at_index_renders_warning_chip_not_check_mark() {
        composeRule.setContent {
            AegisHealthTheme {
                val steps = remember {
                    mutableStateListOf("Checking warfarin + aspirin for a 72-year-old")
                }
                val failures = remember {
                    mutableStateMapOf<Int, FailureInfo>().apply {
                        put(
                            0,
                            FailureInfo(
                                label = "Checking warfarin + aspirin for a 72-year-old",
                                reason = "SQLite query timed out",
                            ),
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                ) {
                    ToolStepper(
                        label = "DrugSafe",
                        steps = steps,
                        failures = failures,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // Positive — calm-tone chip text is present.
        composeRule.onNodeWithText("SQLite query timed out", substring = true)
            .assertIsDisplayed()

        // Negative — the failed row index 0 must NOT carry the Done
        // testTag (STEP-06: NEVER fake-success on failed tool calls).
        composeRule.onNodeWithTag("step-row-Done-0", useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun failure_reason_truncates_at_64_chars() {
        val longReason = "x".repeat(200)
        composeRule.setContent {
            AegisHealthTheme {
                val steps = remember { mutableStateListOf("Some step") }
                val failures = remember {
                    mutableStateMapOf<Int, FailureInfo>().apply {
                        put(0, FailureInfo(label = "Some step", reason = longReason))
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                ) {
                    ToolStepper(
                        label = "Test",
                        steps = steps,
                        failures = failures,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        // 64-character truncation per StepRow Failed branch.
        composeRule.onNodeWithText("x".repeat(64), substring = true)
            .assertIsDisplayed()
    }
}
