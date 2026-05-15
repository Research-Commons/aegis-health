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
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // ========================================================================
    // Phase 4.1 D-15 — in-place extension. The 5 @Test methods above remain
    // byte-identical (Phase 2 D-04 contract preserved); the 3 methods below add
    // the Wave 7 field-level + generic-smoke exit gate.
    //
    // Field-level rationale (D-11 limit): synthetic PDFs generated by
    // tools/parsers/synthesize_fixture.py are byte-deterministic on the
    // *generator* side but their decoded text-coordinate stream is sensitive
    // to pdfbox-android device-quirk decoding (T-PHASE-2-W5-04). Rather than
    // committing fragile byte-identical GT JSONs, Phase 4.1 asserts field-level
    // invariants (canonical_name set, status enum, defer_reason vocabulary)
    // per LM-3 + Phase 2 D-12 contract. The generic-smoke path asserts
    // vendorKey routing + GENERIC_FALLBACK status + per-row gate enforcement.
    //
    // Vendor key choice (per 04.1-2-01-NOTES.md): the Wave 2 named-vendor
    // decision was Dr Lal Path Labs — NOT Apollo. D-13 Apollo fallback was
    // not triggered. The drlalpathlabs_field_level test name reflects that.
    // ========================================================================

    /**
     * Phase 2 D-12 nine-entry defer_reason vocabulary. Any EvaluatedRow with
     * status="unknown" must carry a defer_reason in this set; status != unknown
     * rows must carry defer_reason=null. See EXTRACTION-SPEC.md.
     */
    private val D12_VOCABULARY = setOf(
        "missing_units", "mismatched_units", "non_numeric_result", "range_unavailable",
        "kb_no_pediatric", "kb_no_pregnancy",
        "auto_defer:tumor_marker", "auto_defer:genetic", "auto_defer:pathology",
    )

    @Test
    fun tata1mg_field_level() = vendorFieldLevel("tata1mg")

    @Test
    fun drlalpathlabs_field_level() = vendorFieldLevel("drlalpathlabs")

    @Test
    fun generic_acme_smoke() = vendorGenericSmoke("generic")

    /**
     * Named-vendor field-level invariants (Tata 1mg / Dr Lal): the page-1
     * fingerprint MUST route to a named extractor (report_status.code == "OK",
     * NOT "GENERIC_FALLBACK"), rows are non-empty, each row has a non-blank
     * canonical_name + a status in {IN_RANGE, BORDERLINE, OUTSIDE_RANGE,
     * unknown}, and unknown rows carry a defer_reason in the Phase 2 D-12
     * vocabulary.
     */
    private fun vendorFieldLevel(vendor: String) {
        val db = app.database
        val pdfStream = LabReportFixtureLoader.pdfStream(vendor)
        val report: PreparsedReport = pdfStream.use { stream ->
            ReportReaderPipeline.parse(stream, db)
        }
        assertEquals(
            "Vendor=$vendor expected report_status.code=OK (named-extractor route)",
            "OK",
            report.report_status.code,
        )
        assertTrue(
            "Vendor=$vendor expected non-empty rows; got ${report.rows.size}",
            report.rows.isNotEmpty(),
        )
        for (row in report.rows) {
            assertTrue(
                "Vendor=$vendor canonical_name blank for row raw_name=${row.raw_name}",
                row.canonical_name.isNotBlank(),
            )
            assertTrue(
                "Vendor=$vendor unexpected status=${row.status} for row raw_name=${row.raw_name}",
                row.status in setOf("IN_RANGE", "BORDERLINE", "OUTSIDE_RANGE", "unknown"),
            )
            if (row.status == "unknown") {
                assertTrue(
                    "Vendor=$vendor defer_reason=${row.defer_reason} not in D12 vocab " +
                        "for row raw_name=${row.raw_name}",
                    row.defer_reason != null && row.defer_reason in D12_VOCABULARY,
                )
            }
        }
    }

    /**
     * Generic-fallback smoke (Acme): the page-1 fingerprint MUST miss all
     * named extractors and route to GenericExtractor, surfacing
     * report_status.code == "GENERIC_FALLBACK" with rows.size >= 3, and each
     * surviving row MUST pass the per-row gate (units OR a non-null
     * ref_low / ref_high). The aggregate-floor gate at <3 rows would otherwise
     * route to UNKNOWN_VENDOR per ReportReaderPipeline.selectStatusCodeAndMessage
     * precedence cascade (D-02).
     */
    private fun vendorGenericSmoke(vendor: String) {
        val db = app.database
        val pdfStream = LabReportFixtureLoader.pdfStream(vendor)
        val report: PreparsedReport = pdfStream.use { stream ->
            ReportReaderPipeline.parse(stream, db)
        }
        assertEquals(
            "Vendor=$vendor expected report_status.code=GENERIC_FALLBACK " +
                "(catch-all route); got code=${report.report_status.code}",
            "GENERIC_FALLBACK",
            report.report_status.code,
        )
        assertTrue(
            "Vendor=$vendor Acme rows.size=${report.rows.size} < 3 (aggregate-floor)",
            report.rows.size >= 3,
        )
        for (row in report.rows) {
            val hasUnits = row.units?.isNotBlank() == true
            val hasRange = row.ref_low != JsonNull || row.ref_high != JsonNull
            assertTrue(
                "Vendor=$vendor row raw_name=${row.raw_name} fails per-row gate " +
                    "(units AND range both blank/null)",
                hasUnits || hasRange,
            )
        }
    }
}
