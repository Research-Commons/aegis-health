package com.aegis.health.ui.common

import com.aegis.health.inference.ToolDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the [MonotonicFlagList.appendIfNew] contract per Plan 06-02 / ROADMAP
 * SC-5 (monotonic-growth guard) / Pitfall M2 (state flapping) mitigation.
 *
 * Contract (defense-in-depth):
 *  - `result.size >= previous.size` always — list NEVER shrinks.
 *  - Dedup tuple is `(description, citation)` — matches DrugSafe's existing
 *    inline filter at `DrugSafeScreen.kt:200` and what the new
 *    ReportReaderScreen / HealthPartnerScreen consumers must rely on.
 *  - Pure: no I/O, no shared state, no Compose dependency. Safe to call from
 *    any thread; no Composable scope required.
 *
 * JUnit 4 idiom only (junit:junit:4.13.2 is the sole test dep); per-method
 * `@Test`; no `@RunWith(Parameterized::class)`. Matches the project's other
 * pure-helper test files (`FriendlyToolSummarizerTest`,
 * `LiteRtLmEngineStreamSplitTest`, `FlagsStreamParserTest`).
 *
 * Plan 06-03 (HealthPartner) will import the same helper, so any regression
 * here lights up on every JVM test run before either Plan 06-02 or 06-03 can
 * ship a divergent inline dedup.
 */
class MonotonicFlagListTest {

    // ── Helpers ─────────────────────────────────────────────────────────

    /**
     * Builds a [ToolDispatcher.ProgressEvent.FlagPreview] with the common
     * defaults used by every test below. Severity defaults to 3 (moderate);
     * citation defaults to the verbatim "FDA label" string the parser emits
     * after `humanizeCitationSource` passes it through unchanged (FDA label
     * is not a registered code in the FDA_CODE_LABELS table, so it round-
     * trips byte-identical — same idiom as `FlagsStreamParserTest.kt:24`).
     */
    private fun fp(
        description: String,
        citation: String = "FDA label",
        severity: Int = 3,
    ): ToolDispatcher.ProgressEvent.FlagPreview =
        ToolDispatcher.ProgressEvent.FlagPreview(
            severity = severity,
            description = description,
            citation = citation,
        )

    // ── Append-if-new contract ──────────────────────────────────────────

    @Test
    fun appendIfNew_empty_plus_new_returns_singleton() {
        val result = MonotonicFlagList.appendIfNew(emptyList(), fp("A"))
        assertEquals(1, result.size)
        assertEquals("A", result[0].description)
        assertEquals("FDA label", result[0].citation)
    }

    @Test
    fun appendIfNew_dedups_duplicate_by_description_and_citation_tuple() {
        val previous = listOf(fp("A", "FDA label"))
        val result = MonotonicFlagList.appendIfNew(previous, fp("A", "FDA label"))
        assertEquals(
            "duplicate (description, citation) tuple must not append",
            1,
            result.size,
        )
        assertEquals("A", result[0].description)
    }

    @Test
    fun appendIfNew_appends_new_distinct_flag() {
        val previous = listOf(fp("A"))
        val result = MonotonicFlagList.appendIfNew(previous, fp("B"))
        assertEquals(2, result.size)
        assertEquals(
            "new flags append in arrival order — A before B",
            "A",
            result[0].description,
        )
        assertEquals("B", result[1].description)
    }

    @Test
    fun appendIfNew_dedups_middle_element_without_reordering() {
        val previous = listOf(fp("A"), fp("B"), fp("C"))
        val result = MonotonicFlagList.appendIfNew(previous, fp("B"))
        assertEquals(3, result.size)
        // Source order preserved — dedup must not rearrange the existing list.
        assertEquals("A", result[0].description)
        assertEquals("B", result[1].description)
        assertEquals("C", result[2].description)
    }

    @Test
    fun appendIfNew_never_returns_shorter_list() {
        // Exhaustive over a small enumeration: previous sizes 0..3, incoming
        // either matches an existing entry (dedup path) or is novel (append
        // path). Result size must always be >= previous size — the core M2
        // mitigation invariant for ROADMAP SC-5.
        val universe = listOf(fp("A"), fp("B"), fp("C"))
        for (size in 0..3) {
            val previous = universe.take(size)
            // 1. Novel-incoming path: appending a distinct entry must grow
            //    the list by exactly one (size + 1).
            val novel = fp("NEW-$size")
            val afterNovel = MonotonicFlagList.appendIfNew(previous, novel)
            assertTrue(
                "novel append must never shrink the list (size=$size)",
                afterNovel.size >= previous.size,
            )
            assertEquals(
                "novel append must grow the list by exactly one (size=$size)",
                previous.size + 1,
                afterNovel.size,
            )
            // 2. Duplicate-incoming path (only meaningful when previous is
            //    non-empty): re-feeding an existing entry must NOT change
            //    the size at all.
            if (previous.isNotEmpty()) {
                val duplicate = previous.first()
                val afterDup = MonotonicFlagList.appendIfNew(previous, duplicate)
                assertTrue(
                    "duplicate append must never shrink the list (size=$size)",
                    afterDup.size >= previous.size,
                )
                assertEquals(
                    "duplicate append must leave size unchanged (size=$size)",
                    previous.size,
                    afterDup.size,
                )
            }
        }
    }
}
