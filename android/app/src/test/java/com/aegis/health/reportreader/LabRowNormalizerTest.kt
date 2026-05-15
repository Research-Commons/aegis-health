package com.aegis.health.reportreader

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Stage-level JVM unit tests for [LabRowNormalizer] (D-09 / LM-4 cross-language
 * parity gate).
 *
 * Two-tier coverage:
 *   1. Local Kotlin-side spot checks (normalize behavior, threshold const,
 *      entry-count sentinel).
 *   2. python_alias_map_matches_kotlin_alias_map -- subprocesses Python via
 *      [PythonRunner] (F-08 remediation; helper owned by Plan 02-06) and
 *      diffs the 140-entry alias map key-by-key (126 base + 14 Phase 4.1 D-10
 *      British/Indian variants). Build fails on any drift.
 */
class LabRowNormalizerTest {

    @Test
    fun entry_count_is_140() {
        assertEquals(140, LabRowNormalizer.LAB_TERM_ALIASES.size)
    }

    @Test
    fun ldl_c_maps_to_LDL_cholesterol() {
        // Case-preserving canonical per _alias_map.py
        assertEquals("LDL cholesterol", LabRowNormalizer.normalize("LDL-C"))
    }

    @Test
    fun whitespace_collapsing_works() {
        // "  Cholesterol,  Total  " -> key "cholesterol, total" -> value "total cholesterol"
        assertEquals("total cholesterol", LabRowNormalizer.normalize("  Cholesterol,  Total  "))
    }

    @Test
    fun unknown_rawName_returns_null() {
        assertEquals(null, LabRowNormalizer.normalize("totally fake test name"))
    }

    @Test
    fun null_or_blank_rawName_returns_null() {
        assertEquals(null, LabRowNormalizer.normalize(null))
        assertEquals(null, LabRowNormalizer.normalize(""))
        assertEquals(null, LabRowNormalizer.normalize("   "))
    }

    @Test
    fun row_count_threshold_is_25() {
        assertEquals(25, LabRowNormalizer.ROW_COUNT_DEFER_THRESHOLD)
    }

    @Test
    fun python_alias_map_matches_kotlin_alias_map() {
        // D-09 sibling: subprocess Python, diff entry-set + per-entry values.
        // F-08 remediation: PythonRunner.resolve() picks a candidate interpreter
        // portable across Windows venv / Unix venv / PATH-installed python.
        val repoRoot = locateRepoRoot()
        val python = PythonRunner.resolve()
        val script = """
            import json
            from tools.parsers._alias_map import LAB_TERM_ALIASES
            print(json.dumps(LAB_TERM_ALIASES, ensure_ascii=False))
        """.trimIndent()
        val pb = ProcessBuilder(python, "-c", script)
            .directory(repoRoot)
            .redirectErrorStream(false)
        pb.environment()["PYTHONIOENCODING"] = "utf-8"
        val proc = pb.start()
        // Read stdout as raw UTF-8 bytes to bypass platform default charset
        // (cp1252 on Windows would mangle non-ASCII characters).
        val stdout = proc.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
        val stderr = proc.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val exit = proc.waitFor()
        check(exit == 0) { "python subprocess exit=$exit; stderr=$stderr" }

        val parsed = Json.parseToJsonElement(stdout.trim()) as JsonObject
        val pyMap: Map<String, String> = parsed.mapValues { (_, v) ->
            (v as JsonPrimitive).content
        }

        assertEquals(
            "Python and Kotlin alias-map entry counts must match",
            pyMap.size,
            LabRowNormalizer.LAB_TERM_ALIASES.size,
        )
        val missing = pyMap.keys - LabRowNormalizer.LAB_TERM_ALIASES.keys
        val extra = LabRowNormalizer.LAB_TERM_ALIASES.keys - pyMap.keys
        assertTrue("Kotlin missing Python keys: $missing", missing.isEmpty())
        assertTrue("Kotlin has extra keys not present in Python: $extra", extra.isEmpty())
        for (k in pyMap.keys) {
            assertEquals(
                "Value drift at key='$k'",
                pyMap[k],
                LabRowNormalizer.LAB_TERM_ALIASES[k],
            )
        }
    }

    private fun locateRepoRoot(): File {
        var dir: File? = File("").absoluteFile
        repeat(8) {
            val cur = dir ?: return@repeat
            if (File(cur, "CLAUDE.md").exists() && File(cur, "kb").exists()) return cur
            dir = cur.parentFile
        }
        error("Could not locate repo root from ${File("").absolutePath}")
    }
}
