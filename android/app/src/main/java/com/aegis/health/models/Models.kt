package com.aegis.health.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

@Serializable
data class Flag(
    val severity: Int,
    val description: String,
    val citation: String,
)

@Serializable
data class Citation(
    val source: String,
    val text: String,
)

@Serializable
data class AegisResponse(
    val confidence: Double = 0.0,
    val defer_to_professional: Boolean = false,
    val flags: List<Flag> = emptyList(),
    val citations: List<Citation> = emptyList(),
    val explanation: String = "",
)

@Serializable
data class DrugInfo(
    val generic_name: String,
    val rxcui: String,
    val category: String,
)

@Serializable
data class ProductDecomposition(
    val product: String,
    val ingredients: List<DrugInfo>,
    val citation: String,
)

@Serializable
data class TermDefinition(
    val term: String,
    val plain_language_definition: String,
    val citation: String,
)

@Serializable
data class GuidelineRecommendation(
    val title: String,
    val grade: String,
    val description: String,
    val population: String,
    val citation: String,
)

@Serializable
data class ToolCall(
    val name: String,
    val arguments: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
)

@Serializable
data class ToolResult(
    val name: String,
    val result: String,
)

@Serializable
data class HealthProfile(
    val name: String? = null,
    val age: Int? = null,
    val sex: String? = null,
    val conditions: List<String> = emptyList(),
    val medications: List<String> = emptyList(),
    val familyHistory: List<String> = emptyList(),
)

// ============================================================================
// Phase 2 — ReportReader: PreparsedReport + tool result types (D-03, D-10)
// ============================================================================

/** KB reference-range row returned by lookup_lab_reference_range tool. */
@Serializable
data class LabReferenceRange(
    val test_name: String,
    val ref_low: Double? = null,
    val ref_high: Double? = null,
    val units: String,
    val population: String,
    val citation: String,
)

/** Tool result envelope for LookupLabReferenceRange (mirror LookupTermResult shape). */
@Serializable
data class LookupLabReferenceRangeResult(
    val range: LabReferenceRange? = null,
    val error: String? = null,
)

/** Tool result envelope for ExplainLabTest. */
@Serializable
data class ExplainLabTestResult(
    val test_name: String? = null,
    val plain_language_definition: String? = null,
    val citation: String? = null,
    val error: String? = null,
)

/** D-10 status envelope on every PreparsedReport. */
@Serializable
data class ReportStatus(
    val code: String,
    val message: String? = null,
)

/**
 * PreparsedReport.citations[] entry. Shape: { label, url }.
 *
 * NB: distinct from the existing Citation class above which uses (source, text)
 * for AegisResponse.citations[]. These serve different consumers — PreparsedReport
 * feeds the synthesis turn and the byte-identical test gate; AegisResponse.Citation
 * is the model's output format. See PATTERNS.md LM-5 + Group D ReportAssembler note.
 */
@Serializable
data class LabCitation(
    val label: String,
    val url: String,
)

/** Patient demographics extracted from PDF cover sheet (INPUT-02). */
@Serializable
data class Profile(
    val age: Int? = null,
    val sex: String? = null,
)

/**
 * EvaluatedRow — one per extracted lab analyte after RangeEvaluator runs.
 *
 * LM-3 (D-07): value, ref_low, ref_high are JsonElement (JsonPrimitive int OR
 * float OR JsonNull) to preserve numeric type fidelity against the live GT JSONs.
 * Widening to Double here would canonicalize 151 → 151.0 and break byte-identity.
 *
 * D-12: defer_reason is a stable short-code string when status='unknown'; null
 * for IN_RANGE / BORDERLINE / OUTSIDE_RANGE rows. See EXTRACTION-SPEC.md for the
 * 9-entry vocabulary.
 */
@Serializable
data class EvaluatedRow(
    val canonical_name: String,
    val raw_name: String,
    val value: JsonElement = JsonNull,
    val units: String? = null,
    val ref_low: JsonElement = JsonNull,
    val ref_high: JsonElement = JsonNull,
    val ref_source: String,
    val status: String,
    val definition: String? = null,
    val definition_citation: String? = null,
    val defer_reason: String? = null,
)

/**
 * PreparsedReport — the structured artifact produced by the Phase 2 Kotlin
 * pipeline and consumed by Phase 4's synthesis turn. Matches
 * .planning/specs/PreparsedReport.schema.json after the Plan 02-01 update.
 *
 * Byte-identical against the 5 ground-truth JSONs at
 * eval/fixtures/lab_reports/{vendor}/{vendor}-evaluated.json is the Phase 2 exit gate
 * (Wave 4 androidTest LabReportPipelineFixtureTest).
 */
@Serializable
data class PreparsedReport(
    val rows: List<EvaluatedRow> = emptyList(),
    val has_outside_range: Boolean = false,
    val has_unknown: Boolean = false,
    val profile_used: Profile = Profile(),
    val citations: List<LabCitation> = emptyList(),
    val report_status: ReportStatus = ReportStatus(code = "OK"),
)
