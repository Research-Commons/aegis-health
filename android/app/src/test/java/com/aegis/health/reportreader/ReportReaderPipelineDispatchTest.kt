package com.aegis.health.reportreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 4.1 Wave 3 — JVM unit coverage for the aggregate-floor +
 * GENERIC_FALLBACK status-code precedence cascade introduced by
 * 04.1-3-01 Task 3 (D-02 + D-05).
 *
 * Why a dispatch-test (not a full pipeline-test)?
 *
 * [ReportReaderPipeline.parse] takes an `InputStream` + `KBQueries`
 * and chains 5 stages (PDF extract → vendor dispatch → normalize →
 * range evaluate → assemble). End-to-end fixture coverage lives in
 * `androidTest/LabReportPipelineFixtureTest.kt` (Wave 5 plan adds the
 * Phase-4.1-specific fixture rows). This JVM test exercises the new
 * **decision predicate** in isolation via
 * [ReportReaderPipeline.selectStatusCodeAndMessage], which the
 * pipeline composes after Stage 3 (normalize) and Stage 4 (evaluate)
 * to decide between UNKNOWN_VENDOR / TOO_MANY_ANALYTES /
 * GENERIC_FALLBACK / OK without owning any PDF or KB plumbing.
 *
 * Covers the 8 behaviors enumerated in 04.1-3-01-PLAN Task 3:
 *  1. generic + 0 post-normalization rows  -> UNKNOWN_VENDOR (rows=[])
 *  2. generic + 2 post-normalization rows  -> UNKNOWN_VENDOR (rows=[]) -- aggregate-floor fires
 *  3. generic + 3 rows                     -> GENERIC_FALLBACK (rows populated)
 *  4. generic + 5 rows                     -> GENERIC_FALLBACK (rows populated)
 *  5. generic + 30 rows                    -> TOO_MANY_ANALYTES (precedence: >25 still defers)
 *  6. named vendor (labcorp) + 10 rows     -> OK (named vendors never route to GENERIC_FALLBACK)
 *  7. IMAGE_ONLY is upstream of this dispatch (Stage 1 short-circuits) -- documented; not asserted here.
 *  8. GENERIC_FALLBACK message copy matches Wave 4 banner contract.
 */
class ReportReaderPipelineDispatchTest {

    @Test
    fun generic_zero_rows_routes_to_unknown_vendor_empty_rows() {
        val decision = ReportReaderPipeline.selectStatusCodeAndMessage(
            vendorKey = "generic",
            normalizedRowCount = 0,
        )
        assertEquals("UNKNOWN_VENDOR", decision.statusCode)
        assertEquals("Lab vendor format not recognized.", decision.statusMessage)
        assertEquals(
            "UNKNOWN_VENDOR must empty rows (Phase 2 D-10 strict-empty invariant)",
            true,
            decision.dropRows,
        )
    }

    @Test
    fun generic_two_rows_below_aggregate_floor_routes_to_unknown_vendor() {
        // D-02 aggregate-floor: less than 3 rows post-normalization -> UNKNOWN_VENDOR.
        val decision = ReportReaderPipeline.selectStatusCodeAndMessage(
            vendorKey = "generic",
            normalizedRowCount = 2,
        )
        assertEquals("UNKNOWN_VENDOR", decision.statusCode)
        assertEquals(true, decision.dropRows)
    }

    @Test
    fun generic_three_rows_emits_generic_fallback_rows_populated() {
        // Threshold edge: exactly 3 rows post-normalization -> GENERIC_FALLBACK.
        val decision = ReportReaderPipeline.selectStatusCodeAndMessage(
            vendorKey = "generic",
            normalizedRowCount = 3,
        )
        assertEquals("GENERIC_FALLBACK", decision.statusCode)
        assertEquals(false, decision.dropRows)
    }

    @Test
    fun generic_five_rows_emits_generic_fallback_rows_populated() {
        val decision = ReportReaderPipeline.selectStatusCodeAndMessage(
            vendorKey = "generic",
            normalizedRowCount = 5,
        )
        assertEquals("GENERIC_FALLBACK", decision.statusCode)
        assertEquals(false, decision.dropRows)
        // Wave 4 banner copy contract: the human-facing message must include
        // "Lab vendor not recognized" so the banner-copy verification test
        // (Wave 4 Plan 04.1-4-01) finds it.
        val msg = decision.statusMessage ?: error("GENERIC_FALLBACK must carry a non-null message")
        assert(msg.contains("Lab vendor not recognized")) {
            "GENERIC_FALLBACK message must contain banner-copy substring; got: $msg"
        }
    }

    @Test
    fun generic_thirty_rows_above_too_many_analytes_threshold_takes_precedence() {
        // Precedence: post-normalization > ROW_COUNT_DEFER_THRESHOLD (25) ->
        // TOO_MANY_ANALYTES even for generic vendor.
        val decision = ReportReaderPipeline.selectStatusCodeAndMessage(
            vendorKey = "generic",
            normalizedRowCount = 30,
        )
        assertEquals("TOO_MANY_ANALYTES", decision.statusCode)
        assertEquals(true, decision.dropRows)
    }

    @Test
    fun named_vendor_ten_rows_emits_ok_unchanged_phase2_contract() {
        // Phase 2 byte-identical contract: named vendors continue to emit OK
        // when post-normalization rows in [1, ROW_COUNT_DEFER_THRESHOLD].
        // GENERIC_FALLBACK is exclusive to vendorKey == "generic".
        val decision = ReportReaderPipeline.selectStatusCodeAndMessage(
            vendorKey = "labcorp",
            normalizedRowCount = 10,
        )
        assertEquals("OK", decision.statusCode)
        assertNull(decision.statusMessage)
        assertEquals(false, decision.dropRows)
    }

    @Test
    fun named_vendor_thirty_rows_emits_too_many_analytes() {
        // Pre-existing Phase 2 path: named vendor + > 25 rows -> TOO_MANY_ANALYTES.
        val decision = ReportReaderPipeline.selectStatusCodeAndMessage(
            vendorKey = "labcorp",
            normalizedRowCount = 30,
        )
        assertEquals("TOO_MANY_ANALYTES", decision.statusCode)
        assertEquals(true, decision.dropRows)
    }

    @Test
    fun generic_fallback_message_matches_wave4_banner_contract() {
        val decision = ReportReaderPipeline.selectStatusCodeAndMessage(
            vendorKey = "generic",
            normalizedRowCount = 5,
        )
        assertEquals(
            "Lab vendor not recognized -- best-effort extraction; verify each row against your PDF.",
            decision.statusMessage,
        )
    }
}
