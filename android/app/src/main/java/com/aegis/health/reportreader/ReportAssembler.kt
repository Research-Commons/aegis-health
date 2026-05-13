package com.aegis.health.reportreader

import com.aegis.health.models.EvaluatedRow
import com.aegis.health.models.LabCitation
import com.aegis.health.models.PreparsedReport
import com.aegis.health.models.Profile
import com.aegis.health.models.ReportStatus

/**
 * Phase 2 — Stage 5: assemble PreparsedReport.
 *
 * Mirrors Python tools/parsers/lab_report_parser.py:_assemble_report
 * (lines 1278-1327) byte-for-byte.
 *
 * LM-5 (citation dedup): Citations are deduplicated by canonical_name,
 * NOT by (label, url) pair. LabCorp's GT JSON has TWO entries with the
 * same "MedlinePlus: Cholesterol Levels" label — one from
 * canonical="cholesterol ratio", one from canonical="non-HDL cholesterol".
 * Both are preserved because the dedup set keys on canonical_name (which
 * differs), not on label. Conversely, Quest's GT has only 18 citations
 * for 19 rows because its 2 eGFR rows share the same canonical_name and
 * collapse to a single entry.
 *
 * Citations are sorted alphabetically by label after dedup (matches Python
 * `citations.sort(key=lambda c: c["label"])`). Duplicate labels from
 * distinct canonicals therefore appear adjacent in the sorted list.
 *
 * D-10: every PreparsedReport carries a top-level report_status envelope.
 *   - status_code="OK"                 → happy path, rows populated
 *   - status_code="IMAGE_ONLY"         → EXTRACT-03 short-circuit (Stage 1)
 *   - status_code="UNKNOWN_VENDOR"     → EXTRACT-01 short-circuit (Stage 2)
 *   - status_code="TOO_MANY_ANALYTES"  → INTERPRET-05 short-circuit (post-Stage 3)
 *
 * For the three non-OK codes, ReportReaderPipeline passes rows=emptyList();
 * citations are therefore empty too (the dedup loop has nothing to iterate).
 * Schema invariant per D-10: report_status.code != "OK" implies rows=[] and
 * citations=[].
 */
object ReportAssembler {

    /** Happy-path assemble (status code defaults to OK). */
    fun assemble(rows: List<EvaluatedRow>, profile: Profile): PreparsedReport =
        assemble(rows = rows, profile = profile, statusCode = "OK", statusMessage = null)

    /**
     * Full assemble: supports the 3 deferral codes (IMAGE_ONLY /
     * TOO_MANY_ANALYTES / UNKNOWN_VENDOR). Pipeline orchestrator passes
     * rows=emptyList() for these paths; the GT JSON enforces rows=[] when
     * code != "OK".
     */
    fun assemble(
        rows: List<EvaluatedRow>,
        profile: Profile,
        statusCode: String,
        statusMessage: String?,
    ): PreparsedReport {
        val hasOutsideRange = rows.any { it.status == "OUTSIDE_RANGE" }
        val hasUnknown = rows.any { it.status == "unknown" }

        // LM-5: dedup by canonical_name (NOT by (label, url)), preserve
        // first-appearance order before final alpha-sort by label.
        val seenCanonicals = mutableSetOf<String>()
        val pendingCitations = mutableListOf<LabCitation>()
        for (row in rows) {
            if (row.canonical_name in seenCanonicals) continue
            val entry = DefinitionDb.lookup(row.canonical_name) ?: continue
            seenCanonicals += row.canonical_name
            pendingCitations += LabCitation(
                label = entry.citationLabel,
                url = entry.citationUrl,
            )
        }
        val citations = pendingCitations.sortedBy { it.label }

        return PreparsedReport(
            rows = rows,
            has_outside_range = hasOutsideRange,
            has_unknown = hasUnknown,
            profile_used = profile,
            citations = citations,
            report_status = ReportStatus(code = statusCode, message = statusMessage),
        )
    }
}
