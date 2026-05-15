package com.aegis.health.reportreader

import kotlinx.serialization.json.JsonNull

/**
 * Phase 4.1 — Dr Lal PathLabs extractor.
 *
 * Mirrors the [Tata1mgExtractor] / [MayoExtractor] structural template
 * (Phase 2 D-02): `object : VendorExtractor`, one regex per known per-test
 * row, [numLiteral] for value / refLow / refHigh to preserve LM-3 int/float
 * fidelity (Phase 2 D-07).
 *
 * **Fingerprint substrings** (case-insensitive on lowercased page-1 text)
 * sourced from `04.1-2-01-NOTES.md` empirical inspection of a publicly-
 * distributed Dr Lal PathLabs demo report (D-13 Apollo fallback NOT triggered):
 *
 *  - `dr lal pathlabs`              (masthead/logo — empirical)
 *  - `lpl-national reference lab`   (processed-at section — empirical)
 *  - `dr lal path labs`             (low-confidence fallback per RESEARCH.md A2;
 *                                    kept in case any vintage layout splits
 *                                    the brand into 3 words)
 *
 * Explicitly NOT used as fingerprints (per NOTES.md decisions):
 *  - `drlpl` — A2 candidate dropped; NOT empirically observed on page 1.
 *  - `Sample Report` — cosmetic watermark, only present on demo PDFs.
 *  - `SWASTHFIT SUPER 4` — too panel-specific.
 *
 * **R-02 ordering safety net**: DrLalPathLabsExtractor sits at
 * `VendorRegistry` slot 1 (brand-tokens first, after Tata1mgExtractor at
 * slot 0). All 3 fingerprint substrings are unique to Dr Lal and do NOT
 * appear in any of the 5 existing vendor page-1 texts nor in Tata 1mg
 * page-1 texts (anti-collision verified in
 * [DrLalPathLabsExtractorTest.fingerprint_rejects_all_5_existing_vendor_anchors_and_tata1mg]).
 *
 * **Row layout: Family B** (Mayo / HospitalLis / UrgentCare / Tata 1mg
 * convention) — `TEST_NAME  VALUE  UNIT  REF_LOW - REF_HIGH`. The sample
 * PDF in NOTES.md confirms this for the boxed-table-region rows.
 *
 * **NOTES.md vendor-specific divergences handled here**:
 *  - Inequality ranges (`>59`, `<0.3`, `<1.10`) — supported via dedicated
 *    single-sided regex variants (mirrors HospitalLisExtractor / UrgentCare
 *    single-sided patterns).
 *  - `mL/min/1.73m2` unit token (contains `/` and digits) — included
 *    explicitly in UNITS_TOKEN alternation.
 *  - Parenthetical-only method lines (e.g. `(Modified Jaffe,Kinetic)`,
 *    `(Urease UV)`) — naturally skipped because they contain no value/unit/
 *    range token; the per-test regex anchored on a real test name will
 *    never match them.
 *  - All-caps panel-heading lines (e.g. `LIVER & KIDNEY PANEL, SERUM`) —
 *    naturally skipped for the same reason (no numeric tokens).
 *
 * **Defensive minimal row set**: per 04.1-2-02-PLAN action, the initial
 * named-row regex set is anchored on Liver & Kidney panel + lipid panel
 * + A1c + glucose analytes (covering NOTES.md sample rows and common
 * Dr Lal Swasthfit panels). Additional analytes can be added as further
 * sample PDFs surface them.
 *
 * Cross-language: DrLalPathLabsExtractor is **Kotlin-only** per Phase 4.1
 * D-11; no Python `_extract_drlalpathlabs` ancestor. The cross-language
 * D-09 parity test (alias-map only) is unaffected.
 */
object DrLalPathLabsExtractor : VendorExtractor {
    override val vendorKey: String = "drlalpathlabs"

    override fun fingerprintMatches(page1Lower: String): Boolean =
        "dr lal pathlabs" in page1Lower ||
            "lpl-national reference lab" in page1Lower ||
            "dr lal path labs" in page1Lower

    /** Numeric token that allows comma thousand-separators; numLiteral strips them. */
    private const val NUM_COMMA = """\d[\d,]*(?:\.\d+)?"""

    /**
     * Family-B unit token — alternation per RESEARCH.md "Parseable units token set"
     * plus Dr Lal sample-specific `mL/min/1.73m2` (GFR), and the `10^N/...` shapes
     * shared with Tata 1mg for cross-vendor consistency.
     *
     * Ordered most-specific-first so longer tokens match before shorter ones.
     */
    private const val UNITS_TOKEN =
        """(?:10\^3/ÂµL|10\^6/ÂµL|10\^3/uL|10\^6/uL|10\^9/L|10\^12/L|10\^3/cu\.mm|10\^6/cu\.mm|""" +
            """mL/min/1\.73m2|cells/uL|cells/cumm|lakhs/cumm|million/cumm|""" +
            """mg/dL|g/dL|mmol/L|mEq/L|mIU/L|uIU/mL|IU/L|U/L|ng/mL|pg/mL|ng/dL|""" +
            """cumm|fL|fl|pg|Pg|%|/uL|/mm3|Ratio)"""

    override fun extract(pagesText: List<String>): List<ParsedRow> {
        val text = pagesText.joinToString("\n")
        val rows = mutableListOf<ParsedRow>()

        // Row 1: Creatinine   1.00   mg/dL   0.70 - 1.30  (NOTES.md sample, bilateral)
        Regex("""Creatinine\s+($NUM_COMMA)\s+($UNITS_TOKEN)\s+($NUM)\s*-\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "Creatinine",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = numLiteral(m.groupValues[3]),
                refHigh = numLiteral(m.groupValues[4]),
            )
        }

        // Row 2: GFR Estimated   107   mL/min/1.73m2   >59  (NOTES.md, inequality single-low)
        Regex("""GFR\s+Estimated\s+($NUM_COMMA)\s+($UNITS_TOKEN)\s+>\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "GFR Estimated",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = numLiteral(m.groupValues[3]),
                refHigh = JsonNull,
            )
        }

        // Row 3: Urea   40.00   mg/dL   13.00 - 43.00  (NOTES.md sample, bilateral)
        Regex("""Urea\s+($NUM_COMMA)\s+($UNITS_TOKEN)\s+($NUM)\s*-\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "Urea",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = numLiteral(m.groupValues[3]),
                refHigh = numLiteral(m.groupValues[4]),
            )
        }

        // Row 4: Urea Nitrogen Blood   18.68   mg/dL   6.00 - 20.00  (NOTES.md sample)
        Regex("""Urea\s+Nitrogen\s+Blood\s+($NUM_COMMA)\s+($UNITS_TOKEN)\s+($NUM)\s*-\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "Urea Nitrogen Blood",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = numLiteral(m.groupValues[3]),
                refHigh = numLiteral(m.groupValues[4]),
            )
        }

        // Row 5: Uric Acid   7.00   mg/dL   3.50 - 7.20  (NOTES.md sample)
        Regex("""Uric\s+Acid\s+($NUM_COMMA)\s+($UNITS_TOKEN)\s+($NUM)\s*-\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "Uric Acid",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = numLiteral(m.groupValues[3]),
                refHigh = numLiteral(m.groupValues[4]),
            )
        }

        // Row 6: AST (SGOT)   30.0   U/L   15.00 - 40.00  (NOTES.md, parenthetical name)
        Regex("""AST\s+\(SGOT\)\s+($NUM_COMMA)\s+($UNITS_TOKEN)\s+($NUM)\s*-\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "AST (SGOT)",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = numLiteral(m.groupValues[3]),
                refHigh = numLiteral(m.groupValues[4]),
            )
        }

        // Row 7: ALT (SGPT)   40.0   U/L   10.00 - 49.00  (NOTES.md, parenthetical name)
        Regex("""ALT\s+\(SGPT\)\s+($NUM_COMMA)\s+($UNITS_TOKEN)\s+($NUM)\s*-\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "ALT (SGPT)",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = numLiteral(m.groupValues[3]),
                refHigh = numLiteral(m.groupValues[4]),
            )
        }

        // Row 8: GGTP   50.0   U/L   0 - 73  (NOTES.md sample)
        Regex("""GGTP\s+($NUM_COMMA)\s+($UNITS_TOKEN)\s+($NUM)\s*-\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "GGTP",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = numLiteral(m.groupValues[3]),
                refHigh = numLiteral(m.groupValues[4]),
            )
        }

        // Row 9: Alkaline Phosphatase (ALP)   100.00   U/L   30.00 - 120.00  (NOTES.md, parenthetical)
        Regex("""Alkaline\s+Phosphatase\s+\(ALP\)\s+($NUM_COMMA)\s+($UNITS_TOKEN)\s+($NUM)\s*-\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "Alkaline Phosphatase (ALP)",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = numLiteral(m.groupValues[3]),
                refHigh = numLiteral(m.groupValues[4]),
            )
        }

        // Row 10: Bilirubin Total   1.00   mg/dL   0.30 - 1.20  (NOTES.md sample)
        Regex("""Bilirubin\s+Total\s+($NUM_COMMA)\s+($UNITS_TOKEN)\s+($NUM)\s*-\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "Bilirubin Total",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = numLiteral(m.groupValues[3]),
                refHigh = numLiteral(m.groupValues[4]),
            )
        }

        // Row 11: Bilirubin Direct   <0.3   mg/dL  (defensive — NOTES.md inequality '<0.3')
        Regex("""Bilirubin\s+Direct\s+($NUM_COMMA)\s+($UNITS_TOKEN)\s+<\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "Bilirubin Direct",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = JsonNull,
                refHigh = numLiteral(m.groupValues[3]),
            )
        }

        // Row 12: Total Cholesterol   <value>   mg/dL   <200  (defensive — common Dr Lal lipid)
        Regex("""Total\s+Cholesterol\s+($NUM_COMMA)\s+($UNITS_TOKEN)\s+<\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "Total Cholesterol",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = JsonNull,
                refHigh = numLiteral(m.groupValues[3]),
            )
        }

        // Row 13: HbA1c   6.0   %   4 - 5.6  (defensive — common Dr Lal A1c)
        Regex("""HbA1c\s+($NUM_COMMA)\s+($UNITS_TOKEN)\s+($NUM)\s*-\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "HbA1c",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = numLiteral(m.groupValues[3]),
                refHigh = numLiteral(m.groupValues[4]),
            )
        }

        // Row 14: Glucose Fasting   <value>   mg/dL   70 - 99  (defensive — common Dr Lal)
        Regex("""Glucose\s+Fasting\s+($NUM_COMMA)\s+($UNITS_TOKEN)\s+($NUM)\s*-\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "Glucose Fasting",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = numLiteral(m.groupValues[3]),
                refHigh = numLiteral(m.groupValues[4]),
            )
        }

        return rows
    }
}
