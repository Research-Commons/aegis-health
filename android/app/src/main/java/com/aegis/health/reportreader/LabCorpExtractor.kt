package com.aegis.health.reportreader

import kotlinx.serialization.json.JsonNull

/**
 * Phase 2 — LabCorp lipid panel extractor.
 *
 * Cross-language port of `tools/parsers/lab_report_parser.py:_parse_labcorp`
 * (lines 587-646). 6 rows in row-for-row order matching the GT JSON at
 * `eval/fixtures/lab_reports/labcorp/labcorp_lipid_panel-evaluated.json`:
 *
 *  1. CHOLESTEROL, TOTAL          (bilateral range)
 *  2. HDL CHOLESTEROL             (single-sided ref_low, refHigh=null)
 *  3. TRIGLYCERIDES               (single-sided refHigh, refLow=null)
 *  4. LDL CHOLESTEROL             (single-sided refHigh, refLow=null)
 *  5. CHOL/HDLC RATIO             (single-sided refHigh, units=null)
 *  6. NON HDL CHOLESTEROL         (no range; refLow=refHigh=null)
 *
 * Page-1 fingerprint: "lipid panel" + "cholesterol, total" on lowercased page 1
 * (mirrors Python `_detect_vendor:539`).
 */
object LabCorpExtractor : VendorExtractor {
    override val vendorKey: String = "labcorp"

    override fun fingerprintMatches(page1Lower: String): Boolean =
        "lipid panel" in page1Lower && "cholesterol, total" in page1Lower

    override fun extract(pagesText: List<String>): List<ParsedRow> {
        val text = pagesText.joinToString("\n")
        val rows = mutableListOf<ParsedRow>()

        // Row 1: CHOLESTEROL, TOTAL 151 125-200 mg/dL EN  (Python line 593)
        Regex("""CHOLESTEROL,\s*TOTAL\s+($NUM)\s+($NUM)-($NUM)\s+(mg/dL)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "CHOLESTEROL, TOTAL",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[4],
                refLow = numLiteral(m.groupValues[2]),
                refHigh = numLiteral(m.groupValues[3]),
            )
        }

        // Row 2: HDL CHOLESTEROL 58 > OR = 46 mg/dL EN  (Python line 602, single-sided)
        Regex("""HDL\s*CHOLESTEROL\s+($NUM)\s+>\s*OR\s*=\s*($NUM)\s+(mg/dL)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "HDL CHOLESTEROL",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[3],
                refLow = numLiteral(m.groupValues[2]),
                refHigh = JsonNull,
            )
        }

        // Row 3: TRIGLYCERIDES 48 <150 mg/dL EN  (Python line 611, single-sided high)
        Regex("""TRIGLYCERIDES\s+($NUM)\s+<\s*($NUM)\s+(mg/dL)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "TRIGLYCERIDES",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[3],
                refLow = JsonNull,
                refHigh = numLiteral(m.groupValues[2]),
            )
        }

        // Row 4: LDL-CHOLESTEROL 83 <130 mg/dL (calc) EN  (Python line 620 — GT raw_name is "LDL CHOLESTEROL")
        Regex("""LDL-?CHOLESTEROL\s+($NUM)\s+<\s*($NUM)\s+(mg/dL)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "LDL CHOLESTEROL",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[3],
                refLow = JsonNull,
                refHigh = numLiteral(m.groupValues[2]),
            )
        }

        // Row 5: CHOL/HDLC RATIO 2.6 < OR = 5.0 (calc) EN  (Python line 629, units=null)
        Regex("""CHOL/HDLC\s+RATIO\s+($NUM)\s+<\s*OR\s*=\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "CHOL/HDLC RATIO",
                value = numLiteral(m.groupValues[1]),
                units = null,
                refLow = JsonNull,
                refHigh = numLiteral(m.groupValues[2]),
            )
        }

        // Row 6: NON HDL CHOLESTEROL 93 mg/dL (calc) EN  (Python line 638, no range)
        Regex("""NON\s*HDL\s*CHOLESTEROL\s+($NUM)\s+(mg/dL)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "NON HDL CHOLESTEROL",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = JsonNull,
                refHigh = JsonNull,
            )
        }

        return rows
    }
}
