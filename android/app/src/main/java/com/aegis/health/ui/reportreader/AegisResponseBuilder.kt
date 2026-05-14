package com.aegis.health.ui.reportreader

import com.aegis.health.models.AegisResponse
import com.aegis.health.models.Citation
import com.aegis.health.models.EvaluatedRow
import com.aegis.health.models.Flag
import com.aegis.health.models.LabCitation
import com.aegis.health.models.PreparsedReport
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Phase 3 D-08 forward-compat — builds the Phase-4-shape AegisResponse from a
 * PreparsedReport so the DeferralStore handoff is wire-format-stable from day
 * one. Phase 4 will swap only the `explanation` field for model prose; every
 * other wire-format slot stays unchanged.
 *
 * Three entry points correspond to the three deferral CTAs in Phase 3:
 *
 *   1. `build(report)` — top SummaryCard CTA path. Flags every flagged
 *      (non-IN_RANGE) row.
 *
 *   2. `buildForRow(report, row)` — per-row Discuss CTA path (D-05).
 *      Flags exactly one row.
 *
 *   3. `buildForStatus(report, statusCode, statusMessage)` — D-06 empty-state
 *      Discuss CTA. Stages a defer-shaped response with a single Flag carrying
 *      the status code/message. `defer_to_professional` is always true here.
 *
 * All three entry points produce the same FIXED_EXPLANATION string. Phase 4
 * replaces this constant's usage with model prose via runReportReaderFastPath.
 */
object AegisResponseBuilder {

    /**
     * D-08 explanation field — Phase 4 string-swap target.
     * Phase 4's runReportReaderFastPath replaces this with model prose AFTER
     * enforceModeContract has re-emitted flags. No other wire-format slot
     * changes between Phase 3 and Phase 4.
     */
    const val FIXED_EXPLANATION =
        "Bring this to your clinician to discuss any flagged values."

    /** Top SummaryCard CTA — flags every flagged row in the report. */
    fun build(report: PreparsedReport): AegisResponse =
        buildFor(
            report = report,
            focusRows = report.rows.filter { it.status != "IN_RANGE" },
        )

    /** Per-row Discuss CTA — flags exactly one row. */
    fun buildForRow(report: PreparsedReport, row: EvaluatedRow): AegisResponse =
        buildFor(report = report, focusRows = listOf(row))

    /**
     * D-06 empty-state Discuss CTA — non-OK report_status path.
     * Always defers; carries a single Flag with the status code/message.
     */
    fun buildForStatus(
        report: PreparsedReport,
        statusCode: String,
        statusMessage: String?,
    ): AegisResponse {
        val description = statusMessage?.takeIf { it.isNotBlank() }
            ?: "Report could not be read ($statusCode). Discuss with your clinician."
        val flag = Flag(
            severity = severityForStatusCode(statusCode),
            description = description,
            citation = "",
        )
        return AegisResponse(
            confidence = 0.0,
            defer_to_professional = true,
            flags = listOf(flag),
            citations = report.citations.map { it.toCitation() },
            explanation = FIXED_EXPLANATION,
        )
    }

    // ── Internals ────────────────────────────────────────────────────

    private fun buildFor(
        report: PreparsedReport,
        focusRows: List<EvaluatedRow>,
    ): AegisResponse {
        val flags = focusRows.map { row ->
            Flag(
                severity = severityForStatus(row.status),
                description = flagMessage(row),
                citation = row.definition_citation.orEmpty(),
            )
        }
        return AegisResponse(
            confidence = 0.0,
            defer_to_professional = report.has_outside_range || report.has_unknown,
            flags = flags,
            citations = report.citations.map { it.toCitation() },
            explanation = FIXED_EXPLANATION,
        )
    }

    /** Severity mapping per D-08 / PATTERNS.md §9. */
    private fun severityForStatus(status: String): Int = when (status) {
        "OUTSIDE_RANGE" -> 4
        "BORDERLINE"    -> 3
        "unknown"       -> 2
        else            -> 1
    }

    /**
     * Severity for non-OK report_status — TOO_MANY_ANALYTES, IMAGE_ONLY, and
     * UNKNOWN_VENDOR all surface as severity 2 (review) since they don't
     * carry a row-level value to flag.
     *
     * WR-03: collapsed from a dead `when` block (every branch returned 2) to
     * a constant. The previous shape risked drift — if Phase 4 changes one
     * branch but forgets the others, no test fixture would catch it. Phase 4
     * can re-introduce per-code stratification with a unit test asserting the
     * mapping, making the placeholder/non-placeholder boundary visible.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun severityForStatusCode(statusCode: String): Int = 2

    /**
     * Per-row flag description string. Per D-08 field table:
     *   - For OUTSIDE_RANGE / BORDERLINE: `canonical_name value units outside printed range`
     *   - For unknown: `canonical_name — <DeferReasonCopy.lookup(defer_reason)>`
     */
    private fun flagMessage(row: EvaluatedRow): String {
        val value = (row.value as? JsonPrimitive)?.contentOrNull
        val units = row.units?.takeIf { it.isNotBlank() } ?: ""
        return when (row.status) {
            "unknown" -> {
                val reasonText = row.defer_reason?.let { DeferReasonCopy.lookup(it) }
                    ?: "Discuss with your doctor."
                "${row.canonical_name} — $reasonText"
            }
            else -> {
                // OUTSIDE_RANGE / BORDERLINE / fallback path.
                val valueText = listOfNotNull(value, units.takeIf { it.isNotEmpty() })
                    .joinToString(" ")
                if (valueText.isNotEmpty()) {
                    "${row.canonical_name}: $valueText — outside printed range"
                } else {
                    "${row.canonical_name} — outside printed range"
                }
            }
        }
    }

    /** LabCitation → Citation mapping per D-08 row 3. */
    private fun LabCitation.toCitation(): Citation =
        Citation(source = this.label, text = this.url)
}
