package com.aegis.health.reportreader

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4.1 Wave 3 — Stage-level JVM unit tests for [DrLalPathLabsExtractor].
 *
 * Vendor decision per `04.1-2-01-NOTES.md`: **DrLal** (D-13 Apollo fallback NOT triggered).
 *
 * Covers (per 04.1-2-02-PLAN behaviors):
 *   - Test 1: fingerprintMatches accepts the 3 substrings recorded in NOTES.md:
 *     `dr lal pathlabs`, `lpl-national reference lab`, `dr lal path labs`.
 *   - Test 2: fingerprintMatches rejects each of the 5 existing vendor anchors
 *     plus Tata1mg anchors (anti-collision proven).
 *   - Test 3: extract() returns a Family-B ParsedRow with inequality range
 *     (e.g. `GFR Estimated 107 mL/min/1.73m2 >59`).
 *   - Test 4: extract() drops metadata lines (no false positives).
 *   - Test 5: numLiteral preserves int vs float per LM-3.
 *   - Additional: parenthetical-method skip (`(Modified Jaffe,Kinetic)`),
 *     panel-heading skip (`LIVER & KIDNEY PANEL, SERUM`), bilateral-range row.
 */
class DrLalPathLabsExtractorTest {

    @Test
    fun fingerprint_accepts_dr_lal_pathlabs_masthead() {
        val page1 = "Dr Lal PathLabs - Trusted Diagnostics\nMore header".lowercase()
        assertTrue(DrLalPathLabsExtractor.fingerprintMatches(page1))
    }

    @Test
    fun fingerprint_accepts_lpl_national_reference_lab() {
        val page1 = "Processed at: LPL-NATIONAL REFERENCE LAB, New Delhi".lowercase()
        assertTrue(DrLalPathLabsExtractor.fingerprintMatches(page1))
    }

    @Test
    fun fingerprint_accepts_dr_lal_path_labs_vintage_spacing() {
        // Per NOTES.md: kept as a low-confidence fallback for any vintage layout
        // that splits the brand into 3 words.
        val page1 = "Dr Lal Path Labs - Sample Report".lowercase()
        assertTrue(DrLalPathLabsExtractor.fingerprintMatches(page1))
    }

    /**
     * R-02 anti-collision proof: each of the 5 existing vendor anchors
     * + Tata1mg anchors must NOT match DrLalPathLabsExtractor's fingerprint.
     */
    @Test
    fun fingerprint_rejects_all_5_existing_vendor_anchors_and_tata1mg() {
        // 5 existing vendors
        assertFalse(
            "LabCorp anchor must not match DrLal fingerprint",
            DrLalPathLabsExtractor.fingerprintMatches("lipid panel\ncholesterol, total".lowercase()),
        )
        assertFalse(
            "Quest anchor must not match DrLal fingerprint",
            DrLalPathLabsExtractor.fingerprintMatches("comprehensive metabolic panel".lowercase()),
        )
        assertFalse(
            "Mayo 'hematology' anchor must not match DrLal fingerprint",
            DrLalPathLabsExtractor.fingerprintMatches("hematology\nresults".lowercase()),
        )
        assertFalse(
            "HospitalLis anchor must not match DrLal fingerprint",
            DrLalPathLabsExtractor.fingerprintMatches("lipid profile\nbiological ref. interval".lowercase()),
        )
        assertFalse(
            "UrgentCare 'hgb a1c' anchor must not match DrLal fingerprint",
            DrLalPathLabsExtractor.fingerprintMatches("hgb a1c\nresults".lowercase()),
        )
        // Tata1mg anchors
        assertFalse(
            "Tata1mg masthead anchor must not match DrLal fingerprint",
            DrLalPathLabsExtractor.fingerprintMatches("tata 1mg | labs".lowercase()),
        )
        assertFalse(
            "Tata1mg promo card anchor must not match DrLal fingerprint",
            DrLalPathLabsExtractor.fingerprintMatches("personal health smart report".lowercase()),
        )
    }

    @Test
    fun fingerprint_rejects_drlpl_per_notes_md_drop() {
        // Per 04.1-2-01-NOTES.md: A2 had 'drlpl' as a low-confidence candidate;
        // dropped because it was NOT empirically observed on page 1 of the
        // acquired sample. We assert the extractor does NOT match 'drlpl' alone,
        // mirroring the NOTES.md decision.
        val page1 = "drlpl-something-else".lowercase()
        assertFalse(
            "'drlpl' alone must NOT trigger DrLal fingerprint (per NOTES.md drop)",
            DrLalPathLabsExtractor.fingerprintMatches(page1),
        )
    }

    @Test
    fun extract_creatinine_bilateral_range_row() {
        val pagesText = listOf(
            "Dr Lal PathLabs\nCreatinine                       1.00   mg/dL          0.70 - 1.30\n",
        )
        val rows = DrLalPathLabsExtractor.extract(pagesText)
        assertTrue("Expected Creatinine row", rows.any { it.rawName == "Creatinine" })
        val cre = rows.first { it.rawName == "Creatinine" }
        assertEquals("mg/dL", cre.units)
        // Float fidelity per LM-3
        assertEquals(JsonPrimitive(1.00), cre.value)
        assertEquals(JsonPrimitive(0.70), cre.refLow)
        assertEquals(JsonPrimitive(1.30), cre.refHigh)
    }

    /**
     * NOTES.md: GFR row carries inequality range `>59` (single-sided low) +
     * unusual unit `mL/min/1.73m2` (contains `/` and digits). Both must be
     * captured by the extractor.
     */
    @Test
    fun extract_gfr_inequality_range_with_complex_unit() {
        val pagesText = listOf(
            "Dr Lal PathLabs\nGFR Estimated                    107    mL/min/1.73m2  >59\n",
        )
        val rows = DrLalPathLabsExtractor.extract(pagesText)
        assertTrue("Expected GFR Estimated row", rows.any { it.rawName == "GFR Estimated" })
        val gfr = rows.first { it.rawName == "GFR Estimated" }
        assertEquals("mL/min/1.73m2", gfr.units)
        assertEquals(JsonPrimitive(107L), gfr.value)
        // Inequality '>59' is a single-sided low: ref_low captured, ref_high null
        assertEquals(JsonPrimitive(59L), gfr.refLow)
        assertEquals(JsonNull, gfr.refHigh)
    }

    @Test
    fun extract_drops_metadata_lines() {
        // Page 1 of 3, MRN, date headers, etc. — none have a parseable row shape
        val pagesText = listOf("Page 1 of 3\nDate: 12/15/2024\nMRN: 78901234\n")
        val rows = DrLalPathLabsExtractor.extract(pagesText)
        assertTrue(
            "Metadata-only lines must produce zero ParsedRows (no false positives)",
            rows.isEmpty(),
        )
    }

    /**
     * NOTES.md: parenthetical-only method lines (e.g. `(Modified Jaffe,Kinetic)`)
     * MUST be skipped — they print BENEATH test names and are not analyte rows.
     * Asserted by ensuring such a line on its own does NOT extract a ParsedRow.
     */
    @Test
    fun extract_skips_parenthetical_only_method_lines() {
        val pagesText = listOf(
            "Dr Lal PathLabs\n(Modified Jaffe,Kinetic)\n(Urease UV)\n(IFCC without P5P)\n",
        )
        val rows = DrLalPathLabsExtractor.extract(pagesText)
        assertTrue(
            "Parenthetical-method-only lines must not produce ParsedRows",
            rows.isEmpty(),
        )
    }

    /**
     * NOTES.md: panel-heading lines (e.g. `LIVER & KIDNEY PANEL, SERUM`) appear
     * INSIDE the boxed table region but MUST be skipped — they are all-caps
     * phrases without numbers, so the row regex will naturally not match
     * (no value/unit/range). Asserted defensively.
     */
    @Test
    fun extract_skips_panel_heading_lines() {
        val pagesText = listOf(
            "Dr Lal PathLabs\nLIVER & KIDNEY PANEL, SERUM\nCBC, WHOLE BLOOD EDTA\n",
        )
        val rows = DrLalPathLabsExtractor.extract(pagesText)
        assertTrue(
            "Panel-heading lines must not produce ParsedRows",
            rows.isEmpty(),
        )
    }

    @Test
    fun extract_preserves_int_vs_float_per_lm3() {
        val pagesText = listOf(
            "Dr Lal PathLabs\n" +
                "Urea                             40.00  mg/dL          13.00 - 43.00\n" +
                "Urea Nitrogen Blood              18.68  mg/dL          6.00 - 20.00\n" +
                "GGTP                             50.0   U/L            0 - 73\n",
        )
        val rows = DrLalPathLabsExtractor.extract(pagesText)

        val urea = rows.first { it.rawName == "Urea" }
        // "40.00" -> float (contains '.')
        assertEquals(JsonPrimitive(40.00), urea.value)
        assertTrue(
            "Float JsonPrimitive renders as decimal in toString()",
            urea.value.toString().contains("."),
        )

        val ggtp = rows.first { it.rawName == "GGTP" }
        // "0" and "73" -> int (no '.')
        assertEquals(JsonPrimitive(0L), ggtp.refLow)
        assertEquals(JsonPrimitive(73L), ggtp.refHigh)
        assertFalse(
            "Int JsonPrimitive must NOT render with a decimal point",
            ggtp.refLow.toString().contains("."),
        )
    }
}
