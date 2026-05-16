package com.aegis.health.ui.common

import com.aegis.health.inference.ToolDispatcher

/**
 * Defensive guard against Pitfall M2 (state flapping) for the streaming
 * preview rail on `ReportReaderScreen` (Plan 06-02) and `HealthPartnerScreen`
 * (Plan 06-03). The `ToolDispatcher.FlagsStreamParser` cursor only ever
 * advances and never replays previously-closed flag objects, so this helper
 * is **defense-in-depth**: a future parser regression that emits a duplicate
 * `FlagPreview` — or a future onProgress collector that double-routes one —
 * must not cause a `SeverityCard` to disappear and reappear under the user.
 *
 * Contract:
 *  - For every call: `result.size >= previous.size`. The returned list NEVER
 *    shrinks. This is the load-bearing M2 / ROADMAP SC-5 invariant.
 *  - If any entry in [previous] has the same `description` AND `citation` as
 *    [incoming], return [previous] unchanged (the structural-equality dedup
 *    tuple matches DrugSafe's existing inline filter at
 *    `DrugSafeScreen.kt:200` and the FlagPreview event surface at
 *    `ToolDispatcher.kt:394-402`).
 *  - Otherwise, return an immutable copy of [previous] with [incoming]
 *    appended at the end (arrival order preserved).
 *  - Pure: no I/O, no shared state, no Compose dependency. Callers may
 *    invoke this from any thread; tests pin the invariant on JVM without
 *    Robolectric or an instrumented runtime.
 *
 * Visibility precedent: mirrors Phase 5 / Plan 05-02
 * `ToolCallBoundaryDetector` (`ToolCallBoundaryDetector.kt:45`). `internal`
 * is wide enough for both screen consumers (`ui/reportreader/`,
 * `ui/healthpartner/`) and the JVM unit test
 * (`src/test/java/com/aegis/health/ui/common/MonotonicFlagListTest.kt`) to
 * call this helper without exposing it on the production public API surface.
 *
 * Anti-patterns explicitly avoided (per 06-RESEARCH.md §Anti-Patterns
 * lines 343-351): no `MutableList` mutation API — the helper returns a new
 * `List` each call; callers diff the size and append a single event onto
 * their `mutableStateListOf` so Compose only recomposes the deltas. No
 * `Flow<...>`, no `produceState`, no ViewModel — those would widen the
 * ARCHITECTURE.md:99-103 lock-out surface.
 */
internal object MonotonicFlagList {
    fun appendIfNew(
        previous: List<ToolDispatcher.ProgressEvent.FlagPreview>,
        incoming: ToolDispatcher.ProgressEvent.FlagPreview,
    ): List<ToolDispatcher.ProgressEvent.FlagPreview> {
        val alreadyPresent = previous.any {
            it.description == incoming.description && it.citation == incoming.citation
        }
        return if (alreadyPresent) previous else previous + incoming
    }
}
