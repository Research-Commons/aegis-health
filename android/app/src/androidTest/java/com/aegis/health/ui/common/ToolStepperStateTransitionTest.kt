package com.aegis.health.ui.common

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateListOf
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
 * three-state row transitions (Running ↻ → Done ✓) driven by reactive
 * `mutableStateListOf<String>` appends — the consumer pattern Phase 5 D-09
 * pins (`steps.add(label)` from `ProgressEvent.Step` arrivals).
 *
 * Pinned assertions:
 *  - When a new step is added to the steps list, the prior step's row
 *    transitions from Running to Done. We assert via the testTag scheme
 *    `step-row-Done-N` exposed by [ToolStepper]'s StepRow.
 *  - When there is only one step, that single row is in Running state — we
 *    assert via `step-row-Running-0`.
 *
 * Ships `@Ignore`'d. See class-level `@Ignore` message for the
 * TEST-FRAMEWORK-01 carry-over from Phase 5. `:app:assembleDebugAndroidTest`
 * verifies this test compiles; `:app:connectedDebugAndroidTest` skips it via
 * `@Ignore` so SM-S918B keeps BUILD SUCCESSFUL. When Phase 10 P1 stretch
 * lands the v2-API migration (`androidx.compose.ui.test.junit4.v2`), the
 * `@Ignore` removal flips this test live with no other code change required.
 *
 * Memory pin (feedback_gradle_connected_androidtest_filter): selective run
 * uses `-Pandroid.testInstrumentationRunnerArguments.class=com.aegis.health.\
 * ui.common.ToolStepperStateTransitionTest`, NEVER `--tests "*X*"`
 * (broken on AGP).
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
class ToolStepperStateTransitionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun new_step_arrival_transitions_prior_row_to_done() {
        // Start with one row — initially Running because it is the last
        // element (D-03 — last=running, prior=done).
        composeRule.setContent {
            AegisHealthTheme {
                val steps = remember { mutableStateListOf("Step A") }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                ) {
                    ToolStepper(label = "Test", steps = steps)
                }
                // Simulate ProgressEvent.Step arrival — append "Step B".
                composeRule.runOnIdle { steps += "Step B" }
            }
        }
        composeRule.waitForIdle()
        // Both labels remain reachable in the tree post-mutation.
        composeRule.onNodeWithText("Step A").assertIsDisplayed()
        composeRule.onNodeWithText("Step B").assertIsDisplayed()
        // The first row (idx=0) is now Done; the second row (idx=1) is
        // Running. testTag scheme: step-row-<state.name>-<idx>.
        composeRule.onNodeWithTag("step-row-Done-0", useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag("step-row-Running-1", useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun single_step_renders_as_running() {
        composeRule.setContent {
            AegisHealthTheme {
                val steps = remember { mutableStateListOf("Only step") }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                ) {
                    ToolStepper(label = "Test", steps = steps)
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Only step").assertIsDisplayed()
        composeRule.onNodeWithTag("step-row-Running-0", useUnmergedTree = true)
            .assertIsDisplayed()
    }
}
