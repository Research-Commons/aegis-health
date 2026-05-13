package com.aegis.health.reportreader

import com.aegis.health.db.KBQueries
import com.aegis.health.models.Profile
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Stage-level JVM unit tests for [RangeEvaluator] (F-02 / F-05 / F-09
 * remediations).
 *
 * Coverage:
 *   - 4-state classifier truth table (IN_RANGE / BORDERLINE / OUTSIDE_RANGE /
 *     unknown) per F-05.
 *   - All 9 D-12 defer_reason short-codes (mismatched_units, missing_units,
 *     non_numeric_result, range_unavailable, kb_no_pediatric, kb_no_pregnancy,
 *     auto_defer:tumor_marker, auto_defer:genetic, auto_defer:pathology).
 *   - All 3 auto_defer categories (F-09): tumor_marker, genetic, pathology --
 *     each must override any printed range, never emit IN_RANGE.
 *   - BORDERLINE emission via clinical_thresholds band match (F-05) and the
 *     graceful fallback to IN_RANGE when no threshold band exists.
 *
 * F-02 remediation: FakeKb implements [KBQueries] (the JVM-clean interface
 * from Plan 02-05), NOT the concrete KBDatabase -- subclassing KBDatabase
 * would require the Android SQLite JNI which is unavailable under
 * `./gradlew :app:testDebugUnitTest`. Each test instantiates FakeKb with
 * the in-memory Map stubs relevant to its assertion.
 */
class RangeEvaluatorTest {

    /**
     * F-02 remediation: JVM-side fake. Implements [KBQueries] from Plan 02-05;
     * does NOT subclass KBDatabase. Backed by in-memory Maps; constructor args
     * control which queries return what.
     */
    // Implements interface: class FakeKb : com.aegis.health.db.KBQueries
    private class FakeKb(
        private val auto: Map<String, String> = emptyMap(),
        private val ranges: Map<String, Map<String, String>> = emptyMap(),
        private val pediatric: Map<String, Map<String, String>> = emptyMap(),
        private val pregnancy: Map<String, Map<String, String>> = emptyMap(),
        private val thresholds: Map<String, List<Map<String, String>>> = emptyMap(),
    ) : com.aegis.health.db.KBQueries {
        override fun queryAutoDefer(canonicalName: String): String? =
            auto[canonicalName.lowercase()]

        override fun queryLabReferenceRange(testName: String, age: Int?, sex: String?): Map<String, String>? =
            ranges[testName.lowercase()]

        override fun queryPediatricRange(testName: String, age: Int?, sex: String?): Map<String, String>? =
            pediatric[testName.lowercase()]

        override fun queryPregnancyRange(testName: String, trimester: Int?): Map<String, String>? =
            pregnancy[testName.lowercase()]

        override fun queryClinicalThresholds(canonicalName: String): List<Map<String, String>>? =
            thresholds[canonicalName.lowercase()]
    }

    /** Helper to build NormalizedRow with sensible defaults. */
    private fun row(
        canonical: String,
        value: JsonElement = JsonPrimitive(120),
        units: String? = "mg/dL",
        refLow: JsonElement = JsonNull,
        refHigh: JsonElement = JsonNull,
    ): LabRowNormalizer.NormalizedRow =
        LabRowNormalizer.NormalizedRow(
            canonicalName = canonical,
            raw = ParsedRow(rawName = canonical, value = value, units = units,
                            refLow = refLow, refHigh = refHigh),
        )

    // -- IN_RANGE / OUTSIDE_RANGE / classification truth table --

    @Test
    fun in_range_when_value_within_pdf_range() {
        val r = row("total cholesterol",
            value = JsonPrimitive(151),
            refLow = JsonPrimitive(125),
            refHigh = JsonPrimitive(200))
        val ev = RangeEvaluator.evaluate(r, Profile(), false, FakeKb())
        assertEquals("IN_RANGE", ev.status)
        assertEquals("report", ev.ref_source)
        assertEquals(null, ev.defer_reason)
    }

    @Test
    fun outside_range_when_value_above_pdf_high() {
        val r = row("total cholesterol",
            value = JsonPrimitive(240),
            refLow = JsonPrimitive(125),
            refHigh = JsonPrimitive(200))
        val ev = RangeEvaluator.evaluate(r, Profile(), false, FakeKb())
        assertEquals("OUTSIDE_RANGE", ev.status)
    }

    @Test
    fun outside_range_when_value_below_pdf_low() {
        val r = row("hemoglobin",
            value = JsonPrimitive(7.5),
            units = "g/dL",
            refLow = JsonPrimitive(12.0),
            refHigh = JsonPrimitive(16.0))
        val ev = RangeEvaluator.evaluate(r, Profile(), false, FakeKb())
        assertEquals("OUTSIDE_RANGE", ev.status)
    }

    // -- D-12 short-code: auto_defer:tumor_marker / genetic / pathology (F-09) --

    @Test
    fun auto_defer_tumor_marker_overrides_printed_range() {
        // Even with a printed "in-range" value, tumor markers MUST defer.
        val r = row("PSA",
            value = JsonPrimitive(2.0),
            refLow = JsonPrimitive(0),
            refHigh = JsonPrimitive(4))
        val ev = RangeEvaluator.evaluate(r, Profile(),
            isPregnant = false,
            db = FakeKb(auto = mapOf("psa" to "tumor_marker")))
        assertEquals("unknown", ev.status)
        assertEquals("auto_defer:tumor_marker", ev.defer_reason)
        assertEquals("none", ev.ref_source)
    }

    /** F-09 remediation: auto_defer:genetic emits regardless of printed range. */
    @Test
    fun auto_defer_genetic_overrides_printed_range() {
        val r = row("BRCA1",
            value = JsonPrimitive("positive"),
            refLow = JsonNull,
            refHigh = JsonNull,
            units = null)
        val ev = RangeEvaluator.evaluate(r, Profile(),
            isPregnant = false,
            db = FakeKb(auto = mapOf("brca1" to "genetic")))
        assertEquals("unknown", ev.status)
        assertEquals("auto_defer:genetic", ev.defer_reason)
        assertEquals("none", ev.ref_source)
    }

    /** F-09 remediation: auto_defer:pathology emits regardless of printed range. */
    @Test
    fun auto_defer_pathology_overrides_printed_range() {
        val r = row("Gleason score",
            value = JsonPrimitive(7),
            refLow = JsonPrimitive(0),
            refHigh = JsonPrimitive(10))
        val ev = RangeEvaluator.evaluate(r, Profile(),
            isPregnant = false,
            db = FakeKb(auto = mapOf("gleason score" to "pathology")))
        assertEquals("unknown", ev.status)
        assertEquals("auto_defer:pathology", ev.defer_reason)
        assertEquals("none", ev.ref_source)
    }

    // -- BORDERLINE state (F-05) --

    /**
     * F-05 remediation: BORDERLINE state reachable when clinical_thresholds
     * has a band bracketing the value (ADA prediabetes A1C 5.7-6.4).
     */
    @Test
    fun borderline_when_clinical_threshold_brackets_value() {
        val r = row(
            "hemoglobin a1c",
            value = JsonPrimitive(5.9),
            units = "%",
            refLow = JsonPrimitive(4.0),  // PDF printed range -- value is inside
            refHigh = JsonPrimitive(6.0),
        )
        val ev = RangeEvaluator.evaluate(r, Profile(age = 50), false,
            FakeKb(thresholds = mapOf("hemoglobin a1c" to listOf(
                mapOf(
                    "threshold_tier" to "prediabetes",
                    "low_cutoff" to "5.7",
                    "high_cutoff" to "6.4",
                    "units" to "%",
                    "citation" to "ADA; https://diabetes.org/about-diabetes/a1c",
                ),
            ))))
        assertEquals("BORDERLINE", ev.status)
        assertEquals(null, ev.defer_reason)
        assertEquals("report", ev.ref_source)
    }

    /**
     * F-05 remediation: when clinical_thresholds is empty for the analyte,
     * value inside the printed range falls through to IN_RANGE (graceful
     * degradation).
     */
    @Test
    fun in_range_when_no_clinical_threshold_for_analyte() {
        val r = row(
            "random analyte",
            value = JsonPrimitive(50),
            units = "mg/dL",
            refLow = JsonPrimitive(0),
            refHigh = JsonPrimitive(100),
        )
        val ev = RangeEvaluator.evaluate(r, Profile(age = 50), false,
            FakeKb(thresholds = emptyMap()))
        assertEquals("IN_RANGE", ev.status)
    }

    // -- D-12 short-code: non_numeric_result --

    @Test
    fun non_numeric_result_defers() {
        val r = row("HIV", value = JsonNull, units = null)
        val ev = RangeEvaluator.evaluate(r, Profile(), false, FakeKb())
        assertEquals("unknown", ev.status)
        assertEquals("non_numeric_result", ev.defer_reason)
    }

    // -- D-12 short-code: kb_no_pediatric --

    @Test
    fun kb_no_pediatric_when_age_under_18_and_kb_miss() {
        val r = row("LDL cholesterol")  // no PDF range
        val ev = RangeEvaluator.evaluate(r, Profile(age = 10), false,
            FakeKb(pediatric = emptyMap()))
        assertEquals("unknown", ev.status)
        assertEquals("kb_no_pediatric", ev.defer_reason)
    }

    // -- D-12 short-code: kb_no_pregnancy --

    @Test
    fun kb_no_pregnancy_when_isPregnant_and_pregnancy_kb_miss() {
        val r = row("HCG")
        val ev = RangeEvaluator.evaluate(r, Profile(age = 30, sex = "female"), true, FakeKb())
        assertEquals("unknown", ev.status)
        assertEquals("kb_no_pregnancy", ev.defer_reason)
    }

    // -- D-12 short-code: range_unavailable --

    @Test
    fun range_unavailable_when_no_pdf_no_kb_for_adult() {
        // No PDF range; no KB row; units present so not missing_units.
        val r = row("obscureanalyte")
        val ev = RangeEvaluator.evaluate(r, Profile(age = 40), false, FakeKb())
        assertEquals("unknown", ev.status)
        assertEquals("range_unavailable", ev.defer_reason)
    }

    // -- D-12 short-code: missing_units --

    @Test
    fun missing_units_when_no_pdf_no_kb_no_units() {
        val r = row("obscureanalyte", units = null)
        val ev = RangeEvaluator.evaluate(r, Profile(age = 40), false, FakeKb())
        assertEquals("unknown", ev.status)
        assertEquals("missing_units", ev.defer_reason)
    }

    // -- D-12 short-code: mismatched_units --

    @Test
    fun mismatched_units_when_pdf_units_differ_from_kb_units() {
        // European units (g/L); KB has g/dL.
        val r = row("hemoglobin", value = JsonPrimitive(130), units = "g/L")
        val ev = RangeEvaluator.evaluate(r, Profile(age = 40), false,
            FakeKb(ranges = mapOf("hemoglobin" to mapOf(
                "ref_low" to "12.0",
                "ref_high" to "16.0",
                "units" to "g/dL",
            ))))
        assertEquals("unknown", ev.status)
        assertEquals("mismatched_units", ev.defer_reason)
    }
}
