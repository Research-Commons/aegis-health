---
phase: 07-toolstepper-ui-latency-honest-skeletons
plan: 03
subsystem: android
tags: [android, kotlin, compose, ui, screen-wiring, drop-in-swap, progress-event, step-failure, snapshot-state-map]

# Dependency graph
requires:
  - plan: 07-01
    provides: ProgressEvent.StepFailure subtype + dispatchToolCall catch emission site — the events this plan routes through the screen-scope `failures` map
  - plan: 07-02
    provides: ToolStepper(label, steps, modifier, failures) 4-param signature + public FailureInfo(label, reason) data class consumed by both screens
  - phase: 06-streaming-preview-wiring-reportreader-healthpartner
    provides: STREAM-02 wiring-parity invariant + FlagPreviewWiringParityTest 4-method JVM gate — preserved structurally through this plan's typed-when refactor
provides:
  - DrugSafeScreen.kt LoadingPanel → ToolStepper drop-in swap with `failures: SnapshotStateMap<Int, FailureInfo>` side channel (D-01, D-04c)
  - HealthPartnerScreen.kt LoadingPanel → ToolStepper drop-in swap mirroring DrugSafe verbatim (STREAM-02 wiring-parity preserved)
  - 2 of 3 ProgressEvent.StepFailure typed-when consumers under ui/ (the 3rd lands in Plan 07-04 ReportReader migration)
affects: [plan-07-04, plan-07-05]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Drop-in swap pattern: identical `if (isLoading) { … }` branch contents around the swapped composable; only the composable name + parameter shape changes (D-01)"
    - "Typed-when onProgress lambda with explicit StepFailure branch routing via screen-scoped SnapshotStateMap<Int, FailureInfo> (D-04c — no UI-side sentinel-prefix parsing)"
    - "(progress.size - 1).coerceAtLeast(0) idiom for indexing the most-recently-added Step row when StepFailure fires (defensive against pre-first-Step edge case)"
    - "STREAM-02 wiring-parity-preserving when-block: FlagPreview branch shape diverges per screen (DrugSafe inline filter vs HealthPartner MonotonicFlagList.appendIfNew) but the structural shape — typed sealed-subtype branches + else fallthrough to applyTo — is identical and structurally enforced by FlagPreviewWiringParityTest"

key-files:
  created:
    - .planning/phases/07-toolstepper-ui-latency-honest-skeletons/07-03-SUMMARY.md
  modified:
    - android/app/src/main/java/com/aegis/health/ui/drugsafe/DrugSafeScreen.kt
    - android/app/src/main/java/com/aegis/health/ui/healthpartner/HealthPartnerScreen.kt

key-decisions:
  - "D-01 drop-in swap honored on both screens — `LoadingPanel(autoAdvance=false, ...)` → `ToolStepper(label, steps, modifier, failures)`. The `autoAdvance=false` parameter has no analog in ToolStepper (live-tools only) and is dropped."
  - "D-04c side-channel routing — `ProgressEvent.StepFailure` events flow into a screen-scoped `SnapshotStateMap<Int, FailureInfo>` rather than the existing `progress: List<String>` (which would require a sentinel-prefix render path). ToolStepper consumes the map via the `failures` parameter from Plan 07-02."
  - "Typed-when refactor with explicit `is FlagPreview` + `is StepFailure` + `else -> applyTo` branches. The `else` branch covers `Step` + `Update` via the shared `applyTo` contract (Phase 5 D-09 preserved). This shape — three explicit when-branches + final `else applyTo` — establishes the pattern Plan 07-04 will mirror on ReportReaderScreen."
  - "STREAM-02 wiring-parity invariant honored — HealthPartnerScreen's `MonotonicFlagList.appendIfNew` call site at the live FlagPreview branch preserved verbatim; DrugSafeScreen's inline `flagPreviews.none { … }` filter preserved verbatim (the FlagPreviewWiringParityTest matcher relaxes for DrugSafe per Plan 06-03 close-out)."
  - "Index-clamping with `(progress.size - 1).coerceAtLeast(0)` chosen over a more elaborate StepFailure-vs-Step-order assertion. The dispatcher emits Step before the tool runs and StepFailure from the catch, so in normal flow `progress.size >= 1` when StepFailure fires. The defensive `coerceAtLeast(0)` protects against the theoretical pre-first-Step edge case without coupling this plan to dispatcher emission ordering."

patterns-established:
  - "Pattern: drop-in composable swap inside `if (isLoading) { … }` branch — only the composable name + param shape changes; surrounding `Spacer`, downstream rail, and visual hierarchy stay byte-identical (Pitfall 7 placement invariant)."
  - "Pattern: screen-scoped SnapshotStateMap<Int, FailureInfo> indexed by `(progress.size - 1).coerceAtLeast(0)` — the StepFailure handler's canonical idiom across all stepper-bearing screens (DrugSafe + HealthPartner now; ReportReader in Plan 07-04)."
  - "Pattern: typed-when with `is FlagPreview` + `is StepFailure` + `else -> applyTo` — the canonical onProgress lambda shape for stepper-bearing screens. The `else` covers `Step` + `Update` via the shared `ProgressEvent.applyTo` contract; explicit typed branches handle the side-channel events (FlagPreview rail + StepFailure chip)."

requirements-completed: [STEP-01, STEP-02]

# Metrics
duration: ~5min
completed: 2026-05-15
---

# Phase 07 Plan 03: DrugSafe + HealthPartner LoadingPanel → ToolStepper migration Summary

**Wires the new Plan 07-02 ToolStepper into two of three target screens via D-01 drop-in swap + D-04c failures side channel — two atomic commits, ~28+34 LOC delta, JVM suite 195/195 green with FlagPreviewWiringParityTest 4/4 preserved (STREAM-02 wiring-parity invariant intact).**

## Performance

- **Duration:** ~5 min (Gradle build cache warm; full assembleDebug + assembleDebugAndroidTest + testDebugUnitTest re-runs after each commit)
- **Started:** 2026-05-15T15:30:08Z
- **Completed:** 2026-05-15T15:35:53Z
- **Tasks:** 2/2 complete
- **Files modified:** 2 production screens (DrugSafeScreen.kt + HealthPartnerScreen.kt)
- **Files created:** 0 — plan explicitly forbids new files; existing JVM `FlagPreviewWiringParityTest` already protects the structural invariants
- **Atomic commits:** 2 (one per screen)
- **LOC delta:**
  - DrugSafeScreen.kt: +28 insertions / −13 deletions (net +15)
  - HealthPartnerScreen.kt: +34 insertions / −11 deletions (net +23)

## Accomplishments

- **DrugSafeScreen.kt swapped (commit `e9747c4`):** Five surgical edits land:
  1. `val failures = remember { mutableStateMapOf<Int, FailureInfo>() }` state declaration added immediately after the existing `flagPreviews` declaration (line 88).
  2. `failures.clear()` appended to the submit-tap reset block at line 191 alongside `progress.clear()` + `flagPreviews.clear()`.
  3. `onProgress` lambda refactored from `if (event is FlagPreview) { … } else { event.applyTo(progress) }` (Phase 6 shape) to a typed `when` with explicit `is FlagPreview`, `is StepFailure`, and `else -> applyTo(progress)` branches. The StepFailure branch sets `failures[idx] = FailureInfo(event.label, event.reason)` with `idx = (progress.size - 1).coerceAtLeast(0)`.
  4. `LoadingPanel(label = "Analyzing ${drugs.size} medications…", steps = progress, autoAdvance = false, modifier = Modifier.fillMaxWidth())` at line 233 → `ToolStepper(label = "Analyzing ${drugs.size} medications…", steps = progress, modifier = Modifier.fillMaxWidth(), failures = failures)`. Label string copied verbatim (STEP-02 friendly-summary preservation).
  5. Imports: dropped now-unused `LoadingPanel`; added `FailureInfo`, `ToolStepper`, and `mutableStateMapOf`.

- **HealthPartnerScreen.kt swapped (commit `67b201c`):** Verbatim mirror of DrugSafe's 5 edits with the FlagPreview branch preserving `MonotonicFlagList.appendIfNew(flagPreviews.toList(), event)` verbatim from Plan 06-03 (STREAM-02 wiring-parity invariant). `mutableStateMapOf` import was already present from the pre-existing `checked: SnapshotStateMap<Int, Boolean>()` declaration at line 99 — only `FailureInfo` + `ToolStepper` imports added (and `LoadingPanel` dropped).

- **flagPreviews SeverityCard rail preserved byte-identical on both screens** — DrugSafeScreen.kt:262-276 (formerly :248-262) and HealthPartnerScreen.kt:263-277 (formerly :249-263). Renders BELOW the new ToolStepper per D-01 + Pitfall 7 slot ordering. The `Spacer(Modifier.height(24.dp))` immediately above the swap point on DrugSafe stays; HealthPartner has no equivalent Spacer above the LoadingPanel call so nothing changed there either.

- **STREAM-02 wiring-parity invariant preserved structurally and validated by JVM test:** `FlagPreviewWiringParityTest` 4/4 @Test methods all pass post-edit:
  - `bothScreens_useMonotonicFlagList_appendIfNew_noPerModeForks` — HealthPartner's live call site at `MonotonicFlagList.appendIfNew(flagPreviews.toList(), event)` (now under the typed `is FlagPreview ->` branch instead of the old `if (event is FlagPreview)` branch) still matches. DrugSafe's Path A comment + STREAM-01-followup tag still present in deferred-items.md.
  - `bothScreens_referenceFlagPreviewEventType` — both screens still contain the `ToolDispatcher.ProgressEvent.FlagPreview` literal (now as `is ToolDispatcher.ProgressEvent.FlagPreview ->` in the when block).
  - `noScreenReferencesStreamBuffer` — zero `streamBuffer` references under `ui/` (D-13 single-buffer-owner invariant).
  - `noScreenIntroducesViewModelOrFlowOfProgressEvent` — no `ViewModel` extension, no `Flow<ProgressEvent>` collector (ARCHITECTURE.md:99-103 lock-out).

- **Phase 7 verification anchors (plan-level grep gates) all green:**
  - `LoadingPanel(` count in DrugSafe + HealthPartner: **0** (down from 2 pre-plan).
  - `ToolStepper(` count in DrugSafe + HealthPartner: **2** (one per screen, at DrugSafeScreen.kt:248 + HealthPartnerScreen.kt:253).
  - `is ToolDispatcher.ProgressEvent.StepFailure ->` typed-when branches in `ui/`: **2** (DrugSafeScreen.kt:215 + HealthPartnerScreen.kt:206 — ready for Plan 07-04 to land the 3rd hit on ReportReaderScreen).
  - ConsentReader negative gate: `ToolStepper(` count in `ui/consentreader/` = **0** (STEP-05 exclusion preserved); LoadingPanel at ConsentReaderScreen.kt:216 untouched.
  - Phase 6 STREAM-04 throttle invariant: this plan didn't touch ToolDispatcher.kt — preserved by omission.

- **JVM suite 195/195 green** including `FlagPreviewWiringParityTest` (4 tests), `ProgressEventStepFailureTest` (Plan 07-01), `MonotonicFlagListTest`, `FlagsStreamParserTest`, and all upstream test suites — zero regressions across 24 test classes.

- **Build gauntlet on both commits:** `:app:assembleDebug` + `:app:assembleDebugAndroidTest` + `:app:testDebugUnitTest` all `BUILD SUCCESSFUL` on the post-Task-1 and post-Task-2 trees. Compiles against the new 4-param `ToolStepper(label, steps, modifier, failures)` signature shipped by Plan 07-02.

## Decisions Made

1. **Index idiom for StepFailure → failures map.** `(progress.size - 1).coerceAtLeast(0)` chosen for both screens. The dispatcher's emission ordering (Step before tool runs, StepFailure from catch) means `progress.size >= 1` in normal flow; the clamp defends against the theoretical pre-first-Step edge case without coupling this plan to the dispatcher's emission contract. Documented in both screens' onProgress comments.
2. **Parameter ordering on the new ToolStepper call site.** Named args used throughout (`label = …, steps = …, modifier = Modifier.fillMaxWidth(), failures = failures`) — keeps the call site readable and lets the trailing `failures = failures` line stand out visually as the new-in-Phase-7 addition. Matches the ToolStepper signature's positional order (`label, steps, modifier, failures`).
3. **`failures = failures` self-name passthrough.** Both screens use the same identifier `failures` for the screen-scoped SnapshotStateMap. Named args make the passthrough explicit and avoid Kotlin's named-vs-positional argument shadowing risk.
4. **Typed-when else branch.** Both screens use `else -> event.applyTo(progress)` for the `Step` + `Update` events. Phase 5 D-09 preserved: the applyTo contract on those subtypes stays unchanged. StepFailure's no-op applyTo (Plan 07-01 Path A) makes the `else` branch safe even if a StepFailure event ever fell through (it wouldn't — the explicit `is StepFailure ->` branch catches it first).
5. **Comment density.** Added 4-5 line block comments on the new `failures` declaration (both screens), 7-9 line block comments on the typed-when lambda (both screens), and a single-line phase-7 marker on the `failures.clear()` line — establishes a discoverable trail for Plan 07-04's ReportReader migration and any future maintainer.

## Deviations from Plan

**One minor scope deviation (no auto-fix rule invoked):**

The plan's HealthPartnerScreen Task 2 acceptance criterion says `grep -c "mutableStateMapOf<Int" android/.../HealthPartnerScreen.kt` should return **exactly 1**. The actual count is **2** because the screen already has `val checked = remember { mutableStateMapOf<Int, Boolean>() }` at line 99 (pre-existing from Phase 4.x — the checklist's checkbox state). The new `failures` declaration adds the second occurrence. This is correct behavior; the acceptance criterion appears to have been written without accounting for the pre-existing `checked` state. The intent — "exactly one NEW failures state map" — is satisfied; the literal grep count is 2 instead of 1. Documenting here for transparency; no code change needed.

**No Rule-1/2/3 deviations.** Both tasks landed exactly as specified in Plan 07-03's `<action>` blocks. No bugs found, no missing critical functionality discovered, no blocking issues.

**No auth gates encountered.** Local Gradle build only — no network, no auth.

**No CLAUDE.md-driven adjustments.** Project constraints (offline guarantee, Backend.CPU, no INTERNET permission, frozen SFT v4) are upstream of this plan's UI-only edits.

## Known Stubs

None. Both screens now consume real ToolDispatcher events through the failures map; the StepFailure branch fires on real dispatcher catch-block emissions (Plan 07-01 emission site at ToolDispatcher.kt:913). No mock data, no placeholder text, no "TODO" markers introduced.

## TDD Gate Compliance

This plan's frontmatter declares `type: execute` (not `type: tdd`), and neither task uses `tdd="true"`. The structural invariants (FlagPreviewWiringParityTest, ProgressEventStepFailureTest, the smoke + Phase 7 @Ignore'd androidTests) were all written in prior plans (06-03, 07-01, 07-02) — this plan inherits the gates and structurally satisfies them. No RED/GREEN/REFACTOR cycle applies.

## File Diff Summary

```
android/app/src/main/java/com/aegis/health/ui/drugsafe/DrugSafeScreen.kt | 41 +++++++++++++++-------
1 file changed, 28 insertions(+), 13 deletions(-)

android/app/src/main/java/com/aegis/health/ui/healthpartner/HealthPartnerScreen.kt | 45 ++++++++++++++++------
1 file changed, 34 insertions(+), 11 deletions(-)
```

Net plan footprint: **62 insertions, 24 deletions, 2 files modified, 0 files created**. Within the plan's "~10 LOC per screen" target band when measured as net additions; the larger raw diff reflects the typed-when refactor's structural reshaping (each new branch displaces an existing if/else line).

## Commits

| Task | Name                        | Commit    | Files                                            |
|------|-----------------------------|-----------|--------------------------------------------------|
| 1    | DrugSafeScreen LoadingPanel → ToolStepper drop-in swap | `e9747c4` | `android/app/src/main/java/com/aegis/health/ui/drugsafe/DrugSafeScreen.kt` |
| 2    | HealthPartnerScreen LoadingPanel → ToolStepper drop-in swap (STREAM-02 mirror) | `67b201c` | `android/app/src/main/java/com/aegis/health/ui/healthpartner/HealthPartnerScreen.kt` |

## Handoff to Plan 07-04 (ReportReader)

Plan 07-04 inherits the canonical 5-edit shape this plan establishes:
1. `val failures = remember { mutableStateMapOf<Int, FailureInfo>() }` declared alongside `progress` + `flagPreviews`.
2. `failures.clear()` in the reset block.
3. Typed-when onProgress with `is FlagPreview` + `is StepFailure` + `else -> applyTo` branches. ReportReaderScreen's existing FlagPreview branch uses `MonotonicFlagList.appendIfNew` (Plan 06-02 STREAM-01 wiring) — copy verbatim, only add the StepFailure branch and `else applyTo`.
4. `ToolStepper(label = "…", steps = progress, modifier = Modifier.fillMaxWidth(), failures = failures)` mounted inside `isLoading -> { item { … } }` LazyColumn branch (D-02b headerSlotCount math invariant — verify the item placement keeps the count unchanged when `isLoading == false`).
5. Imports: `FailureInfo`, `ToolStepper`, `mutableStateMapOf`; drop `LoadingPanel` if no longer referenced.

After Plan 07-04 lands, `grep -rn "is ToolDispatcher.ProgressEvent.StepFailure" android/app/src/main/java/com/aegis/health/ui/` should return **3 hits**, completing the typed-when consumer set across all three stepper-bearing screens (the Phase 7 verification anchor at line 355 of 07-03-PLAN.md).

## Self-Check: PASSED

Verified before declaring complete:

- [x] DrugSafeScreen.kt exists with `ToolStepper(` at line 248 and no `LoadingPanel(` references.
- [x] HealthPartnerScreen.kt exists with `ToolStepper(` at line 253 and no `LoadingPanel(` references.
- [x] `is ToolDispatcher.ProgressEvent.StepFailure` typed-when branches present in both screens (DrugSafeScreen.kt:215 + HealthPartnerScreen.kt:206).
- [x] `FailureInfo(` constructor present in both screens (1 each).
- [x] `mutableStateMapOf<Int, FailureInfo>` present in both screens.
- [x] `failures.clear()` present in both screens (1 each).
- [x] `MonotonicFlagList.appendIfNew` preserved at HealthPartnerScreen's live FlagPreview branch (4 raw hits — 1 live + 3 doc-comment).
- [x] Commit `e9747c4` exists in git log.
- [x] Commit `67b201c` exists in git log.
- [x] `:app:assembleDebug` + `:app:assembleDebugAndroidTest` + `:app:testDebugUnitTest` all BUILD SUCCESSFUL on HEAD.
- [x] JVM suite 195/195 green; `FlagPreviewWiringParityTest` 4/4 passing; `ProgressEventStepFailureTest` (Plan 07-01) passing; zero failures, zero errors across 24 test XMLs.
- [x] ConsentReader negative gate clean (`ToolStepper(` count = 0 under `ui/consentreader/`); ConsentReaderScreen.kt:216 LoadingPanel reference preserved (STEP-05 exclusion intact).
