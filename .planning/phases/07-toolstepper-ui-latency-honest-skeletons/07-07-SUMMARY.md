---
phase: 07-toolstepper-ui-latency-honest-skeletons
plan: 07
subsystem: android-ui
tags: [gap-closure, blocker, screens, try-finally, isloading-guard, cr-02, step-06]

# Dependency graph
requires:
  - phase: 07
    provides: ReportReaderScreen.kt:386-435 canonical try/catch/finally pattern (07-04), ProgressEvent.StepFailure data class (07-02), FailureInfo data class (07-02), failures SnapshotStateMap side channel (07-02), dispatcher-side StepFailure emission (07-06)
provides:
  - "User-recoverable isLoading reset on EVERY exit path through DrugSafeScreen + HealthPartnerScreen scope.launch (CR-02 BLOCKER closed)"
  - "Calm-tone ⚠ chip rendering on screen-side catch-Throwable for OOM / JNI / withContext IO / history-insert exception paths (STEP-06 end-to-end closure)"
  - "Cross-file structural symmetry across all 3 Phase-7 screens (DrugSafe + HealthPartner + ReportReader all share identical guard shape)"
affects: [phase 8, phase 10 (TEST-FRAMEWORK-01), future screen scaffolds]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Screen-side belt-and-suspenders: dispatcher catches tool throws via Plan 07-06; screen catch-Throwable is the secondary guard for everything downstream of the tool (response state assignment, withContext IO, history insert)"
    - "FQ-name catch clause (kotlinx.coroutines.CancellationException) to avoid touching imports — minimizes diff and matches ReportReaderScreen.kt:417 precedent"
    - "Mode-appropriate fallback label inside catch-Throwable (\"Drug safety check\" / \"Prevention plan check\") when progress.getOrNull(idx) is null — keeps the ⚠ chip user-readable even on pre-Step crashes"

key-files:
  created: []
  modified:
    - android/app/src/main/java/com/aegis/health/ui/drugsafe/DrugSafeScreen.kt
    - android/app/src/main/java/com/aegis/health/ui/healthpartner/HealthPartnerScreen.kt

key-decisions:
  - "Used FQ-name CancellationException (kotlinx.coroutines.CancellationException) in the catch clause instead of adding a top-level import — matches ReportReaderScreen.kt:417 precedent and keeps the diff zero-import-churn."
  - "catch-Throwable block populates failures[(progress.size - 1).coerceAtLeast(0)] = FailureInfo(...) — the SAME indexing strategy the existing StepFailure branch uses inside the onProgress lambda. STEP-06's calm-tone ⚠ chip renders on the row that was running when the exception fired."
  - "Mode-appropriate fallback label (\"Drug safety check\" / \"Prevention plan check\") via progress.getOrNull(idx) ?: <fallback> — handles the pre-Step crash case where progress is still empty when the exception fires."
  - "State-reset block (clear / emptyList / null assignments) and HealthPartner's profileDesc buildString stay OUTSIDE the try block — they cannot throw and keeping them out makes the diff additive (a wrapper, not a restructure)."
  - "No new tests added. Per TEST-FRAMEWORK-01 (memory `compose_bom_test_regression`, 2026-05-15) Compose instrumented tests are blocked on SM-S918B / Compose BOM 2026.05.00; grep gates are the structural test vehicle for this plan. The behavior is verifiable on-device once TEST-FRAMEWORK-01 lifts."

patterns-established:
  - "Pattern: screen-side try/catch CancellationException/catch Throwable/finally{ isLoading=false }. All Phase-7 screen scope.launch blocks should follow this shape — ReportReaderScreen.kt:386-435 is the canonical reference; DrugSafeScreen.kt:191-260 and HealthPartnerScreen.kt:162-255 are the now-aligned siblings."
  - "Pattern: catch-Throwable populates the failures SnapshotStateMap so STEP-06 invariant is reachable end-to-end. Dispatcher emits StepFailure for tool throws (Plan 07-06); screen catches Throwable for the secondary post-tool failure modes (OOM / JNI / withContext IO / history insert)."

requirements-completed: [STEP-06, CR-02]

# Metrics
duration: 12min
completed: 2026-05-15
---

# Phase 7 Plan 07: Screen-Side isLoading try/finally Guard Summary

**Closes CR-02 BLOCKER end-to-end: both DrugSafeScreen and HealthPartnerScreen now wrap their `scope.launch` bodies in `try { ... } catch (CancellationException) { throw } catch (Throwable) { log + populate failures map } finally { isLoading = false }`, mirroring ReportReaderScreen.kt:386-435. The user is NEVER stranded on an infinite spinner — every exception path resets isLoading and renders the calm-tone ⚠ chip.**

## Performance

- **Duration:** ~12 min
- **Completed:** 2026-05-15
- **Tasks:** 2/2 (one per screen)
- **Files modified:** 2 production files (zero new tests — per TEST-FRAMEWORK-01 gate)
- **LOC delta:** +132 / −86 net across both files
  - DrugSafeScreen.kt: +60 / −37 (per-line indent shift accounts for most deletions; net new code ~23 LOC of try/catch/finally wrapper + comments)
  - HealthPartnerScreen.kt: +72 / −49 (same pattern, ~23 LOC of wrapper + comments)

## Accomplishments

- **CR-02 BLOCKER closed.** Both `DrugSafeScreen.kt` and `HealthPartnerScreen.kt` now wrap their `scope.launch` bodies in the canonical `try / catch CancellationException / catch Throwable / finally { isLoading = false }` shape. The user can ALWAYS retry — never stranded on an infinite spinner if the fast-path crashes (OOM, JNI crash, `withContext(Dispatchers.IO)` failure, history-insert exception, or any downstream-of-tool throw not caught by Plan 07-06's dispatcher-side guard).
- **STEP-06 end-to-end closure.** Combined with Plan 07-06's dispatcher-side `try/catch + emit StepFailure` (BLOCKER half of STEP-06 — the tool-throws path), this plan closes the screen-side half (the post-tool-failure path: response assignment, withContext IO, history insert). The calm-tone ⚠ chip is now reachable on the production execution path from BOTH the dispatcher catch AND the screen catch.
- **Cross-file structural symmetry.** All three Phase-7 screens (ReportReaderScreen, DrugSafeScreen, HealthPartnerScreen) now share an identical try/catch/finally guard shape. The only per-screen variation is the log tag string (`"DrugSafeScreen"` / `"HealthPartnerScreen"`), the fallback label inside `FailureInfo` (`"Drug safety check"` / `"Prevention plan check"`), and the body inside try (mode-specific tool call + state updates).
- **Zero-import-churn diff.** Used FQ-name `kotlinx.coroutines.CancellationException` in the catch clause — matches ReportReaderScreen.kt:417's precedent. No new import lines added in either file.
- **Test baseline preserved.** `./gradlew :app:testDebugUnitTest` reports 200/200 tests green (25 suites, 0 failures, 0 errors). Plan 07-06's `FastPathStepFailureTest` additions remain green; this plan adds no JVM tests (Compose instrumented tests are blocked per TEST-FRAMEWORK-01 — grep gates serve as the structural test vehicle).

## Task Commits

1. **Task 1 — DrugSafeScreen try/catch/finally wrapper** — `8c0192c` (feat)
2. **Task 2 — HealthPartnerScreen try/catch/finally wrapper** — `e9524ab` (feat)

_Atomic per-file commits: one screen per commit, mirroring the plan's per-task structure. Both commits land on `main` directly (no worktree — orchestrator confirmed worktree spawned at stale Phase-4 ancestor; main checkout is the working path)._

## Plan-Level Grep Gate Results

### G1: CR-02 closed — try / catch CancellationException / catch Throwable / finally in both screens

| File | `catch (ce: kotlinx.coroutines.CancellationException)` | `catch (t: Throwable)` | `finally {` |
| ---- | --- | --- | --- |
| DrugSafeScreen.kt | 1 ✓ | 1 ✓ | 1 ✓ |
| HealthPartnerScreen.kt | 1 ✓ | 1 ✓ | 1 ✓ |

### G2: STEP-06 closure — `failures[idx] = FailureInfo` in both files (≥ 2 hits each)

| File | `failures[idx] = FailureInfo` count | Sites |
| ---- | --- | --- |
| DrugSafeScreen.kt | 2 ✓ | StepFailure branch (line 218) + catch-Throwable branch (line 254) |
| HealthPartnerScreen.kt | 2 ✓ | StepFailure branch (line 209) + catch-Throwable branch (line 249) |

### G3: No orphan `isLoading = false` outside finally

| File | `^\s*isLoading\s*=\s*false\s*$` exact match count |
| ---- | --- |
| DrugSafeScreen.kt | 1 ✓ (line 259 — inside finally) |
| HealthPartnerScreen.kt | 1 ✓ (line 254 — inside finally) |

### must_haves frontmatter key-link patterns (CR-02 closure proofs)

| Pattern | File | Match (line) |
| ------- | ---- | --- |
| `finally\s*\{\s*isLoading\s*=\s*false` (multiline) | DrugSafeScreen.kt | 258–259 ✓ |
| `finally\s*\{\s*isLoading\s*=\s*false` (multiline) | HealthPartnerScreen.kt | 253–254 ✓ |

## Cross-File Symmetry Confirmation

Both newly-edited screens have **structurally identical guard shapes** — counts match across the board:

```
                          DrugSafeScreen | HealthPartnerScreen
try {                              1     |        1
catch (ce: ...CancellationException) 1   |        1
catch (t: Throwable)               1     |        1
finally {                          1     |        1
isLoading = true (exact)           1     |        1
isLoading = false (exact)          1     |        1
failures[idx] = FailureInfo        2     |        2
```

ReportReaderScreen.kt is **NOT modified** by this plan — it already had the correct pattern as of Phase 7 Plan 07-04 and serves as the canonical reference for the other two screens.

## Test Vehicle Note

Per TEST-FRAMEWORK-01 (memory `compose_bom_test_regression`, 2026-05-15): Compose BOM 2026.05 introduced a regression on SM-S918B where Compose UI instrumented tests fail with `No compose hierarchies found`. The v2-API migration is tracked as a Phase 10 P1 (TEST-FRAMEWORK-01). Until that lifts, **grep gates are the structural test vehicle** for screen-level changes — they verify the SHAPE of the code, not its runtime behavior on a real device. Once TEST-FRAMEWORK-01 lifts, an instrumented test such as `DrugSafeScreen_isLoadingResets_whenFastPathThrows` could exercise the catch-Throwable path via a `runDrugSafeFastPath` mock that throws; for now, the assertion is structural (the catch clause is present and writes to the failures map).

## Build Sweep

- `./gradlew :app:testDebugUnitTest` — **BUILD SUCCESSFUL in 14s** (200 tests / 25 suites / 0 failures / 0 errors)
- `./gradlew :app:assembleDebug` — **BUILD SUCCESSFUL in 7s**
- Self-check: both commits exist on `main` (`git log --oneline 8c0192c e9524ab`), both files modified, no stray deletions.

## Deviations from Plan

**None — plan executed exactly as written.** Both tasks were straightforward additive edits matching the action description verbatim. No Rule 1-4 deviations triggered.

The `<read_first>` block's claim that line numbers would be ~192 / ~237 / ~163 / ~232 was approximate but close (actual: DrugSafe pre-edit had `isLoading = true` at 192 and `isLoading = false` at 237; HealthPartner had them at 163 and 232 — line numbers match the plan exactly).

## Self-Check: PASSED

- ✓ `android/app/src/main/java/com/aegis/health/ui/drugsafe/DrugSafeScreen.kt` modified (commit `8c0192c`)
- ✓ `android/app/src/main/java/com/aegis/health/ui/healthpartner/HealthPartnerScreen.kt` modified (commit `e9524ab`)
- ✓ Both `must_haves.key_links` grep patterns match (`finally\s*\{\s*isLoading\s*=\s*false` in both files)
- ✓ All G1/G2/G3 grep gates pass with expected hit counts
- ✓ `./gradlew :app:assembleDebug :app:testDebugUnitTest` both green (200/200 tests)
- ✓ ReportReaderScreen.kt NOT modified (already had correct pattern)
- ✓ Orthogonal uncommitted files (.gitignore, 07-05-SUMMARY.md) untouched per orchestrator instruction
