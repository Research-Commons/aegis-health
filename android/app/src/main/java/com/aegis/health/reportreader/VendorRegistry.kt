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
 */
object VendorRegistry {
    /** Order matters only when fingerprints overlap; current 5 are disjoint. */
    private val extractors: List<VendorExtractor> = listOf(
        LabCorpExtractor,
        QuestExtractor,
        MayoExtractor,
        HospitalLisExtractor,
        UrgentCareExtractor,
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
