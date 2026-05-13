package com.aegis.health.db

/**
 * Phase 2 — ReportReader: JVM-testable query contract.
 *
 * F-02 remediation. KBDatabase wraps android.database.sqlite.SQLiteDatabase
 * which cannot be instantiated under `./gradlew :app:testDebugUnitTest`
 * (JVM-only — no Android SQLite JNI). RangeEvaluator (Plan 02-11) and
 * ReportReaderPipeline (Plan 02-12) depend on this interface; tests inject
 * a FakeKb implementation (Plan 02-13) backed by in-memory Maps; production
 * wires KBDatabase which implements this interface (Task 1 of this plan).
 *
 * Return shapes mirror the existing Map<String, String> convention used by
 * KBDatabase.queryTerm / queryDrugByName / queryGuidelines so callers
 * (RangeEvaluator) read fields by key (e.g., row["ref_low"]) without coupling
 * to a Cursor-shaped type.
 */
interface KBQueries {
    /**
     * Adult / pediatric / sex-stratified reference-range lookup.
     * Pediatric path tried first when age<18; returns null if age<18 AND pediatric KB miss
     * (caller emits defer_reason="kb_no_pediatric" rather than silent adult-fallback).
     * INTERPRET-02 / INTERPRET-03.
     */
    fun queryLabReferenceRange(testName: String, age: Int?, sex: String?): Map<String, String>?

    /** Pediatric-specific range query. Returns null on miss. INTERPRET-03. */
    fun queryPediatricRange(testName: String, age: Int?, sex: String?): Map<String, String>?

    /** Pregnancy-specific range query. Returns null on miss. INTERPRET-03. */
    fun queryPregnancyRange(testName: String, trimester: Int?): Map<String, String>?

    /**
     * D-11 / INTERPRET-04: tumor-marker / genetic / pathology-grade auto-defer lookup.
     * Returns category ("tumor_marker" | "genetic" | "pathology") on hit, null on miss.
     * Implementations MUST treat a missing auto_defer_tests table as a miss
     * (return null), not a failure — Phase 2 should still function against an
     * older KB build that predates Plan 02-03.
     */
    fun queryAutoDefer(canonicalName: String): String?

    /**
     * INTERPRET-01 BORDERLINE support (F-05 remediation). Returns ALL rows of
     * clinical_thresholds for the given canonical name, each row being a Map with
     * keys: threshold_tier, low_cutoff, high_cutoff, units, citation.
     * Returns null when the table is missing (older KB build) or empty for this
     * canonical. Returns an empty list never — RangeEvaluator treats null and
     * empty-list as equivalent ("no threshold available, fall through to IN_RANGE").
     *
     * The table is populated by Phase 1's kb/kb/sources/curated_lab_ranges.py
     * (13 rows shipped: A1C prediabetes/diabetes, fasting glucose prediabetes/diabetes,
     * LDL borderline_high/high/very_high/extreme, eGFR stages 2/3a/3b/4, TSH
     * subclinical_hypo). New tiers may land in future curation; this query is
     * tier-agnostic — RangeEvaluator collapses all tiers to BORDERLINE.
     */
    fun queryClinicalThresholds(canonicalName: String): List<Map<String, String>>?
}
