package com.aegis.health.reportreader

/**
 * Phase 2 — Stage 2: vendor dispatch + row extraction.
 *
 * Page-1 text passes through VendorRegistry.fingerprintMatches; on hit, the matched
 * VendorExtractor produces a List<ParsedRow>. On miss, returns ParseResult with
 * vendorKey = null — caller (ReportAssembler / pipeline orchestrator) emits
 * report_status.code = "UNKNOWN_VENDOR".
 *
 * EXTRACT-02 multi-page handling: VendorExtractor.extract receives all pages;
 * per-vendor regex already crosses page boundaries when needed (joined inside
 * each vendor's `extract` per LM-3 / Plan 02-08 design).
 *
 * Mirrors `tools/parsers/lab_report_parser.py:parse` (lines 460-500) and
 * `_detect_vendor` (lines 531-552): empty / missing vendor short-circuits to
 * a status-only report upstream; this dispatcher itself only carries the
 * structural signal (vendorKey + rows).
 */
object LabValueParser {

    /**
     * Result of one Stage-2 dispatch.
     *
     * - `vendorKey`: stable short-code matching Python `_detect_vendor`
     *   ("labcorp" | "quest" | "mayo" | "hospital_lis" | "urgent_care"), or null
     *   when no fingerprint matched (UNKNOWN_VENDOR sentinel — caller emits
     *   report_status.code = "UNKNOWN_VENDOR" with rows = [] per D-10).
     * - `rows`: raw pre-normalization rows. Empty list on UNKNOWN_VENDOR or
     *   when the vendor extractor produced no matches.
     */
    data class ParseResult(
        val vendorKey: String?,
        val rows: List<ParsedRow>,
    )

    /**
     * Stage-2 dispatch. Treats empty `pages` as UNKNOWN_VENDOR — defensive guard
     * for callers that haven't yet routed image-only short-circuits through
     * PdfTextExtractor.PdfText.imageOnly (Stage-1 already short-circuits this
     * case to report_status.code = "IMAGE_ONLY" upstream, so this branch only
     * fires for programmer error / malformed callers).
     */
    fun parse(pages: List<String>): ParseResult {
        if (pages.isEmpty()) {
            return ParseResult(vendorKey = null, rows = emptyList())
        }
        val extractor = VendorRegistry.fingerprintMatches(pages.first())
            ?: return ParseResult(vendorKey = null, rows = emptyList())
        val rows = extractor.extract(pages)
        return ParseResult(vendorKey = extractor.vendorKey, rows = rows)
    }
}
