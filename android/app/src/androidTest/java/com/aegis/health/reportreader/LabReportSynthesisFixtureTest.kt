package com.aegis.health.reportreader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aegis.health.AegisApp
import com.aegis.health.StartupState
import com.aegis.health.inference.EngineRouter
import com.aegis.health.inference.ToolDispatcher
import com.aegis.health.models.PreparsedReport
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 4 SC-3 smoke test. Runs full
 *   PDF → ReportReaderPipeline.parse → ToolDispatcher.runReportReaderFastPath
 * against the 5 production fixtures (one per VendorExtractor path). Each
 * fixture should produce a non-fallback AegisResponse with Kotlin-computed
 * flags exactly matching the parsed report.
 *
 * Expected runtime: 5 × ~60–120 s = 5–10 minutes total on SD8G2.
 *
 * Requires sideloaded aegis_model.litertlm; otherwise skipped via assumeTrue.
 *
 * Startup-wait pattern follows LabReportPipelineFixtureTest (Phase 2): wait
 * for AegisApp.startup to leave Initializing, then read app.database
 * directly. UNLIKE the Phase 2 byte-identical test, synthesis additionally
 * requires EngineRouter.isReady == true — the assumeTrue guard skips the
 * test when no model is sideloaded so hermetic CI environments don't
 * hard-fail.
 *
 * To run:
 *   adb push ./downloads/model.litertlm /sdcard/Android/data/com.aegis.health/files/aegis_model.litertlm
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.aegis.health.reportreader.LabReportSynthesisFixtureTest
 */
@RunWith(AndroidJUnit4::class)
class LabReportSynthesisFixtureTest {

    private lateinit var app: AegisApp

    @Before
    fun setup() {
        app = InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .applicationContext as AegisApp
        PDFBoxResourceLoader.init(app)
        // Wait until startup settles. Synthesis requires Ready, not Failed —
        // assumeTrue skips when the model isn't sideloaded so CI / hermetic
        // environments don't hard-fail.
        runBlocking { app.startup.first { it !is StartupState.Initializing } }
        Assume.assumeTrue(
            "ReportReader synthesis smoke requires sideloaded aegis_model.litertlm + EngineRouter.isReady=true",
            EngineRouter.isReady,
        )
    }

    @Test
    fun labcorp_smoke() = vendorSmoke("labcorp")

    @Test
    fun quest_smoke() = vendorSmoke("quest")

    @Test
    fun mayo_smoke() = vendorSmoke("mayo")

    @Test
    fun hospital_lis_smoke() = vendorSmoke("hospital_lis")

    @Test
    fun urgent_care_smoke() = vendorSmoke("urgent_care")

    private fun vendorSmoke(vendor: String) {
        val db = app.database
        val pdfStream = LabReportFixtureLoader.pdfStream(vendor)
        val report: PreparsedReport = pdfStream.use { stream ->
            ReportReaderPipeline.parse(stream, db)
        }

        // Smoke is only meaningful when the fixture parses cleanly to OK.
        // Edge fixtures route to non-OK ReportEmptyState and never trigger
        // synthesis (per D-02). If a smoke fixture is non-OK, that's a
        // pipeline regression — fail loudly.
        assertEquals(
            "$vendor fixture must parse to report_status=OK for the smoke path",
            "OK",
            report.report_status.code,
        )
        assertTrue("$vendor fixture must contain rows", report.rows.isNotEmpty())

        val response = runBlocking {
            ToolDispatcher.runReportReaderFastPath(report)
        }

        // (a) Synthesis ran — explanation is non-blank AND distinct from the Phase 3 fallback.
        assertTrue(
            "$vendor explanation must be non-blank",
            response.explanation.isNotBlank(),
        )
        assertNotEquals(
            "$vendor explanation must not equal FIXED_EXPLANATION (would indicate synthesis fallback fired)",
            com.aegis.health.ui.reportreader.AegisResponseBuilder.FIXED_EXPLANATION,
            response.explanation,
        )

        // (b) Kotlin override fired — flags match parsed report.
        val expectedFlagged = report.rows.count { it.status != "IN_RANGE" }
        assertEquals(
            "$vendor flag count must equal Kotlin-computed flagged rows (SAFETY-01)",
            expectedFlagged,
            response.flags.size,
        )

        // (c) defer_to_professional is Kotlin-computed (D-03).
        assertEquals(
            "$vendor defer_to_professional must equal report.has_outside_range || has_unknown",
            report.has_outside_range || report.has_unknown,
            response.defer_to_professional,
        )

        // (d) confidence is the fixed 0.6 floor (D-03).
        assertEquals(
            "$vendor confidence must be the fixed 0.6 floor (D-03)",
            0.6, response.confidence, 0.001,
        )

        // (e) Every citation came from PreparsedReport.citations
        // (EXPLAIN-01 — MedlinePlus + Phase 3 D-08 citation backfill).
        val expectedSources = report.citations.map { it.label }.toSet()
        for (citation in response.citations) {
            assertTrue(
                "$vendor citation source '${citation.source}' must be from PreparsedReport.citations (got expected sources: $expectedSources)",
                citation.source in expectedSources,
            )
        }
    }
}
