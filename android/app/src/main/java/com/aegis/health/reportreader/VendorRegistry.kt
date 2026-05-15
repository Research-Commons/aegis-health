package com.aegis.health.reportreader

/**
 * Phase 2 — Stage 2a: vendor fingerprint dispatch.
 *
 * Mirrors `tools/parsers/lab_report_parser.py:_detect_vendor` (lines 531-552)
 * row-for-row: lowercases page 1 and matches header tokens against the 5 known
 * vendor fingerprints. Returns null on UNKNOWN_VENDOR — caller (LabValueParser
 * / ReportAssembler) short-circuits to report_status.code = "UNKNOWN_VENDOR"
 * with rows = [] per D-10.
 *
 * Adding a 6th vendor = create a new VendorExtractor object + append it to
 * `extractors`. No `when`-exhaustiveness churn (D-02).
 *
 * **Phase 4.1 R-02 ordering (post-Wave-3)**: brand-token-fingerprint extractors
 * precede keyword-fingerprint extractors so high-specificity Indian-lab brand
 * substrings claim their PDFs before Mayo's permissive `"hematology"` keyword
 * (which would otherwise short-circuit any Indian-lab PDF containing a CBC
 * hematology panel header). See `.planning/phases/04.1-vendor-coverage-
 * expansion-generic-fallback/04.1-CONTEXT.md` resolution R-02 + RESEARCH.md
 * "Pitfall 2: Vendor registry order mismatch".
 *
 * Slot 7 reserved for `GenericExtractor` catch-all (added by Wave 3 Plan
 * 04.1-3-01); `firstOrNull` semantics keep it last so the 7 named extractors
 * always claim PDFs they fingerprint-match.
 */
object VendorRegistry {
    /**
     * Order matters per Phase 4.1 R-02: brand-tokens-first to eliminate
     * the Mayo `"hematology"` collision on Indian-lab CBC PDFs. The 5 existing
     * extractors' fingerprint logic and regex bodies are unchanged; only their
     * list-position shifted (Phase 2 D-04 byte-identical contract preserved).
     */
    private val extractors: List<VendorExtractor> = listOf(
        Tata1mgExtractor,        // slot 0 — brand-token (Phase 4.1 D-09 + R-02)
        DrLalPathLabsExtractor,  // slot 1 — brand-token (Phase 4.1 D-09 + R-02)
        LabCorpExtractor,        // slot 2 — keyword "lipid panel" + "cholesterol, total"
        QuestExtractor,          // slot 3 — keyword "comprehensive metabolic panel"
        MayoExtractor,           // slot 4 — keyword "complete blood count" / "hematology"
        HospitalLisExtractor,    // slot 5 — keyword "lipid profile" + "biological ref"
        UrgentCareExtractor,     // slot 6 — keyword "hgb a1c" / "hemoglobin a1c" + "eag"
        GenericExtractor,        // slot 7 — catch-all per D-03 + R-02, MUST be last
    )

    /**
     * Page-1 fingerprint dispatch. Returns null on UNKNOWN_VENDOR — caller
     * (LabValueParser / ReportAssembler) short-circuits to
     * report_status.code = "UNKNOWN_VENDOR" and rows = [].
     */
    fun fingerprintMatches(page1: String): VendorExtractor? {
        val haystack = page1.lowercase()
        return extractors.firstOrNull { it.fingerprintMatches(haystack) }
    }
}
