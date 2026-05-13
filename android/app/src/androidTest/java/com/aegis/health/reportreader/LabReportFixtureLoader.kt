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
        "labcorp",
        "quest",
        "mayo",
        "hospital_lis",
        "urgent_care",
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
