package com.aegis.health.reportreader

import com.aegis.health.models.EvaluatedRow
import com.aegis.health.models.Profile
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage-level JVM unit tests for [ReportAssembler] (LM-5 dedup regression +
 * D-10 status-envelope happy-path coverage).
 *
 * LabCorp scenario: two distinct canonical_names ("cholesterol ratio" and
 * "non-HDL cholesterol") both have a DefinitionDb entry with label
 * "MedlinePlus: Cholesterol Levels". Both must appear in citations[] because
 * dedup is keyed on canonical_name, not on (label, url). This regression test
 * pins that behavior so any future refactor that accidentally dedups by label
 * fails CI immediately.
 */
class ReportAssemblerTest {

    /** Helper: minimal EvaluatedRow carrying just enough to drive dedup +
     *  status booleans. The definition fields are nullable; ReportAssembler
     *  reads canonical_name + status to compute citations and flags. */
    private fun row(canonical: String, status: String = "IN_RANGE"): EvaluatedRow =
        EvaluatedRow(
            canonical_name = canonical,
            raw_name = canonical,
            value = JsonNull,
            units = null,
            ref_low = JsonNull,
            ref_high = JsonNull,
            ref_source = "report",
            status = status,
            definition = null,
            definition_citation = null,
            defer_reason = null,
        )

    @Test
    fun lm5_distinct_canonicals_with_same_label_preserved() {
        // LabCorp scenario: "cholesterol ratio" + "non-HDL cholesterol" both
        // have a DefinitionDb entry with label "MedlinePlus: Cholesterol Levels"
        // -- both must appear in citations[] per LM-5.
        val rows = listOf(
            row("cholesterol ratio"),
            row("non-HDL cholesterol"),
        )
        val report = ReportAssembler.assemble(rows, Profile())
        val choleClass = report.citations.filter {
            it.label.contains("Cholesterol Levels", ignoreCase = true)
        }
        assertEquals(
            "Expected 2 citation entries with 'Cholesterol Levels' label per LM-5",
            2,
            choleClass.size,
        )
    }

    @Test
    fun citations_sorted_by_label_alphabetically() {
        val rows = listOf(row("total cholesterol"), row("HDL cholesterol"))
        val report = ReportAssembler.assemble(rows, Profile())
        val labels = report.citations.map { it.label }
        assertEquals(labels.sorted(), labels)
    }

    @Test
    fun same_canonical_repeated_collapses_to_one_citation() {
        // Quest scenario: 2 eGFR rows with the same canonical_name "eGFR"
        // collapse to 1 citation. We simulate with "total cholesterol" twice.
        val rows = listOf(row("total cholesterol"), row("total cholesterol"))
        val report = ReportAssembler.assemble(rows, Profile())
        assertEquals(
            "Two rows sharing a canonical_name must produce a single citation",
            1,
            report.citations.size,
        )
    }

    @Test
    fun image_only_emits_empty_rows_and_status() {
        val report = ReportAssembler.assemble(
            rows = emptyList(),
            profile = Profile(),
            statusCode = "IMAGE_ONLY",
            statusMessage = "scanned",
        )
        assertTrue("rows must be empty on IMAGE_ONLY", report.rows.isEmpty())
        assertEquals("IMAGE_ONLY", report.report_status.code)
        assertEquals("scanned", report.report_status.message)
        assertTrue("citations must be empty on IMAGE_ONLY", report.citations.isEmpty())
        assertEquals(false, report.has_outside_range)
        assertEquals(false, report.has_unknown)
    }

    @Test
    fun unknown_vendor_emits_empty_rows() {
        val report = ReportAssembler.assemble(
            rows = emptyList(),
            profile = Profile(age = 40, sex = "male"),
            statusCode = "UNKNOWN_VENDOR",
            statusMessage = "Lab vendor format not recognized.",
        )
        assertTrue(report.rows.isEmpty())
        assertEquals("UNKNOWN_VENDOR", report.report_status.code)
        // Profile is preserved on the UNKNOWN_VENDOR path -- mirrors Python
        // parser semantics (demographics extracted before vendor dispatch).
        assertEquals(40, report.profile_used.age)
    }

    @Test
    fun has_outside_range_computed_from_rows() {
        val report = ReportAssembler.assemble(
            rows = listOf(row("total cholesterol", status = "OUTSIDE_RANGE")),
            profile = Profile(),
        )
        assertEquals(true, report.has_outside_range)
    }

    @Test
    fun has_unknown_computed_from_rows() {
        val report = ReportAssembler.assemble(
            rows = listOf(row("non-HDL cholesterol", status = "unknown")),
            profile = Profile(),
        )
        assertEquals(true, report.has_unknown)
    }

    @Test
    fun happy_path_status_is_OK() {
        val report = ReportAssembler.assemble(listOf(row("total cholesterol")), Profile())
        assertEquals("OK", report.report_status.code)
        assertEquals(null, report.report_status.message)
    }
}
