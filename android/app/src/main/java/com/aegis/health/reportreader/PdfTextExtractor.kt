package com.aegis.health.reportreader

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.InputStream

/**
 * Phase 2 — Stage 1: PDF bytes → per-page text spans.
 *
 * Wraps pdfbox-android 2.0.27.0 PDFTextStripper. Does NOT call
 * PDFBoxResourceLoader.init — that's wired once in AegisApp.onCreate (production)
 * and in androidTest @Before (LM-1).
 *
 * EXTRACT-03: when no extractable text is found on any page, returns
 * imageOnly = true and pages = []; ReportAssembler short-circuits to
 * report_status.code = "IMAGE_ONLY" without ever invoking downstream stages.
 *
 * T-PDF-PARSER: PDDocument.load is wrapped in try/catch to handle malformed
 * PDFs without crashing the calling thread. On parse failure, returns
 * imageOnly = true with an error message in errorMessage (which is consumed by
 * ReportStatus.message).
 */
object PdfTextExtractor {

    /** Conservative threshold: any page with fewer alphanumeric chars is treated as image-only. */
    private const val MIN_ALNUM_CHARS_PER_PAGE = 10

    /** Hard cap on PDF byte size to prevent memory exhaustion (T-PDF-PARSER). Caller-enforced. */
    const val MAX_PDF_BYTES: Long = 25L * 1024 * 1024  // 25 MB

    /** Hard cap on page count to prevent OOM (T-PDF-PARSER). */
    const val MAX_PAGES: Int = 50

    data class PdfText(
        val pages: List<String>,
        val imageOnly: Boolean,
        val errorMessage: String? = null,
    )

    /**
     * Extract per-page text. On any failure path, returns PdfText(pages = emptyList(),
     * imageOnly = true). Caller (ReportAssembler) emits report_status.code = "IMAGE_ONLY"
     * with optional message.
     */
    fun extract(input: InputStream): PdfText {
        return try {
            val doc = PDDocument.load(input)
            try {
                if (doc.numberOfPages > MAX_PAGES) {
                    return PdfText(
                        pages = emptyList(),
                        imageOnly = true,
                        errorMessage = "PDF has too many pages (${doc.numberOfPages} > $MAX_PAGES).",
                    )
                }
                val stripper = PDFTextStripper()
                val pages = mutableListOf<String>()
                val totalPages = doc.numberOfPages
                for (i in 1..totalPages) {
                    stripper.startPage = i
                    stripper.endPage = i
                    pages += stripper.getText(doc)
                }
                val isImageOnly = pages.all { alnumCount(it) < MIN_ALNUM_CHARS_PER_PAGE }
                PdfText(
                    pages = if (isImageOnly) emptyList() else pages,
                    imageOnly = isImageOnly,
                    errorMessage = if (isImageOnly)
                        "This appears to be a scanned image; please use a digital lab report."
                    else null,
                )
            } finally {
                doc.close()
            }
        } catch (e: Exception) {
            // T-PDF-PARSER: malformed PDF, pdfbox crash, IO error — defer rather than crash
            PdfText(
                pages = emptyList(),
                imageOnly = true,
                errorMessage = "Unable to read PDF: ${e.message?.take(120) ?: "parse error"}.",
            )
        }
    }

    private fun alnumCount(s: String): Int = s.count { it.isLetterOrDigit() }
}
