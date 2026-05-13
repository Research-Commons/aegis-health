package com.aegis.health.reportreader

import kotlinx.serialization.json.JsonNull

/**
 * Phase 2 — Mayo / Indian-style CBC (Complete Blood Count) extractor.
 *
 * Cross-language port of `tools/parsers/lab_report_parser.py:_parse_mayo`
 * (lines 815-936). 13 rows in row-for-row order matching the GT JSON at
 * `eval/fixtures/lab_reports/mayo/mayo_cbc-evaluated.json`.
 *
 * Mayo-specific quirks:
 * - "TEST [FLAG] VALUE UNIT REF_LOW - REF_HIGH" column order (unit precedes range).
 * - Thousand-separators in some numbers (e.g. "5,100"); the dedicated
 *   `NUM_COMMA` pattern captures these, and `numLiteral` strips commas
 *   before parsing.
 * - Some rows carry an optional single-letter flag between name and value
 *   (e.g. "LYMPHOCYTE L 18" — `[A-Z]?\s*` tolerance).
 *
 * Page-1 fingerprint: "complete blood count" OR "haematology" OR "hematology"
 * on lowercased page 1 (mirrors Python `_detect_vendor:544`).
 */
object MayoExtractor : VendorExtractor {
    override val vendorKey: String = "mayo"

    override fun fingerprintMatches(page1Lower: String): Boolean =
        "complete blood count" in page1Lower ||
            "haematology" in page1Lower ||
            "hematology" in page1Lower

    /** Numeric token that allows comma thousand-separators; numLiteral strips them. */
    private const val NUM_COMMA = """\d[\d,]*(?:\.\d+)?"""

    override fun extract(pagesText: List<String>): List<ParsedRow> {
        val text = pagesText.joinToString("\n")
        val rows = mutableListOf<ParsedRow>()

        // Row 1: HEMOGLOBIN 15 g/dl 13 - 17  (Python line 821)
        Regex("""HEMOGLOBIN\s+($NUM)\s+(g/dl)\s+($NUM)\s*-\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "HEMOGLOBIN",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = numLiteral(m.groupValues[3]),
                refHigh = numLiteral(m.groupValues[4]),
            )
        }

        // Row 2: TOTAL LEUKOCYTE COUNT 5,100 cumm 4,800 - 10,800  (Python line 830, comma in numbers)
        Regex("""TOTAL\s+LEUKOCYTE\s+COUNT\s+($NUM_COMMA)\s+(cumm)\s+($NUM_COMMA)\s*-\s*($NUM_COMMA)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "TOTAL LEUKOCYTE COUNT",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = numLiteral(m.groupValues[3]),
                refHigh = numLiteral(m.groupValues[4]),
            )
        }

        // Row 3: NEUTROPHILS 79 % 40 - 80  (Python line 841)
        Regex("""NEUTROPHILS\s+($NUM)\s+(%)\s+($NUM)\s*-\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "NEUTROPHILS",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = numLiteral(m.groupValues[3]),
                refHigh = numLiteral(m.groupValues[4]),
            )
        }

        // Row 4: LYMPHOCYTE L 18 % 20 - 40  (Python line 850, optional flag letter)
        Regex("""LYMPHOCYTE\s+[A-Z]?\s*($NUM)\s+(%)\s+($NUM)\s*-\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "LYMPHOCYTE",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = numLiteral(m.groupValues[3]),
                refHigh = numLiteral(m.groupValues[4]),
            )
        }

        // Row 5: EOSINOPHILS 1 % 1 - 6  (Python line 859)
        Regex("""EOSINOPHILS\s+($NUM)\s+(%)\s+($NUM)\s*-\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "EOSINOPHILS",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = numLiteral(m.groupValues[3]),
                refHigh = numLiteral(m.groupValues[4]),
            )
        }

        // Row 6: MONOCYTES L 1 % 2 - 10  (Python line 868, optional flag letter)
        Regex("""MONOCYTES\s+[A-Z]?\s*($NUM)\s+(%)\s+($NUM)\s*-\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "MONOCYTES",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = numLiteral(m.groupValues[3]),
                refHigh = numLiteral(m.groupValues[4]),
            )
        }

        // Row 7: BASOPHILS 1 % < 2  (Python line 877, single-sided high)
        Regex("""BASOPHILS\s+($NUM)\s+(%)\s+<\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "BASOPHILS",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = JsonNull,
                refHigh = numLiteral(m.groupValues[3]),
            )
        }

        // Row 8: PLATELET COUNT 3.5 lakhs/cumm 1.5 - 4.1  (Python line 883)
        Regex("""PLATELET\s+COUNT\s+($NUM)\s+(lakhs/cumm)\s+($NUM)\s*-\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "PLATELET COUNT",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = numLiteral(m.groupValues[3]),
                refHigh = numLiteral(m.groupValues[4]),
            )
        }

        // Row 9: TOTAL RBC COUNT 5 million/cumm 4.5 - 5.5  (Python line 892)
        Regex("""TOTAL\s+RBC\s+COUNT\s+($NUM)\s+(million/cumm)\s+($NUM)\s*-\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "TOTAL RBC COUNT",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = numLiteral(m.groupValues[3]),
                refHigh = numLiteral(m.groupValues[4]),
            )
        }

        // Row 10: HEMATOCRIT VALUE, HCT 42 % 40 - 50  (Python line 901)
        Regex("""HEMATOCRIT\s+VALUE,?\s*HCT\s+($NUM)\s+(%)\s+($NUM)\s*-\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "HEMATOCRIT VALUE, HCT",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = numLiteral(m.groupValues[3]),
                refHigh = numLiteral(m.groupValues[4]),
            )
        }

        // Row 11: MEAN CORPUSCULAR VOLUME, MCV 84.0 fL 83 - 101  (Python line 910)
        Regex("""MEAN\s+CORPUSCULAR\s+VOLUME,?\s*MCV\s+($NUM)\s+(fL)\s+($NUM)\s*-\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "MEAN CORPUSCULAR VOLUME, MCV",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = numLiteral(m.groupValues[3]),
                refHigh = numLiteral(m.groupValues[4]),
            )
        }

        // Row 12: MEAN CELL HAEMOGLOBIN, MCH 30.0 Pg 27 - 32  (Python line 919)
        Regex("""MEAN\s+CELL\s+HAEMOGLOBIN,?\s*MCH\s+($NUM)\s+(Pg)\s+($NUM)\s*-\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "MEAN CELL HAEMOGLOBIN, MCH",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = numLiteral(m.groupValues[3]),
                refHigh = numLiteral(m.groupValues[4]),
            )
        }

        // Row 13: MEAN CELL HAEMOGLOBIN CON, MCHC H 35.7 % 31.5 - 34.5  (Python line 928, optional flag)
        Regex("""MEAN\s+CELL\s+HAEMOGLOBIN\s+CON,?\s*MCHC\s+[A-Z]?\s*($NUM)\s+(%)\s+($NUM)\s*-\s*($NUM)""").find(text)?.let { m ->
            rows += ParsedRow(
                rawName = "MEAN CELL HAEMOGLOBIN CON, MCHC",
                value = numLiteral(m.groupValues[1]),
                units = m.groupValues[2],
                refLow = numLiteral(m.groupValues[3]),
                refHigh = numLiteral(m.groupValues[4]),
            )
        }

        return rows
    }
}
