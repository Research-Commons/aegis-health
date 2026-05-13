package com.aegis.health.reportreader

import com.aegis.health.db.KBQueries
import com.aegis.health.models.PreparsedReport
import com.aegis.health.models.Profile
import java.io.InputStream

/**
 * Phase 2 — public façade. Chains all 5 pipeline stages and short-circuits on
 * the 3 deferral codes per D-10.
 *
 * Fire-point ordering (per 02-CONTEXT.md "Claude's Discretion" notes,
 * mirroring `tools/parsers/lab_report_parser.py:parse` top-level cascade):
 *   1. PdfTextExtractor   — IMAGE_ONLY fires here (EXTRACT-03)
 *   2. LabValueParser     — UNKNOWN_VENDOR fires here via VendorRegistry miss
 *                           (EXTRACT-01)
 *   3. LabRowNormalizer   — TOO_MANY_ANALYTES fires here when count >
 *                           ROW_COUNT_DEFER_THRESHOLD (25) (INTERPRET-05)
 *   4. RangeEvaluator     — per-row defer_reason emitted on each EvaluatedRow
 *   5. ReportAssembler    — produces final PreparsedReport (LM-5 citation dedup)
 *
 * DemographicExtractor runs after PdfTextExtractor to populate Profile +
 * isPregnant for RangeEvaluator's demographic routing (INTERPRET-03).
 *
 * **Always returns a PreparsedReport.** Every failure path becomes a structured
 * deferral via report_status.code; pipeline never throws and never returns null.
 * Caller branches on `report.report_status.code`:
 *   - "OK"                 → rows populated, citations populated, UI renders
 *   - "IMAGE_ONLY"         → rows=[], citations=[], UI shows "scanned image"
 *   - "UNKNOWN_VENDOR"     → rows=[], citations=[], UI shows "format not recognized"
 *   - "TOO_MANY_ANALYTES"  → rows=[], citations=[], UI shows "discuss with clinician"
 *
 * F-02 remediation: depends on KBQueries (the JVM-clean interface from Plan 02-05),
 * NOT on concrete KBDatabase. Wave 4 Plan 02-13 RangeEvaluatorTest will inject a
 * FakeKb backed by in-memory Maps; Wave 4 Plan 02-14 androidTest passes the real
 * KBDatabase instance (which implements KBQueries).
 *
 * F-03 remediation (INPUT-01): SAF-friendly bridge `parseFromUri(uri, context, db)`
 * opens the SAF Uri via ContentResolver and delegates to `parse(stream, db)`.
 * Phase 3 UI will pass the Uri returned by ACTION_OPEN_DOCUMENT
 * (MIME application/pdf); Phase 2 ships the non-UI half of the picker flow.
 */
object ReportReaderPipeline {

    /**
     * Public entry. Takes a PDF byte stream + KB, returns PreparsedReport.
     * Never throws; every failure surfaces as a structured deferral.
     *
     * Caller is responsible for stream lifecycle. The pipeline does NOT close
     * the stream — the SAF bridge (parseFromUri) uses .use { } to manage it.
     */
    fun parse(pdfInput: InputStream, db: KBQueries): PreparsedReport {
        // Stage 1 — PdfTextExtractor: extract per-page text.
        val pdf = PdfTextExtractor.extract(pdfInput)
        if (pdf.imageOnly || pdf.pages.isEmpty()) {
            // EXTRACT-03: IMAGE_ONLY short-circuit. Forwards extractor's
            // human-facing message into report_status.message per the plan's
            // T-PDF-PARSER contract.
            return ReportAssembler.assemble(
                rows = emptyList(),
                profile = Profile(),
                statusCode = "IMAGE_ONLY",
                statusMessage = pdf.errorMessage
                    ?: "This appears to be a scanned image; please use a digital lab report.",
            )
        }

        // INPUT-02: demographics from cover sheet. Computed before vendor
        // dispatch so even an UNKNOWN_VENDOR deferral carries Profile context
        // (matches Python parser: `_extract_demographics(pages_text, vendor=None)`
        // runs on the UNKNOWN_VENDOR path too).
        val profile = DemographicExtractor.extract(pdf.pages)
        val isPregnant = DemographicExtractor.isPregnant(pdf.pages)

        // Stage 2 — LabValueParser: vendor dispatch + row extraction.
        val parsed = LabValueParser.parse(pdf.pages)
        if (parsed.vendorKey == null) {
            // EXTRACT-01: UNKNOWN_VENDOR short-circuit. Page-1 fingerprint did
            // not match any registered VendorExtractor.
            return ReportAssembler.assemble(
                rows = emptyList(),
                profile = profile,
                statusCode = "UNKNOWN_VENDOR",
                statusMessage = "Lab vendor format not recognized.",
            )
        }

        // Stage 3 — LabRowNormalizer: alias map raw_name → canonical_name.
        // Unknown rawNames are silently dropped (Python parity); INTERPRET-04
        // auto-defer is handled downstream in RangeEvaluator via queryAutoDefer.
        val normalized = LabRowNormalizer.normalizeRows(parsed.rows)
        if (normalized.size > LabRowNormalizer.ROW_COUNT_DEFER_THRESHOLD) {
            // INTERPRET-05: TOO_MANY_ANALYTES short-circuit. Checked here
            // (post-Stage 3, pre-Stage 4) because the threshold is a row-count
            // concern, not a per-row evaluation concern. Mirrors Python parser:
            // `if len(normalized_rows) > 25` at lab_report_parser.py:483.
            return ReportAssembler.assemble(
                rows = emptyList(),
                profile = profile,
                statusCode = "TOO_MANY_ANALYTES",
                statusMessage = "Unusually many values (${normalized.size}); discuss with clinician.",
            )
        }

        // Stage 4 — RangeEvaluator: deterministic three-state classification
        // with per-row defer_reason on the 'unknown' fourth-state.
        val evaluated = normalized.map { RangeEvaluator.evaluate(it, profile, isPregnant, db) }

        // Stage 5 — ReportAssembler: LM-5 citation dedup + final envelope.
        return ReportAssembler.assemble(rows = evaluated, profile = profile)
    }

    /**
     * INPUT-01 (F-03 remediation): SAF-friendly bridge.
     *
     * Phase 2 ships the non-UI half of the SAF picker flow. Phase 3 will create
     * the Compose ActivityResultLauncher that calls into this function with the
     * Uri returned by `ACTION_OPEN_DOCUMENT` (MIME `application/pdf`). We open
     * the URI via ContentResolver, hand the stream to `parse`, and surface a
     * structured `PreparsedReport` with `report_status.code = "IMAGE_ONLY"` if
     * the URI cannot be opened (defensive — typical reason: revoked permission,
     * deleted file).
     *
     * Constraints:
     *   - Stays inside the reportreader package — no Activity / Fragment /
     *     Compose code here.
     *   - Uses `Uri` and `Context` from `android.*` — these are non-test
     *     (production) dependencies; JVM unit tests don't exercise this path
     *     (androidTest in Plan 02-14 does, indirectly via
     *     `LabReportFixtureLoader.pdfStream`).
     */
    fun parseFromUri(
        uri: android.net.Uri,
        context: android.content.Context,
        db: KBQueries,
    ): PreparsedReport {
        val stream = context.contentResolver.openInputStream(uri)
            ?: return ReportAssembler.assemble(
                rows = emptyList(),
                profile = Profile(),
                statusCode = "IMAGE_ONLY",
                statusMessage = "Could not open URI; the file may have been moved or permission revoked.",
            )
        return stream.use { parse(it, db) }
    }
}
