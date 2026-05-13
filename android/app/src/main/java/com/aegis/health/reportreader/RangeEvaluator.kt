package com.aegis.health.reportreader

import com.aegis.health.db.KBQueries
import com.aegis.health.models.EvaluatedRow
import com.aegis.health.models.Profile
import com.aegis.health.tools.LookupLabReferenceRange
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Phase 2 - Stage 4: deterministic three-state classifier (INTERPRET-01..05).
 *
 * The model is downstream of this decision (per .planning/research/ARCHITECTURE.md
 * "key inversion"). Every status emitted here is a safety verdict; flipping it
 * requires either a parser bug or a KB-data fix, never an LLM hallucination.
 *
 * Status states: IN_RANGE | BORDERLINE | OUTSIDE_RANGE | unknown.
 * unknown -> defer_reason MUST be non-null (one of the 9 D-12 short-codes):
 *   mismatched_units, missing_units, non_numeric_result, range_unavailable,
 *   kb_no_pediatric, kb_no_pregnancy,
 *   auto_defer:tumor_marker, auto_defer:genetic, auto_defer:pathology.
 *
 * BORDERLINE is emitted when the KB clinical_thresholds table has at least
 * one threshold band that brackets the value (e.g., ADA prediabetes A1C
 * 5.7-6.4, NHLBI ATP III LDL borderline_high 100-129). When clinical_thresholds
 * has no entry for the analyte, classification falls back to the binary
 * IN_RANGE/OUTSIDE_RANGE decision -- this is the common case for Phase 2's
 * 5 fixtures since most fixture analytes do not have ADA/NHLBI tiers, but the
 * logic IS wired and is tested via FakeKb injection in Plan 02-13.
 *
 * The KB row is provided by KBQueries.queryClinicalThresholds(canonicalName)
 * which KBDatabase implements over the clinical_thresholds table populated
 * by kb/kb/sources/curated_lab_ranges.py (Phase 1 source -- 13 threshold
 * rows ship today: A1C, fasting glucose, LDL, eGFR, TSH).
 *
 * Order of deferral checks mirrors tools/parsers/lab_report_parser.py:_evaluate_row
 * (lines 1096-1185): auto_defer FIRST so tumor markers etc. never emit IN_RANGE,
 * then non_numeric_result, then PDF range, then pregnancy KB, then pediatric KB,
 * then adult KB, with mismatched-units / missing-units / range-unavailable as
 * the per-tier fallbacks.
 *
 * F-02 remediation: depends on KBQueries (the JVM-clean interface from Plan 02-05),
 * NOT on the concrete KBDatabase. Plan 02-13's RangeEvaluatorTest will inject a
 * FakeKb implementation backed by in-memory Maps for unit testing without an
 * Android emulator.
 */
object RangeEvaluator {

    /** Phase 2 entry: applies INTERPRET-01..05 rules; emits IN_RANGE / BORDERLINE / OUTSIDE_RANGE / unknown. */
    fun evaluate(
        row: LabRowNormalizer.NormalizedRow,
        profile: Profile,
        isPregnant: Boolean,
        db: KBQueries,
    ): EvaluatedRow {
        val canonical = row.canonicalName
        val parsed = row.raw
        val value = parsed.value
        val pdfRefLow = parsed.refLow
        val pdfRefHigh = parsed.refHigh
        val units = parsed.units

        // (1) INTERPRET-04: KB-driven auto-defer for tumor markers / genetic / pathology.
        // Checked FIRST so a printed range never overrides the auto-defer verdict.
        val autoCategory = db.queryAutoDefer(canonical)
        if (autoCategory != null) {
            return mkRow(
                canonical = canonical, parsed = parsed,
                refLow = JsonNull, refHigh = JsonNull,
                refSource = "none", status = "unknown",
                deferReason = "auto_defer:$autoCategory",
            )
        }

        // (2) Non-numeric value path (e.g. "Negative", "Positive", "Detected").
        // Python parity: value is None -> defer with non_numeric_result. LM-3:
        // numeric tokens were already typed at the extractor stage via numLiteral()
        // so anything still JsonNull here means the PDF really did carry a
        // qualitative result.
        if (value is JsonNull) {
            return mkRow(
                canonical = canonical, parsed = parsed,
                refLow = pdfRefLow, refHigh = pdfRefHigh,
                refSource = "none", status = "unknown",
                deferReason = "non_numeric_result",
            )
        }

        // (3) INTERPRET-02: PDF range is primary. KB is fallback ONLY when
        // both PDF bounds are absent. PDF range exists -> classify against it.
        if (pdfRefLow !is JsonNull || pdfRefHigh !is JsonNull) {
            val status = classifyStatus(canonical, value, pdfRefLow, pdfRefHigh, units, db)
            return mkRow(
                canonical = canonical, parsed = parsed,
                refLow = pdfRefLow, refHigh = pdfRefHigh,
                refSource = "report", status = status,
                deferReason = null,
            )
        }

        // (4) INTERPRET-03: pregnancy-routed KB fallback FIRST when pregnancy markers fire.
        // Trimester is unknown in Phase 2 (DemographicExtractor returns Boolean only);
        // pass null so the KB query matches any trimester row or the trimester-IS-NULL
        // catch-all per kb/kb/schema.sql:218-229 (queryPregnancyRange handles the
        // CAST(? AS INTEGER) bind with empty-string sentinel).
        if (isPregnant) {
            val pregRow = db.queryPregnancyRange(canonical, trimester = null)
            if (pregRow == null) {
                return mkRow(
                    canonical = canonical, parsed = parsed,
                    refLow = JsonNull, refHigh = JsonNull,
                    refSource = "none", status = "unknown",
                    deferReason = "kb_no_pregnancy",
                )
            }
            return evaluateKbHit(canonical, parsed, pregRow, units, value, refSource = "kb-fallback", db = db)
        }

        // (5) INTERPRET-03: pediatric-routed KB fallback when age<18.
        // Pediatric miss returns null (NOT silent adult-fallback) per Plan 02-05's
        // intentional divergence from Python decision 12 -- this is what enables
        // the kb_no_pediatric defer_reason to fire instead of silently mixing
        // pediatric and adult ranges.
        if (profile.age != null && profile.age < 18) {
            val pedRow = db.queryPediatricRange(canonical, profile.age, profile.sex)
            if (pedRow == null) {
                return mkRow(
                    canonical = canonical, parsed = parsed,
                    refLow = JsonNull, refHigh = JsonNull,
                    refSource = "none", status = "unknown",
                    deferReason = "kb_no_pediatric",
                )
            }
            return evaluateKbHit(canonical, parsed, pedRow, units, value, refSource = "kb-fallback", db = db)
        }

        // (6) Adult KB fallback via the LookupLabReferenceRange tool port (D-01).
        // Mirrors Python parser's lazy import of lookup_lab_reference_range
        // (tools/parsers/lab_report_parser.py:1143-1153). The tool returns a
        // typed envelope (range or error); we extract range bounds if present.
        val kbResult = LookupLabReferenceRange.lookup(canonical, profile.age, profile.sex, db)
        if (kbResult.range == null) {
            // INTERPRET-05: distinguish missing-units (row has value but no units)
            // from generic range-unavailable (no units AND no KB hit). Per Python
            // lab_report_parser.py:1174-1185 the `not units` short-code applies
            // ONLY when the KB also has no entry -- otherwise units presence is
            // checked against the KB hit for mismatch.
            return mkRow(
                canonical = canonical, parsed = parsed,
                refLow = JsonNull, refHigh = JsonNull,
                refSource = "none", status = "unknown",
                deferReason = if (units.isNullOrBlank()) "missing_units" else "range_unavailable",
            )
        }

        // (7) Unit mismatch between row units and KB units -> defer. D-12 mismatched_units.
        if (!units.isNullOrBlank() && !units.equals(kbResult.range.units, ignoreCase = true)) {
            return mkRow(
                canonical = canonical, parsed = parsed,
                refLow = JsonNull, refHigh = JsonNull,
                refSource = "none", status = "unknown",
                deferReason = "mismatched_units",
            )
        }

        // (8) KB hit, units agree (or row units absent) -> classify against KB range.
        // LM-3: re-wrap the LabReferenceRange.ref_low/ref_high (Double?) back to
        // JsonElement so EvaluatedRow.ref_low / ref_high stay typed-numeric.
        val kbLow: JsonElement = kbResult.range.ref_low?.let { JsonPrimitive(it) } ?: JsonNull
        val kbHigh: JsonElement = kbResult.range.ref_high?.let { JsonPrimitive(it) } ?: JsonNull
        val status = classifyStatus(canonical, value, kbLow, kbHigh, units, db)
        return mkRow(
            canonical = canonical, parsed = parsed,
            refLow = kbLow, refHigh = kbHigh,
            refSource = "kb-fallback", status = status,
            deferReason = null,
        )
    }

    /** Shared helper for KB-row evaluation (pediatric + pregnancy paths). */
    private fun evaluateKbHit(
        canonical: String,
        parsed: ParsedRow,
        kbRow: Map<String, String>,
        units: String?,
        value: JsonElement,
        refSource: String,
        db: KBQueries,
    ): EvaluatedRow {
        val kbUnits = kbRow["units"] ?: ""
        if (!units.isNullOrBlank() && kbUnits.isNotBlank() && !units.equals(kbUnits, ignoreCase = true)) {
            return mkRow(
                canonical = canonical, parsed = parsed,
                refLow = JsonNull, refHigh = JsonNull,
                refSource = "none", status = "unknown",
                deferReason = "mismatched_units",
            )
        }
        val kbLow: JsonElement = kbRow["ref_low"]?.takeIf { it.isNotBlank() }
            ?.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonNull
        val kbHigh: JsonElement = kbRow["ref_high"]?.takeIf { it.isNotBlank() }
            ?.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonNull
        val status = classifyStatus(canonical, value, kbLow, kbHigh, units, db)
        return mkRow(
            canonical = canonical, parsed = parsed,
            refLow = kbLow, refHigh = kbHigh,
            refSource = refSource, status = status,
            deferReason = null,
        )
    }

    /**
     * INTERPRET-01: three-state in/out classification -- IN_RANGE | BORDERLINE | OUTSIDE_RANGE | unknown.
     *
     * Order of checks (matches ROADMAP Phase 2 success criterion 2):
     *   1. Value parses as a number? else "unknown"
     *   2. Value outside [low, high]? -> OUTSIDE_RANGE
     *   3. KB has a clinical_thresholds band that brackets the value? -> BORDERLINE
     *   4. Else -> IN_RANGE
     *
     * clinical_thresholds tiers ("prediabetes", "diabetes", "borderline_high",
     * "high", "very_high", "extreme", "stage_2", "subclinical_hypo", etc.) ALL
     * map to BORDERLINE for Phase 2 purposes -- UI distinguishes tier severity
     * in Phase 3 / EVAL-03 -- but for the safety verdict the existence of any
     * matching threshold band means "not strictly normal, defer to clinician".
     */
    private fun classifyStatus(
        canonical: String,
        value: JsonElement,
        refLow: JsonElement,
        refHigh: JsonElement,
        units: String?,
        db: KBQueries,
    ): String {
        // LM-3: do NOT cast value to Double directly -- use the JsonPrimitive helper.
        val v = (value as? JsonPrimitive)?.doubleOrNull ?: return "unknown"
        val low = (refLow as? JsonPrimitive)?.doubleOrNull
        val high = (refHigh as? JsonPrimitive)?.doubleOrNull
        // Single-sided ranges: A1C has no low; some tests have no high.
        if (low != null && v < low) return "OUTSIDE_RANGE"
        if (high != null && v > high) return "OUTSIDE_RANGE"
        // BORDERLINE check: any clinical_thresholds band brackets v?
        if (matchesBorderlineBand(canonical, v, units, db)) return "BORDERLINE"
        return "IN_RANGE"
    }

    /**
     * Returns true if clinical_thresholds has at least one row for canonical
     * where the value v falls within [low_cutoff, high_cutoff] (inclusive on
     * both ends; nulls treated as unbounded on that side -- e.g., A1C "diabetes"
     * tier has low_cutoff=6.5 high_cutoff=NULL so any v >= 6.5 matches).
     *
     * Unit mismatch between row and threshold is a silent skip per-band (the
     * threshold table is sparsely populated; a unit mismatch typically means the
     * threshold row belongs to a different lab format). Plan 02-13 includes a
     * JVM unit test asserting BORDERLINE fires correctly for a canned A1C 5.9%
     * case via FakeKb injection.
     *
     * Graceful degradation per LM-2: implementations of KBQueries can return null
     * when the table is missing (older KB build); we treat that as "no threshold
     * available" and skip BORDERLINE, falling through to IN_RANGE.
     *
     * cursorToMap converts SQL NULL to "" (empty string) so .toDoubleOrNull()
     * returns null on missing cutoffs, which correctly represents the "unbounded"
     * side.
     */
    private fun matchesBorderlineBand(
        canonical: String,
        v: Double,
        units: String?,
        db: KBQueries,
    ): Boolean {
        val bands = db.queryClinicalThresholds(canonical) ?: return false
        for (band in bands) {
            val bandUnits = band["units"]
            if (!units.isNullOrBlank() && !bandUnits.isNullOrBlank()
                && !units.equals(bandUnits, ignoreCase = true)) continue
            val bLow = band["low_cutoff"]?.takeIf { it.isNotBlank() }?.toDoubleOrNull()
            val bHigh = band["high_cutoff"]?.takeIf { it.isNotBlank() }?.toDoubleOrNull()
            val passLow = bLow == null || v >= bLow
            val passHigh = bHigh == null || v <= bHigh
            if (passLow && passHigh) return true
        }
        return false
    }

    /**
     * Construct an EvaluatedRow with definition attached lazily via DefinitionDb.
     * Per LM-3, all numeric fields stay JsonElement to round-trip int/float.
     *
     * D-08: definition + definition_citation come from the Kotlin-bundled
     * DefinitionDb (mirrors Python _DEFINITION_DB byte-for-byte; consistency
     * verified by DefinitionDbConsistencyTest from Plan 02-06). Phase 2 does
     * NOT touch the terms KB table -- that's a Phase 4 EXPLAIN-01 concern.
     */
    private fun mkRow(
        canonical: String,
        parsed: ParsedRow,
        refLow: JsonElement,
        refHigh: JsonElement,
        refSource: String,
        status: String,
        deferReason: String?,
    ): EvaluatedRow {
        val entry = DefinitionDb.lookup(canonical)
        return EvaluatedRow(
            canonical_name = canonical,
            raw_name = parsed.rawName,
            value = parsed.value,
            units = parsed.units,
            ref_low = refLow,
            ref_high = refHigh,
            ref_source = refSource,
            status = status,
            definition = entry?.definition,
            definition_citation = entry?.citationUrl,
            defer_reason = deferReason,
        )
    }
}
