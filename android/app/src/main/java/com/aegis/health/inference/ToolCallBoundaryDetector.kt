package com.aegis.health.inference

/**
 * Stateful pure helper that owns the `<|tool_call>` / `<tool_call|>`
 * marker-boundary detection extracted from `LiteRtLmEngine.kt`
 * `MessageCallback.onMessage` (per D-11 / INFRA-05).
 *
 * Public contract:
 * - [advance] returns [HostStopReason.TOOL_CALL] exactly once — on the
 *   first piece that completes the closing `<tool_call|>` marker, taking
 *   into account split-token tolerance via a tiny internal accumulator.
 *   Returns `null` otherwise; returns `null` forever after the first
 *   non-null return (mirrors `JsonCloseDetector` at LiteRtLmEngine.kt:382
 *   `if (fired) return false`).
 * - [hasSeenOpeningMarker] returns `true` once `<|tool_call>` has fully
 *   arrived in the internal accumulator; `false` until then. Replaces
 *   the engine's prior inline `sb.indexOf("<|tool_call>") >= 0` check.
 *
 * D-13 invariant (single-buffer-owner): this class NEVER receives a
 * reference to `LiteRtLmEngine.sb`, the engine instance, or any other
 * engine-internal state. Its only input is `piece: String`. The internal
 * 32-char sliding-window accumulator is private and exists ONLY for
 * split-marker tolerance — the engine's `sb` remains the single canonical
 * owner of the full decode buffer.
 *
 * Allocation: one fresh instance per `inferSync` call, alongside the
 * existing `JsonCloseDetector` in `LiteRtLmEngine.MessageCallback`.
 * Instances are fire-once and not reusable across decodes — same
 * constraint as `JsonCloseDetector`.
 *
 * Adversarial-input robustness:
 * - Very long pieces (>1 MB) do not OOM — the accumulator caps at
 *   `ACCUMULATOR_CAP` (32) chars, older chars are dropped after each
 *   append. O(n) over the piece, O(1) memory residency.
 * - Malformed sequences (`<|tool_callgarbage`) do not fire `TOOL_CALL`
 *   until the EXACT closing marker `<tool_call|>` appears.
 * - Repeated `<|tool_call>` markers in args (which would be invalid
 *   recursion from the model) trip only the first closing marker; the
 *   `fired` flag guarantees once-only semantics per D-12 case 4.
 *
 * No logging — telemetry stays at the engine call site
 * (LiteRtLmEngine.kt MessageCallback.onMessage), which reads
 * [HostStopReason.label] for span tags and Log.i lines.
 */
internal class ToolCallBoundaryDetector {

    private val accumulator: StringBuilder = StringBuilder(ACCUMULATOR_CAP)
    private var fired: Boolean = false
    private var hasOpeningMarker: Boolean = false

    /**
     * Feed one decoded piece. Returns [HostStopReason.TOOL_CALL] on the
     * first piece that completes the closing `<tool_call|>` marker
     * (split-token tolerant via the internal sliding-window accumulator);
     * returns `null` otherwise and forever after the first non-null return.
     */
    fun advance(piece: String): HostStopReason? {
        if (fired) return null

        // Append the full piece, scan for markers BEFORE trimming so that
        // a piece longer than the cap (e.g., a single-piece baseline that
        // contains both the opening AND closing markers — D-12 case 3)
        // still gets its markers detected. After detection, trim the
        // sliding window down to the cap so the next call only retains
        // enough tail to catch a split-token marker that straddles the
        // piece boundary.
        accumulator.append(piece)

        // Detection order matters: set hasOpeningMarker BEFORE checking
        // the closing marker so a single-piece baseline correctly reports
        // both flags after one advance() call (D-12 case 3).
        if (!hasOpeningMarker && accumulator.contains(OPENING_MARKER)) {
            hasOpeningMarker = true
        }

        val matchedClose = accumulator.contains(CLOSING_MARKER)

        if (accumulator.length > ACCUMULATOR_CAP) {
            accumulator.delete(0, accumulator.length - ACCUMULATOR_CAP)
        }

        if (matchedClose) {
            fired = true
            return HostStopReason.TOOL_CALL
        }

        return null
    }

    /**
     * `true` once the opening `<|tool_call>` marker has fully arrived in
     * the internal accumulator. The engine consults this instead of the
     * prior `sb.indexOf("<|tool_call>") >= 0` inline check to gate
     * JSON-close suppression on tool-call turns (D-12 case 2 contract).
     */
    fun hasSeenOpeningMarker(): Boolean = hasOpeningMarker

    private companion object {
        // Longest marker is "<|tool_call>" at 12 chars. Cap at 32 to
        // tolerate adjacent text from the same piece without unbounded
        // growth; the marker substrings are still found by `.contains`
        // as long as both halves of a split marker land within the
        // window (worst case: 11 chars from piece N + first char from
        // piece N+1, well under 32).
        private const val ACCUMULATOR_CAP = 32
        private const val OPENING_MARKER = "<|tool_call>"
        private const val CLOSING_MARKER = "<tool_call|>"
    }
}
