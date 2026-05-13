package com.aegis.health.tools

import com.aegis.health.db.KBDatabase
import com.aegis.health.models.LabReferenceRange
import com.aegis.health.models.LookupLabReferenceRangeResult

/**
 * Phase 2 — ReportReader: KB reference-range lookup tool (D-01).
 *
 * Mirrors tools/tools/lookup_lab_reference_range.py (Python reference). Used
 * by RangeEvaluator (Wave 3) as the KB-fallback path when the PDF didn't print
 * its own reference range (INTERPRET-02).
 *
 * Surface shape mirrors LookupTerm.kt exactly (D-01).
 */
object LookupLabReferenceRange {
    fun lookup(
        testName: String,
        age: Int? = null,
        sex: String? = null,
        db: KBDatabase,
    ): LookupLabReferenceRangeResult {
        if (testName.isBlank()) {
            return LookupLabReferenceRangeResult(error = "Empty test_name provided")
        }
        val row = db.queryLabReferenceRange(testName, age, sex)
            ?: return LookupLabReferenceRangeResult(
                error = "No reference range for '${testName.trim()}' in KB",
            )
        return LookupLabReferenceRangeResult(
            range = LabReferenceRange(
                test_name = row["test_name"] ?: testName.trim(),
                ref_low   = row["ref_low"]?.toDoubleOrNull(),
                ref_high  = row["ref_high"]?.toDoubleOrNull(),
                units     = row["units"] ?: "",
                population = row["population"] ?: "adult",
                citation  = row["citation"] ?: "",
            ),
        )
    }
}
