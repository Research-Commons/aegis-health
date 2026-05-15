package com.aegis.health.reportreader

import kotlinx.serialization.json.JsonNull

/**
 * Phase 4.1 — Tata 1mg Labs extractor.
 *
 * Mirrors the [LabCorpExtractor] / [MayoExtractor] structural template
 * (Phase 2 D-02): `object : VendorExtractor`, one regex per known per-test
 * row, [numLiteral] for value / refLow / refHigh to preserve LM-3 int/float
 * fidelity (Phase 2 D-07).
 *
 * **Fingerprint substrings** (case-insensitive on lowercased page-1 text)
 * sourced from `04.1-2-01-NOTES.md` empirical inspection of the user's real
 * Tata 1mg PDF, plus the RESEARCH.md A1 candidate substrings kept as
 * low-confidence fallbacks for vendor-layout variants:
 *
 *  - `tata 1mg | labs`          (masthead/logo — empirical)
 *  - `personal health smart report`  (large title — empirical)
 *  - `tata 1mg app`             (promo card footer — empirical)
 *  - `tata 1mg`                 (RESEARCH.md A1 fallback)
 *  - `1mg labs`                 (RESEARCH.md A1 fallback)
 *  - `1mg health`               (RESEARCH.md A1 fallback)
 *
 * **R-02 ordering safety net**: Tata1mgExtractor sits at `VendorRegistry`
 * slot 0 (brand-tokens first). All 6 fingerprint substrings are unique to
 * Tata 1mg and do NOT appear in any of the 5 existing vendor page-1 texts
 * (anti-collision verified in [Tata1mgExtractorTest.fingerprint_rejects_all_5_existing_vendor_anchors]).
 *
 * **Row layout: Family B** (Mayo / HospitalLis / UrgentCare convention) —
 * `TEST_NAME  VALUE  UNIT  REF_LOW - REF_HIGH`. The user's real PDF and
 * NOTES.md sample rows confirm this for both the Doctor Summary table and
 * the per-page Detailed Result tables.
 *
 * **NOTES.md vendor-specific divergences handled here**:
 *  - `ÂµL` mojibake (Â<U+00B5>L) in the extracted PDF text — the unit token
 *    regex accepts both `ÂµL` and `µL`/`uL`.
 *  - `10^N/...` unit shapes (e.g. `10^3/uL`, `10^6/cu.mm`) — unit token
 *    alternation includes these explicitly.
 *  - Multi-word test names with `(...)` (e.g. `Glycosylated Hemoglobin (HbA1c)`) —
 *    name anchors include the literal parenthetical.
 *
 * **Defensive minimal row set**: per 04.1-2-02-PLAN action, the initial
 * named-row regex set is anchored on common NABL CBC + lipid + A1c + fasting
 * glucose analytes documented in NOTES.md. Additional analytes can be added
 * as user-local real-PDF inspection surfaces them.
 *
 * Cross-language: Tata1mgExtractor is **Kotlin-only** per Phase 4.1 D-11; no
 * Python `_extract_tata1mg` ancestor. The cross-language D-09 parity test
 * (alias-map only) is unaffected.
 */
object Tata1mgExtractor : VendorExtractor {
    override val vendorKey: String = "tata1mg"

    override fun fingerprintMatches(page1Lower: String): Boolean =
        "tata 1mg | labs" in page1Lower ||
            "personal health smart report" in page1Lower ||
            "tata 1mg app" in page1Lower ||
            "tata 1mg" in page1Lower ||
            "1mg labs" in page1Lower ||
            "1mg health" in page1Lower

    /** Numeric token that allows comma thousand-separators; numLiteral strips them. */
    private const val NUM_COMMA = """\d[\d,]*(?:\.\d+)?"""

    /**
     * Family-B unit token — alternation per RESEARCH.md "Parseable units token set"
     * plus NOTES.md-specific `10^N/...` shapes and the `ÂµL` mojibake variant.
     *
     * Ordered most-specific-first so longer tokens (`10^3/ÂµL`, `mL/min/1.73m2`)
     * match before shorter ones (`/uL`, `mL`). Regex alternation is greedy on first-match.
     */
    private const val UNITS_TOKEN =
        """(?:10\^3/ÂµL|10\^6/ÂµL|10\^3/uL|10\^6/uL|10\^9/L|10\^12/L|10\^3/cu\.mm|10\^6/cu\.mm|""" +
            """mL/min/1\.73m2|cells/uL|cells/cumm|lakhs/cumm|million/cumm|""" +
            """mg/dL|g/dL|mmol/L|mEq/L|mIU/L|uIU/mL|IU/L|U/L|ng/mL|pg/mL|ng/dL|""" +
            """cumm|fL|fl|pg|Pg|%|/uL|/mm3|Ratio)"""

    override fun extract(pagesText: List<String>): List<ParsedRow> {
        val text = pagesText.joinToString("\n")
        val rows = mutableListOf<ParsedRow>()

        // Row 1: Hemoglobin   13.1   g/dL   13.0 - 17.0  (NOTES.md sample)
        Regex("""Hemoglobin\s+($NUM_COMMA)\s+($UNITS_TOKEN)\s+($NUM)\s*-\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "Hemoglobin",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = numLiteral(m.groupValues[3]),
                refHigh = numLiteral(m.groupValues[4]),
            )
        }

        // Row 2: RBC   4.21   10^6/cu.mm   4.5 - 5.5  (NOTES.md sample)
        Regex("""RBC\s+($NUM_COMMA)\s+($UNITS_TOKEN)\s+($NUM)\s*-\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "RBC",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = numLiteral(m.groupValues[3]),
                refHigh = numLiteral(m.groupValues[4]),
            )
        }

        // Row 3: HCT   41.6   %   40 - 50  (NOTES.md sample)
        Regex("""HCT\s+($NUM_COMMA)\s+($UNITS_TOKEN)\s+($NUM)\s*-\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "HCT",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = numLiteral(m.groupValues[3]),
                refHigh = numLiteral(m.groupValues[4]),
            )
        }

        // Row 4: MCHC   31.4   g/dL   31.5 - 34.5  (NOTES.md sample)
        Regex("""MCHC\s+($NUM_COMMA)\s+($UNITS_TOKEN)\s+($NUM)\s*-\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "MCHC",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = numLiteral(m.groupValues[3]),
                refHigh = numLiteral(m.groupValues[4]),
            )
        }

        // Row 5: Total Leucocyte Count   5.16   10^3/ÂµL   4 - 10  (NOTES.md — ÂµL mojibake)
        Regex("""Total\s+Leucocyte\s+Count\s+($NUM_COMMA)\s+($UNITS_TOKEN)\s+($NUM)\s*-\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "Total Leucocyte Count",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = numLiteral(m.groupValues[3]),
                refHigh = numLiteral(m.groupValues[4]),
            )
        }

        // Row 6: Eosinophils   15   %   1 - 6  (NOTES.md sample)
        Regex("""Eosinophils\s+($NUM_COMMA)\s+($UNITS_TOKEN)\s+($NUM)\s*-\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "Eosinophils",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = numLiteral(m.groupValues[3]),
                refHigh = numLiteral(m.groupValues[4]),
            )
        }

        // Row 7: Absolute Eosinophil Count   0.77   10^3/ÂµL   0.02 - 0.5  (NOTES.md sample)
        Regex("""Absolute\s+Eosinophil\s+Count\s+($NUM_COMMA)\s+($UNITS_TOKEN)\s+($NUM)\s*-\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "Absolute Eosinophil Count",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = numLiteral(m.groupValues[3]),
                refHigh = numLiteral(m.groupValues[4]),
            )
        }

        // Row 8: Platelet Count   165   10^3/ÂµL   150 - 410  (NOTES.md sample)
        Regex("""Platelet\s+Count\s+($NUM_COMMA)\s+($UNITS_TOKEN)\s+($NUM)\s*-\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "Platelet Count",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = numLiteral(m.groupValues[3]),
                refHigh = numLiteral(m.groupValues[4]),
            )
        }

        // Row 9: Glycosylated Hemoglobin (HbA1c)   6.0   %   4 - 5.6  (NOTES.md — parenthetical name)
        Regex("""Glycosylated\s+Hemoglobin\s+\(HbA1c\)\s+($NUM_COMMA)\s+($UNITS_TOKEN)\s+($NUM)\s*-\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "Glycosylated Hemoglobin (HbA1c)",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = numLiteral(m.groupValues[3]),
                refHigh = numLiteral(m.groupValues[4]),
            )
        }

        // Row 10: Glucose - Fasting   100   mg/dL   70 - 99  (NOTES.md sample)
        Regex("""Glucose\s*-\s*Fasting\s+($NUM_COMMA)\s+($UNITS_TOKEN)\s+($NUM)\s*-\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "Glucose - Fasting",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = numLiteral(m.groupValues[3]),
                refHigh = numLiteral(m.groupValues[4]),
            )
        }

        // Row 11: Total Cholesterol   <value>   mg/dL   <range>  (defensive — common Tata 1mg lipid panel)
        Regex("""Total\s+Cholesterol\s+($NUM_COMMA)\s+($UNITS_TOKEN)\s+($NUM)\s*-\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "Total Cholesterol",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = numLiteral(m.groupValues[3]),
                refHigh = numLiteral(m.groupValues[4]),
            )
        }

        // Row 12: LDL Cholesterol   <value>   mg/dL   <range or "<130">  (defensive — single-sided high)
        Regex("""LDL\s+Cholesterol\s+($NUM_COMMA)\s+($UNITS_TOKEN)\s+<\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "LDL Cholesterol",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = JsonNull,
                refHigh = numLiteral(m.groupValues[3]),
            )
        }

        // Row 13: HDL Cholesterol   <value>   mg/dL   >40  (defensive — single-sided low)
        Regex("""HDL\s+Cholesterol\s+($NUM_COMMA)\s+($UNITS_TOKEN)\s+>\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "HDL Cholesterol",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = numLiteral(m.groupValues[3]),
                refHigh = JsonNull,
            )
        }

        // Row 14: Triglycerides   <value>   mg/dL   <150  (defensive — single-sided high)
        Regex("""Triglycerides\s+($NUM_COMMA)\s+($UNITS_TOKEN)\s+<\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "Triglycerides",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = JsonNull,
                refHigh = numLiteral(m.groupValues[3]),
            )
        }

        return rows
    }
}
