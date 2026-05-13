package com.aegis.health.spike

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.json.JSONObject
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PdfBox-Android coordinate-fidelity spike (Phase 1, Plan 08).
 *
 * For each of the 5 Plan-07 vendor fixtures, load the PDF, run PdfBox-Android
 * column-cluster extraction (or a stub starting point), produce a candidate
 * PreparsedReport, and emit per-vendor row-error percent vs ground truth.
 *
 * Results are written to Logcat (tag "PdfBoxSpike"); the spike author copies
 * the numbers into SPIKE-PDFBOX.md (Task 2).
 *
 * Exit criterion (D-09): GO if all 5 vendors <5% row error.
 */
@RunWith(AndroidJUnit4::class)
class PdfBoxSpike {

    companion object {
        @BeforeClass
        @JvmStatic
        fun initPdfBox() {
            // STACK.md line 27 open question — verify the answer in this spike.
            PDFBoxResourceLoader.init(
                InstrumentationRegistry.getInstrumentation().context
            )
        }
    }

    @Test
    fun spike_all_vendors_extract_and_diff_vs_ground_truth() {
        val tag = "PdfBoxSpike"
        val rows = StringBuilder()
        rows.append("vendor,total_gt_rows,rows_correct,row_error_pct\n")

        for (vendor in FixtureLoader.vendors) {
            val pdfStream = FixtureLoader.pdfStream(vendor)
            val candidateReport = extractFromPdfBox(pdfStream)
            pdfStream.close()

            val groundTruth = JSONObject(FixtureLoader.groundTruthJson(vendor))
            val gtRows = groundTruth.getJSONArray("rows")
            val (correct, errorPct) = diffRows(candidateReport, gtRows)

            android.util.Log.i(tag, "$vendor: gt=${gtRows.length()} correct=$correct error_pct=$errorPct")
            rows.append("$vendor,${gtRows.length()},$correct,$errorPct\n")
        }

        android.util.Log.i(tag, "=== SPIKE RESULTS CSV ===\n$rows")
    }

    /**
     * Coordinate-cluster extraction stub. The starting point uses PDFTextStripper
     * for text-only; replace/extend with a PDFTextStripper subclass that overrides
     * writeString(String, List<TextPosition>) to record (x, y) per glyph, then
     * cluster rows by y-band (~3pt) and columns by x-band.
     *
     * For the spike, the simplest stub is: run PDFTextStripper, regex out rows that
     * match `<test_name>\s+<value>\s+<units>\s+<range>` per line. This gives us the
     * floor on what a brute-force text approach captures; the column-cluster impl
     * provides the upper bound.
     */
    private fun extractFromPdfBox(stream: java.io.InputStream): JSONObject {
        val doc = PDDocument.load(stream)
        try {
            val text = PDFTextStripper().getText(doc)
            // TODO(spike): replace with coordinate-clustering approach. For now, a
            // minimal text-line regex provides a baseline.
            val rows = parseTextLines(text)
            return JSONObject().apply {
                put("rows", rows)
                put("has_outside_range", false)
                put("has_unknown", false)
                put("profile_used", JSONObject().apply { put("age", JSONObject.NULL); put("sex", JSONObject.NULL) })
                put("citations", org.json.JSONArray())
            }
        } finally {
            doc.close()
        }
    }

    private fun parseTextLines(text: String): org.json.JSONArray {
        val arr = org.json.JSONArray()
        // Stub regex; spike author replaces with coordinate-clustering implementation.
        val pattern = Regex("""^\s*([A-Za-z][A-Za-z\- ]+?)\s+(\d+\.?\d*)\s+(\S+)\s+(\d+\.?\d*-\d+\.?\d*|<\d+\.?\d*|>\d+\.?\d*)\s*$""", RegexOption.MULTILINE)
        for (m in pattern.findAll(text)) {
            arr.put(JSONObject().apply {
                put("canonical_name", m.groupValues[1].trim())
                put("raw_name", m.groupValues[1].trim())
                put("value", m.groupValues[2].toDouble())
                put("units", m.groupValues[3])
                put("ref_low", JSONObject.NULL)
                put("ref_high", JSONObject.NULL)
                put("ref_source", "report")
                put("status", "IN_RANGE")  // spike does not compute status yet
                put("definition", JSONObject.NULL)
                put("definition_citation", JSONObject.NULL)
            })
        }
        return arr
    }

    /**
     * Row-error computation: a row is "correct" iff its canonical_name (case-insensitive)
     * matches a ground-truth row AND its value rounds to the ground-truth value within
     * 0.01 absolute tolerance AND its units string matches case-insensitively.
     */
    private fun diffRows(candidate: JSONObject, gtRows: org.json.JSONArray): Pair<Int, Double> {
        val candRows = candidate.getJSONArray("rows")
        var correct = 0
        for (i in 0 until gtRows.length()) {
            val gt = gtRows.getJSONObject(i)
            val gtName = gt.getString("canonical_name").lowercase()
            val gtValue = if (gt.isNull("value")) null else gt.getDouble("value")
            val gtUnits = if (gt.isNull("units")) null else gt.getString("units").lowercase()

            for (j in 0 until candRows.length()) {
                val c = candRows.getJSONObject(j)
                val cName = c.optString("canonical_name").lowercase()
                if (cName != gtName) continue
                val cValue = if (c.isNull("value")) null else c.getDouble("value")
                val cUnits = if (c.isNull("units")) null else c.getString("units").lowercase()
                val valueOk = (cValue == null && gtValue == null) || (cValue != null && gtValue != null && kotlin.math.abs(cValue - gtValue) < 0.01)
                val unitsOk = cUnits == gtUnits
                if (valueOk && unitsOk) {
                    correct += 1
                    break
                }
            }
        }
        val total = gtRows.length()
        val errorPct = if (total == 0) 0.0 else 100.0 * (total - correct) / total
        return correct to errorPct
    }
}
