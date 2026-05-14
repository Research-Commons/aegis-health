package com.aegis.health.ui.reportreader

import com.aegis.health.models.EvaluatedRow
import com.aegis.health.models.LabCitation
import com.aegis.health.models.PreparsedReport
import com.aegis.health.models.ReportStatus
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3 D-08 contract tests for [AegisResponseBuilder]. JVM-only — no
 * Android/Compose dependencies, so this runs fast on every commit via
 * :app:testDebugUnitTest.
 *
 * Coverage matrix:
 *   1. build() — empty / OUTSIDE_RANGE / BORDERLINE / unknown row severity
 *      mapping; defer_to_professional resolution; FIXED_EXPLANATION constant.
 *   2. buildForRow() — single-row Flag isolation; defer_to_professional still
 *      reflects whole-report shape.
 *   3. buildForStatus() — non-OK path always defers; statusMessage flows into
 *      Flag.description; null statusMessage falls back to a description
 *      containing the statusCode token.
 *   4. LabCitation → Citation mapping per D-08 row 3 (source=label, text=url).
 *   5. FIXED_EXPLANATION literal — Phase 4 string-swap-target invariant.
 */
class AegisResponseBuilderTest {

    private fun row(
        name: String = "Test",
        status: String,
        value: Double? = null,
        units: String? = "mg/dL",
        deferReason: String? = null,
    ) = EvaluatedRow(
        canonical_name = name,
        raw_name = name,
        value = if (value != null) JsonPrimitive(value) else JsonNull,
        units = units,
        ref_low = JsonNull,
        ref_high = JsonNull,
        ref_source = "PDF",
        status = status,
        definition = null,
        definition_citation = null,
        defer_reason = deferReason,
    )

    @Test
    fun build_emptyReport_yields_zero_flags_no_defer() {
        val report = PreparsedReport(
            rows = emptyList(),
            has_outside_range = false,
            has_unknown = false,
        )
        val resp = AegisResponseBuilder.build(report)
        assertEquals(0, resp.flags.size)
        assertFalse(resp.defer_to_professional)
        assertEquals(AegisResponseBuilder.FIXED_EXPLANATION, resp.explanation)
        assertEquals(0.0, resp.confidence, 0.0)
    }

    @Test
    fun build_outsideRangeRow_produces_severity_4_flag() {
        val r = row(name = "LDL", status = "OUTSIDE_RANGE", value = 200.0, units = "mg/dL")
        val report = PreparsedReport(
            rows = listOf(r),
            has_outside_range = true,
            has_unknown = false,
        )
        val resp = AegisResponseBuilder.build(report)
        assertEquals(1, resp.flags.size)
        assertEquals(4, resp.flags[0].severity)
        // Description carries name + "outside printed range" tail.
        val desc = resp.flags[0].description
        assertTrue("description contains canonical_name", desc.contains("LDL"))
        assertTrue("description contains 'outside'", desc.contains("outside"))
    }

    @Test
    fun build_unknownRow_produces_severity_2_flag_with_defer_reason_copy() {
        val r = row(
            name = "AFP",
            status = "unknown",
            deferReason = "auto_defer:tumor_marker",
        )
        val report = PreparsedReport(
            rows = listOf(r),
            has_outside_range = false,
            has_unknown = true,
        )
        val resp = AegisResponseBuilder.build(report)
        assertEquals(1, resp.flags.size)
        assertEquals(2, resp.flags[0].severity)
        // Description carries canonical_name + DeferReasonCopy.lookup(defer_reason).
        val desc = resp.flags[0].description
        val expectedReason = DeferReasonCopy.lookup("auto_defer:tumor_marker")
        assertTrue("description contains canonical_name", desc.contains("AFP"))
        assertTrue("description contains DeferReasonCopy text", desc.contains(expectedReason))
    }

    @Test
    fun build_borderlineRow_produces_severity_3_flag() {
        val r = row(name = "Glucose", status = "BORDERLINE", value = 99.0)
        val report = PreparsedReport(
            rows = listOf(r),
            has_outside_range = false,
            has_unknown = false,
        )
        val resp = AegisResponseBuilder.build(report)
        assertEquals(1, resp.flags.size)
        assertEquals(3, resp.flags[0].severity)
    }

    @Test
    fun build_setsDeferToProfessional_when_outside_or_unknown_present() {
        val outsideReport = PreparsedReport(
            rows = listOf(row(status = "OUTSIDE_RANGE")),
            has_outside_range = true,
            has_unknown = false,
        )
        assertTrue(AegisResponseBuilder.build(outsideReport).defer_to_professional)

        val unknownReport = PreparsedReport(
            rows = listOf(row(status = "unknown", deferReason = "missing_units")),
            has_outside_range = false,
            has_unknown = true,
        )
        assertTrue(AegisResponseBuilder.build(unknownReport).defer_to_professional)

        val cleanReport = PreparsedReport(
            rows = listOf(row(status = "IN_RANGE")),
            has_outside_range = false,
            has_unknown = false,
        )
        assertFalse(AegisResponseBuilder.build(cleanReport).defer_to_professional)
    }

    @Test
    fun buildForRow_produces_exactly_one_flag() {
        val r1 = row(name = "LDL", status = "OUTSIDE_RANGE", value = 200.0)
        val r2 = row(name = "HDL", status = "OUTSIDE_RANGE", value = 30.0)
        val report = PreparsedReport(
            rows = listOf(r1, r2),
            has_outside_range = true,
            has_unknown = false,
        )
        val resp = AegisResponseBuilder.buildForRow(report, r1)
        assertEquals(1, resp.flags.size)
        assertTrue(resp.flags[0].description.contains("LDL"))
        assertFalse(
            "HDL must NOT appear in a single-row response",
            resp.flags[0].description.contains("HDL"),
        )
        // defer_to_professional still reflects whole-report shape.
        assertTrue(resp.defer_to_professional)
    }

    @Test
    fun buildForStatus_alwaysDefers_and_carries_statusMessage_in_flag_description() {
        val report = PreparsedReport(
            rows = emptyList(),
            report_status = ReportStatus(
                code = "IMAGE_ONLY",
                message = "This appears to be a scanned image.",
            ),
        )
        val resp = AegisResponseBuilder.buildForStatus(
            report = report,
            statusCode = "IMAGE_ONLY",
            statusMessage = "This appears to be a scanned image.",
        )
        assertEquals(1, resp.flags.size)
        assertTrue(resp.defer_to_professional)
        assertEquals("This appears to be a scanned image.", resp.flags[0].description)
        assertEquals(AegisResponseBuilder.FIXED_EXPLANATION, resp.explanation)
    }

    @Test
    fun buildForStatus_nullMessage_falls_back_to_generic_caption() {
        val report = PreparsedReport(rows = emptyList())
        val resp = AegisResponseBuilder.buildForStatus(
            report = report,
            statusCode = "UNKNOWN_VENDOR",
            statusMessage = null,
        )
        // Fallback wording contains the status code token per builder impl.
        assertTrue(resp.flags[0].description.contains("UNKNOWN_VENDOR"))
        assertTrue(resp.defer_to_professional)
    }

    @Test
    fun allEntryPoints_use_FIXED_EXPLANATION() {
        val report = PreparsedReport(rows = listOf(row(status = "OUTSIDE_RANGE")))
        assertEquals(
            AegisResponseBuilder.FIXED_EXPLANATION,
            AegisResponseBuilder.build(report).explanation,
        )
        assertEquals(
            AegisResponseBuilder.FIXED_EXPLANATION,
            AegisResponseBuilder.buildForRow(report, report.rows[0]).explanation,
        )
        assertEquals(
            AegisResponseBuilder.FIXED_EXPLANATION,
            AegisResponseBuilder.buildForStatus(report, "IMAGE_ONLY", null).explanation,
        )
        // Phase 4 string-swap-target invariant — the literal must not drift.
        assertEquals(
            "Bring this to your clinician to discuss any flagged values.",
            AegisResponseBuilder.FIXED_EXPLANATION,
        )
    }

    @Test
    fun labCitation_maps_to_citation_with_source_label_text_url() {
        val report = PreparsedReport(
            rows = listOf(row(status = "OUTSIDE_RANGE")),
            citations = listOf(
                LabCitation(label = "MedlinePlus: LDL", url = "https://medlineplus.gov/ldl"),
            ),
        )
        val resp = AegisResponseBuilder.build(report)
        assertEquals(1, resp.citations.size)
        assertEquals("MedlinePlus: LDL", resp.citations[0].source)
        assertEquals("https://medlineplus.gov/ldl", resp.citations[0].text)
    }
}
