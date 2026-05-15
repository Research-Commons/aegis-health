package com.aegis.health.reportreader

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4.1 Wave 3 — Stage-level JVM unit tests for [GenericExtractor]
 * (slot-7 catch-all per R-02 + D-03).
 *
 * Covers (per 04.1-3-01-PLAN Task 1 behaviors 1-12; Test 13 deferred to
 * Task 3 integration since alias-map silent drop fires in
 * LabRowNormalizer, not in GenericExtractor):
 *
 *   - Test 1 (fingerprint catch-all): [GenericExtractor.fingerprintMatches]
 *     returns `true` unconditionally (D-03).
 *   - Test 2 (per-row gate accept, units AND range): Family-B-style row
 *     yields one ParsedRow with raw_name + value + units + ref_low +
 *     ref_high.
 *   - Test 3 (per-row gate accept, units only): Family-C-style row
 *     (sole-units path) yields one ParsedRow with units populated and
 *     ref_low/ref_high = JsonNull.
 *   - Test 4 (per-row gate accept, range only): row with no units but with
 *     a parseable printed range yields one ParsedRow with units = null
 *     and ref_low/ref_high populated.
 *   - Tests 5-10 (per-row gate DROP — D-14 adversarial cases per
 *     RESEARCH.md False-positive-risk table):
 *       5. "Patient ID: 12345"   -> 0 rows
 *       6. "Date: 12/15/2024"    -> 0 rows
 *       7. "Page 1 of 3"         -> 0 rows
 *       8. "MRN: 78901234"       -> 0 rows
 *       9. "DOB 1990-01-01"      -> 0 rows
 *      10. "Specimen 1 EDTA"     -> 0 rows
 *   - Test 11 (LM-3 fidelity): int vs float preserved at the
 *     [JsonPrimitive] boundary.
 *   - Test 12 (5-row Acme block): 5 Family-B rows all pass the gate.
 */
class GenericExtractorTest {

    @Test
    fun fingerprint_returns_true_for_any_input_catch_all_per_d03() {
        // D-03: GenericExtractor is the slot-7 catch-all; fingerprintMatches
        // must return true unconditionally so the registry's firstOrNull falls
        // through to it after the 7 named extractors miss.
        assertTrue(GenericExtractor.fingerprintMatches(""))
        assertTrue(GenericExtractor.fingerprintMatches("anything"))
        assertTrue(GenericExtractor.fingerprintMatches("not a lab report"))
        assertTrue(GenericExtractor.fingerprintMatches("12345 abc def"))
    }

    @Test
    fun extract_per_row_gate_accepts_family_b_units_and_range() {
        // Family B: name value units low - high
        val pagesText = listOf("Albumin 4.3 g/dL 3.5 - 5.0\n")
        val rows = GenericExtractor.extract(pagesText)
        assertEquals(1, rows.size)
        val r = rows.first()
        assertEquals("Albumin", r.rawName)
        assertEquals(JsonPrimitive(4.3), r.value)
        assertEquals("g/dL", r.units)
        assertEquals(JsonPrimitive(3.5), r.refLow)
        assertEquals(JsonPrimitive(5.0), r.refHigh)
    }

    @Test
    fun extract_per_row_gate_accepts_family_c_sole_units_path() {
        // Family C (sole-units): name value units, NO printed range.
        // Per RESEARCH.md per-row gate, units alone are sufficient.
        val pagesText = listOf("Sodium 142 mmol/L\n")
        val rows = GenericExtractor.extract(pagesText)
        assertEquals(1, rows.size)
        val r = rows.first()
        assertEquals("Sodium", r.rawName)
        assertEquals(JsonPrimitive(142L), r.value)
        assertEquals("mmol/L", r.units)
        assertEquals(JsonNull, r.refLow)
        assertEquals(JsonNull, r.refHigh)
    }

    @Test
    fun extract_per_row_gate_accepts_range_only_no_units() {
        // Range-only path (no units present): value followed by low-high.
        // Per RESEARCH.md gate (hasUnits OR hasRange), this passes.
        val pagesText = listOf("Bilirubin 0.5 0.2-1.2\n")
        val rows = GenericExtractor.extract(pagesText)
        assertEquals(1, rows.size)
        val r = rows.first()
        assertEquals("Bilirubin", r.rawName)
        assertEquals(JsonPrimitive(0.5), r.value)
        assertNull(r.units)
        assertEquals(JsonPrimitive(0.2), r.refLow)
        assertEquals(JsonPrimitive(1.2), r.refHigh)
    }

    @Test
    fun extract_per_row_gate_drops_patient_id_metadata() {
        // Adversarial: "Patient ID: 12345" — no units, no range.
        // RESEARCH.md False-positive-risk: must drop deterministically.
        val rows = GenericExtractor.extract(listOf("Patient ID: 12345\n"))
        assertTrue(
            "Patient ID metadata must drop (no units, no range)",
            rows.isEmpty(),
        )
    }

    @Test
    fun extract_per_row_gate_drops_date_metadata() {
        // "Date: 12/15/2024" — no units, no range.
        val rows = GenericExtractor.extract(listOf("Date: 12/15/2024\n"))
        assertTrue("Date metadata must drop", rows.isEmpty())
    }

    @Test
    fun extract_per_row_gate_drops_page_of_metadata() {
        // "Page 1 of 3" — pagination footer; no units, no range.
        val rows = GenericExtractor.extract(listOf("Page 1 of 3\n"))
        assertTrue("Page-of metadata must drop", rows.isEmpty())
    }

    @Test
    fun extract_per_row_gate_drops_mrn_metadata() {
        // "MRN: 78901234" — no units, no range.
        val rows = GenericExtractor.extract(listOf("MRN: 78901234\n"))
        assertTrue("MRN metadata must drop", rows.isEmpty())
    }

    @Test
    fun extract_per_row_gate_drops_dob_metadata() {
        // "DOB 1990-01-01" — looks superficially like "name value low-high"
        // but "1990-01-01" decomposes via Variant 2 only if numLiteral
        // parses "1990" / "01" / "01"; the regex SHOULD NOT match because the
        // hyphenated date has no surrounding spaces matching the range pattern
        // AND there are no units. Either way, the per-row gate (units OR range)
        // must drop it because no parseable units token is present.
        val rows = GenericExtractor.extract(listOf("DOB 1990-01-01\n"))
        assertTrue("DOB metadata must drop", rows.isEmpty())
    }

    @Test
    fun extract_per_row_gate_drops_specimen_metadata() {
        // "Specimen 1 EDTA" — no units, no range; "EDTA" is not a units token.
        val rows = GenericExtractor.extract(listOf("Specimen 1 EDTA\n"))
        assertTrue("Specimen metadata must drop", rows.isEmpty())
    }

    @Test
    fun extract_preserves_int_vs_float_per_lm3() {
        // LM-3 / Phase 2 D-07: numLiteral preserves int vs float at the
        // JsonPrimitive boundary. Use a row with integer value+range to
        // exercise the int path.
        val pagesText = listOf("Sodium 142 mmol/L 135 - 145\n")
        val rows = GenericExtractor.extract(pagesText)
        assertEquals(1, rows.size)
        val r = rows.first()
        assertEquals(JsonPrimitive(142L), r.value)
        assertEquals(JsonPrimitive(135L), r.refLow)
        assertEquals(JsonPrimitive(145L), r.refHigh)
        assertFalse(
            "Int JsonPrimitive must NOT render with a decimal point",
            r.value.toString().contains("."),
        )
    }

    @Test
    fun extract_acme_5_row_block_all_pass_per_row_gate() {
        // 5-row Acme-style block — each row has units + range so the per-row
        // gate accepts all 5.
        val pagesText = listOf(
            "Sodium 142 mmol/L 135 - 145\n" +
                "Chloride 102 mmol/L 96 - 106\n" +
                "Calcium 9.5 mg/dL 8.5 - 10.5\n" +
                "Albumin 4.3 g/dL 3.5 - 5.0\n" +
                "Glucose 95 mg/dL 70 - 99\n",
        )
        val rows = GenericExtractor.extract(pagesText)
        assertEquals(
            "All 5 Family-B rows must pass the per-row gate (units AND range)",
            5,
            rows.size,
        )
        val names = rows.map { it.rawName }.toSet()
        assertEquals(
            setOf("Sodium", "Chloride", "Calcium", "Albumin", "Glucose"),
            names,
        )
    }
}
