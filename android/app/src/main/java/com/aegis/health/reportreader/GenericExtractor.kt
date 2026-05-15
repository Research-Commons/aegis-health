package com.aegis.health.reportreader

import kotlinx.serialization.json.JsonNull

/**
 * Phase 4.1 Wave 3 — **GenericExtractor** (slot-7 catch-all).
 *
 * Catch-all [VendorExtractor] for PDFs whose page-1 fingerprint does NOT
 * match any of the 7 named extractors. Per R-02, [GenericExtractor] MUST
 * sit at [VendorRegistry] slot 7 (last); per D-03, [fingerprintMatches]
 * returns `true` unconditionally so [VendorRegistry.fingerprintMatches]'
 * `firstOrNull` semantics fall through to it only when all 7 named
 * extractors miss.
 *
 * ### Permissive regex shape (D-01)
 *
 * Single line-by-line cascade of regex variants tried in order; first
 * non-null match wins. The cascade mirrors the empirical column-order
 * Families documented in `04.1-RESEARCH.md`:
 *
 *   - **Variant 1 — Family B** (Mayo / HospitalLis / UrgentCare /
 *     Tata1mg / DrLal): `name value units low - high`. Bilateral range
 *     plus `< high` (single-sided high) plus `> low` (single-sided low)
 *     sub-shapes.
 *   - **Variant 2 — Family A** (LabCorp / Quest): `name value low - high units`.
 *     Units optional so the same shape also captures the range-only
 *     (no units) path called out in RESEARCH.md "Bilirubin 0.5 0.2-1.2".
 *   - **Variant 3 — Family C** (sole-units): `name value units`.
 *     No printed range; the per-row gate accepts because `hasUnits` is
 *     true.
 *
 * ### Per-row gate (D-02)
 *
 * [passesPerRowGate]: a candidate [ParsedRow] survives only when
 * `hasUnits OR hasRange`. Rows matching the regex but carrying neither
 * units nor range are silently dropped — this kills page-footer
 * false-positives like `Patient ID: 12345` / `Date: 12/15/2024` /
 * `Page 1 of 3` / `MRN: 78901234` / `DOB 1990-01-01` / `Specimen 1 EDTA`
 * (RESEARCH.md False-positive-risk table; D-14 adversarial cases
 * proven in [GenericExtractorTest]).
 *
 * ### Cross-language
 *
 * [GenericExtractor] is **Kotlin-only** per Phase 4.1 D-11; no Python
 * `_extract_generic` ancestor. The cross-language D-09 parity test
 * (alias-map only) is unaffected — alias-map silent drop fires
 * downstream in [LabRowNormalizer].
 *
 * ### Aggregate-floor + status-code emission
 *
 * Lives in [ReportReaderPipeline.parse], NOT here. This file only
 * produces candidate rows; pipeline aggregates them, applies the
 * aggregate-floor gate (D-02 `<3` post-normalization rows → UNKNOWN_VENDOR),
 * and selects `GENERIC_FALLBACK` vs `UNKNOWN_VENDOR` vs `TOO_MANY_ANALYTES`
 * via the [LabRowNormalizer.ROW_COUNT_DEFER_THRESHOLD] precedence cascade.
 */
object GenericExtractor : VendorExtractor {
    override val vendorKey: String = "generic"

    /**
     * D-03 catch-all: unconditional `true`. [VendorRegistry] keeps this
     * extractor at slot 7 so the firstOrNull dispatch falls through here
     * only when none of the 7 named extractors fingerprint-match.
     */
    override fun fingerprintMatches(page1Lower: String): Boolean = true

    /** Numeric token that allows comma thousand-separators; numLiteral strips them. */
    private const val NUM_COMMA = """\d[\d,]*(?:\.\d+)?"""

    /**
     * Parseable units token set per RESEARCH.md "Permissive Regex Shape"
     * and "Parseable units token set" sections.
     *
     * Mirrors [Tata1mgExtractor]'s `UNITS_TOKEN` alternation (duplicate
     * intentionally per Phase 2 D-02 single-file-per-vendor convention).
     * Ordered most-specific-first so longer tokens (`10^3/uL`,
     * `mL/min/1.73m2`) win before shorter ones (`/uL`, `mL`) under
     * regex alternation's greedy first-match semantics.
     */
    private const val UNITS_TOKEN =
        """(?:10\^3/ÂµL|10\^6/ÂµL|10\^3/uL|10\^6/uL|10\^9/L|10\^12/L|10\^3/cu\.mm|10\^6/cu\.mm|""" +
            """mL/min/1\.73m2|cells/uL|cells/cumm|lakhs/cumm|million/cumm|""" +
            """mg/dL|g/dL|mmol/L|mEq/L|mIU/L|uIU/mL|IU/L|U/L|ng/mL|pg/mL|ng/dL|""" +
            """cumm|fL|fl|pg|Pg|%|/uL|/mm3|Ratio)"""

    /**
     * Name-anchor token: one or more capitalised words (allowing
     * embedded `()` for parenthetical analyte names like
     * `Glycosylated Hemoglobin (HbA1c)`, and embedded commas/colons
     * for vendor-styled names like `Cholesterol, Total` and
     * `Patient ID:`). Reluctant quantifier so we stop at the value
     * token boundary.
     *
     * Anchored at line start (caller iterates lines) to avoid mid-line
     * partial matches that would chew off non-analyte prefixes.
     */
    private const val NAME_ANCHOR =
        """([A-Za-z][A-Za-z0-9 \-,/()]*?)"""

    // Variant 1 — Family B bilateral: name value units low - high
    private val V1_FAM_B_BILATERAL =
        Regex("""^\s*$NAME_ANCHOR\s+($NUM_COMMA)\s+($UNITS_TOKEN)\s+($NUM)\s*-\s*($NUM)\s*$""")

    // Variant 1b — Family B single-sided high: name value units < high
    private val V1_FAM_B_LT =
        Regex("""^\s*$NAME_ANCHOR\s+($NUM_COMMA)\s+($UNITS_TOKEN)\s+<\s*($NUM)\s*$""")

    // Variant 1c — Family B single-sided low: name value units > low
    private val V1_FAM_B_GT =
        Regex("""^\s*$NAME_ANCHOR\s+($NUM_COMMA)\s+($UNITS_TOKEN)\s+>\s*($NUM)\s*$""")

    // Variant 2 — Family A (units optional): name value low - high (units?)
    // Units optional captures the range-only path: "Bilirubin 0.5 0.2-1.2".
    private val V2_FAM_A_OPT_UNITS =
        Regex("""^\s*$NAME_ANCHOR\s+($NUM_COMMA)\s+($NUM)\s*-\s*($NUM)(?:\s+($UNITS_TOKEN))?\s*$""")

    // Variant 3 — Family C (sole-units): name value units (no range)
    private val V3_FAM_C_SOLE_UNITS =
        Regex("""^\s*$NAME_ANCHOR\s+($NUM_COMMA)\s+($UNITS_TOKEN)\s*$""")

    /**
     * Per-row gate (D-02): a row survives only when at least one of
     * `units` or a parseable range edge is present. See RESEARCH.md
     * "Per-row gate (D-02)" snippet.
     */
    private fun passesPerRowGate(row: ParsedRow): Boolean {
        val hasUnits = row.units?.isNotBlank() == true
        val hasRange = row.refLow != JsonNull || row.refHigh != JsonNull
        return hasUnits || hasRange
    }

    override fun extract(pagesText: List<String>): List<ParsedRow> {
        val text = pagesText.joinToString("\n")
        val rows = mutableListOf<ParsedRow>()

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val candidate = matchVariants(line) ?: continue
            if (!passesPerRowGate(candidate)) continue
            rows += candidate
        }

        return rows
    }

    /**
     * Try the 3-variant cascade against [line] in priority order:
     * Family B (1, 1b, 1c) → Family A (2) → Family C (3). Returns the
     * first non-null parse, or null if no variant matches.
     */
    private fun matchVariants(line: String): ParsedRow? {
        // Variant 1 — Family B bilateral
        V1_FAM_B_BILATERAL.matchEntire(line)?.let { m ->
            return ParsedRow(
                rawName = m.groupValues[1].trim(),
                value = numLiteral(m.groupValues[2]),
                units = m.groupValues[3],
                refLow = numLiteral(m.groupValues[4]),
                refHigh = numLiteral(m.groupValues[5]),
            )
        }
        // Variant 1b — Family B single-sided high
        V1_FAM_B_LT.matchEntire(line)?.let { m ->
            return ParsedRow(
                rawName = m.groupValues[1].trim(),
                value = numLiteral(m.groupValues[2]),
                units = m.groupValues[3],
                refLow = JsonNull,
                refHigh = numLiteral(m.groupValues[4]),
            )
        }
        // Variant 1c — Family B single-sided low
        V1_FAM_B_GT.matchEntire(line)?.let { m ->
            return ParsedRow(
                rawName = m.groupValues[1].trim(),
                value = numLiteral(m.groupValues[2]),
                units = m.groupValues[3],
                refLow = numLiteral(m.groupValues[4]),
                refHigh = JsonNull,
            )
        }
        // Variant 2 — Family A (units optional, captures range-only path)
        V2_FAM_A_OPT_UNITS.matchEntire(line)?.let { m ->
            val unitsCapture = m.groupValues[5]
            return ParsedRow(
                rawName = m.groupValues[1].trim(),
                value = numLiteral(m.groupValues[2]),
                units = unitsCapture.ifBlank { null },
                refLow = numLiteral(m.groupValues[3]),
                refHigh = numLiteral(m.groupValues[4]),
            )
        }
        // Variant 3 — Family C (sole-units, no range)
        V3_FAM_C_SOLE_UNITS.matchEntire(line)?.let { m ->
            return ParsedRow(
                rawName = m.groupValues[1].trim(),
                value = numLiteral(m.groupValues[2]),
                units = m.groupValues[3],
                refLow = JsonNull,
                refHigh = JsonNull,
            )
        }
        return null
    }
}
