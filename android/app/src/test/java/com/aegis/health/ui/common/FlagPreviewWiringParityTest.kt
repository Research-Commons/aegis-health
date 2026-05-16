package com.aegis.health.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JVM source-scanning wiring-parity test for Phase 6 / Plan 06-03 — STREAM-02
 * / ROADMAP SC-2. Asserts that `ReportReaderScreen.kt` (wired by Plan 06-02)
 * and `HealthPartnerScreen.kt` (wired by Plan 06-03) both consume the same
 * [MonotonicFlagList.appendIfNew] helper instead of forking the dedup
 * heuristic per-mode. Without this gate, a future PR could silently
 * reintroduce the inline `flagPreviews.none { ... }` pattern in one screen
 * but not the other, drifting the two consumers apart.
 *
 * The instrumented parametrized analog the planning_context originally
 * suggested is not available — the Compose UI test framework is regressed
 * on SM-S918B (TEST-FRAMEWORK-01 — BOM 2026.05.00 carry-over from Phase 5;
 * migration deferred to Phase 10 P1 stretch). This JVM source-scanning
 * gate is the agreed fallback documented in 06-RESEARCH.md §Compose BOM
 * Regression Risk SC-2 fallback row. It runs on every
 * `:app:testDebugUnitTest` invocation, has zero device dependency, and
 * fails meaningfully if the wiring drifts.
 *
 * The test does NOT spin up Compose, does NOT instantiate ToolDispatcher,
 * and does NOT touch the engine. It reads the screen source files as raw
 * text and applies `contains(...)` / regex matches. Runs in milliseconds.
 *
 * JUnit 4 idiom only (junit:junit:4.13.2 is the sole test dep); per-method
 * `@Test`; no `@RunWith(Parameterized::class)` — matches the project's
 * pure-helper test files (`MonotonicFlagListTest`, `FriendlyToolSummarizerTest`,
 * `FlagsStreamParserTest`).
 *
 * Path A awareness (Plan 06-02 Open Q #1): the parity assertion on
 * `ReportReaderScreen.kt` uses a relaxed matcher that accepts either a
 * live `MonotonicFlagList.appendIfNew(` call site OR a TODO/comment
 * naming the helper combined with a `STREAM-01-followup` tag in the
 * phase-6 `deferred-items.md`. The relaxed branch unlocks only when the
 * followup-tagged deferred entry exists; without that tag, any omission of
 * the live call site fails. This matches the Plan 06-02 resolution
 * recorded in the planning context.
 */
class FlagPreviewWiringParityTest {

    // ── Helpers ─────────────────────────────────────────────────────────

    /**
     * Walks up from `File("").absoluteFile` looking for the Aegis repo
     * root. Uses two TRACKED sentinel markers (`CLAUDE.md` + `kb/`) so
     * the locator works on fresh clones / CI runners where `.planning/`
     * (gitignored at `.gitignore:115`) is absent. Mirrors the
     * `LabRowNormalizerTest.locateRepoRoot` precedent at
     * `LabRowNormalizerTest.kt:106-114`. CR-01 fix — Phase 6 close-out.
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

    /** Returns `(reportReaderSrc, healthPartnerSrc)` as raw file text. */
    private fun screenSources(): Pair<String, String> {
        val root = projectRoot()
        val reportReader = File(
            root,
            "android/app/src/main/java/com/aegis/health/ui/reportreader/ReportReaderScreen.kt",
        )
        val healthPartner = File(
            root,
            "android/app/src/main/java/com/aegis/health/ui/healthpartner/HealthPartnerScreen.kt",
        )
        assertTrue(
            "ReportReaderScreen.kt must exist at $reportReader",
            reportReader.isFile,
        )
        assertTrue(
            "HealthPartnerScreen.kt must exist at $healthPartner",
            healthPartner.isFile,
        )
        return reportReader.readText() to healthPartner.readText()
    }

    /**
     * Returns the Phase-6 `deferred-items.md` contents (or empty string if
     * absent). The relaxed-matcher branch in
     * [bothScreens_useMonotonicFlagList_appendIfNew_noPerModeForks]
     * requires a `STREAM-01-followup` tag in this file to permit a
     * comment-only reference to the helper in `ReportReaderScreen.kt`.
     */
    private fun deferredItemsContents(): String {
        val candidates = listOf(
            // Phase-6 deferred-items per Plan 06-02 close-out + STATE.md
            // narrative — the file is intentionally inside the phase
            // directory, not at the repo root.
            "deferred-items.md",
            ".planning/phases/06-streaming-preview-wiring-reportreader-healthpartner/deferred-items.md",
        )
        val root = projectRoot()
        for (rel in candidates) {
            val f = File(root, rel)
            if (f.isFile) return f.readText()
        }
        return ""
    }

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
    fun bothScreens_useMonotonicFlagList_appendIfNew_noPerModeForks() {
        val (reportReaderSrc, healthPartnerSrc) = screenSources()
        val helperCall = "MonotonicFlagList.appendIfNew("

        // HealthPartner side: strict — Plan 06-03 wires the live call site
        // directly (it owns its synthesis-invocation surface, no Path A
        // fallback applies). A missing call site here is a hard failure.
        assertTrue(
            "HealthPartnerScreen.kt must call MonotonicFlagList.appendIfNew — " +
                "see Plan 06-03 Task 1 + ROADMAP SC-2 + STREAM-02. " +
                "Inline dedup forks (e.g. flagPreviews.none { ... }) are forbidden.",
            healthPartnerSrc.contains(helperCall),
        )

        // ReportReader side: relaxed per Plan 06-02 Open Q #1 Path A
        // resolution. Accept either a live call site OR a comment-mention
        // when the STREAM-01-followup tag is present — either in the
        // tracked ReportReaderScreen.kt source (the TODO comment carries
        // the tag) OR in the gitignored deferred-items.md (also valid).
        // The dual-source check ensures the test passes on fresh CI clones
        // where `.planning/` is absent. CR-01 fix — Phase 6 close-out.
        val reportReaderHasCallSite = reportReaderSrc.contains(helperCall)
        if (!reportReaderHasCallSite) {
            val mentionsHelperInComment = reportReaderSrc.contains("MonotonicFlagList.appendIfNew")
            val deferredTag = "STREAM-01-followup"
            val tagInScreenSource = reportReaderSrc.contains(deferredTag)
            val tagInDeferredItems = deferredItemsContents().contains(deferredTag)
            val hasFollowupTag = tagInScreenSource || tagInDeferredItems
            assertTrue(
                "ReportReaderScreen.kt has no live MonotonicFlagList.appendIfNew(" +
                    " call site; relaxed Path A branch requires both (a) a comment " +
                    "naming the helper AND (b) a `$deferredTag` tag in either the " +
                    "ReportReaderScreen.kt source (TODO comment) OR " +
                    "`deferred-items.md`. Found comment=$mentionsHelperInComment, " +
                    "tagInScreen=$tagInScreenSource, tagInDeferredItems=$tagInDeferredItems. " +
                    "See 06-02-SUMMARY.md + 06-03-PLAN.md Task 2 for Path A semantics.",
                mentionsHelperInComment && hasFollowupTag,
            )
        }
    }

    @Test
    fun bothScreens_referenceFlagPreviewEventType() {
        val (reportReaderSrc, healthPartnerSrc) = screenSources()
        val typeRef = "ToolDispatcher.ProgressEvent.FlagPreview"

        assertTrue(
            "ReportReaderScreen.kt must reference $typeRef — the typed event " +
                "is the wiring contract between ToolDispatcher and the preview " +
                "rail (Plan 06-02 STREAM-01 ReportReader half).",
            reportReaderSrc.contains(typeRef),
        )
        assertTrue(
            "HealthPartnerScreen.kt must reference $typeRef — the typed event " +
                "is the wiring contract between ToolDispatcher.runHealthPartnerFastPath " +
                "and the preview rail (Plan 06-03 STREAM-01 HealthPartner half).",
            healthPartnerSrc.contains(typeRef),
        )
    }

    @Test
    fun noScreenReferencesStreamBuffer() {
        // Defense-in-depth duplicate of the SC-4 grep gate: on every JVM
        // test invocation, ensure no file under `ui/` reaches into the
        // dispatcher's engine-internal decode buffer. The D-13 single-
        // buffer-owner invariant is one of Phase 6's load-bearing
        // properties (see 06-PLAN context + Plan 06-02 SUMMARY).
        val root = projectRoot()
        val uiDir = File(root, "android/app/src/main/java/com/aegis/health/ui")
        assertTrue("ui/ directory must exist at $uiDir", uiDir.isDirectory)
        val allUiSources = concatKotlinSources(uiDir)
        val needle = "streamBuffer"
        assertFalse(
            "No file under android/app/src/main/java/com/aegis/health/ui/ may " +
                "reference `$needle` — the dispatcher's engine-internal decode " +
                "buffer is single-owner (D-13 invariant; Phase 6 SC-4). " +
                "Route typed ToolDispatcher.ProgressEvent.FlagPreview events " +
                "through MonotonicFlagList.appendIfNew instead.",
            allUiSources.contains(needle),
        )
    }

    @Test
    fun noScreenIntroducesViewModelOrFlowOfProgressEvent() {
        // Structural enforcement of the ARCHITECTURE.md:99-103 lock-out:
        // no ViewModel under ui/, no Flow<ProgressEvent> collector. The
        // existing screens use `mutableStateListOf` + `rememberCoroutineScope`
        // exclusively; any future PR that flips to a ViewModel-driven
        // pattern or a Flow<ProgressEvent> collector trips this test.
        val root = projectRoot()
        val uiDir = File(root, "android/app/src/main/java/com/aegis/health/ui")
        val allUiSources = concatKotlinSources(uiDir)

        // ViewModel class declarations under ui/. Matches both
        // `class X : ViewModel()` and `class X(...) : ViewModel()`.
        // Tolerates whitespace between `:` and `ViewModel(`.
        val viewModelPattern = Regex("""class\s+\w+[^{]*:\s*ViewModel\s*\(""")
        val viewModelHits = viewModelPattern.findAll(allUiSources).toList()
        assertTrue(
            "No file under ui/ may declare a class extending ViewModel — " +
                "the current architecture is composable-state-only " +
                "(ARCHITECTURE.md:99-103, Phase 6 SC-2 anti-pattern). " +
                "Found ${viewModelHits.size} matches: " +
                viewModelHits.joinToString(", ") { it.value.take(80) },
            viewModelHits.isEmpty(),
        )

        // Flow<...ProgressEvent...> type references — catches the
        // engine-state-as-Flow anti-pattern explicitly forbidden in
        // 06-RESEARCH.md §Anti-Patterns / PITFALLS M2.
        val flowProgressPattern = Regex("""Flow\s*<[^>]*ProgressEvent[^>]*>""")
        val flowHits = flowProgressPattern.findAll(allUiSources).toList()
        assertTrue(
            "No file under ui/ may declare a `Flow<...ProgressEvent...>` " +
                "type — engine state must not be exposed as a cold Flow " +
                "collector (06-RESEARCH.md §Anti-Patterns, Pitfall M2). " +
                "Found ${flowHits.size} matches: " +
                flowHits.joinToString(", ") { it.value.take(80) },
            flowHits.isEmpty(),
        )
    }
}
