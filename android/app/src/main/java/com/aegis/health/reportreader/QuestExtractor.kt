package com.aegis.health.reportreader

import kotlinx.serialization.json.JsonNull

/**
 * Phase 2 — Quest Comprehensive Metabolic Panel (CMP) extractor.
 *
 * Cross-language port of `tools/parsers/lab_report_parser.py:_parse_quest`
 * (lines 649-812). 19 rows in row-for-row order matching the GT JSON at
 * `eval/fixtures/lab_reports/quest/quest_cmp-evaluated.json`.
 *
 * LM-W: the Quest fixture has stray watermark single-letter tokens
 * (`M E S A L P C ...` diagonal) interspersed into row lines. The regex
 * tolerates these via `[A-Z]*\s*` between value and flag tokens, and via
 * `[A-Z]?` directly after the value for cases like "ALBUMIN 4.3A".
 *
 * Page-1 fingerprint: "comprehensive metabolic panel" on lowercased page 1
 * (mirrors Python `_detect_vendor:541`).
 */
object QuestExtractor : VendorExtractor {
    override val vendorKey: String = "quest"

    override fun fingerprintMatches(page1Lower: String): Boolean =
        "comprehensive metabolic panel" in page1Lower

    override fun extract(pagesText: List<String>): List<ParsedRow> {
        val text = pagesText.joinToString("\n")
        val rows = mutableListOf<ParsedRow>()

        // Row 1: GLUCOSE 99 65-99 mg/dL EN  (Python line 656)
        Regex("""GLUCOSE\s+($NUM)\s+($NUM)-($NUM)\s+(mg/dL)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "GLUCOSE",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[4],
                refLow = numLiteral(m.groupValues[2]),
                refHigh = numLiteral(m.groupValues[3]),
            )
        }

        // Row 2: UREA NITROGEN (BUN) 20 7-25 mg/dL EN  (Python line 662)
        Regex("""UREA\s+NITROGEN\s+\(BUN\)\s+($NUM)\s+($NUM)-($NUM)\s+(mg/dL)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "UREA NITROGEN (BUN)",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[4],
                refLow = numLiteral(m.groupValues[2]),
                refHigh = numLiteral(m.groupValues[3]),
            )
        }

        // Row 3: CREATININE 1.44 HIGH 0.70-1.25 mg/dL EN  (Python line 671, optional HIGH/LOW flag)
        Regex("""CREATININE\s+($NUM)\s+(?:HIGH|LOW)?\s*($NUM)-($NUM)\s+(mg/dL)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "CREATININE",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[4],
                refLow = numLiteral(m.groupValues[2]),
                refHigh = numLiteral(m.groupValues[3]),
            )
        }

        // Row 4: eGFR NON-AFR. AMERICAN 51 LOW > OR = 60 mL/min/1.73m2 EN  (Python line 680, single-sided)
        Regex("""eGFR\s+NON-AFR\.\s+AMERICAN\s+($NUM)\s+(?:HIGH|LOW)?\s*>\s*OR\s*=\s*($NUM)\s+(mL/min/1\.73m2)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "eGFR NON-AFR. AMERICAN",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[3],
                refLow = numLiteral(m.groupValues[2]),
                refHigh = JsonNull,
            )
        }

        // Row 5: eGFR AFRICAN AMERICAN 59 LBOW > OR = 60 mL/min/1.73m2 EN
        // LM-W: "LBOW" = stray watermark; tolerate any letters between value and flag (Python line 690)
        Regex("""eGFR\s+AFRICAN\s+AMERICAN\s+($NUM)\s+[A-Z]*\s*>\s*OR\s*=\s*($NUM)\s+(mL/min/1\.73m2)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "eGFR AFRICAN AMERICAN",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[3],
                refLow = numLiteral(m.groupValues[2]),
                refHigh = JsonNull,
            )
        }

        // Row 6: BUN/CSREATININE RATIO 14 6-22 (calc) EN  -- LM-W: watermark inserts 'S' into name (Python line 699)
        Regex("""BUN/C?S?REATININE\s+RATIO\s+($NUM)\s+($NUM)-($NUM)\s+\(calc\)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "BUN/CREATININE RATIO",
                value = numLiteral(m.groupValues[1]),
                units = null,
                refLow = numLiteral(m.groupValues[2]),
                refHigh = numLiteral(m.groupValues[3]),
            )
        }

        // Row 7: SODIUM 142 135-146 mmol/L EN  (Python line 708)
        Regex("""SODIUM\s+($NUM)\s+($NUM)-($NUM)\s+(mmol/L)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "SODIUM",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[4],
                refLow = numLiteral(m.groupValues[2]),
                refHigh = numLiteral(m.groupValues[3]),
            )
        }

        // Row 8: POTASSIUM 4.2 3.5-5.3 mmol/L EN  (Python line 713)
        Regex("""POTASSIUM\s+($NUM)\s+($NUM)-($NUM)\s+(mmol/L)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "POTASSIUM",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[4],
                refLow = numLiteral(m.groupValues[2]),
                refHigh = numLiteral(m.groupValues[3]),
            )
        }

        // Row 9: CHLORIDE 104 98-110 mmol/L EN  (Python line 719)
        Regex("""CHLORIDE\s+($NUM)\s+($NUM)-($NUM)\s+(mmol/L)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "CHLORIDE",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[4],
                refLow = numLiteral(m.groupValues[2]),
                refHigh = numLiteral(m.groupValues[3]),
            )
        }

        // Row 10: CARBON DIOXIDE 29 19-30 mmol/L EN  (Python line 725)
        Regex("""CARBON\s+DIOXIDE\s+($NUM)\s+($NUM)-($NUM)\s+(mmol/L)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "CARBON DIOXIDE",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[4],
                refLow = numLiteral(m.groupValues[2]),
                refHigh = numLiteral(m.groupValues[3]),
            )
        }

        // Row 11: CALCIUM 9.5 8.6-10.3 mg/dL EN  (Python line 734)
        Regex("""CALCIUM\s+($NUM)\s+($NUM)-($NUM)\s+(mg/dL)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "CALCIUM",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[4],
                refLow = numLiteral(m.groupValues[2]),
                refHigh = numLiteral(m.groupValues[3]),
            )
        }

        // Row 12: PROTEIN, TOTAL 6.6 6.1-8.1 g/dL EEN  (Python line 740, watermark E intrudes into 'EN')
        Regex("""PROTEIN,\s*TOTAL\s+($NUM)\s+($NUM)-($NUM)\s+(g/dL)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "PROTEIN, TOTAL",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[4],
                refLow = numLiteral(m.groupValues[2]),
                refHigh = numLiteral(m.groupValues[3]),
            )
        }

        // Row 13: ALBUMIN 4.3A 3.6-5.1 g/dL EN  -- LM-W: watermark A clings to value (Python line 749)
        Regex("""\bALBUMIN\s+($NUM)[A-Z]?\s+($NUM)-($NUM)\s+(g/dL)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "ALBUMIN",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[4],
                refLow = numLiteral(m.groupValues[2]),
                refHigh = numLiteral(m.groupValues[3]),
            )
        }

        // Row 14: GLOBULIN 2.3 1.9-3.7 g/dL (calc) EN  (Python line 758)
        Regex("""\bGLOBULIN\s+($NUM)\s+($NUM)-($NUM)\s+(g/dL)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "GLOBULIN",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[4],
                refLow = numLiteral(m.groupValues[2]),
                refHigh = numLiteral(m.groupValues[3]),
            )
        }

        // Row 15: ALBUMIN/GLOBULIN RATIO 1.9 1.0-2.5 (calc)LEN  -- units=null (Python line 767)
        Regex("""ALBUMIN/GLOBULIN\s+RATIO\s+($NUM)\s+($NUM)-($NUM)\s+\(calc\)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "ALBUMIN/GLOBULIN RATIO",
                value = numLiteral(m.groupValues[1]),
                units = null,
                refLow = numLiteral(m.groupValues[2]),
                refHigh = numLiteral(m.groupValues[3]),
            )
        }

        // Row 16: BILIRUBIN, TOTAL 0.5 0.2-1.2 mg/dL EN  (Python line 776)
        Regex("""BILIRUBIN,\s*TOTAL\s+($NUM)\s+($NUM)-($NUM)\s+(mg/dL)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "BILIRUBIN, TOTAL",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[4],
                refLow = numLiteral(m.groupValues[2]),
                refHigh = numLiteral(m.groupValues[3]),
            )
        }

        // Row 17: ALKALINE PHOSPHATASE 66 40-115 U/L EN  (Python line 785)
        Regex("""ALKALINE\s+PHOSPHATASE\s+($NUM)\s+($NUM)-($NUM)\s+(U/L)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "ALKALINE PHOSPHATASE",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[4],
                refLow = numLiteral(m.groupValues[2]),
                refHigh = numLiteral(m.groupValues[3]),
            )
        }

        // Row 18: AST 14 10-35 U/L EN  (Python line 795, negative lookbehind to avoid matching 'PAST'/etc.)
        Regex("""(?<![A-Z])AST\s+($NUM)\s+($NUM)-($NUM)\s+(U/L)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "AST",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[4],
                refLow = numLiteral(m.groupValues[2]),
                refHigh = numLiteral(m.groupValues[3]),
            )
        }

        // Row 19: ALT 18 9-46 U/L EN  (Python line 804, negative lookbehind)
        Regex("""(?<![A-Z])ALT\s+($NUM)\s+($NUM)-($NUM)\s+(U/L)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "ALT",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[4],
                refLow = numLiteral(m.groupValues[2]),
                refHigh = numLiteral(m.groupValues[3]),
            )
        }

        return rows
    }
}
