package com.aegis.health.reportreader

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * D-09: Cross-language parity gate between Kotlin [DefinitionDb] and Python
 * `tools/parsers/lab_report_parser._DEFINITION_DB`.
 *
 * Subprocesses Python (resolved via [PythonRunner]), dumps the Python dict as JSON,
 * and asserts that every entry matches the Kotlin literal byte-for-byte on
 * (definition, citationUrl, citationLabel).
 *
 * Build fails on any drift. Per `02-PATTERNS.md` LM-4: the only sanctioned update
 * path for either side is regeneration via the helper script — the cross-language
 * test makes hand-edits structurally impossible to ship.
 *
 * F-08 remediation: Python interpreter resolution is delegated to [PythonRunner]
 * so the same helper can be reused by Plan 02-13's `LabRowNormalizerTest`.
 */
class DefinitionDbConsistencyTest {

    @Test
    fun python_definition_db_matches_kotlin_definition_db() {
        val parsed = Json.parseToJsonElement(dumpPythonDb()).jsonObject

        // Per-entry comparison (D-09 Claude's Discretion: per-entry for actionable diffs).
        val pythonKeys = parsed.keys
        val kotlinKeys = DefinitionDb.ENTRIES.keys

        val missingInKotlin = pythonKeys - kotlinKeys
        val extraInKotlin = kotlinKeys - pythonKeys
        assertEquals(
            "Kotlin DefinitionDb is missing keys present in Python: $missingInKotlin",
            emptySet<String>(),
            missingInKotlin,
        )
        assertEquals(
            "Kotlin DefinitionDb has extra keys not present in Python: $extraInKotlin",
            emptySet<String>(),
            extraInKotlin,
        )

        for (key in pythonKeys) {
            val pyEntry: JsonArray = parsed.getValue(key).jsonArray
            val pyDef = pyEntry[0].jsonPrimitive.content
            val pyUrl = pyEntry[1].jsonPrimitive.content
            val pyLabel = pyEntry[2].jsonPrimitive.content

            val ktEntry = DefinitionDb.ENTRIES[key]
            assertNotNull("Missing canonical=$key in Kotlin", ktEntry)
            assertEquals("definition drift for canonical=$key", pyDef, ktEntry!!.definition)
            assertEquals("citationUrl drift for canonical=$key", pyUrl, ktEntry.citationUrl)
            assertEquals("citationLabel drift for canonical=$key", pyLabel, ktEntry.citationLabel)
        }
    }

    @Test
    fun entry_count_matches() {
        val parsed = Json.parseToJsonElement(dumpPythonDb()).jsonObject
        assertEquals(
            "Python and Kotlin DefinitionDb entry counts must match",
            parsed.size,
            DefinitionDb.ENTRIES.size,
        )
    }

    private fun dumpPythonDb(): String {
        val repoRoot = locateRepoRoot()
        val py = PythonRunner.resolve()
        // Extract _DEFINITION_DB from the parser source via Python AST WITHOUT
        // importing the module. Direct import would require `pdfplumber` (a runtime
        // dep of lab_report_parser at module load time) to be installed in the
        // resolved interpreter — which is not guaranteed on dev machines where
        // PythonRunner may pick a Python that lacks project deps. AST evaluation
        // works on any standard-library-only Python 3.10+.
        //
        // We write the script to a temp file rather than passing via `python -c "..."`
        // because Java ProcessBuilder on Windows mangles embedded double-quotes in
        // inline command strings (observed 2026-05-13: Python receives the path
        // unquoted, producing a SyntaxError). Writing the script to a file sidesteps
        // quoting entirely and is also clearer to reproduce manually if the test fails.
        val parserSourcePath = File(repoRoot, "tools/parsers/lab_report_parser.py")
            .absolutePath.replace("\\", "/")
        val script = buildString {
            append("import ast, json\n")
            append("source_path = r'''")
            append(parserSourcePath)
            append("'''\n")
            append("with open(source_path, encoding='utf-8') as f:\n")
            append("    tree = ast.parse(f.read(), filename=source_path)\n")
            append("found = None\n")
            append("for node in ast.walk(tree):\n")
            append("    if isinstance(node, ast.AnnAssign) and isinstance(node.target, ast.Name) and node.target.id == '_DEFINITION_DB':\n")
            append("        found = node.value\n")
            append("        break\n")
            append("    if isinstance(node, ast.Assign):\n")
            append("        for t in node.targets:\n")
            append("            if isinstance(t, ast.Name) and t.id == '_DEFINITION_DB':\n")
            append("                found = node.value\n")
            append("                break\n")
            append("        if found is not None:\n")
            append("            break\n")
            append("assert found is not None, '_DEFINITION_DB not found in source'\n")
            append("db = ast.literal_eval(found)\n")
            append("out = {k: list(v) for k, v in db.items()}\n")
            append("print(json.dumps(out, ensure_ascii=False))\n")
        }
        val scriptFile = File.createTempFile("aegis-defdb-parity-", ".py").apply {
            deleteOnExit()
            writeText(script, Charsets.UTF_8)
        }
        val pb = ProcessBuilder(py, scriptFile.absolutePath)
            .directory(repoRoot)
            .redirectErrorStream(false)
        // Force the child to emit UTF-8 on stdout; otherwise Windows Python uses cp1252
        // by default and any non-ASCII MedlinePlus characters (em-dash, smart quotes)
        // would be mis-decoded on the JVM side.
        pb.environment()["PYTHONIOENCODING"] = "utf-8"
        val proc = pb.start()
        // Read stdout as raw UTF-8 bytes to bypass platform default charset (cp1252
        // on Windows would mangle non-ASCII characters in MedlinePlus definitions
        // such as the em-dash in "two types of proteins — albumin").
        val stdout = proc.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
        val stderr = proc.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val exit = proc.waitFor()
        check(exit == 0) {
            "python subprocess exit=$exit; stderr=$stderr"
        }
        return stdout.trim()
    }

    /**
     * Walk up from the JVM test runner's working directory until we find a marker
     * directory containing both `CLAUDE.md` and `kb/` — these uniquely identify the
     * aegis-health repo root. Gradle typically runs JVM tests from `android/app/`,
     * so the walk is at most 2 levels.
     */
    private fun locateRepoRoot(): File {
        var dir: File? = File("").absoluteFile
        repeat(8) {
            val cur = dir ?: return@repeat
            if (File(cur, "CLAUDE.md").exists() && File(cur, "kb").exists()) {
                return cur
            }
            dir = cur.parentFile
        }
        error("Could not locate repo root from ${File("").absolutePath}")
    }

    @Suppress("unused")
    private fun JsonPrimitive.contentString(): String = content
}
