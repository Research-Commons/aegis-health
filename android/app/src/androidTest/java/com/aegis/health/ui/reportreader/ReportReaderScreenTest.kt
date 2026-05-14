package com.aegis.health.ui.reportreader

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aegis.health.AegisApp
import com.aegis.health.StartupState
import com.aegis.health.models.EvaluatedRow
import com.aegis.health.ui.theme.AegisHealthTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 3 Compose UI tests for [ReportReaderScreen] and its component atoms.
 *
 * Strategy: rather than drive the SAF picker (hard to stub), we host the
 * screen's component atoms directly with synthetic [PreparsedReport] data.
 * The parse-pipeline path is already covered by Phase 2's
 * [LabReportPipelineFixtureTest]. These tests focus on rendering branches:
 *
 *   1. NotADiagnosisPanel reveals the three SAFETY-04 anchor phrases when
 *      tapped (UI-07).
 *   2. SummaryCard renders the count headline for a synthetic OK report
 *      (UI-05).
 *   3. LabRow rendering — IN_RANGE row has NO Discuss CTA after expand;
 *      OUTSIDE_RANGE row DOES have one (UI-04).
 *   4. ReportEmptyState renders per status code (D-06 / UI-04 corollary).
 *
 * Memory pin (project_kbdatabase_startup_race): @Before waits on
 * AegisApp.startup before composing — same pattern as Phase 2
 * LabReportPipelineFixtureTest. Defensive consistency: these tests don't
 * actually touch KBDatabase, but waiting is harmless and prevents future
 * regressions if anything tested here grows a database dependency.
 *
 * Memory pin (feedback_gradle_connected_androidtest_filter): use
 * `-Pandroid.testInstrumentationRunnerArguments.class=com.aegis.health.ui.reportreader.ReportReaderScreenTest`
 * for selective runs — `--tests "*X*"` is broken on AGP.
 */
@RunWith(AndroidJUnit4::class)
class ReportReaderScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: AegisApp

    @Before
    fun setup() {
        app = InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .applicationContext as AegisApp
        runBlocking {
            app.startup.first { it !is StartupState.Initializing }
        }
    }

    // ── UI-07 anchor phrases (SAFETY-04 grep targets) ────────────────────

    @Test
    fun notADiagnosisPanel_collapsed_shows_only_summary_bar() {
        composeRule.setContent {
            AegisHealthTheme {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    NotADiagnosisPanel()
                }
            }
        }
        composeRule.onNodeWithText("What this is — and what it isn't").assertIsDisplayed()
        // Anchor phrases NOT visible in collapsed state.
        composeRule.onAllNodesWithText("not a diagnosis", substring = true).assertCountEquals(0)
    }

    @Test
    fun notADiagnosisPanel_expanded_reveals_three_safety_anchor_phrases() {
        composeRule.setContent {
            AegisHealthTheme {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    NotADiagnosisPanel()
                }
            }
        }
        // Tap the summary bar to expand.
        composeRule.onNodeWithText("What this is — and what it isn't").performClick()

        // All three SAFETY-04 anchor phrases must now be visible. Substring
        // match because each phrase is embedded in a longer disclaimer
        // sentence rendered as one Text node.
        composeRule.onNodeWithText("not a diagnosis", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("replace medical advice", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("recommend treatment", substring = true).assertIsDisplayed()
    }

    // ── UI-05 SummaryCard count headline ─────────────────────────────────

    @Test
    fun summaryCard_renders_count_headline_for_outside_range_only() {
        val rows = listOf(
            makeRow("LDL", "OUTSIDE_RANGE", 200.0),
            makeRow("HDL", "OUTSIDE_RANGE", 30.0),
            makeRow("Glucose", "BORDERLINE", 99.0),
            makeRow("A1C", "IN_RANGE", 5.4),
        )
        composeRule.setContent {
            AegisHealthTheme {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    SummaryCard(
                        outsideRows = rows.filter { it.status == "OUTSIDE_RANGE" },
                        totalCount = rows.size,
                        onChipTap = {},
                        onClinicianCta = {},
                    )
                }
            }
        }
        // D-03 count framing: "2 of 4 values are outside the printed range".
        // BORDERLINE doesn't promote to chip strip (D-04).
        composeRule.onNodeWithText("2 of 4 values are outside the printed range").assertIsDisplayed()
        composeRule.onNodeWithText("Bring this to your clinician").assertIsDisplayed()
        composeRule.onNodeWithText("LDL").assertIsDisplayed()
        composeRule.onNodeWithText("HDL").assertIsDisplayed()
        // BORDERLINE row must NOT appear as a chip.
        composeRule.onAllNodesWithText("Glucose", substring = false).assertCountEquals(0)
    }

    @Test
    fun summaryCard_all_clear_keeps_clinician_cta_text() {
        val rows = listOf(makeRow("LDL", "IN_RANGE", 95.0))
        composeRule.setContent {
            AegisHealthTheme {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    SummaryCard(
                        outsideRows = emptyList(),
                        totalCount = rows.size,
                        onChipTap = {},
                        onClinicianCta = {},
                    )
                }
            }
        }
        // D-04 all-clear case: headline still uses count framing, CTA never varies.
        composeRule.onNodeWithText("0 of 1 values are outside the printed range").assertIsDisplayed()
        composeRule.onNodeWithText("Bring this to your clinician").assertIsDisplayed()
        // No celebratory "fine" copy.
        composeRule.onAllNodesWithText("fine", substring = true).assertCountEquals(0)
    }

    // ── UI-04 per-row Discuss CTA presence ───────────────────────────────

    @Test
    fun labRow_in_range_expanded_has_no_discuss_cta() {
        val row = makeRow("LDL", "IN_RANGE", 95.0, definition = "LDL is a type of cholesterol.")
        composeRule.setContent {
            AegisHealthTheme {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    LabRow(row = row, onDiscuss = {})
                }
            }
        }
        // Tap the row to expand.
        composeRule.onNodeWithText("LDL").performClick()
        composeRule.onNodeWithText("LDL is a type of cholesterol.").assertIsDisplayed()
        composeRule.onAllNodesWithText("Discuss with your doctor").assertCountEquals(0)
    }

    @Test
    fun labRow_outside_range_expanded_shows_discuss_cta() {
        val row = makeRow("LDL", "OUTSIDE_RANGE", 200.0, definition = "LDL is a type of cholesterol.")
        composeRule.setContent {
            AegisHealthTheme {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    LabRow(row = row, onDiscuss = {})
                }
            }
        }
        composeRule.onNodeWithText("LDL").performClick()
        composeRule.onNodeWithText("Discuss with your doctor").assertIsDisplayed()
    }

    @Test
    fun labRow_unknown_row_shows_defer_reason_caption_and_discuss_cta() {
        val row = makeRow(
            name = "AFP",
            status = "unknown",
            value = null,
            deferReason = "auto_defer:tumor_marker",
            definition = null,
        )
        composeRule.setContent {
            AegisHealthTheme {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    LabRow(row = row, onDiscuss = {})
                }
            }
        }
        composeRule.onNodeWithText("AFP").performClick()
        val expectedCaption = DeferReasonCopy.lookup("auto_defer:tumor_marker")
        composeRule.onNodeWithText(expectedCaption).assertIsDisplayed()
        composeRule.onNodeWithText("Discuss with your doctor").assertIsDisplayed()
    }

    // ── D-06 ReportEmptyState ─────────────────────────────────────────────

    @Test
    fun reportEmptyState_image_only_shows_headline_and_two_ctas() {
        composeRule.setContent {
            AegisHealthTheme {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    ReportEmptyState(
                        statusCode = "IMAGE_ONLY",
                        statusMessage = null,
                        onPickAnother = {},
                        onDiscuss = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("This looks like a scanned image.").assertIsDisplayed()
        composeRule.onNodeWithText("Try another file").assertIsDisplayed()
        composeRule.onNodeWithText("Discuss with your doctor").assertIsDisplayed()
    }

    @Test
    fun reportEmptyState_uses_supplied_statusMessage_when_present() {
        composeRule.setContent {
            AegisHealthTheme {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    ReportEmptyState(
                        statusCode = "TOO_MANY_ANALYTES",
                        statusMessage = "Custom Phase 2 message",
                        onPickAnother = {},
                        onDiscuss = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("Custom Phase 2 message").assertIsDisplayed()
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun makeRow(
        name: String,
        status: String,
        value: Double?,
        units: String? = "mg/dL",
        deferReason: String? = null,
        definition: String? = null,
    ) = EvaluatedRow(
        canonical_name = name,
        raw_name = name,
        value = if (value != null) JsonPrimitive(value) else JsonNull,
        units = units,
        ref_low = JsonPrimitive(70.0),
        ref_high = JsonPrimitive(100.0),
        ref_source = "PDF",
        status = status,
        definition = definition,
        definition_citation = null,
        defer_reason = deferReason,
    )
}
