# Phase 7: ToolStepper UI + Latency-Honest Skeletons — Context

**Gathered:** 2026-05-15
**Status:** Ready for planning

<domain>
## Phase Boundary

Replace the placeholder body in `ui/common/ToolStepper.kt` with the production live-tools UI and wire it onto **DrugSafe / ReportReader / HealthPartner** during the synthesis turn. ConsentReader is permanently excluded (STEP-05 + SC-4 grep gate).

After Phase 7 closes:

- A user tapping submit on DrugSafe / ReportReader / HealthPartner sees `ToolStepper` materialize within 1–2 s, with a compose-shimmer "Preparing… / Loading on-device model…" skeleton row covering the pre-first-`ProgressEvent.Step` window.
- Each tool call appears as a stepper row with the args-aware label from `FriendlyToolSummarizer` (Phase 5), transitioning ↻ running → ✓ done via `AnimatedContent` as the next event fires.
- Failed tool calls render a calm-tone ⚠ error chip, **never** a fake-success ✓ (STEP-06). A new `ProgressEvent.StepFailure(label, reason)` sealed subtype carries the signal from `ToolDispatcher.kt:913` catch site to the UI.
- Skeleton shimmer runs at ≥ 1.8 s cycle; no on-screen motion runs faster than the actual decode rate (~3.7 pieces/s); `Settings.Global.ANIMATOR_DURATION_SCALE = 0` produces a non-animated stepper (SKEL-03 + SKEL-05).
- At least one loading surface per stepper-bearing screen renders the literal phrase **"running on your phone — ~5 minutes"** (or close equivalent) (SKEL-04, C4 mitigation).
- `.planning/codebase/CONVENTIONS.md` documents the `LoadingPanel` (autoAdvance=true, decorative) vs `ToolStepper` (live-tools) split before phase close (SC-6).
- `runReportReaderFastPath` invocation moves from `DeferralScreen.kt:98` into `ReportReaderScreen` (Phase 6 STREAM-01-followup closed). `ReportReaderScreen.flagPreviews` becomes reachable production state.

**Concretely, Phase 7 lands:**

- `ui/common/ToolStepper.kt` body replaced wholesale. Pinned `@Composable fun ToolStepper(label: String, steps: List<String>, modifier: Modifier = Modifier)` signature (Phase 5 D-08) preserved. New body implements:
  - Three-state row rendering (pending ○ — only used during shimmer skeleton; running ↻; done ✓; failed ⚠).
  - `AnimatedContent` per-row state transition at ≤ 350 ms.
  - `AnimatedVisibility` for new-row appearance.
  - `compose-shimmer` 1.4.0 skeleton at ≥ 1.8 s cycle for the pre-first-Step row.
  - `ANIMATOR_DURATION_SCALE` honor (preferred path: framework auto-honor via Compose `AnimatedContent` + the `transitionSpec` reading from `LocalContext`).
- Three screens migrate `LoadingPanel(autoAdvance=false, ...)` → `ToolStepper(...)` — drop-in swap, same `steps: List<String>` consumer pattern. The flagPreviews `SeverityCard` rail (Phase 6) renders unchanged immediately below `ToolStepper`.
- `ReportReaderScreen` gains a direct `runReportReaderFastPath` invocation (mirroring DrugSafeScreen.kt:184-207). DeferralScreen reverts to deferral-only — its flagPreviews chip rail at :174-201 is removed or repurposed.
- `ToolDispatcher.ProgressEvent` sealed class gains a fourth subtype: `data class StepFailure(val label: String, val reason: String) : ProgressEvent()`. Single emission site at the existing `catch (e: Exception)` block in `executeToolCall` (line ~913).
- `androidTest` smoke tests assert: stepper renders, three-state row transitions, calm-tone error chip on synthetic StepFailure, ConsentReader negative-grep gate, ANIMATOR_DURATION_SCALE=0 produces a non-animated stepper, latency-honest copy literal grep on each of the three screens.
- `CONVENTIONS.md` gains a new "LoadingPanel vs ToolStepper" subsection under § Jetpack Compose Conventions (line 308+).

**Out of scope for Phase 7:**

- ConsentReader. STEP-05 explicit exclusion. `ConsentReaderScreen.kt:216` `LoadingPanel(autoAdvance=true)` stays.
- Any ProgressEvent semantic change to `Step` / `Update` / `FlagPreview`. Phase 5 D-09 still holds for the three existing shapes; only the additive fourth subtype lands.
- LoadingPanel deletion. LoadingPanel stays on tree for ConsentReader's decorative `autoAdvance=true` case.
- ReportReader visual polish (Phase 8). The summary card, severity tokens, "Discuss with your doctor" CTA, `tokenForStatus(...)` helper — all Phase 8.
- Home / Startup polish (Phase 9).
- Demo recording + P1 stretch (Phase 10), including STEP-07 collapse-to-summary.
- Any model retraining, .litertlm re-export, or Backend.GPU experiment. SFT v4 frozen; Backend.CPU locked.
- `FriendlyToolSummarizer` label changes. Phase 5 D-01 wording stands; Phase 7 consumes the existing output unchanged.
- `FlagsStreamParser` or `MonotonicFlagList` changes. Phase 6 contracts preserved.

</domain>

<decisions>
## Implementation Decisions

### Migration strategy

- **D-01 (Drop-in swap):** Three screens — `DrugSafeScreen.kt:233`, `HealthPartnerScreen.kt:230`, and `ReportReaderScreen` (new invocation, see D-02) — replace `LoadingPanel(label=..., steps=progress, autoAdvance=false, modifier=...)` with `ToolStepper(label=..., steps=progress, modifier=...)`. The `flagPreviews` SeverityCard rail from Phase 6 stays immediately below, unchanged. ConsentReader's `LoadingPanel(autoAdvance=true)` at `ConsentReaderScreen.kt:216` stays — that's the documented LoadingPanel-vs-ToolStepper split (SC-6).

- **D-01a (No LoadingPanel deletion):** LoadingPanel.kt stays on tree. The decorative-step `autoAdvance=true` codepath is the ConsentReader use case; Phase 7 does not refactor it. CONVENTIONS.md captures the split in prose, not by deletion.

### ReportReader synthesis-invocation site (Phase 6 STREAM-01-followup closed)

- **D-02 (Path B — move into ReportReaderScreen):** `ToolDispatcher.runReportReaderFastPath(report, onProgress = { ... })` invocation moves from `DeferralScreen.kt:98` into `ReportReaderScreen` body. The new invocation site mirrors the DrugSafe pattern at `DrugSafeScreen.kt:184-207` (scope.launch from a button/CTA, isLoading flips, progress + flagPreviews state cleared on each invocation). `ReportReaderScreen.flagPreviews` (declared by Plan 06-02 but unreachable in production) becomes reachable.

- **D-02a (DeferralScreen reverts to deferral-only):** DeferralScreen's flagPreviews chip-rail at `:174-201` and its `LaunchedEffect(initialPendingReport)` synthesis trigger at `:95-101` are removed. `DeferralStore.pendingReport` is repurposed or eliminated. Verify at plan time whether DeferralStore has other consumers — if so, keep the store but stop writing the report into it from ReportReaderScreen's "Bring this to your clinician" CTA.

- **D-02b (headerSlotCount math regression risk):** ReportReaderScreen LazyColumn `headerSlotCount` heuristic at `:283` currently counts ScreenHeader + NotADiagnosisPanel + SummaryCard (= 3) or +GenericFallbackBanner (= 4) for the Phase 4.1 Pitfall 1 fix. Adding ToolStepper + flagPreviews rail inside the `isLoading` branch must NOT bump that count when `isLoading == false`. Plan time: verify the `item { }` placement keeps `headerSlotCount` unchanged in the non-loading state (e.g. emit ToolStepper inside a `if (isLoading) item { ... }` block, not as a permanent header slot).

### ToolStepper state machine

- **D-03 (Last=running, prior=done):** The visible-state derivation from `steps: List<String>` is the same model LoadingPanel already uses (lines 137 + 93-100): the last element is `Running`, all prior elements are `Done`, no `Pending` ○ rows are rendered in steady state. Justification: the list grows reactively from `ProgressEvent.Step` arrival — there are no future labels to mark Pending. Honors Phase 5 D-09 verbatim.

- **D-03a (Pre-first-Step skeleton):** Before the first `ProgressEvent.Step` arrives, render a single `compose-shimmer` skeleton row inside `ToolStepper` body. SKEL-02 specifies the copy sequence: `"Preparing…"` → `"Loading on-device model…"` → `"Thinking through your request…"` → `"Composing the answer…"`. Phase 7 renders only the first of these as a shimmer row while `steps.isEmpty()`; once the first real Step arrives, the shimmer row is replaced. The remaining three SKEL-02 strings are reserved for the `Update` label sub-cadence (deferred to planner discretion — see Claude's Discretion).

- **D-03b (Transition timing):** `AnimatedContent` row state transition cap = 350 ms (per ROADMAP SC-2). Skeleton-shimmer cycle = 1.8 s (per SKEL-01). Spinner rotation, if any, capped at 1.2 s/rev (per SC-3). No animation runs faster than 1 step/sec.

- **D-03c (Failed-row rendering):** `StepFailure` rows render with a calm-tone ⚠ chip in `colors.warningBg` / `colors.warningFg` (or equivalent existing tokens; planner verifies in `Theme.kt`). NOT `sevCritBg` / `sevCritFg` — STEP-06 says "calm-tone error chip, NOT red panic copy". The row stops animating ↻ and stays static at ⚠ with `label — reason.take(64)` as the displayed text.

### Failed tool-call signaling

- **D-04 (Add ProgressEvent.StepFailure(label, reason)):** Extend the `ProgressEvent` sealed class at `ToolDispatcher.kt:373-403` with a fourth subtype:
  ```kotlin
  data class StepFailure(
      val label: String,
      val reason: String,
  ) : ProgressEvent() {
      override fun applyTo(steps: MutableList<String>) {
          steps.add("⚠ $label — ${reason.take(64)}")
      }
  }
  ```
  Note: `applyTo` is defined so the consumer contract stays uniform — non-stepper consumers (none today, but reserved for forward-compat) get a sentinel-prefixed string in their `progress` list and degrade gracefully. ToolStepper itself routes `StepFailure` events through a side channel (a `mutableStateMapOf<Int, FailureInfo>` indexed by step position) so the ⚠ chip renders without parsing the prefixed string.

- **D-04a (Single emission site):** The dispatcher emits `onProgress(ProgressEvent.StepFailure(...))` from the existing `catch (e: Exception)` block at `ToolDispatcher.kt:913-915`. The `label` argument is `FriendlyToolSummarizer.summarize(toolCall)` (same as the Step that was emitted when the tool started); the `reason` is `e.message ?: "Tool execution failed"`. The catch block continues to return a `ToolResult(name, result=errorJson(...))` so the agentic loop's existing error-recovery path is unchanged.

- **D-04b (D-09 relaxation scope):** Phase 5 D-09 said "ProgressEvent sealed class — Step / Update / FlagPreview shapes unchanged." That commitment scoped to Phase 5. Phase 7's addition of `StepFailure` is additive (no existing shape changes) and consistent with the sealed-class extension pattern. No existing Step / Update / FlagPreview consumer breaks; new subtype's `applyTo` keeps the `MutableList<String>` consumer contract intact.

- **D-04c (No UI-side parsing of "⚠" prefix):** ToolStepper does NOT parse `applyTo`'s prefixed string to detect failures. The screen-level `onProgress` callback routes `StepFailure` to a dedicated state surface (e.g. `failures: SnapshotStateMap<Int, FailureInfo>` keyed by step index), and ToolStepper reads from that map. This avoids the sentinel-parse anti-pattern Phase 6 rejected (RESEARCH § Anti-Patterns).

### Latency-honest copy (SKEL-04)

- **D-05 (One subline inside ToolStepper composable):** "running on your phone — ~5 minutes" renders as a single subline at the bottom of the ToolStepper composable, below the steps list. Single source of truth — all three screens inherit the copy without per-screen duplication. Style: `MaterialTheme.typography.bodySmall`, `colors.onSurfaceMuted`. Always rendered while `ToolStepper` is on screen — not just during shimmer skeleton phase, not just on first row. The SC-5 androidTest is a parametrized grep against the three stepper-bearing screens for the literal anchor copy.

### CONVENTIONS.md split documentation

- **D-06 (New subsection under Jetpack Compose Conventions):** A new H3 subsection `### LoadingPanel vs ToolStepper` lands under `## Jetpack Compose Conventions` (line 308 in CONVENTIONS.md). Wording lock:
  > **LoadingPanel** (`ui/common/LoadingPanel.kt`) is the **decorative live-progress** composable. Use `autoAdvance = true` when the screen has no real progress signal and just needs a stepped-illusion of activity. ConsentReader is the canonical caller (`ConsentReaderScreen.kt:216`).
  >
  > **ToolStepper** (`ui/common/ToolStepper.kt`) is the **live-tools** composable backed by `ToolDispatcher.ProgressEvent` stream. Use when the screen subscribes to real `onProgress` callbacks. DrugSafe / ReportReader / HealthPartner are the canonical callers. The flagPreviews `SeverityCard` rail (Phase 6) renders **below** ToolStepper, not inside it.
  >
  > Failed tool calls reach the stepper via `ProgressEvent.StepFailure(label, reason)`; rendered with a calm-tone ⚠ chip, **never** as a fake-success ✓.

  Phase 7 lands this commit before phase close (SC-6).

### Claude's Discretion

- Exact distribution of SKEL-02's four-copy sequence across the synthesis lifecycle. D-03a specifies only the first ("Preparing…") for the pre-Step shimmer row. The remaining three ("Loading on-device model…" / "Thinking through your request…" / "Composing the answer…") map naturally to:
  - Update events with `count < 50` → "Loading on-device model…"
  - Update events with `count >= 50` and `lastFlagCount == 0` → "Thinking through your request…"
  - Update events with `lastFlagCount > 0` → "Composing the answer…"
  Planner adopts or adjusts based on the actual fast-path / agentic-loop emission cadence at ToolDispatcher.kt:626-644 + :813-838.
- Choice of `compose-shimmer` API surface — likely `Shimmer.rememberShimmer(ShimmerBounds.View)` + `Modifier.shimmer(shimmer)` per the upstream `valentinilk/compose-shimmer` 1.4.0 readme. Cycle config: `ShimmerTheme(animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart))`.
- Calm-tone error-chip color tokens. If `Theme.kt` lacks `warningBg` / `warningFg`, planner either adds them (1 line each in `Color.kt` + `AegisColors.kt`) or repurposes an existing low-severity token. AVOID `sevCritBg` / `sevCritFg` (red panic palette per STEP-06).
- Whether DeferralScreen-removal under D-02a is in-scope for Phase 7 or split to a follow-up plan. Recommend: in-scope. The plan's third or fourth wave can land a small DeferralScreen cleanup commit alongside the ReportReader invocation rewire. If `DeferralStore.pendingReport` has no other consumers, delete the store.
- Per-plan wave structure. Likely:
  1. **Wave 1 (foundation):** add `ProgressEvent.StepFailure` subtype + dispatcher emission at line 913 + a JVM unit test on the new subtype's `applyTo`. No UI consumer yet.
  2. **Wave 2 (ToolStepper body):** replace placeholder body with three-state + shimmer + latency-copy implementation. Smoke androidTest on the placeholder contract still passes (label + step-string presence); new tests added for state transitions and ANIMATOR_DURATION_SCALE.
  3. **Wave 3 (DrugSafe + HealthPartner migration):** drop-in swap on those two screens. Existing flagPreviews rail untouched. androidTest mirror of DrugSafe's existing 10 Compose tests verified.
  4. **Wave 4 (ReportReader migration + DeferralScreen revert):** move runReportReaderFastPath invocation, mount ToolStepper inside isLoading branch, revert DeferralScreen, verify headerSlotCount math.
  5. **Wave 5 (CONVENTIONS.md doc + grep gates):** land D-06 subsection; run the six grep gates (no `ToolStepper(` in ConsentReaderScreen, latency copy literal on each of three screens, etc.).
- Whether the StepFailure path needs a regression test against a synthetic dispatcher failure (preferred: yes — feed a deliberately-throwing tool to `runDrugSafeFastPath` in androidTest, assert ⚠ chip appears, assert no ✓ on the failed row).
- Exact wording of `D-04`'s `reason.take(64)` truncation length. Planner finalizes after eyeballing typical `e.message` lengths (SQLite errors, JSON parse errors, NPE). Recommend 80 chars if the chip wraps to 2 lines cleanly.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Project-level context (always read first)
- `.planning/PROJECT.md` — v1.1 Hackathon Polish milestone goal: make the on-device tool-call loop visible to demo-video viewers; tighten every screen judges see during the ~5-min synthesis turn. Phase 7 is the visible-stepper deliverable for Track A.
- `.planning/REQUIREMENTS.md` — Phase 7 owns STEP-01 through STEP-06 + SKEL-01 through SKEL-05. Cross-references upstream from Phase 5 (INFRA-06 ToolStepper skeleton) + Phase 6 (STREAM-01..04 flagPreviews rail).
- `.planning/ROADMAP.md` §"Phase 7: ToolStepper UI + Latency-Honest Skeletons" (line 386+) — locked goal, 11 requirements (6 STEP + 5 SKEL), 6 success criteria, open-question #1 resolution.
- `.planning/STATE.md` — v1.1 locked decisions: SFT v4 frozen, LiteRT-LM 0.10.2 pinned, Backend.CPU only, AegisResponse schema frozen, ConsentReader excluded from stepper UI.
- `CLAUDE.md` (repo root) — Backend.CPU constraint (memory `project_gpu_precision_drift.md`); LiteRT-LM ↔ litert-torch major-version coupling (memory `project_litertlm_prefill_lengths.md`); offline-no-INTERNET-permission guarantee.

### Prior-phase locked decisions Phase 7 inherits
- `.planning/phases/05-stepper-streaming-infrastructure/05-CONTEXT.md` —
  - **D-08:** `ToolStepper(label: String, steps: List<String>, modifier: Modifier = Modifier)` signature is PINNED. Phase 7 replaces body wholesale; signature unchanged.
  - **D-09:** `steps: List<String>` consumer pattern. Screens accumulate `ProgressEvent.Step` labels into `mutableStateListOf<String>` — no Flow, no ViewModel, no typed StepItem.
  - **D-13:** Single-buffer-owner. `LiteRtLmEngine.sb` stays local. Phase 7's StepFailure addition doesn't touch this.
- `.planning/phases/06-streaming-preview-wiring-reportreader-healthpartner/06-PATTERNS.md` — Pattern 5 + Pattern 6 (DrugSafeScreen.kt:84-87, 184-207, 248-262 as the mandated mirror shape for screen wiring). Phase 7 mirrors the same shape for ReportReader's new direct invocation.
- `.planning/phases/06-.../deferred-items.md` (STREAM-01-followup) — explicitly tags Phase 7 as the closure phase. Path B (D-02) is the chosen resolution.

### Code anchors (Phase 7 consumes / extends)
- `android/app/src/main/java/com/aegis/health/ui/common/ToolStepper.kt` — current placeholder body at lines 19-30. Phase 7 replaces lines 25-29 wholesale; line 1-18 signature + KDoc stay (refresh KDoc to describe production body).
- `android/app/src/main/java/com/aegis/health/ui/common/LoadingPanel.kt` —
  - Existing `StepState` enum + `StepRow` private composable at lines 137-176. Phase 7 either references these as the visual model OR re-implements equivalents inside ToolStepper (planner discretion; recommend re-implement for cleaner separation).
  - `PulsingDot` at lines 107-135. Reference for SKEL-05 ANIMATOR_DURATION_SCALE behavior — `infiniteRepeatable` auto-honors the system setting.
- `android/app/src/main/java/com/aegis/health/inference/ToolDispatcher.kt` —
  - `ProgressEvent` sealed class at lines 373-403 — Phase 7 adds 4th subtype `StepFailure(label, reason)`.
  - `catch (e: Exception)` at lines 913-915 (`executeToolCall`) — Phase 7 emission site for the new event.
  - Fast-path emission sites at lines ~422, ~462, ~519 — already emit `ProgressEvent.Step(FriendlyToolSummarizer.summarize(...))` from Phase 5 D-05/D-06; unchanged.
  - Agentic-loop emission site at line ~830 — already emits the same `Step` shape; unchanged.
- `android/app/src/main/java/com/aegis/health/ui/drugsafe/DrugSafeScreen.kt:233` — Phase 7 swaps `LoadingPanel(autoAdvance=false, ...)` → `ToolStepper(...)`. flagPreviews rail at :248-262 unchanged.
- `android/app/src/main/java/com/aegis/health/ui/healthpartner/HealthPartnerScreen.kt:230` — Same swap.
- `android/app/src/main/java/com/aegis/health/ui/reportreader/ReportReaderScreen.kt` —
  - Add new `scope.launch { ToolDispatcher.runReportReaderFastPath(report, onProgress = { ... }) }` invocation, mirroring DrugSafeScreen.kt:184-207.
  - Mount `ToolStepper` inside the `isLoading -> { item { ... } }` LazyColumn branch.
  - Existing `flagPreviews` state (Plan 06-02, line ~122) finally becomes reachable.
  - `headerSlotCount` at :283 — verify Phase 4.1 Pitfall 1 fix not regressed.
- `android/app/src/main/java/com/aegis/health/ui/deferral/DeferralScreen.kt` —
  - `LaunchedEffect(initialPendingReport)` synthesis trigger at :95-101 — removed.
  - flagPreviews chip-rail at :174-201 — removed.
  - `LoadingPanel(autoAdvance=...)` call at :203 — TBD: if DeferralScreen still renders loading state for non-synthesis flows, LoadingPanel stays; otherwise remove.
- `android/app/src/main/java/com/aegis/health/ui/consentreader/ConsentReaderScreen.kt:216` — `LoadingPanel(autoAdvance=true)` STAYS. Phase 7 SC-4 negative grep gate: `grep -n "ToolStepper(" android/app/src/main/java/com/aegis/health/ui/consentreader/` returns empty.
- `android/app/src/main/java/com/aegis/health/ui/theme/Theme.kt` + `Color.kt` — calm-tone warning tokens for the ⚠ failure chip (D-03c). Planner verifies token availability or adds 2 lines.
- `android/app/build.gradle.kts:68` — `compose-shimmer:1.4.0` already in place (Phase 5). No new deps.
- `android/app/src/main/AndroidManifest.xml:9-17` — INTERNET stripped. Phase 7 does NOT regress (no new deps).

### Test seam anchors
- `android/app/src/test/java/com/aegis/health/inference/` — Phase 7 adds `ProgressEventStepFailureTest.kt` (JVM unit test on the new subtype's `applyTo` contract). Pattern: `FriendlyToolSummarizerTest.kt` (Phase 5).
- `android/app/src/androidTest/java/com/aegis/health/ui/common/ToolStepperSmokeTest.kt` (Phase 5) — existing smoke test must still pass post-body-rewrite (asserts label + step-string presence only, no visual-style assertions per Phase 5 D-10).
- Phase 7 adds new androidTest files (planner finalizes paths):
  - `ToolStepperStateTransitionTest.kt` — ↻ → ✓ transition assertions.
  - `ToolStepperFailureChipTest.kt` — calm-tone ⚠ chip on synthetic StepFailure.
  - `ToolStepperAnimatorScaleTest.kt` — ANIMATOR_DURATION_SCALE=0 produces non-animated stepper (SC-3 + SKEL-05).
  - `ConsentReaderNoToolStepperTest.kt` — negative grep gate as a class-level test (or as a build-time grep in CI).
  - `LatencyHonestCopyTest.kt` — parametrized across three screens for the literal anchor copy.

### Codebase maps (read selectively)
- `.planning/codebase/CONVENTIONS.md` — Phase 7 lands a new LoadingPanel-vs-ToolStepper subsection (D-06) under § Jetpack Compose Conventions (line 308+).
- `.planning/codebase/CONCERNS.md` — relevant landmines:
  - C1 recomposition storm — Phase 6 every-4-pieces throttle mitigates; Phase 7 must not introduce a competing throttle.
  - C4 fake-typing animation — Phase 7 directly addresses via SC-3 animation-rate ceiling + SKEL-04 latency copy.
  - C5 StartupState gate — Phase 9 territory, not Phase 7.
- `.planning/codebase/STACK.md`, `STRUCTURE.md` — read if any decision triggers a new dep (none expected; compose-shimmer already on tree from Phase 5).

### Memory anchors (cross-conversation context)
- `project_gpu_precision_drift.md` — Backend.CPU stays. Phase 7 doesn't touch backend.
- `project_litertlm_prefill_lengths.md` — LiteRT-LM 0.10.2 pinned. Phase 7 doesn't touch deps.
- `project_sft_required.md` + `project_phase4_complete.md` + `project_phase4_1_complete.md` — model frozen.
- `project_compose_bom_test_regression.md` — 2026-05-15: BOM bump broke 10 Compose UI instrumented tests on SM-S918B (`No compose hierarchies found`); JVM tests unaffected. Phase 7 androidTests will inherit this gap until the v2-API migration (Phase 10 P1 TEST-FRAMEWORK-01) lands. Plan time: ship Phase 7 androidTests with class-level `@Ignore("TEST-FRAMEWORK-01: ...")` pattern Plan 06-02 + 06-03 already established, OR find a working subset.
- `project_kbdatabase_startup_race.md` + `project_startup_gate_blocks_reportreader.md` — Phase 7 doesn't change app startup; inherited assumptions hold.

</canonical_refs>

<code_context>
## Reusable Assets in the Codebase

### Patterns Phase 7 extends or mirrors
- **`LoadingPanel`'s three-state visual model** (`LoadingPanel.kt:137-176`) — `private enum class StepState { Pending, Active, Done }` + `StepRow` composable with circular ring + check tint. Phase 7 reuses the visual vocabulary (filled circle for Done, hairline ring for Pending, accent ring for Active/Running) and extends with a 4th calm-tone ⚠ chip for Failure. The `colors.accent` + `colors.hairline` + `colors.accentInk` token set is the right palette source.
- **`PulsingDot` infinite transition** (`LoadingPanel.kt:107-135`) — `rememberInfiniteTransition` + `infiniteRepeatable(tween(700ms), RepeatMode.Reverse)` for the pulse dot. Phase 7's shimmer skeleton uses the same `infiniteRepeatable` pattern at 1.8s cycle. The Compose `infiniteRepeatable` framework auto-honors `Settings.Global.ANIMATOR_DURATION_SCALE`.
- **`ProgressEvent` sealed class + `applyTo` contract** (`ToolDispatcher.kt:373-403`) — Step / Update / FlagPreview. Phase 7 adds StepFailure as a 4th subtype following the exact same pattern (data class + `applyTo` method that mutates `MutableList<String>`).
- **DrugSafeScreen's synthesis-invocation + state-routing pattern** (`DrugSafeScreen.kt:184-207, 248-262`) — Phase 6 PATTERNS.md already documents this as the mandated mirror shape. Phase 7's ReportReaderScreen new invocation mirrors it verbatim.
- **`MonotonicFlagList.appendIfNew`** (`ui/common/MonotonicFlagList.kt`, Phase 6) — flagPreviews state-update pattern used by ReportReader + HealthPartner today. Phase 7 doesn't change it; ReportReader's new direct invocation routes through the same helper.

### Files Phase 7 creates
- `android/app/src/test/java/com/aegis/health/inference/ProgressEventStepFailureTest.kt` — JVM unit test on the new subtype's `applyTo` contract. Pattern source: `FriendlyToolSummarizerTest.kt` (Phase 5).
- `android/app/src/androidTest/java/com/aegis/health/ui/common/ToolStepperStateTransitionTest.kt` — three-state ↻→✓ transition assertions.
- `android/app/src/androidTest/java/com/aegis/health/ui/common/ToolStepperFailureChipTest.kt` — calm-tone ⚠ chip on synthetic StepFailure event.
- `android/app/src/androidTest/java/com/aegis/health/ui/common/ToolStepperAnimatorScaleTest.kt` — SC-3 + SKEL-05 (ANIMATOR_DURATION_SCALE=0 → non-animated stepper).
- `android/app/src/androidTest/java/com/aegis/health/ui/common/LatencyHonestCopyTest.kt` — parametrized across the 3 stepper-bearing screens for the SKEL-04 literal anchor copy.
- `android/app/src/androidTest/java/com/aegis/health/ui/consentreader/ConsentReaderNoToolStepperTest.kt` — STEP-05 negative gate (no `ToolStepper(` call in ConsentReaderScreen.kt). May be a build-time grep gate instead — planner discretion.

### Files Phase 7 edits
- `android/app/src/main/java/com/aegis/health/ui/common/ToolStepper.kt` — replace body wholesale (lines ~25-29). Signature + KDoc updated.
- `android/app/src/main/java/com/aegis/health/inference/ToolDispatcher.kt` — add `StepFailure` subtype to `ProgressEvent` sealed class at :373-403; add `onProgress(StepFailure(...))` emission at :913 catch block (single new line + minor signature wrangling on the `runProcess`-vs-`runReportReaderFastPath` call site, planner verifies).
- `android/app/src/main/java/com/aegis/health/ui/drugsafe/DrugSafeScreen.kt:233` — swap `LoadingPanel(autoAdvance=false, ...)` → `ToolStepper(...)`. ~5 LOC diff.
- `android/app/src/main/java/com/aegis/health/ui/healthpartner/HealthPartnerScreen.kt:230` — same swap.
- `android/app/src/main/java/com/aegis/health/ui/reportreader/ReportReaderScreen.kt` —
  - Add `scope.launch { ToolDispatcher.runReportReaderFastPath(...) }` invocation (~30 LOC mirroring DrugSafeScreen pattern).
  - Add `ToolStepper(...)` mount inside `isLoading -> { item { ... } }` branch (~10 LOC).
  - Reroute existing `flagPreviews` consumer to the new local invocation site (~5 LOC).
  - Net: ~45 LOC added.
- `android/app/src/main/java/com/aegis/health/ui/deferral/DeferralScreen.kt` —
  - Remove `LaunchedEffect(initialPendingReport)` at :95-101 (~7 LOC removed).
  - Remove flagPreviews chip-rail at :174-201 (~28 LOC removed).
  - Net: ~35 LOC removed.
- `android/app/src/main/java/com/aegis/health/ui/theme/Theme.kt` and/or `Color.kt` — add calm-tone `warningBg` / `warningFg` (or equivalent) tokens if absent. ~4 LOC added.
- `.planning/codebase/CONVENTIONS.md` — add LoadingPanel-vs-ToolStepper subsection per D-06. ~20 LOC added.

### Files Phase 7 does NOT touch
- `android/app/src/main/java/com/aegis/health/ui/consentreader/ConsentReaderScreen.kt` — locked exclusion.
- `android/app/src/main/java/com/aegis/health/ui/common/LoadingPanel.kt` — stays for ConsentReader.
- `android/app/src/main/java/com/aegis/health/ui/common/MonotonicFlagList.kt` — Phase 6 contract preserved.
- `android/app/src/main/java/com/aegis/health/inference/FriendlyToolSummarizer.kt` — Phase 5 D-01 wording stands.
- `android/app/src/main/java/com/aegis/health/inference/ToolCallBoundaryDetector.kt` — Phase 5 D-11 contract preserved.
- `android/app/src/main/java/com/aegis/health/inference/LiteRtLmEngine.kt` — engine + buffer ownership unchanged.
- KB, datagen, training, RL, export, eval directories. Model + data frozen.

</code_context>

<verification_anchors>
## Goal-Backward Verification Anchors

Phase 7 plan completes ↔ ALL of:

1. **ToolStepper body shipped** — `ToolStepper.kt` body replaced; signature unchanged. Smoke test (Phase 5 D-10) still passes. New tests for state transition (↻→✓), shimmer skeleton, ANIMATOR_DURATION_SCALE=0, and calm-tone ⚠ chip all pass. (STEP-01 + STEP-03 + STEP-04 + STEP-06 + SKEL-01 + SKEL-03 + SKEL-05.)
2. **Three screens swapped** — `grep -n "LoadingPanel(" android/app/src/main/java/com/aegis/health/ui/drugsafe android/app/src/main/java/com/aegis/health/ui/healthpartner android/app/src/main/java/com/aegis/health/ui/reportreader/ReportReaderScreen.kt` returns empty (or only documentation comments). `grep -n "ToolStepper(" android/app/src/main/java/com/aegis/health/ui/drugsafe android/app/src/main/java/com/aegis/health/ui/healthpartner android/app/src/main/java/com/aegis/health/ui/reportreader/ReportReaderScreen.kt` returns three live call sites.
3. **ConsentReader negative gate** — `grep -n "ToolStepper(" android/app/src/main/java/com/aegis/health/ui/consentreader/` returns empty. `grep -n "LoadingPanel(" android/app/src/main/java/com/aegis/health/ui/consentreader/ConsentReaderScreen.kt` returns line 216. (STEP-05; SC-4.)
4. **`ProgressEvent.StepFailure` added** — sealed class has 4 subtypes; `ProgressEventStepFailureTest.kt` JVM test passes; dispatcher emits from `:913` catch site verified by a synthetic-throwing-tool androidTest.
5. **ReportReader invocation moved** — `grep -n "runReportReaderFastPath" android/app/src/main/java/com/aegis/health/ui/` returns one hit in `ReportReaderScreen.kt`; DeferralScreen no longer references `runReportReaderFastPath`. ReportReaderScreen's `flagPreviews` state is reachable in production (manual on-device verification on SM-S918B with one of the Phase 4.1 vendor fixtures).
6. **Latency-honest copy literal** — `grep -rEn "running on your phone" android/app/src/main/java/com/aegis/health/ui/common/ToolStepper.kt` returns ≥ 1 hit (single source of truth per D-05). Parametrized `LatencyHonestCopyTest` passes against all three screens.
7. **headerSlotCount unchanged in non-loading state** — ReportReaderScreen at `:283` math: still 3 (or 4 with GenericFallbackBanner) when `isLoading == false`. Regression test from Phase 4.1 still passes.
8. **CONVENTIONS.md updated** — `grep -n "LoadingPanel vs ToolStepper" .planning/codebase/CONVENTIONS.md` returns ≥ 1 hit. Commit landed before phase close (SC-6).
9. **No INTERNET regression** — `adb shell dumpsys package com.aegis.health | grep permission` baseline diff empty (no new deps Phase 7).
10. **Existing Compose UI test suite green** — Phase 6 + Phase 5 instrumented tests unaffected. Tests written in Phase 7 ship with class-level `@Ignore("TEST-FRAMEWORK-01: ...")` per `project_compose_bom_test_regression.md` if they hit the BOM-bump framework gap, OR find a working subset (planner discretion).

</verification_anchors>

<deferred_ideas>
## Deferred for Future Phases

- **STEP-07 (stepper collapses to one-line summary at final render, expandable on tap with citation chips per step)** — P1 stretch for Phase 10. Phase 7 ships the expanded vertical stepper only.
- **`LoadingPanel` deletion + ConsentReader rewire** — D-01a explicitly keeps LoadingPanel for ConsentReader's autoAdvance=true case. Future cleanup phase could collapse to a single shared composable if the decorative-vs-live distinction proves cruft-y; not Phase 7's scope.
- **DeferralStore complete removal** — D-02a says if `DeferralStore.pendingReport` has no other consumers post-ReportReader-invocation-move, planner deletes the store. If other consumers exist (verify at plan time), defer the cleanup.
- **Reading the four-copy SKEL-02 sequence dynamically from engine state** — Claude's Discretion notes the natural mapping (count thresholds + lastFlagCount); planner adopts or defers a more sophisticated state machine to a follow-up plan.
- **`ProgressEvent.StepStart(label)` vs reusing `Step(label)`** — Phase 7 keeps the existing `Step` semantics (each Step marks both the start AND becomes the "running" row). A future phase could introduce StepStart/StepEnd typed events for finer-grained timing; not needed for Phase 7's three-state visual.
- **Animation-rate-ceiling lint rule** — Phase 7 enforces via androidTest assertions on `ANIMATOR_DURATION_SCALE=0` + manual code review of `animationSpec` parameters. A future build-time lint rule could enforce no `tween` < 350ms in `ui/common/`.

</deferred_ideas>
