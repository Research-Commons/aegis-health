package com.aegis.health.reportreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Stage-level JVM unit tests for [JsonCanonicalizer] (D-06).
 *
 * Two-tier coverage:
 *   1. Synthetic primitives (LM-3 int/float fidelity, key sorting, trailing
 *      newline) -- runs on its own without filesystem access.
 *   2. labcorp_lipid_panel-evaluated.json round-trip -- proves that the
 *      Kotlin canonicalizer produces bytes identical to the live GT JSON
 *      (which Python's json.dumps(sort_keys=True, indent=2,
 *      ensure_ascii=False) produced). If this fails, the Wave 4 androidTest
 *      byte-identical exit gate CANNOT pass; this is the critical pre-gate.
 */
class JsonCanonicalizerTest {

    /**
     * Locate the aegis-health repo root by walking up from the JVM test
     * runner's working directory until we find CLAUDE.md + kb/. Gradle
     * typically runs JVM tests from `android/app/`, so the walk is at most
     * 2 levels.
     */
    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        repeat(8) {
            val cur = dir ?: return@repeat
            if (File(cur, "CLAUDE.md").exists() && File(cur, "kb").exists()) return cur
            dir = cur.parentFile
        }
        error("Could not locate repo root from ${File("").absolutePath}")
    }

    @Test
    fun integer_value_stays_integer() {
        val input = """{"value": 151}"""
        val out = JsonCanonicalizer.parseAndCanonicalize(input).trim()
        assertTrue("integer 151 should stay '151', got: $out", out.contains("\"value\": 151"))
        assertTrue("must not contain '151.0'", !out.contains("151.0"))
    }

    @Test
    fun float_5_dot_0_stays_float() {
        val input = """{"value": 5.0}"""
        val out = JsonCanonicalizer.parseAndCanonicalize(input).trim()
        assertTrue("5.0 must stay '5.0', got: $out", out.contains("\"value\": 5.0"))
    }

    @Test
    fun trailing_zeros_after_decimal_stripped() {
        val input = """{"value": 5.10}"""
        val out = JsonCanonicalizer.parseAndCanonicalize(input).trim()
        assertTrue("5.10 -> 5.1, got: $out", out.contains("\"value\": 5.1"))
    }

    @Test
    fun keys_sorted_alphabetically() {
        val input = """{"z": 1, "a": 2}"""
        val out = JsonCanonicalizer.parseAndCanonicalize(input)
        val zIdx = out.indexOf("\"z\"")
        val aIdx = out.indexOf("\"a\"")
        assertTrue("a should come before z", aIdx < zIdx && aIdx >= 0)
    }

    @Test
    fun trailing_newline_present() {
        val out = JsonCanonicalizer.parseAndCanonicalize("""{"x": 1}""")
        assertTrue("must end with newline", out.endsWith("\n"))
    }

    @Test
    fun booleans_lowercase() {
        val out = JsonCanonicalizer.parseAndCanonicalize("""{"a": true, "b": false}""")
        assertTrue("true must be lowercase, got: $out", out.contains(": true"))
        assertTrue("false must be lowercase, got: $out", out.contains(": false"))
    }

    @Test
    fun null_value_emitted() {
        val out = JsonCanonicalizer.parseAndCanonicalize("""{"x": null}""")
        assertTrue("null must serialize as null, got: $out", out.contains(": null"))
    }

    @Test
    fun empty_object_compact() {
        val out = JsonCanonicalizer.parseAndCanonicalize("""{}""").trim()
        assertEquals("{}", out)
    }

    @Test
    fun empty_array_compact() {
        val out = JsonCanonicalizer.parseAndCanonicalize("""[]""").trim()
        assertEquals("[]", out)
    }

    @Test
    fun labcorp_gt_round_trips_byte_identical() {
        val gt = File(repoRoot(), "eval/fixtures/lab_reports/labcorp/labcorp_lipid_panel-evaluated.json")
            .readText(Charsets.UTF_8)
        val out = JsonCanonicalizer.parseAndCanonicalize(gt)
        // The GT itself is canonicalized via Python's
        // json.dumps(sort_keys=True, indent=2, ensure_ascii=False) + "\n";
        // round-trip through the Kotlin canonicalizer must produce identical
        // bytes. If this fails, fix the canonicalizer before any fixture
        // parity test will pass.
        assertEquals("LabCorp GT must round-trip byte-identically", gt, out)
    }
}
