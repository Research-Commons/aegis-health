---
phase: 07-toolstepper-ui-latency-honest-skeletons
plan: 02
subsystem: android
tags: [android, kotlin, compose, compose-shimmer, animation, accessibility, ui, androidtest]

# Dependency graph
requires:
  - plan: 07-01
    provides: ProgressEvent.StepFailure subtype + AegisColors.warningFg/warningBg tokens consumed by the new ToolStepper body
  - phase: 05-stepper-streaming-infrastructure
    provides: D-08 pinned ToolStepper signature (label, steps, modifier) — preserved byte-identical, only extended with a 4th default-valued parameter
provides:
  - Production three-state ToolStepper body (Running ↻ / Done ✓ / Failed ⚠) with compose-shimmer pre-first-Step skeleton at 1800ms LinearEasing cycle, AnimatedContent / AnimatedVisibility tween(350) transitions, and D-05 latency-honest subline 'running on your phone — ~5 minutes' as single source of truth
  - Public data class FailureInfo(label, reason) at ui.common scope for Plan 07-03 / 07-04 screen wiring
  - Open Q #2 Option B parameter shape — failures: Map<Int, FailureInfo> = emptyMap() default — so Phase 5 D-10 3-param ToolStepperSmokeTest call site continues compiling unchanged
  - Four @Ignore'd androidTest files (state transition / failure chip / animator scale / latency copy) that compile against the new 4-param signature; light up automatically when Phase 10 P1 lifts TEST-FRAMEWORK-01
affects: [plan-07-03, plan-07-04, plan-07-05]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Map<Int, FailureInfo> = emptyMap() default parameter (Open Q #2 Option B) — preserves pinned-signature compatibility without requiring all callers to opt into a SnapshotStateMap"
    - "compose-shimmer 1.4.0 CompositionLocalProvider(LocalShimmerTheme provides …) for plan-specific cycle tuning (1800ms LinearEasing)"
    - "AnimatedContent { … } togetherWith { … } (Compose 1.6+ canonical replacement for deprecated `with` infix)"
    - "Modifier.testTag('step-row-<state.name>-<idx>') for semantic state assertions in @Ignore'd androidTest carry-over until Phase 10 P1"
    - "Per-test @Before captureScale + @After restoreScale via Settings.Global.getFloat / UiAutomation.executeShellCommand for device-wide setting tests (Pitfall 4 cleanup invariant)"

key-files:
  created:
    - .planning/phases/07-toolstepper-ui-latency-honest-skeletons/07-02-SUMMARY.md
    - android/app/src/androidTest/java/com/aegis/health/ui/common/ToolStepperStateTransitionTest.kt
    - android/app/src/androidTest/java/com/aegis/health/ui/common/ToolStepperFailureChipTest.kt
    - android/app/src/androidTest/java/com/aegis/health/ui/common/ToolStepperAnimatorScaleTest.kt
    - android/app/src/androidTest/java/com/aegis/health/ui/common/LatencyHonestCopyTest.kt
  modified:
    - android/app/src/main/java/com/aegis/health/ui/common/ToolStepper.kt

key-decisions:
  - "Open Q #2 Option B adopted — failures: Map<Int, FailureInfo> = emptyMap() default parameter. Preserves Phase 5 D-08 pinned 3-param call shape (ToolStepperSmokeTest:55-58) without requiring all callers to opt into a SnapshotStateMap remember-block in default position."
  - "Open Q #1 resolved as rendered-semantics check via LatencyHonestCopyTest mounting ToolStepper directly. D-05's single-source-of-truth wording means testing ToolStepper once + relying on composable-inclusion guarantee covers all three stepper-bearing screens (Plans 07-03/07-04 add their own thin smokes if needed)."
  - "Private enum renamed StepState → ToolStepperState to avoid same-package redeclaration with LoadingPanel.kt's existing private `enum class StepState { Pending, Active, Done }`. testTag scheme (step-row-Done-N / step-row-Running-N) preserved per plan Task 2 needs — `ToolStepperState.Done.name == \"Done\"`."
  - "TEST-FRAMEWORK-01 @Ignore wrapper byte-identical to Plan 06-02/06-03 pattern (ReportReaderFlagPreviewTest.kt:56-63) — single source-of-truth wording that Phase 10 P1 grep-and-remove can target across all carry-over tests."

patterns-established:
  - "Pattern: Map<Int, T> = emptyMap() over SnapshotStateMap<Int, T> remember { mutableStateMapOf() } in default-parameter position. SnapshotStateMap implements Map via interface inheritance so screens can pass either; the Map default avoids the need to legalize a `remember { … }` lambda in a parameter default."
  - "Pattern: rename file-private enums when their names would collide with other file-private enums in the same package. Kotlin's `private` at top level is file-private, but the compiler treats same-named declarations in the same package as redeclarations in some IDE / build configurations — defensive rename avoids the trap."
  - "Pattern: Map.containsKey(idx) FIRST in state-derivation when-block, ahead of `idx < steps.lastIndex` and `idx == steps.lastIndex` — STEP-06 structural guarantee that a failed row cannot be misidentified as Done. T-07-09 mitigation."

requirements-completed: [STEP-03, STEP-04, STEP-06, SKEL-01, SKEL-02, SKEL-03, SKEL-04, SKEL-05]

# Metrics
duration: 11min
completed: 2026-05-15
---

# Phase 07 Plan 02: ToolStepper UI + latency-honest skeletons — production body rewrite Summary

**Replaces the placeholder ToolStepper body with a 261-line production three-state composable (Running ↻ / Done ✓ / Failed ⚠) backed by compose-shimmer 1.4.0 skeletons at 1800ms LinearEasing cycle, AnimatedContent / AnimatedVisibility tween(350) transitions, calm-tone ⚠ chip via Plan 07-01's warningFg/warningBg tokens, and a single source-of-truth D-05 latency-honest subline — plus four `@Ignore`'d androidTest files that compile against the new 4-param signature and light up automatically when Phase 10 P1 lifts TEST-FRAMEWORK-01.**

## Performance

- **Duration:** ~11 min (Gradle build cache warm; full assembleDebug + assembleDebugAndroidTest + testDebugUnitTest re-runs after each commit)
- **Started:** 2026-05-15T15:10:21Z
- **Completed:** 2026-05-15T15:21:42Z
- **Tasks:** 2/2 complete
- **Files modified:** 1 production source (wholesale rewrite)
- **Files created:** 4 androidTest sources
- **Atomic commits:** 2 (one per task)
- **Production ToolStepper.kt LOC:** 279 (target ~150 in plan; actual higher because of two helper composables `ShimmerSkeletonRow` + `StepRow` declared at file scope with their own doc commentary + the new public `data class FailureInfo`)

## Accomplishments

- ToolStepper.kt body replaced wholesale. Phase 5 D-08 pinned 3-parameter signature preserved byte-identical; 4th default-valued parameter `failures: Map<Int, FailureInfo> = emptyMap()` added per Open Q #2 Option B.
- Three-state visual model lands: last `steps` element renders as Running ↻ (hairline ring), prior elements render as Done ✓ (filled accent circle + Check icon), `failures[idx]`-keyed rows render as Failed ⚠ in calm-tone amber chip — STEP-06 satisfied structurally (Map.containsKey FIRST in the when-block; failed rows cannot be misidentified as Done).
- compose-shimmer 1.4.0 skeleton path lights up exactly during `steps.isEmpty() && failures.isEmpty()` — renders the first of SKEL-02's four-copy sequence ("Preparing…") at 1800ms LinearEasing cycle via a private `aegisShimmerTheme` consumed through `CompositionLocalProvider(LocalShimmerTheme provides …)`.
- `AnimatedContent` per-row state transitions and `AnimatedVisibility` new-row reveals all capped at `tween(350)` — uses `togetherWith` (Compose 1.6+ canonical) instead of deprecated `with` infix.
- D-05 latency-honest subline literal **"running on your phone — ~5 minutes"** present exactly once as a final `Text` in the outer `Column` — always rendered while ToolStepper is on screen (every stepper-bearing screen inherits via composable inclusion; SKEL-04 / C4 mitigation).
- Calm-tone ⚠ chip uses `LocalAegisColors.current.warningFg` / `.warningBg` (Plan 07-01 tokens) — STEP-06 satisfied; never the red severity-critical palette. Failure reason truncated at 64 chars (T-07-05 / ASVS V7 mitigation).
- No manual `Settings.Global.getFloat` lookup in production code — Compose framework auto-honors `Settings.Global.ANIMATOR_DURATION_SCALE` on every `tween` / `infiniteRepeatable` spec (SKEL-05 / LoadingPanel.PulsingDot precedent).
- Four `@Ignore`'d androidTest files land with TEST-FRAMEWORK-01 carry-over wrapper byte-identical to Plan 06-02/06-03 pattern — `:app:assembleDebugAndroidTest` BUILD SUCCESSFUL confirms the 4 new tests compile against the post-Task-1 4-parameter `ToolStepper(...)` signature.
- `ToolStepperAnimatorScaleTest` lands defensive `@Before captureScale` / `@After restoreScale` (Pitfall 4) — the cleanup is in place even while `@Ignore`'d, so Phase 10 P1's ignore-removal lights up the test without further work.
- JVM suite stays 195/195 green (no JVM tests added or removed in this plan; Plan 07-01 added the 4 JVM cases for `ProgressEvent.StepFailure`).

## Task Commits

Each task was committed atomically:

1. **Task 1: Replace ToolStepper body wholesale** — `b8632b9` (feat) — 261 line additions / 12 deletions to `ui/common/ToolStepper.kt`. Production three-state body + compose-shimmer skeleton + latency-honest subline + data class FailureInfo + private aegisShimmerTheme + private ToolStepperState enum.
2. **Task 2: Add 4 `@Ignore`'d androidTest files** — `cd14d40` (test) — 460 new lines across four files under `ui/common/`. State transition + failure chip + animator scale (with @Before/@After capture-restore) + latency copy. All carry the byte-identical TEST-FRAMEWORK-01 wrapper.

_TDD note: Task 1 was marked `tdd="true"` in the plan. The five behavior tests enumerated in the plan's `<behavior>` block (smoke preservation + D-03 last=running + D-04c side-channel + D-05 subline presence + D-03a shimmer) are all Compose UI tests that hit the TEST-FRAMEWORK-01 wall on SM-S918B — Plan 07-01 established the precedent of shipping these as `@Ignore`'d Task-2 androidTest files alongside the GREEN implementation. The substantive contract is locked by Task 2's four `@Ignore`'d test files (which DO compile against the new signature and DO ship the assertion logic so Phase 10 P1's ignore-removal lights up the gate). No JVM-level proxy test exists for the visual contract — but the existing `ToolStepperSmokeTest` (Phase 5 D-10) compiles unchanged against the new 4-param signature and remains the load-bearing build-time check that the smoke contract isn't accidentally broken._

## Files Created/Modified

- `android/app/src/main/java/com/aegis/health/ui/common/ToolStepper.kt` — **modified** — wholesale body rewrite. New imports for AnimatedContent, AnimatedVisibility, fadeIn/fadeOut, expandVertically/shrinkVertically, togetherWith, infiniteRepeatable, LinearEasing, RepeatMode, tween, background, border, Box, Spacer, fillMaxWidth, height, padding, size, width, CircleShape, RoundedCornerShape, Icons.Default.Check, Icon, MaterialTheme, CompositionLocalProvider, Alignment, Color.Transparent, testTag, dp, LocalAegisColors, plus the 5 compose-shimmer imports (LocalShimmerTheme, ShimmerBounds, defaultShimmerTheme, rememberShimmer, shimmer). Body restructured into: top-level `data class FailureInfo`, top-level `private enum class ToolStepperState`, top-level `private val aegisShimmerTheme`, two private composables `ShimmerSkeletonRow` + `StepRow`, and the public `ToolStepper` with the new 4-param signature.
- `android/app/src/androidTest/java/com/aegis/health/ui/common/ToolStepperStateTransitionTest.kt` — **created** — 110 LOC. Two `@Test` methods (`new_step_arrival_transitions_prior_row_to_done` asserts step-row-Done-0 + step-row-Running-1 testTags after a `runOnIdle { steps += "Step B" }`; `single_step_renders_as_running` asserts step-row-Running-0 testTag).
- `android/app/src/androidTest/java/com/aegis/health/ui/common/ToolStepperFailureChipTest.kt` — **created** — 132 LOC. Two `@Test` methods (`failure_at_index_renders_warning_chip_not_check_mark` asserts BOTH presence of the calm-tone chip text AND absence of step-row-Done-0 testTag; `failure_reason_truncates_at_64_chars` injects a 200-char reason and asserts the rendered text contains exactly 64 x's).
- `android/app/src/androidTest/java/com/aegis/health/ui/common/ToolStepperAnimatorScaleTest.kt` — **created** — 120 LOC. `@Before captureScale` + `@After restoreScale` + one `@Test scale_zero_disables_animations`. First repo test mutating `Settings.Global` — defensive cleanup invariant in place even while `@Ignore`'d.
- `android/app/src/androidTest/java/com/aegis/health/ui/common/LatencyHonestCopyTest.kt` — **created** — 98 LOC. Two `@Test` methods (`toolStepper_renders_latency_honest_subline` with real steps; `toolStepper_renders_subline_even_in_empty_steps_state` with empty steps — both substring-match "running on your phone").

## Decisions Made

### 1. Open Q #2 Option B — Map default vs SnapshotStateMap remember

The plan's `<read_first>` block recommends `failures: Map<Int, FailureInfo> = emptyMap()` over `failures: SnapshotStateMap<Int, FailureInfo> = remember { mutableStateMapOf() }`. Reasons:

- The Phase 5 D-08 pinned 3-param call shape (`ToolStepperSmokeTest:55-58`) compiles unchanged with either default — the Map default is byte-cleaner.
- `SnapshotStateMap<Int, FailureInfo>` IS-A `Map<Int, FailureInfo>` via interface inheritance, so screens that hold a `SnapshotStateMap` in remembered state simply pass it (Compose snapshot tracking continues to fire on the source map's mutations).
- Compose's default-parameter language semantics make `remember { … }` in parameter-default position legal but visually noisy and easy to misread.
- Plan 07-03/07-04 screens declare their own `failures = remember { mutableStateMapOf<Int, FailureInfo>() }` and pass it explicitly.

### 2. Open Q #1 resolution — rendered-semantics check via single LatencyHonestCopyTest

D-05 specifies the literal lives ONCE in `ToolStepper.kt` and all three screens inherit it via composable inclusion. The plan's earlier draft suggested a parametrized test across three screens; 07-RESEARCH.md Open Q #1 explicitly resolved this as "testing ToolStepper once + composable-inclusion guarantee covers all three screens." The plan adopts that resolution — `LatencyHonestCopyTest` mounts ToolStepper directly and substring-matches "running on your phone". Plans 07-03/07-04 may add thin per-screen smokes if the verifier wants additional defense-in-depth.

### 3. Private enum rename — `StepState` → `ToolStepperState`

Building Task 1's first cut surfaced a Kotlin compilation error: `Redeclaration: enum class StepState`. LoadingPanel.kt:137 already declares `private enum class StepState { Pending, Active, Done }`. Kotlin's `private` at top level is file-private, but the build failed regardless — likely an IDE / AGP interaction around enum name resolution in the same package. Renamed the ToolStepper enum to `ToolStepperState { Running, Done, Failed }` to avoid the collision; the testTag scheme `step-row-${state.name}-${idx}` keeps the plan's expected tags (`step-row-Done-0`, `step-row-Running-0`, `step-row-Failed-N`) unchanged because `ToolStepperState.Done.name == "Done"`.

### 4. Modifier.testTag at StepRow root, not inner widget

The plan suggested `Modifier.testTag("step-row-$state-$idx")` inside StepRow. Implementation places the testTag on the outermost `Row` of StepRow (one tag per logical step row) — semantic-tree lookups via `onNodeWithTag("step-row-Done-0", useUnmergedTree = true)` resolve to that node. This is simpler and clearer than tagging the inner check-icon Box (which would still work but reads less intuitively).

## Deviations from Plan

### Rule 3 — blocking issue auto-fix

**1. [Rule 3 — Blocking] Renamed private enum to avoid LoadingPanel redeclaration**
- **Found during:** Task 1 first compile (`./gradlew :app:assembleDebug`)
- **Issue:** Plan instructed to declare `private enum class StepState { Running, Done, Failed }` at file scope inside ToolStepper.kt. Compiler reported `Redeclaration: enum class StepState` because LoadingPanel.kt:137 in the same package already declares a file-private `private enum class StepState { Pending, Active, Done }`. (Kotlin's `private` at top level is file-private in the spec, but the AGP / IDE build chain treats same-named enums in the same package as redeclarations.)
- **Fix:** Renamed to `private enum class ToolStepperState { Running, Done, Failed }` and updated all 7 references inside ToolStepper.kt. The plan's testTag scheme `step-row-Done-0` / `step-row-Running-0` is preserved because the format string uses `state.name` not the type name — `ToolStepperState.Done.name == "Done"`.
- **Files modified:** `android/app/src/main/java/com/aegis/health/ui/common/ToolStepper.kt`
- **Commit:** Folded into the Task 1 commit `b8632b9` (build had to pass for the commit to land).

### Plan documentation drift (informational — substantive intent satisfied)

**2. Acceptance-criteria `exactly 1` greps return >1 due to KDoc references**
- **Found during:** Task 1 final verification
- **Issue:** Several acceptance-criteria greps specified "exactly 1" or "exactly 2" hits — e.g. `grep -c "modifier: Modifier = Modifier" returns exactly 1` and `grep -c "running on your phone" returns exactly 1`. Actual counts are higher because the production KDoc cites the pinned signature shape and the D-05 literal verbatim. Specifically: `modifier: Modifier = Modifier` returns 2 (1 declaration + 1 KDoc quote of the pin); `running on your phone` returns 2 (1 Text + 1 KDoc reference); `steps: List<String>` returns 3 (1 declaration + 2 KDoc references); `failures: Map<Int, FailureInfo>` returns 3 (1 declaration + 2 KDoc).
- **Disposition:** Substantive intent (one declaration site for each surface, one Text literal for D-05) is fully satisfied. The acceptance-criteria wording assumed source files contain only code, not commentary. Plan 07-01's SUMMARY tracks a similar grep-pattern drift in its `## Deviations from Plan` section.
- **Files modified:** N/A (cosmetic on the plan; the code is the right shape)
- **Commit:** N/A

**3. Acceptance-criteria `sevCritFg|sevCritBg` returned 2 in initial draft (KDoc explained STEP-06 negative rule using the token names)**
- **Found during:** Task 1 acceptance-criteria grep audit
- **Issue:** Plan acceptance criterion `grep -c "sevCritFg\\|sevCritBg" returns 0` was violated because the KDoc and an inline comment both referenced the token names to document the negative rule ("NEVER `sevCritFg` / `sevCritBg` (STEP-06)").
- **Fix:** Reworded both occurrences to "NEVER the red severity-critical palette (STEP-06: calm-tone, NOT red panic copy)" — the substantive STEP-06 enforcement (no code use of the red tokens) is unchanged; the grep gate now returns 0 cleanly.
- **Files modified:** `android/app/src/main/java/com/aegis/health/ui/common/ToolStepper.kt` (KDoc + one inline comment).
- **Commit:** Folded into Task 1 commit `b8632b9`.

**4. `assertDoesNotExist` import path correction**
- **Found during:** Task 2 first compile
- **Issue:** Imported `androidx.compose.ui.test.assertDoesNotExist` per plan-style import lists. Compiler reported `Unresolved reference 'assertDoesNotExist'` — the method is a member of `SemanticsNodeInteraction` (returned by `onNodeWithTag`), not a free function under `androidx.compose.ui.test`. No import needed for `onNodeWithTag(...).assertDoesNotExist()`.
- **Fix:** Removed the unused import. Build then succeeded.
- **Files modified:** `android/app/src/androidTest/java/com/aegis/health/ui/common/ToolStepperFailureChipTest.kt`
- **Commit:** Folded into Task 2 commit `cd14d40`.

**5. Worktree bootstrap copies (plan & support files)**
- **Found during:** Plan start
- **Issue:** The agent's git worktree base (`75bf711 chore: merge executor worktree`) did not contain `07-02-PLAN.md`, `07-RESEARCH.md`, or `07-PATTERNS.md` — those files exist only in the main repo's working tree (uncommitted). To execute the plan, copied 07-02-PLAN.md, 07-RESEARCH.md, 07-PATTERNS.md into the worktree under `.planning/phases/07-toolstepper-ui-latency-honest-skeletons/`. Also copied `android/local.properties` (gitignored — local SDK path needed for Gradle).
- **Disposition:** None of the copied files are committed in this worktree. They are tooling-time artifacts that the orchestrator merges separately. Verified via `git status` post-task that no `.planning/phases/07-…/07-{02-PLAN,RESEARCH,PATTERNS}.md` is tracked in this worktree's commits.
- **Files modified:** N/A (out-of-tree tooling artifacts)
- **Commit:** N/A

### No Rule 1 (bug) or Rule 2 (missing critical functionality) deviations

The plan's `<threat_model>` was satisfied entirely by the explicit code paths the plan specified — no missing-critical-functionality issues surfaced beyond the plan's anticipated set:

- **T-07-05 (Information Disclosure via `failureReason`):** Mitigated by `failureReason?.take(64)` clamp in StepRow's Failed branch (line ~234 of ToolStepper.kt). Implemented as specified.
- **T-07-06 (Transitive churn via compose-shimmer):** No new deps added. compose-shimmer 1.4.0 was already on tree (`android/app/build.gradle.kts:68`, Phase 5 INFRA-02).
- **T-07-07 (DoS via animations faster than decode rate):** Mitigated by `tween(350)` for state transitions, `tween(1800, easing = LinearEasing)` for shimmer cycle. Compose framework auto-honors `ANIMATOR_DURATION_SCALE` (no manual lookup; SKEL-05).
- **T-07-08 (Test pollution via ANIMATOR_DURATION_SCALE leak):** Mitigated by `@Before captureScale` + `@After restoreScale` in `ToolStepperAnimatorScaleTest.kt`. Defensive cleanup in place even while `@Ignore`'d.
- **T-07-09 (Repudiation via fake-success ✓ on failed row):** Mitigated structurally by the when-block ordering in ToolStepper body — `failures.containsKey(idx) -> ToolStepperState.Failed` BEFORE `idx < steps.lastIndex -> ToolStepperState.Done`. A failed row CANNOT be misidentified as Done.

### No authentication gates

No auth gates encountered during execution.

## Threat Surface Scan

No new threat surface introduced beyond what Plan 07-01 + Plan 07-02's `<threat_model>` already enumerated. All five threats (T-07-05 / T-07-06 / T-07-07 / T-07-08 / T-07-09) are mitigated as specified. No `threat_flag` entries needed.

## Known Stubs

None. All code paths are wired to real data sources or Compose primitives. The `failures: Map<Int, FailureInfo> = emptyMap()` default is intentional (it represents "no failures reported"), not a stub. Plans 07-03 / 07-04 will populate `failures` from `ProgressEvent.StepFailure` events routed through screen-level `SnapshotStateMap` state holders.

## TDD Gate Compliance

Task 1 was marked `tdd="true"` in the plan's frontmatter. Strict RED/GREEN ordering as defined in `references/tdd.md` could not be applied to the plan's enumerated behavior tests because all 5 behavior tests are Compose UI tests blocked by the SM-S918B TEST-FRAMEWORK-01 BOM 2026.05.00 regression (memory pin `project_compose_bom_test_regression.md`).

The plan explicitly addressed this in Task 2: ship the behavior tests as class-level `@Ignore`'d files that compile (and therefore lock the contract surface at build time) against the new ToolStepper 4-param signature. The substantive TDD guarantee — that the behavior is testable and the test code lives in tree — is satisfied:

- Test 1 (smoke contract preservation): pinned by the pre-existing `ToolStepperSmokeTest` (Phase 5 D-10) compiling unchanged against the new signature via the `failures` default. `:app:assembleDebugAndroidTest` BUILD SUCCESSFUL is the gate.
- Tests 2-5 (D-03 / D-04c / D-05 / D-03a shimmer): pinned by the four new `@Ignore`'d androidTest files. They compile against the new 4-param signature (`:app:assembleDebugAndroidTest` BUILD SUCCESSFUL) and ship the assertion logic so Phase 10 P1's ignore-removal flips them live.

No `test(...)` commit ships ahead of the `feat(...)` commit for Task 1 because the test code IS the four `@Ignore`'d test files committed in Task 2. The strict TDD gate enforcement (`gsd-sdk query task.is-behavior-adding`) does not block this plan because the orchestrator did not run with `MVP_MODE=true && TDD_MODE=true` flags in the prompt context for this worktree spawn.

## Self-Check: PASSED

- File `android/app/src/main/java/com/aegis/health/ui/common/ToolStepper.kt` modified — 279 LOC, contains `fun ToolStepper(` (1) + `data class FailureInfo` (1) + `running on your phone` literal (1 Text + 1 KDoc) + `tween(350)` (5 sites) + `tween(durationMillis = 1800` (1 site) + `togetherWith` (2) + `warningFg|warningBg` (5 references) — verified.
- File `android/app/src/androidTest/java/com/aegis/health/ui/common/ToolStepperStateTransitionTest.kt` exists — verified.
- File `android/app/src/androidTest/java/com/aegis/health/ui/common/ToolStepperFailureChipTest.kt` exists — verified.
- File `android/app/src/androidTest/java/com/aegis/health/ui/common/ToolStepperAnimatorScaleTest.kt` exists — verified.
- File `android/app/src/androidTest/java/com/aegis/health/ui/common/LatencyHonestCopyTest.kt` exists — verified.
- All 4 androidTest files contain the literal `TEST-FRAMEWORK-01: BOM 2026.05.00 regressed Compose UI test framework on SM-S918B` — verified (count = 4).
- Each of the 4 androidTest files has a class-level `@Ignore` annotation — verified (count = 1 per file).
- `ToolStepperAnimatorScaleTest.kt` contains BOTH `@Before` (2 matches: annotation + import) and `@After` (2 matches: annotation + import) — verified.
- `ToolStepperAnimatorScaleTest.kt` contains `Settings.Global.ANIMATOR_DURATION_SCALE` and `settings put global animator_duration_scale` — verified.
- `LatencyHonestCopyTest.kt` contains `onNodeWithText("running on your phone"` (3 references — 2 test methods + 1 KDoc reference) — verified.
- `ToolStepperFailureChipTest.kt` contains `FailureInfo(` (2 — both test methods construct one) and `assertDoesNotExist` (1 — the negative-guard line) — verified.
- Commit `b8632b9` (Task 1) exists in git log — verified.
- Commit `cd14d40` (Task 2) exists in git log — verified.
- `:app:assembleDebug` BUILD SUCCESSFUL — verified.
- `:app:assembleDebugAndroidTest` BUILD SUCCESSFUL — verified.
- `:app:testDebugUnitTest` 195/195 green — verified via test-results XML parse (total=195 skipped=0 failures=0 errors=0).

## What Phase 07 Plan 03 Unlocks

With this plan landed, Plan 07-03 (DrugSafe + HealthPartner screen migration) can:

1. Replace `LoadingPanel(autoAdvance=false, ...)` with `ToolStepper(...)` at `DrugSafeScreen.kt:233` and `HealthPartnerScreen.kt:230` — drop-in swap; same `steps: List<String>` consumer pattern.
2. Add a screen-level `failures = remember { mutableStateMapOf<Int, FailureInfo>() }` state holder.
3. Extend the existing `onProgress = { event -> when (event) { ... } }` block to route `ProgressEvent.StepFailure` into `failures[steps.lastIndex] = FailureInfo(...)`.
4. Pass the populated `failures` map as the 4th argument: `ToolStepper(label, steps, modifier, failures = failures)`.
5. Inherit the D-05 latency-honest subline automatically via composable inclusion — no per-screen copy duplication needed.

Plan 07-04 (ReportReader migration + DeferralScreen revert) follows the same shape, with the additional `runReportReaderFastPath` invocation move from `DeferralScreen.kt:98` into `ReportReaderScreen` per D-02.
