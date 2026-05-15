package com.aegis.health.inference

import com.aegis.health.models.AegisResponse
import com.aegis.health.models.EvaluatedRow
import com.aegis.health.models.LabCitation
import com.aegis.health.models.PreparsedReport
import com.aegis.health.models.ReportStatus
import com.aegis.health.ui.reportreader.AegisResponseBuilder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4.1 D-07 contract tests for `ToolDispatcher.enforceReportReaderContract`.
 *
 * Covers the new GENERIC_FALLBACK sub-clause that extends Phase 4 D-03:
 *   - When `PreparsedReport.report_status.code == "GENERIC_FALLBACK"`:
 *       defer_to_professional = true   (unconditional — overrides has_outside_range || has_unknown)
 *       confidence              = 0.4  (lowered from the 0.6 OK-path floor)
 *   - All other Phase 4 D-03 overrides (Kotlin-emitted flags from rows, citation
 *     backfill from PreparsedReport, D-04 explanation sanitization against
 *     SafetyBoundaryPhrases) continue to apply unchanged.
 *
 * Test names embed the literal numeric values (0.4 / 0.6) so a future refactor
 * that drifts the constants without touching the GENERIC_FALLBACK path will
 * fail by name-mismatch as well as by assertion — defense in depth per the
 * plan's Pitfall 7.
 *
 * JVM-only — no Android/Compose dependencies. `enforceReportReaderContract` is
 * `internal` (same module visibility) so it can be invoked directly here
 * without going through the full mode-dispatch path.
 */
class ToolDispatcherTest {

    // ── Test fixtures ───────────────────────────────────────────────────

    private fun row(
        name: String = "Hemoglobin",
        status: String = "IN_RANGE",
        value: Double? = 14.0,
        units: String? = "g/dL",
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

    private fun report(
        statusCode: String,
        hasOutsideRange: Boolean = false,
        hasUnknown: Boolean = false,
        rows: List<EvaluatedRow> = listOf(row()),
        citations: List<LabCitation> = emptyList(),
    ) = PreparsedReport(
        rows = rows,
        has_outside_range = hasOutsideRange,
        has_unknown = hasUnknown,
        citations = citations,
        report_status = ReportStatus(code = statusCode),
    )

    private fun modelResponse(
        confidence: Double = 0.9,
        defer: Boolean = false,
        explanation: String = "Three values are outside the printed range; bring this to your clinician.",
    ) = AegisResponse(
        confidence = confidence,
        defer_to_professional = defer,
        flags = emptyList(),
        citations = emptyList(),
        explanation = explanation,
    )

    // ── D-07: GENERIC_FALLBACK overrides ────────────────────────────────

    @Test
    fun generic_fallback_input_forces_defer_to_true() {
        // Model claims defer=false, AND the underlying report has no outside/unknown
        // rows — under Phase 4 D-03 alone this would yield defer=false. The D-07
        // sub-clause must override to defer=true unconditionally because the
        // extraction provenance is uncertain on the catch-all path.
        val resp = ToolDispatcher.enforceReportReaderContract(
            response = modelResponse(defer = false),
            report = report(statusCode = "GENERIC_FALLBACK"),
            span = null,
        )
        assertTrue(
            "GENERIC_FALLBACK must force defer_to_professional = true (D-07)",
            resp.defer_to_professional,
        )
    }

    @Test
    fun generic_fallback_input_lowers_confidence_to_0_4() {
        // Model claims confidence=0.9; D-07 must lower to 0.4.
        val resp = ToolDispatcher.enforceReportReaderContract(
            response = modelResponse(confidence = 0.9),
            report = report(statusCode = "GENERIC_FALLBACK"),
            span = null,
        )
        assertEquals(
            "GENERIC_FALLBACK must lower confidence to 0.4 (D-07)",
            0.4, resp.confidence, 0.0,
        )
    }

    @Test
    fun generic_fallback_with_outside_range_still_defers_and_confidence_0_4() {
        // has_outside_range=true would also defer under Phase 4 D-03, but the
        // confidence path must still be 0.4 (D-07 overrides the 0.6 floor)
        // rather than the 0.6 fixed value used on the OK path.
        val resp = ToolDispatcher.enforceReportReaderContract(
            response = modelResponse(defer = true, confidence = 0.9),
            report = report(
                statusCode = "GENERIC_FALLBACK",
                hasOutsideRange = true,
                rows = listOf(row(status = "OUTSIDE_RANGE", value = 200.0)),
            ),
            span = null,
        )
        assertTrue(resp.defer_to_professional)
        assertEquals(
            "GENERIC_FALLBACK with outside-range row must still pin confidence to 0.4",
            0.4, resp.confidence, 0.0,
        )
    }

    // ── Phase 4 D-03 regression baseline (OK reports unaffected) ────────

    @Test
    fun ok_input_keeps_confidence_0_6_phase4_d03_regression() {
        // OK report with no outside/unknown rows: Phase 4 D-03 baseline says
        // confidence=0.6 + defer=false. The new D-07 sub-clause must NOT fire.
        val resp = ToolDispatcher.enforceReportReaderContract(
            response = modelResponse(defer = false, confidence = 0.9),
            report = report(statusCode = "OK"),
            span = null,
        )
        assertEquals(
            "OK path must preserve Phase 4 D-03 confidence floor of 0.6",
            0.6, resp.confidence, 0.0,
        )
        assertFalse(
            "OK path with no outside/unknown rows must not defer",
            resp.defer_to_professional,
        )
    }

    @Test
    fun ok_with_outside_range_defers_and_confidence_0_6_phase4_d03_regression() {
        // OK report with has_outside_range=true: defer=true per D-03, but
        // confidence is still the 0.6 OK floor (not 0.4 — D-07 must not leak).
        val resp = ToolDispatcher.enforceReportReaderContract(
            response = modelResponse(defer = false, confidence = 0.9),
            report = report(
                statusCode = "OK",
                hasOutsideRange = true,
                rows = listOf(row(status = "OUTSIDE_RANGE", value = 200.0)),
            ),
            span = null,
        )
        assertTrue(
            "OK path with has_outside_range must defer per Phase 4 D-03",
            resp.defer_to_professional,
        )
        assertEquals(
            "OK path with has_outside_range must keep confidence at the 0.6 floor",
            0.6, resp.confidence, 0.0,
        )
    }

    @Test
    fun ok_with_unknown_row_defers_and_confidence_0_6_phase4_d03_regression() {
        // OK report with has_unknown=true: defer=true per D-03; confidence still 0.6.
        val resp = ToolDispatcher.enforceReportReaderContract(
            response = modelResponse(defer = false, confidence = 0.9),
            report = report(
                statusCode = "OK",
                hasUnknown = true,
                rows = listOf(row(status = "unknown", value = null, deferReason = "missing_range")),
            ),
            span = null,
        )
        assertTrue(
            "OK path with has_unknown must defer per Phase 4 D-03",
            resp.defer_to_professional,
        )
        assertEquals(0.6, resp.confidence, 0.0)
    }

    // ── Phase 4 D-04 sanitization still applies on GENERIC_FALLBACK ─────

    @Test
    fun generic_fallback_d04_sanitization_still_applies() {
        // Model emits a two-leg banned phrase (diagnostic verb + disease noun
        // from SafetyBoundaryPhrases). The D-04 cascade must reject the prose
        // and substitute the FIXED_EXPLANATION envelope EVEN ON the
        // GENERIC_FALLBACK path — sanitization is orthogonal to D-07.
        val bannedExplanation = "Your LDL indicates diabetes."
        val resp = ToolDispatcher.enforceReportReaderContract(
            response = modelResponse(explanation = bannedExplanation),
            report = report(statusCode = "GENERIC_FALLBACK"),
            span = null,
        )
        assertFalse(
            "D-04 sanitization must strip the banned phrase on GENERIC_FALLBACK output",
            resp.explanation.contains("indicates diabetes"),
        )
        assertEquals(
            "D-04 reject must substitute FIXED_EXPLANATION",
            AegisResponseBuilder.FIXED_EXPLANATION, resp.explanation,
        )
        // The D-07 overrides still hold on the sanitized envelope.
        assertTrue(resp.defer_to_professional)
        assertEquals(0.4, resp.confidence, 0.0)
    }

    // ── Phase 4 D-03 flag re-emission still applies on GENERIC_FALLBACK ─

    @Test
    fun generic_fallback_d03_flag_reemission_discards_model_flags() {
        // Model emits a fabricated flag list. Phase 4 D-03 re-emits flags from
        // PreparsedReport.rows and DISCARDS the model's flags[] entirely. That
        // override must continue to apply on the GENERIC_FALLBACK path.
        val fabricated = com.aegis.health.models.Flag(
            severity = 5,
            description = "BOGUS fabricated flag",
            citation = "fake-citation",
        )
        val resp = ToolDispatcher.enforceReportReaderContract(
            response = modelResponse().copy(flags = listOf(fabricated)),
            report = report(
                statusCode = "GENERIC_FALLBACK",
                hasOutsideRange = true,
                rows = listOf(row(name = "LDL", status = "OUTSIDE_RANGE", value = 200.0)),
            ),
            span = null,
        )
        assertFalse(
            "Phase 4 D-03 must drop the model's fabricated flag on GENERIC_FALLBACK output",
            resp.flags.any { it.description.contains("BOGUS") },
        )
        // The Kotlin-emitted flag for the OUTSIDE_RANGE row must be present.
        assertTrue(
            "Kotlin-emitted flag for the OUTSIDE_RANGE row must surface",
            resp.flags.any { it.description.contains("LDL") },
        )
    }

    // ── Phase 4 D-03 citation backfill still applies on GENERIC_FALLBACK ─

    @Test
    fun generic_fallback_d03_citation_backfill_from_preparsed_report() {
        // Model emits empty citations[]. Phase 4 D-03 backfills citations from
        // PreparsedReport.citations regardless of report_status.code. The
        // override must continue to apply on the GENERIC_FALLBACK path.
        val labCitation = LabCitation(label = "MedlinePlus", url = "https://medlineplus.gov/labtests")
        val resp = ToolDispatcher.enforceReportReaderContract(
            response = modelResponse(),
            report = report(
                statusCode = "GENERIC_FALLBACK",
                citations = listOf(labCitation),
            ),
            span = null,
        )
        assertEquals(1, resp.citations.size)
        assertEquals("MedlinePlus", resp.citations[0].source)
        assertEquals("https://medlineplus.gov/labtests", resp.citations[0].text)
    }

    // ── D-07 idempotence ────────────────────────────────────────────────

    @Test
    fun generic_fallback_override_is_idempotent() {
        // Applying the contract twice must produce identical output. No
        // accumulator state, no append-only mutation. This guards against
        // accidental state leakage on the singleton `object ToolDispatcher`.
        val once = ToolDispatcher.enforceReportReaderContract(
            response = modelResponse(),
            report = report(statusCode = "GENERIC_FALLBACK"),
            span = null,
        )
        val twice = ToolDispatcher.enforceReportReaderContract(
            response = once,
            report = report(statusCode = "GENERIC_FALLBACK"),
            span = null,
        )
        assertEquals(once.confidence, twice.confidence, 0.0)
        assertEquals(once.defer_to_professional, twice.defer_to_professional)
        assertEquals(once.explanation, twice.explanation)
        assertEquals(once.flags, twice.flags)
        assertEquals(once.citations, twice.citations)
    }
}
