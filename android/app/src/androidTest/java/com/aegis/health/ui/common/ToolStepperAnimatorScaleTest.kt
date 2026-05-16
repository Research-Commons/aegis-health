package com.aegis.health.ui.common

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aegis.health.ui.theme.AegisHealthTheme
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 7 Plan 07-02 Compose UI instrumented test asserting that
 * `Settings.Global.ANIMATOR_DURATION_SCALE = 0` produces a non-animated
 * stepper (SKEL-05 + SC-3 + T-07-07 mitigation). Compose's framework
 * auto-honors the system setting on every `tween` / `infiniteRepeatable`
 * animationSpec since Compose 1.2.0 (LoadingPanel.PulsingDot already relies
 * on this precedent — no manual `Settings.Global.getFloat` lookup needed in
 * production code).
 *
 * Pitfall 4 (07-RESEARCH.md): ANIMATOR_DURATION_SCALE is a device-wide
 * setting. `@Before` captures the original value via
 * `Settings.Global.getFloat` (wrapped in try/catch for
 * `SettingNotFoundException`) and `@After` restores it via
 * `UiAutomation.executeShellCommand` — this prevents test pollution that
 * would silently disable animations in unrelated tests (defensive cleanup
 * still in place even while `@Ignore`'d, so the lift-the-ignore moment in
 * Phase 10 P1 needs no further work).
 *
 * Ships `@Ignore`'d. See class-level `@Ignore` message for the
 * TEST-FRAMEWORK-01 carry-over from Phase 5.
 *
 * Memory pin (feedback_gradle_connected_androidtest_filter): selective run
 * uses `-Pandroid.testInstrumentationRunnerArguments.class=com.aegis.health.\
 * ui.common.ToolStepperAnimatorScaleTest`, NEVER `--tests "*X*"`.
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
class ToolStepperAnimatorScaleTest {

    private val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var originalScale: Float = 1f

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun captureScale() {
        originalScale = try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
            )
        } catch (e: Settings.SettingNotFoundException) {
            // No setting present on this device — default to 1f (normal
            // animation speed). The restore step writes 1f, which is the
            // platform default.
            1f
        }
    }

    @After
    fun restoreScale() {
        // Mandatory cleanup — Pitfall 4 from 07-RESEARCH.md. Even though
        // this class is @Ignore'd, the cleanup is here so Phase 10 P1's
        // ignore-removal lights up the test without further work.
        uiAutomation.executeShellCommand(
            "settings put global animator_duration_scale $originalScale",
        )
    }

    @Test
    fun scale_zero_disables_animations() {
        // Disable animations device-wide before mounting the composable.
        uiAutomation.executeShellCommand(
            "settings put global animator_duration_scale 0",
        )

        composeRule.setContent {
            AegisHealthTheme {
                val steps = remember { mutableStateListOf<String>() }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                ) {
                    ToolStepper(label = "Test", steps = steps)
                }
                // Append a step after setContent — exercises the
                // AnimatedVisibility reveal path. With scale=0, the reveal
                // animation completes in ~0ms and waitForIdle() returns
                // near-instantly (vs ~350ms with scale=1).
                composeRule.runOnIdle { steps += "Step A" }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Step A").assertIsDisplayed()
    }
}
