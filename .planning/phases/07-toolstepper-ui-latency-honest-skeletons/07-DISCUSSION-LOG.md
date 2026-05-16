# Phase 7 Discussion Log — ToolStepper UI + Latency-Honest Skeletons

**Date:** 2026-05-15
**Mode:** Default (single-question per area)
**Areas discussed:** 4 of 4 selected

## Gray Area Selection

Presented four phase-specific gray areas; user selected all four for discussion. Two other potential gray areas — latency-honest copy placement and ANIMATOR_DURATION_SCALE implementation lever — were left to planner discretion as both had a clearly-cheaper default.

## Area 1 — Migration vs Coexistence

**Question:** How should DrugSafe / ReportReader / HealthPartner adopt ToolStepper?

**Options:**
1. Drop-in swap *(Recommended)* — Replace `LoadingPanel(autoAdvance=false, ...)` with `ToolStepper(...)` on three screens; LoadingPanel stays for ConsentReader's autoAdvance=true case.
2. Coexist (ToolStepper above LoadingPanel) — Mount both; risks duplicate "Generating response…" rendering.
3. Drop-in swap + delete LoadingPanel entirely — Cuts dead code but contradicts STEP-05 ConsentReader-excluded.

**User selected:** Drop-in swap

**Decision recorded:** D-01 — Drop-in swap on three screens; LoadingPanel stays for ConsentReader; flagPreviews rail unchanged below ToolStepper.

## Area 2 — STREAM-01-followup Resolution

**Question:** Where should `runReportReaderFastPath` be invoked once Phase 7's ToolStepper mounts on ReportReaderScreen?

**Options:**
1. Path B: invocation moves into ReportReaderScreen *(Recommended)* — Mirrors DrugSafeScreen.kt:184-207 pattern; closes Phase 6's unreachable-state issue; all three Track-A screens converge.
2. Path A: mirror DeferralScreen's rail INTO ReportReaderScreen — Smaller diff but state coupling; risks Phase 4.1 Pitfall 1 regression.
3. Stay-as-is: stepper only on DeferralScreen surface — Violates STEP-01 (stepper must appear on ReportReader).

**User selected:** Path B

**Decision recorded:** D-02 + D-02a + D-02b — Invocation moves into ReportReaderScreen; DeferralScreen reverts to deferral-only; verify headerSlotCount math not regressed.

## Area 3 — Three-State State Derivation

**Question:** How should ToolStepper derive pending ○ / running ↻ / done ✓ from a growing `List<String>`?

**Options:**
1. Last=running, prior=done *(Recommended)* — Mirror LoadingPanel's index-based model; honors Phase 5 D-09 verbatim; compose-shimmer skeleton handles pre-first-Step window.
2. Hardcoded pre-known pending rows — Risks divergence from engine state; violates SKEL-03 "no fake-typing".
3. Extend ProgressEvent shape — Breaks Phase 5 D-09 lock; scope creep.

**User selected:** Last=running, prior=done

**Decision recorded:** D-03 + D-03a + D-03b + D-03c — Index-based state derivation; pre-first-Step compose-shimmer row with SKEL-02 copy; 350ms AnimatedContent cap, 1.8s shimmer cycle, 1.2s/rev spinner cap; calm-tone ⚠ chip for StepFailure rows.

## Area 4 — Failed Tool-Call Signaling

**Survey before question:** Tool failures today are caught at ToolDispatcher.kt:913, wrapped in `ToolResult(result=errorJson(...))`, fed back to the model. The UI never learns — the running ↻ row silently transitions to ✓ on `isLoading=false`. STEP-06 is structurally unsatisfied today.

**Question:** How should failed tool calls reach ToolStepper for STEP-06 compliance?

**Options:**
1. Add `ProgressEvent.StepFailure(label, reason)` *(Recommended)* — One new sealed subtype; emission from existing catch site; one new UI branch. Phase 5 D-09 "shapes unchanged" was Phase 5 scope; additive subtype in Phase 7 is in-scope and minimal.
2. Sentinel-prefix Step strings — Couples dispatcher format to UI parsing; brittle.
3. Defer STEP-06 as "no per-row error" — Risks SC-2 flag at verification.

**User selected:** Add ProgressEvent.StepFailure(label, reason)

**Decision recorded:** D-04 + D-04a + D-04b + D-04c — New sealed subtype with `applyTo` defined; single emission site at line 913 catch; D-09 relaxation scope explicitly limited to additive subtypes; no UI-side parsing of sentinel prefix.

## Deferred Ideas

Captured in `07-CONTEXT.md` `<deferred_ideas>` section:

- STEP-07 (stepper collapses to one-line summary) — Phase 10 P1 stretch.
- LoadingPanel deletion + ConsentReader rewire — future cleanup phase.
- DeferralStore complete removal — pending consumer audit at plan time.
- Dynamic SKEL-02 four-copy sequence engine-state mapping — planner discretion or follow-up.
- `ProgressEvent.StepStart(label)` / `StepEnd(label)` typed lifecycle — not needed for Phase 7's three-state visual.
- Animation-rate-ceiling lint rule — Phase 7 enforces via test, not lint.

## Claude's Discretion Items

Left to planner judgment (captured in `<decisions>` § Claude's Discretion):

- Distribution of SKEL-02's four copies across the synthesis lifecycle.
- Choice of `compose-shimmer` API surface and exact cycle config.
- Calm-tone warning token names — repurpose existing or add 2 lines to Theme.kt.
- DeferralScreen cleanup commit splitting (in-scope vs follow-up plan).
- Wave structure (recommended 5 waves).
- Synthetic-throwing-tool regression test for StepFailure path.
- `reason.take(64)` truncation length adjustment.

## Open Questions for Planner

None — all four selected gray areas resolved with concrete decisions. Planner inherits a fully-locked context.
