package com.aegis.health.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression suite for the four D-12 split-token cases driving INFRA-05
 * (Phase 5 / SC-2 — the C2 single-buffer-owner gate).
 *
 * Pins the [ToolCallBoundaryDetector] contract that replaces the inline
 * `sb.indexOf("<tool_call|>")` / `sb.indexOf("<|tool_call>")` checks at
 * `LiteRtLmEngine.kt` MessageCallback.onMessage. Each test allocates a
 * fresh detector — the detector is fire-once (mirrors `JsonCloseDetector`
 * at LiteRtLmEngine.kt:382 `if (fired) return false`); instances are not
 * reusable across cases.
 *
 * D-13 invariant: the detector receives only `piece: String` per call,
 * never `LiteRtLmEngine.sb` or any engine-internal state. Enforced
 * structurally by the function signatures asserted here.
 */
class LiteRtLmEngineStreamSplitTest {

    /**
     * D-12 case 1 — canonical SC-2 case: the closing `<tool_call|>` marker
     * splits across two `onMessage` callbacks (`<tool_` then `call|>`).
     * The detector must fire `HostStopReason.TOOL_CALL` exactly once, on
     * the second piece. A subsequent advance must return null (once-fired
     * guarantee), letting the engine-level CAS at LiteRtLmEngine.kt
     * stop decode reliably no matter how the SDK chunks the stream.
     *
     * Note: the plan's literal piece text in D-12 case 1 was `<|tool_`
     * then `call>`, which concatenates to the OPENING `<|tool_call>`
     * marker — not the closing one the engine actually uses for the
     * TOOL_CALL stop (see LiteRtLmEngine.kt MessageCallback.onMessage
     * tool_call boundary check). Treating that literally would change
     * the engine's stop-on-closing semantics, which D-13 explicitly
     * preserves. Rule 1 fix: split the CLOSING marker instead, which
     * is the canonical SC-2 case CONCERNS.md flags
     * ("<|tool_call> marker can be split across two MessageCallback
     * pieces" — the concern is split-tolerance for the marker boundary,
     * and the closing marker is the one driving the stop).
     */
    @Test fun d12_case_1_mid_marker_split_fires_once_after_second_piece() {
        val detector = ToolCallBoundaryDetector()
        assertNull(
            "piece 1 does not yet contain the full <tool_call|> closing marker",
            detector.advance("<tool_"),
        )
        assertEquals(
            "piece 2 completes <tool_call|> — TOOL_CALL must fire exactly here",
            HostStopReason.TOOL_CALL,
            detector.advance("call|>"),
        )
        assertNull(
            "detector is fire-once; subsequent advance must return null",
            detector.advance("anything"),
        )
    }

    /**
     * D-12 case 2 — opening-marker split: the OPENING `<|tool_call>` marker
     * splits across two pieces. Validates that JSON-close suppression at
     * the engine site (LiteRtLmEngine.kt:270 `if (!detector.hasSeenOpeningMarker() ...)`)
     * cannot trip on a half-arrived opening marker — otherwise json_close
     * would fire mid-tool-call args on the balanced `{...}` heuristic and
     * leave ToolDispatcher with an unclosed fragment.
     */
    @Test fun d12_case_2_opening_marker_split_suppresses_json_close_until_full_marker_arrives() {
        val detector = ToolCallBoundaryDetector()

        // Piece 1: opening marker is only half-arrived (`<|tool_`).
        // The engine's json_close path must NOT be suppressed yet, so
        // `hasSeenOpeningMarker()` MUST be false.
        val r1 = detector.advance("<|tool_")
        assertNull("piece 1 only contains half the opening marker", r1)
        assertFalse(
            "JSON-close suppression must not trip on a half-arrived <|tool_call>",
            detector.hasSeenOpeningMarker(),
        )

        // Piece 2: the rest of the opening marker arrives AND the args
        // start. `hasSeenOpeningMarker()` flips true; the closing marker
        // has not yet arrived so advance still returns null.
        val r2 = detector.advance("call>name{x:1}")
        assertNull("closing <tool_call|> has not yet arrived", r2)
        assertTrue(
            "opening <|tool_call> is now complete — suppression flag must flip",
            detector.hasSeenOpeningMarker(),
        )
    }

    /**
     * D-12 case 3 — single-piece baseline: the full tool_call sequence
     * arrives in a single decode piece. Detector must fire exactly once
     * on that piece (the closing marker is present); subsequent calls
     * return null. Disambiguates split-related regressions from
     * marker-related regressions.
     */
    @Test fun d12_case_3_single_piece_baseline_fires_exactly_once() {
        val detector = ToolCallBoundaryDetector()
        val result = detector.advance(
            "<|tool_call>check_warnings{drug_list:[warfarin]}<tool_call|>"
        )
        assertEquals(
            "single-piece full sequence must fire TOOL_CALL on first advance",
            HostStopReason.TOOL_CALL,
            result,
        )
        assertTrue(
            "opening <|tool_call> was in the same piece — flag must be true",
            detector.hasSeenOpeningMarker(),
        )
        assertNull(
            "detector is fire-once; subsequent advance must return null",
            detector.advance("foo"),
        )
    }

    /**
     * D-12 case 4 — multi-marker decode: two complete
     * `<|tool_call>...<tool_call|>` sequences in one piece. Detector
     * fires exactly once (on the first closing marker), satisfying the
     * once-only contract that lets the engine's
     * `AtomicReference.compareAndSet` at LiteRtLmEngine.kt:249 stop
     * decode after the first tool_call. ToolDispatcher handles
     * multi-call extraction from the truncated buffer downstream.
     */
    @Test fun d12_case_4_multi_marker_decode_stops_at_first_call() {
        val detector = ToolCallBoundaryDetector()
        val result = detector.advance(
            "<|tool_call>a{}<tool_call|>more<|tool_call>b{}<tool_call|>"
        )
        assertEquals(
            "multi-marker piece must still fire TOOL_CALL exactly once",
            HostStopReason.TOOL_CALL,
            result,
        )
        assertNull(
            "second closing marker in the same stream must NOT re-fire",
            detector.advance("trailing"),
        )
    }
}
