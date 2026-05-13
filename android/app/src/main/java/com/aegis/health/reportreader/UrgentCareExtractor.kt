package com.aegis.health.reportreader

import kotlinx.serialization.json.JsonNull

/**
 * Phase 2 — Urgent-care A1C extractor (2-row report).
 *
 * Cross-language port of `tools/parsers/lab_report_parser.py:_parse_urgent_care`
 * (lines 1019-1043). 2 rows in row-for-row order matching the GT JSON at
 * `eval/fixtures/lab_reports/urgent_care/urgent_care_a1c-evaluated.json`:
 *
 *  1. Hemoglobin A1c             (bilateral range, optional High/Low flag)
 *  2. Estim. Avg Glu (eAG)       (no range; refLow=refHigh=null)
 *
 * LM-W: the eAG row has a stray watermark "k" prefixed to the value
 * ("k180" instead of "180"); the regex tolerates this via `[a-z]?\s*`
 * between the label and value capture.
 *
 * Page-1 fingerprint: "hgb a1c" OR ("hemoglobin a1c" + "eag") on lowercased
 * page 1 (mirrors Python `_detect_vendor:550`).
 */
object UrgentCareExtractor : VendorExtractor {
    override val vendorKey: String = "urgent_care"

    override fun fingerprintMatches(page1Lower: String): Boolean =
        "hgb a1c" in page1Lower ||
            ("hemoglobin a1c" in page1Lower && "eag" in page1Lower)

    override fun extract(pagesText: List<String>): List<ParsedRow> {
        val text = pagesText.joinToString("\n")
        val rows = mutableListOf<ParsedRow>()

        // Row 1: Hemoglobin A1c 7.9 High % 4.8 - 5.6  (Python line 1026, optional High/Low flag)
        Regex("""Hemoglobin\s+A1c\s+($NUM)\s+(?:High|Low)?\s*(%)\s+($NUM)\s*-\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "Hemoglobin A1c",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = numLiteral(m.groupValues[3]),
                refHigh = numLiteral(m.groupValues[4]),
            )
        }

        // Row 2: Estim. Avg Glu (eAG) k180 mg/dL  -- LM-W: watermark "k" prefixes value (Python line 1035)
        Regex("""Estim\.?\s*Avg\s*Glu\s*\(eAG\)\s+[a-z]?\s*($NUM)\s+(mg/dL)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "Estim. Avg Glu (eAG)",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = JsonNull,
                refHigh = JsonNull,
            )
        }

        return rows
    }
}
