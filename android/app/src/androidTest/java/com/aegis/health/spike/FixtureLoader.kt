package com.aegis.health.spike

import androidx.test.platform.app.InstrumentationRegistry
import java.io.InputStream

/** Loads the Plan-07 fixture corpus from androidTest assets. */
object FixtureLoader {
    val vendors = listOf("labcorp", "quest", "mayo", "hospital_lis", "urgent_care")

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
