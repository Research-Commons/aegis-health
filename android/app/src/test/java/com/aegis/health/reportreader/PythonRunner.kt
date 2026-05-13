package com.aegis.health.reportreader

/**
 * F-08 remediation: locate a Python interpreter for cross-language consistency tests.
 *
 * Tries (in order): system property `aegis.python` override, `.venv/Scripts/python.exe`
 * (Windows venv), `.venv/bin/python` (Unix venv), `python3`, `python`. Returns the first
 * candidate that responds to `--version` with exit code 0. Throws if none work.
 *
 * Used by:
 *   - DefinitionDbConsistencyTest (Plan 02-06; D-09 cross-language parity gate)
 *   - LabRowNormalizerTest.python_alias_map_matches_kotlin_alias_map (Plan 02-13, future)
 *
 * Lives in `src/test/` because it is only used by JVM unit tests; it has no place on
 * the Android runtime classpath.
 */
object PythonRunner {
    fun resolve(): String {
        val candidates = listOfNotNull(
            System.getProperty("aegis.python")?.takeIf { it.isNotBlank() },
            ".venv/Scripts/python.exe",
            ".venv/bin/python",
            "python3",
            "python",
        )
        val found = candidates.firstOrNull { candidate ->
            runCatching {
                ProcessBuilder(candidate, "--version")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor() == 0
            }.getOrDefault(false)
        }
        return found ?: error(
            "No python interpreter found on PATH. Tried: $candidates. " +
                "Override with `-Daegis.python=/path/to/python`.",
        )
    }
}
