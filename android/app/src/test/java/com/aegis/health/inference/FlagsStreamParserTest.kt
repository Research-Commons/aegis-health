package com.aegis.health.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression suite for [ToolDispatcher].FlagsStreamParser — closes the
 * long-standing CONCERNS.md "FlagsStreamParser untested" gap (Phase 6 / SC-3,
 * requirement STREAM-03 — "Streaming preview never exposes raw partial JSON;
 * only completed `Flag` objects render").
 *
 * Pins the contract that `extractNewFlags` never emits a `FlagPreview` whose
 * description is mid-string, whose closing `}` has not arrived, or whose JSON
 * failed to fully parse — and that one (and only one) `FlagPreview` lands per
 * genuinely-closed flag object, even when the closing `}`, an embedded escape
 * sequence, or the `"description":` value itself is split across streamed
 * pieces.
 *
 * SC-3 mandates three named split-token cases (`splitInsideDescriptionValue`,
 * `splitAcrossClosingBrace`, `splitInsideEscapeSequence`); 7 additional
 * defensive cases close the remaining table entries from 06-RESEARCH.md
 * §FlagsStreamParser Current State (lines 775-787).
 *
 * Access path: the parser is `private class FlagsStreamParser` inside
 * `object ToolDispatcher`. To preserve that visibility while enabling JVM
 * coverage we go through a narrow `internal fun extractFlagPreviewsForTest`
 * wrapper on `ToolDispatcher` — same shape Phase 5 Plan 05-02 used when it
 * promoted `ToolCallBoundaryDetector` for the engine-side regression suite
 * (D-12). Each call instantiates a fresh `FlagsStreamParser`; tests fake
 * cross-piece parser-state continuity by reusing cumulative buffers, which
 * mirrors how `LiteRtLmEngine.inferSync` accumulates pieces into `sb` and
 * passes a growing `streamBuffer` snapshot to the parser. The
 * `findNextBalancedObject` scan is cursor-monotonic per piece anyway, so a
 * fresh-parser-per-cumulative-snapshot is observationally identical to the
 * production "same-parser-many-pieces" path for the buffer contents we feed
 * here (no piece introduces a closed object the previous piece already
 * emitted — we just keep growing the buffer until the closing `}` arrives).
 *
 * JUnit 4 idiom only (junit:junit:4.13.2 is the sole test dep); per-method
 * `@Test`; no `@RunWith(Parameterized::class)` — mirrors the project's other
 * inference/ JVM tests (`LiteRtLmEngineStreamSplitTest`,
 * `FriendlyToolSummarizerTest`).
 */
class FlagsStreamParserTest {

    // ── Helpers ─────────────────────────────────────────────────────────

    /**
     * Builds the AegisResponse-shaped JSON prefix up through the opening
     * `"flags":[` so each test only has to specify what comes inside the
     * array. The leading fields are exactly what the model emits before
     * `flags` in production (confidence + defer_to_professional), per
     * `AegisResponse` schema order.
     */
    private fun synthesizePrefix(): String =
        """{"confidence":"high","defer_to_professional":false,"flags":["""

    // ── SC-3 Mandated split-token cases ─────────────────────────────────

    /**
     * SC-3 case 1 — `"description":` value split across pieces. Snapshot 1
     * ends mid-string (`"par`), snapshot 2 closes the description string
     * (`tial text"`), snapshot 3 adds the citation field and the closing
     * `}` of the flag object. Only the third snapshot may emit a preview.
     */
    @Test fun splitInsideDescriptionValue() {
        val prefix = synthesizePrefix()
        val s1 = prefix + """{"severity":3,"description":"par"""
        val s2 = s1 + """tial text""""
        val s3 = s2 + ""","citation":"FDA label"}"""

        assertTrue(
            "snapshot 1 ends inside an open string — parser must not emit",
            ToolDispatcher.extractFlagPreviewsForTest(s1).isEmpty(),
        )
        assertTrue(
            "snapshot 2 closes the string but the flag's `}` has not arrived",
            ToolDispatcher.extractFlagPreviewsForTest(s2).isEmpty(),
        )
        val previews = ToolDispatcher.extractFlagPreviewsForTest(s3)
        assertEquals("third snapshot closes the flag — exactly one preview", 1, previews.size)
        assertEquals(3, previews[0].severity)
        assertEquals("partial text", previews[0].description)
        // humanizeCitationSource expands "FDA label" via word-boundary IGNORE_CASE
        // replacement against FDA_CODE_LABELS. The exact resolved string is
        // pinned at the call site rather than re-derived here to keep this
        // assertion stable against label-table edits.
        assertEquals("FDA label", previews[0].citation)
    }

    /**
     * SC-3 case 2 — closing `}` of a complete flag split across pieces.
     * Snapshot 1 contains every field of the flag but is missing the
     * closing `}`; snapshot 2 supplies the `}`. The first call must return
     * empty (cursor pinned at the open `{`); the second must emit exactly
     * one preview.
     */
    @Test fun splitAcrossClosingBrace() {
        val prefix = synthesizePrefix()
        val s1 = prefix + """{"severity":3,"description":"x","citation":"y""""
        val s2 = s1 + "}"

        assertTrue(
            "snapshot 1 — flag's closing `}` has not arrived",
            ToolDispatcher.extractFlagPreviewsForTest(s1).isEmpty(),
        )
        val previews = ToolDispatcher.extractFlagPreviewsForTest(s2)
        assertEquals("snapshot 2 — closing `}` lands; exactly one preview", 1, previews.size)
        assertEquals(3, previews[0].severity)
        assertEquals("x", previews[0].description)
    }

    /**
     * SC-3 case 3 — `\\` escape sequence split across pieces. Snapshot 1
     * ends with the lone backslash inside the description string (the
     * parser's `inString` + `escaped` walk must preserve the escape state
     * across calls implicitly — we re-feed cumulative buffers, so the
     * fresh-per-call parser re-walks from `cursor = flagsStart` and
     * arrives at the same `escaped = true` state at the boundary).
     * Snapshot 2 supplies the escaped char and closes the flag. The
     * `parseFlagPreview` step routes the closed object through
     * kotlinx.serialization, which decodes the `\\` JSON escape to a
     * single literal backslash — the assertion uses the Kotlin literal
     * `"a\\b"` (two chars: 'a', '\\', 'b' — backslash + b in JSON source,
     * backslash + b after decode).
     */
    @Test fun splitInsideEscapeSequence() {
        val prefix = synthesizePrefix()
        val s1 = prefix + """{"severity":3,"description":"a\"""
        val s2 = s1 + """\b","citation":"FDA label"}"""

        assertTrue(
            "snapshot 1 ends inside an open string with a pending escape",
            ToolDispatcher.extractFlagPreviewsForTest(s1).isEmpty(),
        )
        val previews = ToolDispatcher.extractFlagPreviewsForTest(s2)
        assertEquals("snapshot 2 closes the flag — exactly one preview", 1, previews.size)
        assertEquals(3, previews[0].severity)
        // After JSON-unescape, "a\\b" in JSON source (two backslashes + b) becomes
        // "a\b" — a 3-char Kotlin string (a, single backslash, b). The Kotlin
        // literal for that 3-char string is "a\\b".
        assertEquals("a\\b", previews[0].description)
    }

    // ── Defensive coverage — Pitfalls #5–#6 + 06-RESEARCH.md table ─────

    /**
     * Tool-call selection turns (turn 0 of the agentic loop) emit the
     * `<|tool_call>call:...<tool_call|>` block, which never contains the
     * substring `"flags":[`. The parser must produce zero events on such
     * input — defensive coverage of Pitfall #6 (a future refactor that
     * moves the parser invocation outside `if (turn > 0)` would surface
     * here).
     */
    @Test fun toolCallArgsDoNotTrigger() {
        val toolCallBuffer =
            "<|tool_call>call:check_warnings{drug_list:[warfarin],age:72}<tool_call|>"
        assertTrue(
            "tool-call selection turns must not produce FlagPreview events",
            ToolDispatcher.extractFlagPreviewsForTest(toolCallBuffer).isEmpty(),
        )
    }

    /**
     * Stuck-open string literal — closing `"` never arrives. The parser
     * must terminate in bounded O(buffer length) time without crash or
     * hang; the JUnit default per-test timeout is generous (effectively
     * none) but the assertion still completes well under a second on
     * JVM. Defensive coverage of Pitfall #5 + T-06-01-T2 mitigation.
     */
    @Test fun stuckOpenStringDoesNotHang() {
        val prefix = synthesizePrefix()
        val stuck = prefix + """{"severity":3,"description":"never closes..."""
        // Single call — must return empty without hanging. (No explicit
        // timeout annotation: the body is straight-line O(n) and JUnit's
        // default test runtime is more than sufficient to surface a hang.)
        assertTrue(
            "unterminated string must yield zero previews and no hang",
            ToolDispatcher.extractFlagPreviewsForTest(stuck).isEmpty(),
        )
    }

    /**
     * AegisResponse fragment before `"flags":` is emitted by the model.
     * The parser anchors on `"flags":[`; without that substring, it
     * returns empty immediately and leaves `flagsStart` unset.
     */
    @Test fun flagsKeyNotInBufferReturnsEmpty() {
        val noFlagsYet = """{"confidence":"high","defer_to_professional":false,"""
        assertTrue(
            "no `\"flags\":` anchor yet — parser must return empty",
            ToolDispatcher.extractFlagPreviewsForTest(noFlagsYet).isEmpty(),
        )
    }

    /**
     * Two complete flag objects inside a single buffer scan. The outer
     * `while` in `extractNewFlags` must loop until `Pending` or
     * `ArrayClosed`, emitting both in source order.
     */
    @Test fun twoFlagsInOneBufferEmitBoth() {
        val prefix = synthesizePrefix()
        val buffer = prefix +
            """{"severity":2,"description":"first","citation":"FDA label"},""" +
            """{"severity":4,"description":"second","citation":"USPSTF"}]"""
        val previews = ToolDispatcher.extractFlagPreviewsForTest(buffer)
        assertEquals("both flags must be emitted in one call", 2, previews.size)
        assertEquals("first", previews[0].description)
        assertEquals(2, previews[0].severity)
        assertEquals("second", previews[1].description)
        assertEquals(4, previews[1].severity)
    }

    /**
     * Closing `]` of the flags array disables the parser for the rest of
     * the synthesis. Even if a subsequent (artificial) buffer adds another
     * `{...}` after the closing `]`, the parser stays `done` and returns
     * empty. Note: each call here instantiates a FRESH parser via the
     * `extractFlagPreviewsForTest` wrapper, so we cannot test the
     * `done = true` state-stickiness directly across calls. We instead
     * pin the equivalent invariant via buffer shape: a buffer with `[]`
     * (no objects) emits zero; a buffer with `[]` followed by trailing
     * `{...}` text also emits zero — proving the array-closed branch fires
     * before the trailing object is scanned.
     */
    @Test fun arrayClosedDisablesParser() {
        val prefix = synthesizePrefix()
        // Snapshot 1: empty flags array.
        val s1 = prefix + "]"
        assertTrue(
            "empty `\"flags\":[]` — parser must emit zero",
            ToolDispatcher.extractFlagPreviewsForTest(s1).isEmpty(),
        )
        // Snapshot 2: same shape, then trailing `{...}` AFTER the closing
        // `]`. The parser must still emit zero — the array-closed branch
        // returns before any post-`]` scan would happen.
        val s2 = s1 + ""","after":{"severity":5,"description":"ignored","citation":"x"}}"""
        assertTrue(
            "objects after the closing `]` must be ignored",
            ToolDispatcher.extractFlagPreviewsForTest(s2).isEmpty(),
        )
    }

    /**
     * `"flags": null` — non-array value disables the parser (`done = true`
     * on first scan). Subsequent (extended) buffer calls return empty too,
     * pinned by the same fresh-parser observational equivalence as
     * `arrayClosedDisablesParser`.
     */
    @Test fun flagsNullDisablesParser() {
        val nullFlags =
            """{"confidence":"high","defer_to_professional":false,"flags": null,"""
        assertTrue(
            "`\"flags\": null` — parser must emit zero",
            ToolDispatcher.extractFlagPreviewsForTest(nullFlags).isEmpty(),
        )
        // Extended buffer — even if more content arrives after the null,
        // the parser must still return empty.
        val nullFlagsExtended = nullFlags +
            """"trailing":[{"severity":5,"description":"x","citation":"y"}]}"""
        assertTrue(
            "subsequent extended buffer past `\"flags\": null` — parser still emits zero",
            ToolDispatcher.extractFlagPreviewsForTest(nullFlagsExtended).isEmpty(),
        )
    }

    /**
     * Malformed flag (severity=0 AND blank description) inside the array,
     * followed by a well-formed flag. `parseFlagPreview` returns null on
     * the malformed object, but the cursor still advances past its closing
     * `}`, so the second well-formed object is emitted on the same call.
     */
    @Test fun malformedFlagAdvancesCursorPastClose() {
        val prefix = synthesizePrefix()
        val buffer = prefix +
            // Malformed: severity=0 AND blank description — parseFlagPreview returns null.
            """{"severity":0,"description":""},""" +
            // Well-formed: this one must emit.
            """{"severity":3,"description":"real","citation":"FDA label"}]"""
        val previews = ToolDispatcher.extractFlagPreviewsForTest(buffer)
        assertEquals(
            "malformed flag must be skipped but cursor must advance — well-formed flag emits",
            1,
            previews.size,
        )
        assertEquals("real", previews[0].description)
        assertEquals(3, previews[0].severity)
        // Sanity: the well-formed flag is the only one in the list.
        assertFalse(
            "malformed flag must not leak into the preview list",
            previews.any { it.description.isBlank() && it.severity == 0 },
        )
    }
}
