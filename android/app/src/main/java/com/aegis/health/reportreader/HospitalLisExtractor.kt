package com.aegis.health.reportreader

import kotlinx.serialization.json.JsonNull

/**
 * Phase 2 — Hospital LIS lipid-profile extractor.
 *
 * Cross-language port of `tools/parsers/lab_report_parser.py:_parse_hospital_lis`
 * (lines 939-1016). 8 rows in row-for-row order matching the GT JSON at
 * `eval/fixtures/lab_reports/hospital_lis/hospital_lipid-evaluated.json`:
 *
 *  1. Total Cholesterol          (single-sided refHigh)
 *  2. Triglyceride               (single-sided refHigh)
 *  3. HDL Cholesterol            (single-sided refLow)
 *  4. VLDL Cholesterol           (bilateral range)
 *  5. LDL Cholesterol            (single-sided refHigh)
 *  6. Non-HDL Cholesterol        (single-sided refHigh)
 *  7. LDL / HDL Ratio            (bilateral range, units="Ratio")
 *  8. TC / HDL Ratio             (bilateral range, units="Ratio")
 *
 * Column layout: "OBSERVATION  RESULT  UNIT  BIOLOGICAL REF. INTERVAL" — unit
 * precedes the range, unlike LabCorp/Quest where the range comes first.
 *
 * Page-1 fingerprint: "lipid profile" + "biological ref" on lowercased page 1
 * (mirrors Python `_detect_vendor:547`).
 */
object HospitalLisExtractor : VendorExtractor {
    override val vendorKey: String = "hospital_lis"

    override fun fingerprintMatches(page1Lower: String): Boolean =
        "lipid profile" in page1Lower && "biological ref" in page1Lower

    override fun extract(pagesText: List<String>): List<ParsedRow> {
        val text = pagesText.joinToString("\n")
        val rows = mutableListOf<ParsedRow>()

        // Row 1: Total Cholesterol 122 mg/dL <200 ...  (Python line 945)
        Regex("""Total\s+Cholesterol\s+($NUM)\s+(mg/dL)\s+<\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "Total Cholesterol",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = JsonNull,
                refHigh = numLiteral(m.groupValues[3]),
            )
        }

        // Row 2: Triglyceride 184 mg/dL <150 ...  (Python line 954)
        Regex("""Triglyceride\s+($NUM)\s+(mg/dL)\s+<\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "Triglyceride",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = JsonNull,
                refHigh = numLiteral(m.groupValues[3]),
            )
        }

        // Row 3: HDL Cholesterol 37 mg/dL >45 ...  (Python line 963, single-sided low)
        Regex("""HDL\s+Cholesterol\s+($NUM)\s+(mg/dL)\s+>\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "HDL Cholesterol",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = numLiteral(m.groupValues[3]),
                refHigh = JsonNull,
            )
        }

        // Row 4: VLDL Cholesterol 37 mg/dL 5-40 ...  (Python line 972, bilateral)
        Regex("""VLDL\s+Cholesterol\s+($NUM)\s+(mg/dL)\s+($NUM)-($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "VLDL Cholesterol",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = numLiteral(m.groupValues[3]),
                refHigh = numLiteral(m.groupValues[4]),
            )
        }

        // Row 5: LDL Cholesterol 48 mg/dL <100 ...  (Python line 981)
        Regex("""LDL\s+Cholesterol\s+($NUM)\s+(mg/dL)\s+<\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "LDL Cholesterol",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = JsonNull,
                refHigh = numLiteral(m.groupValues[3]),
            )
        }

        // Row 6: Non-HDL Cholesterol 85 mg/dL <130 tCalculated  (Python line 990)
        Regex("""Non-HDL\s+Cholesterol\s+($NUM)\s+(mg/dL)\s+<\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "Non-HDL Cholesterol",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = JsonNull,
                refHigh = numLiteral(m.groupValues[3]),
            )
        }

        // Row 7: LDL / HDL Ratio 1.3 Ratio 1.5-3.5 Calculated  (Python line 999, units="Ratio")
        Regex("""LDL\s*/\s*HDL\s+Ratio\s+($NUM)\s+(Ratio)\s+($NUM)-($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "LDL / HDL Ratio",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = numLiteral(m.groupValues[3]),
                refHigh = numLiteral(m.groupValues[4]),
            )
        }

        // Row 8: TC / HDL Ratio 3.3 Ratio 3-5 Calculated  (Python line 1008, units="Ratio")
        Regex("""TC\s*/\s*HDL\s+Ratio\s+($NUM)\s+(Ratio)\s+($NUM)-($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "TC / HDL Ratio",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = numLiteral(m.groupValues[3]),
                refHigh = numLiteral(m.groupValues[4]),
            )
        }

        return rows
    }
}
