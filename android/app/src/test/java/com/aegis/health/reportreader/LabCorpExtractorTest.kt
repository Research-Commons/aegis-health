package com.aegis.health.reportreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage-level JVM unit tests for [LabCorpExtractor].
 *
 * Covers:
 *   - Fingerprint match for the LabCorp lipid panel header
 *   - Fingerprint rejection for other vendors' headers
 *   - Single-row extraction with canned page text (CHOLESTEROL, TOTAL)
 *   - F-10 / EXTRACT-02 remediation: multi-page header propagation
 *     (header on page 1, row content on page 2 -- the extractor must
 *     joinToString("\n") across pages so the regex matches across the
 *     page boundary).
 */
class LabCorpExtractorTest {

    @Test
    fun fingerprint_matches_labcorp_lipid_panel() {
        val page1 = "LIPID PANEL\nCHOLESTEROL, TOTAL\nMore header text".lowercase()
        assertTrue(LabCorpExtractor.fingerprintMatches(page1))
    }

    @Test
    fun fingerprint_rejects_quest_cmp() {
        val page1 = "comprehensive metabolic panel\nother header".lowercase()
        assertTrue(
            "Quest header must not match LabCorp fingerprint",
            !LabCorpExtractor.fingerprintMatches(page1),
        )
    }

    @Test
    fun extract_cholesterol_total_row() {
        val text = listOf("LIPID PANEL\nCHOLESTEROL, TOTAL 151 125-200 mg/dL EN\nrest")
        val rows = LabCorpExtractor.extract(text)
        assertTrue("Expected at least 1 row", rows.isNotEmpty())
        val chol = rows.first { it.rawName.startsWith("CHOLESTEROL") }
        assertEquals("CHOLESTEROL, TOTAL", chol.rawName)
        // LM-3 numLiteral preserves int for "151"
        assertEquals("151", chol.value.toString())
        assertEquals("125", chol.refLow.toString())
        assertEquals("200", chol.refHigh.toString())
        assertEquals("mg/dL", chol.units)
    }

    /**
     * F-10 / EXTRACT-02 remediation: header on page 1, row content on page 2.
     * The extractor must joinToString("\n") across all pages so the regex
     * matches even when value text spans the page boundary.
     */
    @Test
    fun extract_handles_multi_page_header_propagation() {
        val pagesText = listOf(
            // page 1: header + name only
            "LIPID PANEL\nCHOLESTEROL, TOTAL",
            // page 2: values + a second row to exercise multi-row spanning
            "151 125-200 mg/dL EN\nHDL CHOLESTEROL 58 > OR = 46 mg/dL EN",
        )
        val rows = LabCorpExtractor.extract(pagesText)
        assertTrue(
            "Expected at least 1 row across page boundary (CHOLESTEROL, TOTAL)",
            rows.any { it.rawName == "CHOLESTEROL, TOTAL" },
        )
    }

    @Test
    fun extract_returns_empty_when_no_rows_match() {
        val pagesText = listOf("Header without any analyte rows")
        val rows = LabCorpExtractor.extract(pagesText)
        assertTrue("no analyte rows should extract", rows.isEmpty())
    }
}
