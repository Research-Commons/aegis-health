package com.aegis.health.reportreader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aegis.health.AegisApp
import com.aegis.health.StartupState
import com.aegis.health.models.PreparsedReport
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 2 exit gate — ROADMAP §Phase 2 success criterion 1.
 *
 * For each of the 5 fixture PDFs:
 *   1. Load PDF + GT JSON from androidTest assets via LabReportFixtureLoader
 *   2. Run ReportReaderPipeline.parse against the real KBDatabase
 *   3. Encode the resulting PreparsedReport to JsonElement (preserves LM-3
 *      int/float fidelity via JsonElement-typed value/ref_low/ref_high)
 *   4. Canonicalize both Kotlin output AND the GT JSON via JsonCanonicalizer
 *   5. Assert byte-equality
 *
 * LM-1: PDFBoxResourceLoader.init runs in @Before with targetContext.applicationContext.
 * Phase 1 commit e099aaf empirically proved @BeforeClass + .context returns null on
 * S23 Ultra / AGP 10.x. Mirrors the production wire-up in AegisApp.onCreate.
 *
 * Startup-race fix (commit 2026-05-14): the test reuses AegisApp.database rather than
 * creating its own KBDatabase. AegisApp.onCreate launches an async ensureCopied() at
 * process boot; an independent test-side ensureCopied() racing with it on
 * Dispatchers.IO was clobbering the on-disk file via concurrent
 * FileOutputStream(O_CREAT|O_TRUNC) calls. We instead block once until the app's
 * startup coroutine has settled (Ready or Failed — both indicate ensureCopied
 * has returned), then read app.database directly. Failed is expected on this
 * device because no aegis_model.litertlm is sideloaded; the DB is opened before
 * EngineRouter.initialize is called, so the failure terminates EngineRouter
 * init but leaves the KB usable. The app owns the lifecycle, so no db.close()
 * here.
 *
 * 5 distinct @Test methods (rather than a parametrized loop) so failures are
 * actionable per-vendor: the test report identifies which fixture diverged
 * without requiring readers to count assertion order.
 */
@RunWith(AndroidJUnit4::class)
class LabReportPipelineFixtureTest {

    private lateinit var app: AegisApp

    @Before
    fun setup() {
        app = InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .applicationContext as AegisApp
        PDFBoxResourceLoader.init(app)
        // Wait for AegisApp's startup coroutine to finish ensureCopied + the
        // EngineRouter.initialize attempt. Both Ready and Failed are acceptable
        // — only Initializing means the KB copy is still in flight.
        runBlocking {
            app.startup.first { it !is StartupState.Initializing }
        }
    }

    @Test
    fun labcorp_byte_identical() = vendorByteIdentical("labcorp")

    @Test
    fun quest_byte_identical() = vendorByteIdentical("quest")

    @Test
    fun mayo_byte_identical() = vendorByteIdentical("mayo")

    @Test
    fun hospital_lis_byte_identical() = vendorByteIdentical("hospital_lis")

    @Test
    fun urgent_care_byte_identical() = vendorByteIdentical("urgent_care")

    private fun vendorByteIdentical(vendor: String) {
        val db = app.database

        val pdfStream = LabReportFixtureLoader.pdfStream(vendor)
        val actualReport: PreparsedReport = pdfStream.use { stream ->
            ReportReaderPipeline.parse(stream, db)
        }

        // Serialize Kotlin PreparsedReport -> JsonElement -> canonicalize.
        // encodeDefaults=true is required so optional fields with default
        // values (e.g. profile_used = Profile()) survive into the JSON.
        // ignoreUnknownKeys is irrelevant for encoding; explicitly disabled.
        val codec = Json {
            ignoreUnknownKeys = false
            encodeDefaults = true
            prettyPrint = false
        }
        val actualElement = codec.encodeToJsonElement(
            PreparsedReport.serializer(),
            actualReport,
        )
        val actualCanonical = JsonCanonicalizer.canonicalize(actualElement)

        val expectedGt = LabReportFixtureLoader.groundTruthJson(vendor)
        val expectedCanonical = JsonCanonicalizer.parseAndCanonicalize(expectedGt)

        assertEquals(
            "Vendor=$vendor failed byte-identical check. Kotlin output does not match GT.",
            expectedCanonical,
            actualCanonical,
        )
    }
}
