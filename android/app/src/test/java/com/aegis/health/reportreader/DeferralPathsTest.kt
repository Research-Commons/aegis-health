package com.aegis.health.reportreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Synthetic-input coverage for the 3 D-10 deferral short-circuits
 * (IMAGE_ONLY / UNKNOWN_VENDOR / TOO_MANY_ANALYTES) via direct stage calls.
 *
 * The full pipeline-level integration (ReportReaderPipeline.parse) requires
 * a real KBDatabase + a real PDF stream, which is the Wave 4 androidTest's
 * domain (Plan 02-14, byte-identical fixture exit gate). JVM unit tests
 * here exercise each deferral entry-point in isolation, without touching
 * the SQLite or PDF-parsing JNI dependencies.
 */
class DeferralPathsTest {

    // -- INTERPRET-05: TOO_MANY_ANALYTES threshold constant --

    @Test
    fun row_count_threshold_constant_is_25() {
        // Strict > 25 trips the deferral (matches Python parser
        // lab_report_parser.py:483 `if len(normalized_rows) > 25`).
        assertEquals(25, LabRowNormalizer.ROW_COUNT_DEFER_THRESHOLD)
    }

    // -- EXTRACT-01: UNKNOWN_VENDOR fingerprint dispatch (post-Phase-4.1) --

    @Test
    fun vendor_registry_resolves_to_generic_on_unknown_page1_post_phase_4_1() {
        // Phase 4.1 R-02: GenericExtractor sits at slot 7 as the catch-all.
        // VendorRegistry.fingerprintMatches NEVER returns null post-Phase-4.1
        // because GenericExtractor.fingerprintMatches returns true
        // unconditionally per D-03. The Phase-2 ReportReaderPipeline UNKNOWN_VENDOR
        // null-branch remains as a defensive belt (see ReportReaderPipeline.kt
        // comment above the branch); the primary <3-row UNKNOWN_VENDOR
        // fire-point is now the aggregate-floor gate in
        // ReportReaderPipeline.selectStatusCodeAndMessage.
        val nothingNamedMatches = "this page has no recognizable lab vendor header at all"
        val result = VendorRegistry.fingerprintMatches(nothingNamedMatches)
        assertEquals(
            "Post-Phase-4.1, fingerprint miss falls through to GenericExtractor (slot 7).",
            "generic",
            result?.vendorKey,
        )
    }

    @Test
    fun vendor_registry_matches_labcorp_on_lipid_panel_header() {
        // Sanity check: positive case still resolves to labcorp -- ensures the
        // generic-fallback above came from named-vendor fingerprint miss, not a
        // broken registry. Phase 2 byte-identical contract preserved: named
        // vendors at slots 2..6 still claim their PDFs ahead of GenericExtractor.
        val labcorpPage1 = "LIPID PANEL\nCHOLESTEROL, TOTAL 151 125-200 mg/dL"
            .lowercase()
        val result = VendorRegistry.fingerprintMatches(labcorpPage1)
        assertEquals("labcorp", result?.vendorKey)
    }

    // -- EXTRACT-03: IMAGE_ONLY detection on empty / unparseable PDF --

    @Test
    fun pdf_text_extractor_image_only_detection_returns_flag() {
        // Empty input stream emulates parse failure / image-only PDF
        // (caller cannot extract text from a scanned-image PDF either).
        val empty = ByteArrayInputStream(ByteArray(0))
        val result = PdfTextExtractor.extract(empty)
        assertEquals(true, result.imageOnly)
        assertEquals(emptyList<String>(), result.pages)
        // The PdfTextExtractor surfaces a human-facing message that the
        // pipeline forwards into report_status.message.
        assertTrue(
            "errorMessage should be non-null on image-only / parse-failure path",
            result.errorMessage != null,
        )
    }

    @Test
    fun pdf_text_extractor_garbage_bytes_defer_safely() {
        // T-PDF-PARSER defensive contract: malformed bytes must not throw;
        // they should yield imageOnly = true with a parse-error message.
        val garbage = ByteArrayInputStream("not actually a pdf file".toByteArray())
        val result = PdfTextExtractor.extract(garbage)
        assertEquals(true, result.imageOnly)
        assertEquals(emptyList<String>(), result.pages)
    }

    // -- INTERPRET-05: row count cap interaction with normalizer --

    @Test
    fun normalize_returns_no_rows_when_input_empty() {
        val result = LabRowNormalizer.normalizeRows(emptyList())
        assertTrue(result.isEmpty())
    }
}
