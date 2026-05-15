package com.aegis.health.reportreader

import androidx.test.platform.app.InstrumentationRegistry
import java.io.InputStream

/**
 * Phase 2 — fixture corpus loader for androidTest.
 *
 * Reads from android/app/src/androidTest/assets/lab_reports/{vendor}/ — these
 * assets were committed during Phase 1 Plan 01-08 (commit 06dbe2a). The
 * ground-truth `*-evaluated.json` files are synced from
 * `eval/fixtures/lab_reports/{vendor}/` (the cross-language source of truth)
 * as a mandated first step in Plan 02-14 Task 1.
 *
 * Promoted from androidTest/.../spike/FixtureLoader.kt (Phase 1 spike code);
 * API surface preserved so the spike file can remain for historical reference.
 */
object LabReportFixtureLoader {
    val vendors: List<String> = listOf(
        // Phase 2 — 5 byte-identical fixtures (D-04 contract).
        "labcorp",
        "quest",
        "mayo",
        "hospital_lis",
        "urgent_care",
        // Phase 4.1 — 3 synthetic vendor fixtures (D-15 field-level + generic smoke).
        // Each directory contains exactly 1 *.pdf (and a MANIFEST.md the .pdf filter
        // skips). No *-evaluated.json under these dirs — Phase 4.1 uses field-level
        // invariants, not byte-identical GT (D-11 limit). See 04.1-5-01-SUMMARY.md.
        "tata1mg",
        "drlalpathlabs",
        "generic",
    )

    fun pdfStream(vendor: String): InputStream {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val files = assets.list("lab_reports/$vendor")
            ?.filter { it.endsWith(".pdf") }
            ?: error("no PDFs for vendor=$vendor")
        require(files.size == 1) { "$vendor must have exactly 1 PDF; found ${files.size}" }
        return assets.open("lab_reports/$vendor/${files[0]}")
    }

    fun groundTruthJson(vendor: String): String {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val files = assets.list("lab_reports/$vendor")
            ?.filter { it.endsWith("-evaluated.json") }
            ?: error("no ground-truth JSON for vendor=$vendor")
        require(files.size == 1) { "$vendor must have exactly 1 *-evaluated.json" }
        return assets.open("lab_reports/$vendor/${files[0]}")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
