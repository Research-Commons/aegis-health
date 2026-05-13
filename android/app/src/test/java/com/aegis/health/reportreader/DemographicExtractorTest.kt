package com.aegis.health.reportreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage-level JVM unit tests for [DemographicExtractor] (INPUT-02).
 *
 * Coverage:
 *   - Mayo "Age / Sex : 27 YRS / M" slash-form
 *   - Hospital LIS "Age / Sex : 40 Y / M" slash-form
 *   - Generic "AGE: ..." + "GENDER: ..." fallback
 *   - Out-of-range guard (age > 120) returns null per T-02-09-02
 *   - Pregnancy markers (PREGNANCY_PATTERNS)
 *   - Empty input handling
 */
class DemographicExtractorTest {

    @Test
    fun mayo_slash_yrs_form_parsed() {
        // "Age / Sex : 27 YRS / M" -- Mayo style
        val pages = listOf("Mayo Clinic Laboratories\nAge / Sex : 27 YRS / M\nOther info")
        val p = DemographicExtractor.extract(pages)
        assertEquals(27, p.age)
        assertEquals("male", p.sex)
    }

    @Test
    fun hospital_lis_slash_y_form_parsed() {
        val pages = listOf("Hospital LIS\nAge / Sex : 40 Y / M\nFooter")
        val p = DemographicExtractor.extract(pages)
        assertEquals(40, p.age)
        assertEquals("male", p.sex)
    }

    @Test
    fun generic_age_and_gender_fallback() {
        // LabCorp / Quest style: AGE: and GENDER: independently.
        val pages = listOf("Patient Header\nAGE: 32\nGENDER: Female\nOther")
        val p = DemographicExtractor.extract(pages)
        assertEquals(32, p.age)
        assertEquals("female", p.sex)
    }

    @Test
    fun out_of_range_age_returns_null() {
        // T-02-09-02: malformed PDF prints junk into AGE field -> defer to null.
        val pages = listOf("AGE: 999\nGENDER: Male")
        val p = DemographicExtractor.extract(pages)
        assertEquals(null, p.age)
        // sex still resolves -- independent regex.
        assertEquals("male", p.sex)
    }

    @Test
    fun age_zero_is_valid_lower_bound() {
        // 0..120 inclusive
        val pages = listOf("AGE: 0\nGENDER: Female")
        val p = DemographicExtractor.extract(pages)
        assertEquals(0, p.age)
    }

    @Test
    fun age_120_is_valid_upper_bound() {
        val pages = listOf("AGE: 120\nGENDER: Male")
        val p = DemographicExtractor.extract(pages)
        assertEquals(120, p.age)
    }

    @Test
    fun pregnancy_prenatal_panel_marker_detected() {
        val pages = listOf("Prenatal Panel\nResults follow")
        assertTrue(DemographicExtractor.isPregnant(pages))
    }

    @Test
    fun pregnancy_gestational_age_marker_detected() {
        val pages = listOf("Routine bloodwork", "Gestational Age: 24 weeks")
        assertTrue(DemographicExtractor.isPregnant(pages))
    }

    @Test
    fun no_pregnancy_marker_returns_false() {
        val pages = listOf("Adult routine panel\nNo special markers")
        assertFalse(DemographicExtractor.isPregnant(pages))
    }

    @Test
    fun empty_pages_yield_empty_profile() {
        val p = DemographicExtractor.extract(emptyList())
        assertEquals(null, p.age)
        assertEquals(null, p.sex)
    }

    @Test
    fun empty_pages_returns_false_for_pregnancy() {
        assertFalse(DemographicExtractor.isPregnant(emptyList()))
    }

    @Test
    fun blank_cover_sheet_yields_nulls() {
        // LabCorp / Quest / urgent-care fixtures: no AGE / SEX printed.
        val pages = listOf("CHOLESTEROL, TOTAL 151 125-200 mg/dL\nresults")
        val p = DemographicExtractor.extract(pages)
        assertEquals(null, p.age)
        assertEquals(null, p.sex)
    }
}
