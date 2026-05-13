package com.aegis.health.tools

import com.aegis.health.db.KBDatabase
import com.aegis.health.models.ExplainLabTestResult
import com.aegis.health.reportreader.DefinitionDb

/**
 * Phase 2 — ReportReader: plain-language test-name explanation (D-01).
 *
 * D-08: Phase 2 reads from the bundled DefinitionDb constant (mirrors Python
 * lab_report_parser._DEFINITION_DB byte-for-byte; cross-language consistency
 * test enforces parity in Wave 4). Phase 4 EXPLAIN-01 may rewire this to
 * query the `terms` KB table once the lookup_term schema mismatch is triaged
 * (see 01-06-DEVIATIONS.md). The public signature stays stable; only the body
 * changes.
 *
 * @param testName canonical lab test name (LabRowNormalizer output). Callers
 *                 from RangeEvaluator/ReportAssembler will already have
 *                 normalized.
 * @param db unused in Phase 2 (kept in signature for Phase 4 forward-compat).
 */
object ExplainLabTest {
    @Suppress("UNUSED_PARAMETER")
    fun lookup(testName: String, db: KBDatabase): ExplainLabTestResult {
        if (testName.isBlank()) {
            return ExplainLabTestResult(error = "Empty test_name provided")
        }
        val entry = DefinitionDb.lookup(testName.trim())
            ?: return ExplainLabTestResult(
                error = "No plain-language explanation for '${testName.trim()}'",
            )
        return ExplainLabTestResult(
            test_name = testName.trim(),
            plain_language_definition = entry.definition,
            citation = entry.citationUrl,
        )
    }
}
