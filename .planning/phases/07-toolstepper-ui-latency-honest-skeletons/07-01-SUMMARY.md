---
phase: 07-toolstepper-ui-latency-honest-skeletons
plan: 01
subsystem: android
tags: [android, kotlin, compose, inference, sealed-class, jvm-test, color-tokens, progress-event, tool-dispatcher]

# Dependency graph
requires:
  - phase: 05-friendly-tool-labels
    provides: FriendlyToolSummarizer.summarize(toolCall) — reused at the new StepFailure emission site
  - phase: 06-flag-preview-rail
    provides: FlagPreview.applyTo no-op precedent — adopted by StepFailure.applyTo (Path A)
provides:
  - ProgressEvent.StepFailure(label, reason) sealed subtype on ToolDispatcher (Phase 7 D-04, D-04c)
  - Single emission site of StepFailure from dispatchToolCall catch block (D-04a), threaded via runAgenticLoop's onProgress lambda
  - Pitfall-5 inner try/catch around the StepFailure emission so a throwing UI lambda cannot short-circuit error-recovery
  - AegisColors.warningFg / .warningBg calm-tone tokens with light + dark variants (D-03c) — consumed by Plan 07-02's ToolStepper failure chip
  - JVM contract test ProgressEventStepFailureTest pinning the no-op applyTo + data-class equality semantics
affects: [phase-07-toolstepper-body-rewrite, phase-07-error-chip-rendering, plan-07-02, plan-07-03, plan-07-04]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Sealed-class additive subtype with no-op applyTo (Path A — mirrors FlagPreview.applyTo)"
    - "Default-empty-lambda parameter for source-compatible onProgress threading"
    - "Pitfall-5 inner try/catch guarding a side-effecting UI lambda from short-circuiting an outer catch"
    - "Color-token light/dark variant pair-naming convention (WarningFg / WarningFgDark + WarningBg / WarningBgDark)"

key-files:
  created:
    - .planning/phases/07-toolstepper-ui-latency-honest-skeletons/07-01-SUMMARY.md
    - android/app/src/test/java/com/aegis/health/inference/ProgressEventStepFailureTest.kt
  modified:
    - android/app/src/main/java/com/aegis/health/inference/ToolDispatcher.kt
    - android/app/src/main/java/com/aegis/health/ui/theme/Color.kt

key-decisions:
  - "Path A no-op applyTo (planner override of 07-CONTEXT.md D-04's sentinel-prefix wording) — sanctioned by D-04c side-channel contract + planning-context Pitfall #1 atomicity concern."
  - "Single emission site at dispatchToolCall catch (D-04a) — threaded via runAgenticLoop's onProgress; the public dispatch(modelOutput) entry point at line 71 falls through to the default empty lambda."
  - "Pitfall-5 inner try/catch wraps onProgress(StepFailure(...)) so a throwing UI consumer cannot abort the catch — outer ToolResult return is guaranteed."
  - "AegisColors gains warningFg/warningBg fields between sevInfoBg and isDark — additive position keeps the backward-compatible data-class shape."
  - "Direction A Clinical Calm amber palette adopted (WarningFg = #A0671F, WarningBg = #FAEDD0) — AVOIDS sevCrit/sevCritBg red panic per STEP-06."

patterns-established:
  - "Pattern: additive sealed-class subtype with no-op applyTo when the consumer routes via a side channel."
  - "Pattern: optional default-empty-lambda for backwards-compatible callback threading through internal helpers."
  - "Pattern: 4-insertion-point edit choreography for AegisColors token additions (top-level literal pair × light + dark + data-class field + light-instance assignment + dark-instance assignment)."

requirements-completed: [STEP-06]

# Metrics
duration: 8min
completed: 2026-05-15
---

# Phase 07 Plan 01: ToolStepper UI prerequisites — StepFailure sealed-subtype + calm-tone color tokens Summary

**Plumbs failed-tool-call signaling (STEP-06) and the calm-tone error-chip palette (D-03c) into tree without touching ToolStepper UI — Plan 07-02's body rewrite has every collaborator already in place.**

## Performance

- **Duration:** ~8 min (Gradle build cache warm; JVM suite full re-run)
- **Started:** 2026-05-15T14:55:15Z
- **Completed:** 2026-05-15T15:03:09Z
- **Tasks:** 3/3 complete
- **Files modified:** 2 production sources + 1 new JVM test
- **Atomic commits:** 4 (Task 1 RED + GREEN, Task 2, Task 3)

## Accomplishments

- ProgressEvent sealed class now has 4 subtypes: Step, Update, FlagPreview, StepFailure. The new subtype carries failed-tool-call signal from the dispatcher catch site to the UI side channel (D-04c).
- dispatchToolCall emits exactly one ProgressEvent.StepFailure per catch fire, wrapped in a Pitfall-5 inner try/catch. The existing ToolResult error-recovery return is byte-identical — the agentic loop's error-recovery contract is preserved.
- AegisColors.warningFg / .warningBg tokens land with light + dark variants. Direction A Clinical Calm amber palette (low chroma, warm) — deliberately avoids the sevCrit red palette per STEP-06.
- 4 new JVM tests pin the StepFailure contract: no-op applyTo on empty + non-empty steps lists, data-class equality on (label, reason), data-class inequality on differing reason. JVM suite grew from 191 → 195/195 green.
- `:app:assembleDebug` BUILD SUCCESSFUL — confirms no compile drift in any of the screen or Theme.kt consumers.

## Task Commits

Each task was committed atomically:

1. **Task 1 (RED): Add failing ProgressEventStepFailureTest with 4 contract cases** — `f4f4d16` (test)
2. **Task 1 (GREEN): Add ProgressEvent.StepFailure subtype with no-op applyTo (Path A)** — `ebf0d0e` (feat)
3. **Task 2: Emit ProgressEvent.StepFailure from dispatchToolCall catch (D-04a; Pitfall-5 inner try/catch)** — `36ffd48` (feat)
4. **Task 3: Add calm-tone warningFg/warningBg tokens to AegisColors (D-03c prereq)** — `6d74009` (feat)

_TDD note: Task 1 followed RED→GREEN per `tdd="true"`. No refactor commit was needed — the GREEN-step diff is the final shape._

## Files Created/Modified

- `android/app/src/test/java/com/aegis/health/inference/ProgressEventStepFailureTest.kt` — **created** — new JVM contract test (4 cases) pinning the StepFailure no-op applyTo and data-class equality.
- `android/app/src/main/java/com/aegis/health/inference/ToolDispatcher.kt` — **modified** — added the StepFailure sealed subtype after FlagPreview (line 415-422); threaded `onProgress: (ProgressEvent) -> Unit = {}` through `dispatchToolCall` (line 930-934); inserted the Pitfall-5 inner try/catch + StepFailure emission in the catch block (line 947-956); updated the two runAgenticLoop call sites at lines 887 and 890 to pass `onProgress = onProgress` explicitly. Existing `ToolResult(name=…, result=errorJson(…))` return expression at line 957 is byte-identical to its pre-edit value.
- `android/app/src/main/java/com/aegis/health/ui/theme/Color.kt` — **modified** — added WarningFg/WarningBg/WarningFgDark/WarningBgDark top-level palette literals (lines 68-71); added `warningFg: Color` + `warningBg: Color` fields to AegisColors data class (lines 97-98) immediately before `isDark`; added light + dark assignments in LightAegisColors (lines 123-124) and DarkAegisColors (lines 149-150).

## Decisions Made

### 1. Path A no-op applyTo (D-04c override of D-04 wording)

The plan-time analysis identified that 07-CONTEXT.md D-04 specifies `applyTo` appending `"⚠ $label — ${reason.take(64)}"` to the steps list. If shipped that way, the placeholder ToolStepper body's `steps.forEach { Text(it) }` would render that sentinel string verbatim between Plan 07-01 ship and Plan 07-02 ship (the planning-context Pitfall #1 atomicity concern). The planner adopted Path A — no-op `applyTo`, mirroring `FlagPreview.applyTo`'s existing pattern. This is sanctioned by D-04c (UI routes StepFailure via a SnapshotStateMap side channel, so applyTo need not emit) and the 07-CONTEXT.md side-channel clause at line 88-89. 07-PATTERNS.md Open Q #4 explicitly recommends Path A.

### 2. Single dispatchToolCall emission site (D-04a)

The dispatcher emits StepFailure from exactly one site — the existing `catch (e: Exception)` block at line 938. Two of three call sites (inside `runAgenticLoop` at lines 887 and 890) thread `onProgress` through explicitly. The third call site (`fun dispatch(modelOutput)` at line 71, used by tests / non-streaming callers) has no progress context — it falls through to the default empty lambda, preserving source compatibility.

### 3. Pitfall-5 inner try/catch around onProgress(StepFailure(...))

The catch block's primary purpose is to return a `ToolResult(name, result=errorJson(...))` so the agentic loop can route the failed result back to the model for error recovery. If `onProgress` threw, the outer catch would abort and the ToolResult would never return, breaking the loop. The inner try/catch logs the throw via `Log.w(TAG, "onProgress threw while reporting StepFailure", progressErr)` and swallows it. The outer ToolResult return is then guaranteed.

### 4. Calm-tone amber palette over red sevCrit (D-03c)

STEP-06 explicitly mandates a calm-tone error chip — "NOT red panic copy". Direction A Clinical Calm picks `WarningFg = #A0671F` (low-chroma warm amber) on `WarningBg = #FAEDD0` (soft cream) for light mode. Dark mode reuses the SevModDark hue (`#E2B86A` at 0.12 alpha) so the failure chip visually harmonizes with the moderate-severity SeverityCard row.

## Deviations from Plan

### Documentation Drift (informational only — substantive intent satisfied)

**1. Acceptance-criterion grep mismatch on single-line `onProgress(ProgressEvent.StepFailure` literal**
- **Found during:** Task 2 verification
- **Issue:** The plan's acceptance criterion grep `grep -n "onProgress(ProgressEvent.StepFailure" .../ToolDispatcher.kt` expects exactly 1 line. The actual emission is written in idiomatic multi-line Kotlin form (`onProgress(\n    ProgressEvent.StepFailure(\n        label = ..., reason = ...,\n    )\n)`) so the single-line grep returns 0 lines.
- **Disposition:** Substantive intent (D-04a — single emission site) is fully satisfied. A multiline-tolerant grep confirms exactly one `ProgressEvent.StepFailure(` occurrence in the file (line 949 — the new emission). The plan's grep wording assumed a single-line literal style that Kotlin's named-argument convention argues against for readability of a 4-line constructor invocation.
- **Files modified:** None (cosmetic on the code; the grep wording itself is a doc-level drift in the plan).
- **Commit:** N/A (no fix needed)

**2. Pre-existing FriendlyToolSummarizer.summarize call site count off by one**
- **Found during:** Task 2 verification
- **Issue:** The plan's acceptance criterion `grep -n "FriendlyToolSummarizer.summarize(toolCall)" .../ToolDispatcher.kt` expects 5 lines (4 pre-existing Step emissions per 07-RESEARCH.md `:422, :462, :519, :830` + 1 new StepFailure emission). Reality: only 3 pre-existing Step emissions use the literal `summarize(toolCall)` form (lines 444, 485, 543 — the fast paths). The 4th pre-existing emission at line 894 uses `summarize(call.toolCall)` (note the `.toolCall` accessor — the runAgenticLoop iterates over NativeToolCall wrappers). The new StepFailure emission at line 950 brings the `summarize(toolCall)` count to 4, not 5.
- **Disposition:** No behavior impact. 07-RESEARCH.md cited the call-site count for code-archeology purposes; the substantive contract — that FriendlyToolSummarizer is reused at the failure emission to mirror the Step label — is fully satisfied.
- **Files modified:** None
- **Commit:** N/A

**3. Worktree bootstrap copies**
- **Found during:** Plan start
- **Issue:** The agent's git worktree base (commit `7071d2a docs(07): capture phase context`) contains only 07-CONTEXT.md + 07-DISCUSSION-LOG.md committed under `.planning/phases/07-toolstepper-ui-latency-honest-skeletons/`. The Plan 07-01 PLAN, RESEARCH, and PATTERNS files exist only in the main repo's working tree (not yet committed). To execute the plan the agent copied those three files into the worktree and also bootstrapped `android/local.properties` (gitignored — SDK path for Gradle).
- **Disposition:** None of the copied files are committed. They are tooling-time artifacts that the orchestrator workflow expects to be present on disk. Verified via `git status` that no `.planning/phases/07-…/07-01-PLAN.md`, 07-RESEARCH.md, or 07-PATTERNS.md was staged or tracked in this worktree's commits. The orchestrator merges those source files separately via its own state-update path.
- **Files modified:** N/A (out-of-tree tooling artifacts only)
- **Commit:** N/A

### No auto-fix deviations

No Rule 1 (bug), Rule 2 (missing critical functionality), or Rule 3 (blocking issue) deviations occurred. All work proceeded directly per the plan's intent. The two minor doc-vs-code drifts above are noted for traceability only.

### No authentication gates

No auth gates encountered.

### Fast-path emission sites untouched (per plan §output)

The pre-existing Step emission sites at lines 444 (`runDrugSafeFastPath`), 485 (`runConsentReaderFastPath` / similar), 543 (`runHealthPartnerFastPath` / similar), and 894 (`runAgenticLoop`) were NOT touched. Only the new emission inside `dispatchToolCall`'s catch and the two `dispatchToolCall` invocations in `runAgenticLoop` (lines 887, 890) were modified.

## TDD Gate Compliance

Task 1 followed the RED → GREEN cycle:
- **RED commit:** `f4f4d16` (`test(07-01): add failing ProgressEventStepFailureTest...`). Compile failure was the canonical RED signal — `Unresolved reference 'StepFailure'` — mirroring Plan 06-02's MonotonicFlagListTest RED pattern.
- **GREEN commit:** `ebf0d0e` (`feat(07-01): add ProgressEvent.StepFailure subtype with no-op applyTo (Path A; GREEN)`). All 4 new tests pass; the existing 191 tests stay green.
- **No REFACTOR commit:** The GREEN-step diff is minimal (data class + 4-line KDoc + no-op applyTo body) — no cleanup needed.

Tasks 2 and 3 do not carry `tdd="true"` in the plan frontmatter (they are non-behavior-adding plumbing + theming tasks). No additional RED/GREEN gate applied.

## Threat Surface Scan

No new threat surface introduced beyond what the plan's `<threat_model>` already enumerated:

- **T-07-01 (Information Disclosure via `e.message`):** This plan emits the raw `e.message` into `StepFailure.reason`. Truncation (`reason.take(64)`) belongs at the render path — that lands in Plan 07-02. The typed `reason: String` field deliberately preserves the raw value for telemetry consumers; the UI render is where ASVS V7 truncation lands.
- **T-07-02 (Tampering via FriendlyToolSummarizer):** Accepted — tool name set is build-time-static.
- **T-07-03 (Repudiation via fake-success ✓):** Mitigated at Plan 07-02 close. This plan ships the prerequisite signal so the UI can render the calm-tone ⚠ chip.
- **T-07-04 (DoS via throwing onProgress):** Mitigated by the Pitfall-5 inner try/catch landed in this plan.

No threat_flag entries needed.

## Self-Check: PASSED

- File `android/app/src/test/java/com/aegis/health/inference/ProgressEventStepFailureTest.kt` exists — verified.
- File `android/app/src/main/java/com/aegis/health/inference/ToolDispatcher.kt` modified — `data class StepFailure` at line 415; emission at line 949; threaded onProgress params at lines 887, 890, 933.
- File `android/app/src/main/java/com/aegis/health/ui/theme/Color.kt` modified — WarningFg / WarningBg / WarningFgDark / WarningBgDark literals at lines 68-71; AegisColors fields at lines 97-98; light + dark assignments at lines 123-124, 149-150.
- Commit `f4f4d16` (Task 1 RED) exists in git log — verified.
- Commit `ebf0d0e` (Task 1 GREEN) exists in git log — verified.
- Commit `36ffd48` (Task 2) exists in git log — verified.
- Commit `6d74009` (Task 3) exists in git log — verified.
- `:app:assembleDebug` BUILD SUCCESSFUL — verified.
- JVM suite total = 195 (= 191 baseline + 4 new) — verified via test-results XML parse.

## What Phase 07 Plan 02 Unlocks

With this plan landed, Plan 07-02's ToolStepper body rewrite can:

1. Subscribe to a screen-level `failures: SnapshotStateMap<Int, FailureInfo>` populated from `ProgressEvent.StepFailure` events (D-04c).
2. Render the failed row with a calm-tone ⚠ chip styled via `LocalAegisColors.current.warningFg` / `.warningBg`.
3. Display `label — reason.take(64)` (truncation at render — ASVS V7 mitigation per T-07-01).
4. Pin via androidTest that a synthetic `ProgressEvent.StepFailure` event produces a ⚠ chip and NOT a fake-success ✓.

The placeholder ToolStepper body's `steps.forEach { Text(it) }` is safe to keep until Plan 07-02 because `StepFailure.applyTo` is a no-op — no sentinel-prefix string can leak into the placeholder body's render path.
