package com.aegis.health.reportreader

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4.1 Wave 3 — Stage-level JVM unit tests for [Tata1mgExtractor].
 *
 * Covers (per 04.1-2-02-PLAN behaviors):
 *   - Test 1-3: fingerprintMatches accepts the 3 empirical brand substrings recorded
 *     in 04.1-2-01-NOTES.md (`tata 1mg | labs`, `personal health smart report`,
 *     `tata 1mg app`) plus the 3 RESEARCH.md A1 candidates kept as low-confidence
 *     fallbacks (`tata 1mg`, `1mg labs`, `1mg health`).
 *   - Test 4: fingerprintMatches rejects each of the 5 existing vendor fingerprint
 *     anchors (anti-collision proven — R-02 ordering safety net).
 *   - Test 5: extract() returns a Family-B ParsedRow with raw_name + value + units
 *     + ref_low + ref_high for the canonical Tata 1mg hemoglobin row pattern.
 *   - Test 6: extract() drops a metadata line lacking a parseable row.
 *   - Test 7: numLiteral preserves int vs float per LM-3 / Phase 2 D-07.
 */
class Tata1mgExtractorTest {

    @Test
    fun fingerprint_accepts_tata_1mg_pipe_labs_masthead() {
        val page1 = "TATA 1mg | Labs - NABL accredited\nMore header text".lowercase()
        assertTrue(Tata1mgExtractor.fingerprintMatches(page1))
    }

    @Test
    fun fingerprint_accepts_personal_health_smart_report_title() {
        val page1 = "PERSONAL HEALTH SMART REPORT\nSome footer".lowercase()
        assertTrue(Tata1mgExtractor.fingerprintMatches(page1))
    }

    @Test
    fun fingerprint_accepts_tata_1mg_app_promo_card() {
        val page1 = "View detailed health insights, and trends on Tata 1mg app.".lowercase()
        assertTrue(Tata1mgExtractor.fingerprintMatches(page1))
    }

    @Test
    fun fingerprint_accepts_research_a1_low_confidence_fallback_substrings() {
        // RESEARCH.md A1 candidates kept as low-confidence fallbacks per 04.1-2-02-PLAN behaviors 1-3
        assertTrue(Tata1mgExtractor.fingerprintMatches("tata 1mg".lowercase()))
        assertTrue(Tata1mgExtractor.fingerprintMatches("1mg labs".lowercase()))
        assertTrue(Tata1mgExtractor.fingerprintMatches("1mg health".lowercase()))
    }

    /**
     * R-02 anti-collision proof: each of the 5 existing vendor anchors must NOT match
     * Tata1mgExtractor's fingerprint. With R-02 brand-tokens-first list ordering,
     * Tata1mgExtractor at slot 0 must claim ONLY Tata 1mg PDFs, never short-circuit
     * an existing-vendor PDF that would land at slots 2..6.
     */
    @Test
    fun fingerprint_rejects_all_5_existing_vendor_anchors() {
        // LabCorp anchor: "lipid panel" + "cholesterol, total"
        assertFalse(
            "LabCorp anchor must not match Tata1mg fingerprint",
            Tata1mgExtractor.fingerprintMatches("lipid panel\ncholesterol, total".lowercase()),
        )
        // Quest anchor: "comprehensive metabolic panel"
        assertFalse(
            "Quest anchor must not match Tata1mg fingerprint",
            Tata1mgExtractor.fingerprintMatches("comprehensive metabolic panel".lowercase()),
        )
        // Mayo anchor: "hematology" (the critical R-02 collision candidate)
        assertFalse(
            "Mayo 'hematology' anchor must not match Tata1mg fingerprint",
            Tata1mgExtractor.fingerprintMatches("hematology\nresults".lowercase()),
        )
        // HospitalLis anchor: "lipid profile" + "biological ref"
        assertFalse(
            "HospitalLis anchor must not match Tata1mg fingerprint",
            Tata1mgExtractor.fingerprintMatches("lipid profile\nbiological ref. interval".lowercase()),
        )
        // UrgentCare anchor: "hgb a1c" or "hemoglobin a1c" + "eag"
        assertFalse(
            "UrgentCare 'hgb a1c' anchor must not match Tata1mg fingerprint",
            Tata1mgExtractor.fingerprintMatches("hgb a1c\nresults".lowercase()),
        )
        assertFalse(
            "UrgentCare 'hemoglobin a1c + eag' anchor must not match Tata1mg fingerprint",
            Tata1mgExtractor.fingerprintMatches("hemoglobin a1c\neag".lowercase()),
        )
    }

    @Test
    fun extract_hemoglobin_family_b_row() {
        // Family B: name value units range
        val pagesText = listOf(
            "TATA 1mg | Labs\nHemoglobin   14.5   g/dL   13 - 17\n",
        )
        val rows = Tata1mgExtractor.extract(pagesText)
        assertTrue("Expected at least 1 row", rows.isNotEmpty())
        val hgb = rows.first { it.rawName == "Hemoglobin" }
        assertEquals("g/dL", hgb.units)
        // LM-3 numLiteral fidelity: "14.5" -> float, "13" -> int, "17" -> int
        assertEquals(JsonPrimitive(14.5), hgb.value)
        assertEquals(JsonPrimitive(13L), hgb.refLow)
        assertEquals(JsonPrimitive(17L), hgb.refHigh)
    }

    @Test
    fun extract_drops_metadata_line_lacking_row_shape() {
        val pagesText = listOf("Patient ID: 12345\nDate: 12/15/2024\nPage 1 of 3\n")
        val rows = Tata1mgExtractor.extract(pagesText)
        assertTrue(
            "Metadata-only lines must produce zero ParsedRows (no false positives)",
            rows.isEmpty(),
        )
    }

    @Test
    fun extract_preserves_int_vs_float_per_lm3() {
        // LM-3 per Phase 2 D-07: numLiteral preserves int vs float at the JsonPrimitive boundary.
        // Use a row with integer value+range and a row with float value to exercise both paths.
        val pagesText = listOf(
            "TATA 1mg | Labs\n" +
                "Hemoglobin   14.5   g/dL   13 - 17\n" +
                "Platelet Count   165   10^3/uL   150 - 410\n",
        )
        val rows = Tata1mgExtractor.extract(pagesText)
        val hgb = rows.first { it.rawName == "Hemoglobin" }
        val plt = rows.first { it.rawName == "Platelet Count" }

        // Float fidelity
        assertEquals(JsonPrimitive(14.5), hgb.value)
        assertTrue(
            "Float JsonPrimitive renders as decimal in toString()",
            hgb.value.toString().contains("."),
        )
        // Int fidelity
        assertEquals(JsonPrimitive(165L), plt.value)
        assertEquals(JsonPrimitive(150L), plt.refLow)
        assertEquals(JsonPrimitive(410L), plt.refHigh)
        assertFalse(
            "Int JsonPrimitive must NOT render with a decimal point (widening to Double would break LM-3)",
            plt.value.toString().contains("."),
        )
    }

    /**
     * NOTES.md "ÂµL" mojibake handling: extracted PDF text contains
     * Â<U+00B5>L instead of µL. The unit token regex must accept both.
     */
    @Test
    fun extract_handles_microliter_mojibake() {
        val pagesText = listOf(
            "TATA 1mg | Labs\nTotal Leucocyte Count   5.16   10^3/ÂµL   4 - 10\n",
        )
        val rows = Tata1mgExtractor.extract(pagesText)
        assertTrue(
            "Expected Total Leucocyte Count row despite ÂµL mojibake in unit token",
            rows.any { it.rawName == "Total Leucocyte Count" },
        )
    }
}
