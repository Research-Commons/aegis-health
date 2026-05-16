package com.aegis.health.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JVM source-scanning structural test for Phase 9 / Plan 09-01 — HOME-05
 * (D-05b) and ROADMAP Phase 9 SC #2. Codifies the phase-close grep gate
 * `grep -rEn "EngineRouter|KBDatabase|LiteRtLmEngine" ui/home/ ui/startup/`
 * as a permanent JVM regression test so future PRs cannot silently
 * reintroduce a direct engine read from the home or startup module
 * (PITFALLS C5 — StartupState gate broken by hero polish).
 *
 * The instrumented Compose UI analog is unavailable — TEST-FRAMEWORK-01
 * BOM-2026.05.00 carry-over from Phase 5; migration deferred to Phase 10
 * P1 stretch. This JVM source-scanning gate is the agreed substitute
 * (see 09-RESEARCH.md §Test Surface + Phase 6 precedent
 * `FlagPreviewWiringParityTest`). It runs on every `:app:testDebugUnitTest`
 * invocation, has zero device dependency, and fails meaningfully if any
 * file under `ui/home/` or `ui/startup/` references the forbidden
 * engine-layer symbols.
 *
 * Skeleton shape (projectRoot + concatKotlinSources helpers + JUnit 4)
 * mirrors FlagPreviewWiringParityTest.kt:56-124 verbatim. Wave 2 plans
 * 09-02 + 09-03 extend this class with additional @Test methods
 * (`homeScreenHasExactlyFourFeatureCards`,
 * `everyFeatureCardOnClickBindsToAnExpectedRoute`,
 * `homeScreenHasNoForbiddenN1WordsOrBenchTile`).
 */
class HomeScreenStructureTest {

    // ── Helpers ─────────────────────────────────────────────────────────

    /**
     * Walks up from `File("").absoluteFile` looking for the Aegis repo
     * root. Uses two TRACKED sentinel markers (`CLAUDE.md` + `kb/`) so
     * the locator works on fresh clones / CI runners where `.planning/`
     * (gitignored at `.gitignore:115`) is absent. Mirrors
     * FlagPreviewWiringParityTest.kt:56-66 — Phase 6 precedent.
     */
    private fun projectRoot(): File {
        var dir: File? = File("").absoluteFile
        repeat(10) {
            val cur = dir ?: return@repeat
            if (File(cur, "CLAUDE.md").exists() && File(cur, "kb").isDirectory) {
                return cur
            }
            dir = cur.parentFile
        }
        error("Could not locate Aegis repo root from ${File("").absolutePath}")
    }

    /**
     * Convenience accessor — Wave 2 (Plans 09-02 + 09-03) reads HomeScreen.kt
     * source for the four-tile / forbidden-N1-word / Bench gate assertions.
     */
    @Suppress("unused")
    private fun homeScreenSource(): String = File(
        projectRoot(),
        "android/app/src/main/java/com/aegis/health/ui/home/HomeScreen.kt",
    ).readText()

    /**
     * Convenience accessor — Wave 2 (Plan 09-02) reads MainActivity.kt source
     * for the route-literal NavHost assertion.
     */
    @Suppress("unused")
    private fun mainActivitySource(): String = File(
        projectRoot(),
        "android/app/src/main/java/com/aegis/health/MainActivity.kt",
    ).readText()

    /** Recursively concatenates the source text of every `.kt` file under [dir]. */
    private fun concatKotlinSources(dir: File): String {
        if (!dir.isDirectory) return ""
        val sb = StringBuilder()
        dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach {
                sb.append("// FILE: ").append(it.relativeTo(dir).path).append('\n')
                sb.append(it.readText()).append('\n')
            }
        return sb.toString()
    }

    // ── Tests ───────────────────────────────────────────────────────────

    @Test
    fun noEngineSymbolsLeakIntoHomeOrStartupModules() {
        val root = projectRoot()
        val homeDir = File(root, "android/app/src/main/java/com/aegis/health/ui/home")
        val startupDir = File(root, "android/app/src/main/java/com/aegis/health/ui/startup")
        assertTrue("ui/home/ directory must exist at $homeDir", homeDir.isDirectory)
        assertTrue("ui/startup/ directory must exist at $startupDir", startupDir.isDirectory)

        val combined = concatKotlinSources(homeDir) + "\n" + concatKotlinSources(startupDir)
        val forbiddenSymbols = listOf("EngineRouter", "KBDatabase", "LiteRtLmEngine")
        forbiddenSymbols.forEach { symbol ->
            val pattern = Regex("""\b${Regex.escape(symbol)}\b""")
            val hits = pattern.findAll(combined).toList()
            assertTrue(
                "No file under ui/home/ or ui/startup/ may reference `$symbol` — " +
                    "engine state flows through AegisApp.instance.startup only " +
                    "(HOME-05, PITFALLS C5). Found ${hits.size} match(es). " +
                    "Route warm-up calls through AegisApp.warmUpEngine() instead.",
                hits.isEmpty(),
            )
        }
    }

    /**
     * Combined D-01f (HOME-01 SC-1 regulatory audit) + N7 prevention gate.
     *
     * D-01f: HomeScreen.kt may not contain any of the N1 forbidden words
     * (`diagnose`, `diagnosis`, `treatment`, `prescription advice`,
     * `medical advice`, `AI doctor`) as case-insensitive whole-word matches.
     * The word `check` is N1-safe (the italic prompt `"What can I check for
     * you?"` is preserved by D-01d).
     *
     * N7: HomeScreen.kt may not reference `BatteryBenchScreen` or contain
     * the route literal `"bench"`. The four-tile hero composition (DrugSafe,
     * ConsentReader, HealthPartner, ReportReader) is fixed at four; the
     * Bench surface remains reachable via Profile → Bench in MainActivity's
     * NavHost, never from Home.
     *
     * The ValuePropChip text literal `"Offline · KB-grounded · Cite-or-defer."`
     * lives in `Chips.kt`, not in HomeScreen.kt, so this scan does not
     * directly assert on the chip copy — that contract is enforced by the
     * call-site grep gates in 09-02-PLAN.md `<verification>` and the
     * compile-time import binding.
     */
    @Test
    fun homeScreenHasNoForbiddenN1WordsOrBenchTile() {
        val src = homeScreenSource()
        // D-01f — N1 forbidden words. Case-insensitive whole-word.
        val forbidden = listOf(
            "diagnose",
            "diagnosis",
            "treatment",
            "prescription advice",
            "medical advice",
            "AI doctor",
        )
        forbidden.forEach { word ->
            val pattern = Regex("""(?i)\b${Regex.escape(word)}\b""")
            assertFalse(
                "HomeScreen.kt must not contain the regulatory-ambiguous word " +
                    "`$word` (PITFALLS N1, HOME-01 SC-1). Hero copy may not " +
                    "imply medical advice or diagnosis. Match found at offset: " +
                    "${pattern.find(src)?.range?.first ?: -1}.",
                pattern.containsMatchIn(src),
            )
        }
        // N7 — no Bench fifth tile. The BatteryBenchScreen symbol or the
        // "bench" route literal must not appear in HomeScreen.kt. The check is
        // scoped to HomeScreen.kt only — MainActivity.kt legitimately wires
        // Routes.Bench → BatteryBenchScreen in its NavHost.
        assertFalse(
            "HomeScreen.kt must not reference BatteryBenchScreen — the four-tile " +
                "composition (DrugSafe, ConsentReader, HealthPartner, ReportReader) " +
                "must NOT gain a Bench fifth tile (PITFALLS N7). Bench is reachable " +
                "via Profile → Bench in MainActivity's NavHost, not from Home.",
            src.contains("BatteryBenchScreen"),
        )
        assertFalse(
            "HomeScreen.kt must not contain the route literal \"bench\" — same N7 " +
                "rationale as the BatteryBenchScreen reference assertion above.",
            src.contains("\"bench\""),
        )
    }

    /**
     * Wave 2 Plan 09-03 — HOME-02 SC-2 four-tile composition lock.
     *
     * Asserts HomeScreen.kt contains exactly 4 `FeatureCard(...)` call sites
     * (DrugSafe, ConsentReader, HealthPartner, ReportReader) — excluding the
     * `private fun FeatureCard(` declaration. Guards against:
     * - Accidental Bench fifth-tile addition (PITFALLS N7 — Bench is reachable
     *   via Profile → Bench in MainActivity's NavHost, never from Home).
     * - Accidental tile removal (HOME-02 SC-2 — four-mode hero composition).
     *
     * Body shape mirrors 09-RESEARCH.md §Method 1.1 (~lines 488-508).
     */
    @Test
    fun homeScreenHasExactlyFourFeatureCards() {
        val src = homeScreenSource()
        // Count `FeatureCard(` substring occurrences. The lookbehind excludes
        // inside-identifier matches (e.g. `SomeFeatureCard(` would NOT match;
        // only word-boundary `FeatureCard(` does).
        val callPattern = Regex("""(?<![a-zA-Z_])FeatureCard\(""")
        val invocations = callPattern.findAll(src).count()
        // Subtract the `private fun FeatureCard(` declaration (1 line) so we
        // count call sites only. The declaration itself ALSO matches the
        // callPattern regex (since `private fun ` ends with a space, not a
        // word character), so this subtraction is load-bearing.
        val declarationCount = src.split('\n').count { it.contains("private fun FeatureCard(") }
        val callCount = invocations - declarationCount
        assertEquals(
            "HomeScreen.kt must contain exactly 4 FeatureCard(...) call sites — " +
                "the canonical four-tile composition (DrugSafe, ConsentReader, " +
                "HealthPartner, ReportReader). Adding a Bench fifth tile violates " +
                "PITFALLS N7. Removing any tile violates HOME-02 SC-2. Found $callCount.",
            4,
            callCount,
        )
    }

    /**
     * Wave 2 Plan 09-03 — HOME-02 SC-2 tile-route binding integrity.
     *
     * Asserts each of the 4 expected mode routes (`drugsafe`, `consent`,
     * `partner`, `reportreader`) appears in HomeScreen.kt as
     * `onOpen("route")` AND appears as a literal in MainActivity.kt
     * (in `object Routes` or as a `composable(...)` destination).
     *
     * Catches rename drift: if a future PR renames a route in MainActivity
     * but forgets HomeScreen (or vice versa), the tile tap navigates
     * nowhere and the test fails at JVM-test time.
     *
     * Body shape mirrors 09-RESEARCH.md §Method 1.2 (~lines 518-545).
     */
    @Test
    fun everyFeatureCardOnClickBindsToAnExpectedRoute() {
        val homeSrc = homeScreenSource()
        val mainSrc = mainActivitySource()
        val expectedRoutes = listOf("drugsafe", "consent", "partner", "reportreader")
        expectedRoutes.forEach { route ->
            // Pattern A: HomeScreen must invoke onOpen("route") for this mode.
            val homePattern = """onOpen\("$route"\)"""
            assertTrue(
                "HomeScreen.kt must contain an `onOpen(\"$route\")` call — " +
                    "the canonical wiring for the $route mode tile. See HOME-02 " +
                    "SC-2 four-tile composition. Missing pattern `$homePattern`.",
                Regex(homePattern).containsMatchIn(homeSrc),
            )
            // Pattern B: MainActivity must contain the route literal (either
            // in object Routes or as a composable destination). If the route
            // is renamed in MainActivity but not HomeScreen (or vice versa),
            // the tile tap navigates nowhere — this assertion catches it.
            val mainPattern = "\"$route\""
            assertTrue(
                "MainActivity.kt must contain the route literal \"$route\" — " +
                    "either in object Routes or as a composable destination. " +
                    "If the route is renamed in MainActivity but not HomeScreen, " +
                    "the tile tap navigates nowhere.",
                mainSrc.contains(mainPattern),
            )
        }
    }

    // Wave 2 / Wave 3 navigation hint — preserved so future waves can find
    // the canonical append point for additional structural @Test methods.
    // Plans 09-04 / 09-05 may add more tests below this marker.
    @Suppress("unused")
    private val waveTwoExtensionPoint: Unit = Unit
}
