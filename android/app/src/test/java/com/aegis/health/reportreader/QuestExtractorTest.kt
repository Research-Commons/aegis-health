package com.aegis.health.reportreader

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage-level JVM unit tests for [QuestExtractor] (LM-W watermark tolerance).
 *
 * The real Quest fixture has stray watermark single-letter tokens
 * (`M E S A L P C ...` diagonal) interspersed into row lines, plus
 * single-letter watermark intrusions glued onto values like "ALBUMIN 4.3A
 * 3.6-5.1 g/dL". The regexes in [QuestExtractor] tolerate these via
 * `[A-Z]?` after the value and `[A-Z]*\s*` between value and flag tokens.
 *
 * Tests intentionally exercise the watermark-tolerance paths -- not just
 * the clean baseline -- because regressing the tolerance would silently
 * drop rows on the real fixture.
 */
class QuestExtractorTest {

    @Test
    fun fingerprint_matches_quest_cmp() {
        val page1 = "Quest Diagnostics - Comprehensive Metabolic Panel\nResults".lowercase()
        assertTrue(QuestExtractor.fingerprintMatches(page1))
    }

    @Test
    fun fingerprint_rejects_labcorp_lipid_panel() {
        val page1 = "lipid panel\ncholesterol, total".lowercase()
        assertTrue(
            "LabCorp header must not match Quest fingerprint",
            !QuestExtractor.fingerprintMatches(page1),
        )
    }

    @Test
    fun extract_clean_glucose_row_baseline() {
        val text = listOf("comprehensive metabolic panel\nGLUCOSE 99 65-99 mg/dL EN")
        val rows = QuestExtractor.extract(text)
        assertTrue("Expected glucose to extract from clean row", rows.isNotEmpty())
        val glucose = rows.first { it.rawName == "GLUCOSE" }
        assertTrue("Expected GLUCOSE row", glucose.rawName == "GLUCOSE")
    }

    /**
     * LM-W: watermark letter glued onto value -- "ALBUMIN 4.3A 3.6-5.1 g/dL".
     * The regex `\bALBUMIN\s+($NUM)[A-Z]?\s+($NUM)-($NUM)\s+(g/dL)` tolerates
     * the stray `A`.
     */
    @Test
    fun extract_tolerates_watermark_letter_glued_to_value() {
        val text = listOf("comprehensive metabolic panel\nALBUMIN 4.3A 3.6-5.1 g/dL EN")
        val rows = QuestExtractor.extract(text)
        assertTrue(
            "Expected ALBUMIN row to extract despite watermark letter glued to value",
            rows.any { it.rawName == "ALBUMIN" },
        )
    }

    /**
     * LM-W: watermark intrusion between value and flag, eGFR AFRICAN AMERICAN
     * row -- "eGFR AFRICAN AMERICAN 59 LBOW > OR = 60 mL/min/1.73m2".
     * Regex tolerates "LBOW" via `[A-Z]*\s*` between value and flag.
     */
    @Test
    fun extract_tolerates_watermark_letters_between_value_and_flag() {
        val text = listOf(
            "comprehensive metabolic panel\n" +
                "eGFR AFRICAN AMERICAN 59 LBOW > OR = 60 mL/min/1.73m2 EN",
        )
        val rows = QuestExtractor.extract(text)
        assertTrue(
            "Expected eGFR AFRICAN AMERICAN row to extract despite watermark letters",
            rows.any { it.rawName == "eGFR AFRICAN AMERICAN" },
        )
    }
}
