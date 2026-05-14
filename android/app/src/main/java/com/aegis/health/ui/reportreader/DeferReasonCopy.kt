package com.aegis.health.ui.reportreader

/**
 * Phase 3 D-12: human-readable copy for the 9-entry defer_reason short-code
 * vocabulary defined in `.planning/specs/EXTRACTION-SPEC.md` and Phase 2
 * 02-CONTEXT.md D-12.
 *
 * Keys mirror the Phase 2 EXTRACTION-SPEC.md `defer_reason` enum byte-for-byte.
 * Drift between this map and the Phase 2 spec will surface as a missing key in
 * production — fall back to a generic clinician-handoff caption.
 *
 * Constraints (03-CONTEXT.md:235-237):
 *   - plain-language, calm-tone
 *   - never good/bad framing
 *   - never urgent / scare language
 *   - every entry recommends clinician review explicitly
 */
object DeferReasonCopy {
    val ENTRIES: Map<String, String> = mapOf(
        "missing_units"           to "The reported units are not on the row. A clinician can confirm the result.",
        "mismatched_units"        to "The value units do not match the reference range units. A clinician can confirm the result.",
        "non_numeric_result"      to "This result is reported as text rather than a number, so it cannot be plotted against a range. A clinician can interpret it.",
        "range_unavailable"       to "No reference range is printed and the knowledge base does not carry one for this test. A clinician can interpret it.",
        "kb_no_pediatric"         to "Pediatric reference ranges differ from adult ranges, and the knowledge base does not carry one for this test. A clinician can interpret it.",
        "kb_no_pregnancy"         to "Pregnancy reference ranges differ from non-pregnant ranges, and the knowledge base does not carry one for this test. A clinician can interpret it.",
        "auto_defer:tumor_marker" to "Tumor markers are not interpreted by this app. A clinician should review this result.",
        "auto_defer:genetic"      to "Genetic results are not interpreted by this app. A clinician should review this result.",
        "auto_defer:pathology"    to "Pathology-grade tests are not interpreted by this app. A clinician should review this result.",
    )

    fun lookup(shortCode: String): String =
        ENTRIES[shortCode] ?: "Discuss with your doctor."
}
