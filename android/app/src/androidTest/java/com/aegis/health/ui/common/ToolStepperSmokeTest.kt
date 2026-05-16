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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 5 INFRA-06 smoke test for the `ToolStepper` Composable skeleton.
 *
 * Pins the consumer contract per D-10: renders `ToolStepper(label, steps)` and
 * asserts the label + each step string is displayed. **Asserts label PRESENCE
 * only — not visual style, not order, not animation state.** This is the
 * load-bearing guarantee that Phase 7's body rewrite (three-state pending /
 * running / done, sequential reveal via AnimatedVisibility, shimmer skeletons)
 * does NOT break the consumer contract. If Phase 7 reshuffles render order or
 * briefly hides a step during reveal, this smoke test should keep passing — the
 * 4 strings must always be reachable in the rendered tree.
 *
 * No `@Before` AegisApp.startup wait — the stepper skeleton touches no
 * database / no engine / no AegisApp state. Memory pin
 * `project_kbdatabase_startup_race.md` applies only to tests that USE the
 * database; adding the wait here would slow the smoke test substantially with
 * no defensive benefit.
 *
 * Memory pin (feedback_gradle_connected_androidtest_filter): selective run uses
 * `-Pandroid.testInstrumentationRunnerArguments.class=com.aegis.health.ui.common.ToolStepperSmokeTest`,
 * NEVER `--tests "*X*"` (broken on AGP).
 */
@RunWith(AndroidJUnit4::class)
class ToolStepperSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun renders_label_and_all_step_labels() {
        composeRule.setContent {
            AegisHealthTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                ) {
                    ToolStepper(
                        label = "Test",
                        steps = listOf("Step A", "Step B", "Step C"),
                    )
                }
            }
        }
        composeRule.onNodeWithText("Test").assertIsDisplayed()
        composeRule.onNodeWithText("Step A").assertIsDisplayed()
        composeRule.onNodeWithText("Step B").assertIsDisplayed()
        composeRule.onNodeWithText("Step C").assertIsDisplayed()
    }
}
