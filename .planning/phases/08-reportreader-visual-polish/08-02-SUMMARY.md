---
phase: 08-reportreader-visual-polish
plan: 02
subsystem: ui
tags: [compose, theme, reportreader, status-tokens, polish-02, refactor]

# Dependency graph
requires:
  - phase: 08-reportreader-visual-polish
    plan: 01
    provides: "tokenForStatus(status: String, colors: AegisColors): Pair<Color, Color> + statusLabel(status: String): String in ui/theme/Theme.kt"
  - phase: 03-ui-without-model
    provides: "StatusBadge.kt:28-49 (the migration target) with inline Triple<Color, Color, String> when block"
provides:
  - "StatusBadge composable migrated to consume Theme.kt status helpers — single source of mapping now owns the four canonical EvaluatedRow.status codes + IN_RANGE fall-back"
affects: [08-06]  # phase-close visual smoke on SM-S918B confirms render-output parity

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Compose composable defers token + label mapping to two top-level helpers in ui/theme/Theme.kt instead of inlining a `when` over the canonical status codes (mirrors how SeverityBadge would consume severityColor/severityBackgroundColor/severityLabel)"

key-files:
  modified:
    - "android/app/src/main/java/com/aegis/health/ui/reportreader/StatusBadge.kt (lines 13-15 imports + lines 25-27 KDoc + lines 34-36 body)"

key-decisions:
  - "Body sequence kept as `val colors = …; val (bg, fg) = tokenForStatus(…); val label = statusLabel(…)` — three statements at the same 4-space indent as the original `val colors` line. Matches plan §103-107 verbatim; preserves declaration-order intuition (colors → tokens → label) for future code-readers."
  - "KDoc paragraph 2 reworded to delegate the mapping note to Theme.kt — paragraph 1 (Phase 3 D-01 + UI-03 calm-by-default rationale) preserved intact per plan §118."

requirements-completed: [POLISH-02]

# Metrics
duration: ~7min
completed: 2026-05-16
---

# Phase 8 Plan 02: StatusBadge Migration to Theme.kt Helpers Summary

**StatusBadge.kt's `Triple<Color, Color, String>`-destructured `when` block at lines 34-39 is gone; the composable now delegates to `tokenForStatus(status, colors)` + `statusLabel(status)` from `ui/theme/Theme.kt` (Plan 08-01) — render output byte-identical for all four canonical status codes + fall-back.**

## Performance

- **Duration:** ~7 min (after worktree rebase + accidental main-repo edit recovery, see Issues Encountered)
- **Started:** 2026-05-16T06:44:12Z
- **Completed:** 2026-05-16T06:47:38Z
- **Tasks:** 1 / 1
- **Files modified:** 1

## Accomplishments

- **POLISH-02 fully closed.** Plan 08-01 added the helpers in Theme.kt; this plan removes the last in-tree caller that inlined the mapping (`StatusBadge.kt:34-39`). The four canonical `EvaluatedRow.status` codes (`IN_RANGE` / `BORDERLINE` / `OUTSIDE_RANGE` / `unknown`) → `(bg, fg)` + label mapping now has one source on disk.
- **ROADMAP SC #2 closed.** "`StatusBadge.kt:34-39` migrates to use it and the inline `when` block at those lines is gone" — verified: `grep -c "Triple(" StatusBadge.kt` returns 0; `grep -cE '"OUTSIDE_RANGE"|"BORDERLINE"' StatusBadge.kt` returns 0.
- **Render parity preserved byte-for-byte.** Same `RoundedCornerShape(6.dp)`, same `padding(horizontal = 8.dp, vertical = 3.dp)`, same `MaterialTheme.typography.labelMedium`, same `contentDescription = "Status: $label"`. The four label strings ("In range", "Outside range", "Borderline", "Review") emerge from `statusLabel(status)` exactly as before. The 9 existing `Status: <label>` androidTest contentDescription assertions remain satisfiable when TEST-FRAMEWORK-01 unblocks.
- **Helpers now consumable directly by future row-tint + chip-strip consumers.** Plan 08-03 (warm-ink tokens, already merged), Plan 08-04 (SummaryCard hierarchy, already merged), and any future consumer can `import com.aegis.health.ui.theme.tokenForStatus` and call it without going through `StatusBadge`.
- **Build + JVM suite both green.** 205 tests / 26 classes / 0 failures / 0 errors / 0 skipped.

## Task Commits

Each task was committed atomically (no `git add -A`; per-file staging only):

1. **Task 1: Replace Triple-destructured `when` block with `tokenForStatus` + `statusLabel` calls** — `7be4105` (refactor)

_Plan metadata commit (this SUMMARY) follows in a separate `docs(08-02)` commit per the per-task commit protocol._

## Files Created/Modified

- **Modified — `android/app/src/main/java/com/aegis/health/ui/reportreader/StatusBadge.kt`** (50 LOC → 46 LOC, -4 lines):
  - **Lines 14-15 (imports added):** `import com.aegis.health.ui.theme.statusLabel` + `import com.aegis.health.ui.theme.tokenForStatus`, slotted alphabetically after the existing `LocalAegisColors` import on line 13.
  - **Lines 26-27 (KDoc paragraph 2 rewritten):** Former "Status string must come from `EvaluatedRow.status` verbatim. Unknown status values default to the IN_RANGE styling defensively (never crash the LazyColumn for an unrecognized wire-format value)." replaced with: "Status mapping is delegated to `tokenForStatus` + `statusLabel` in `ui/theme/Theme.kt` (Phase 8 D-02; single source of mapping)." Paragraph 1 (Phase 3 D-01 + UI-03 calm-by-default mandate) preserved intact.
  - **Lines 35-36 (body):** The 6-line `val (bg, fg, label) = when (status) { … }` block (5 lines of mapping + 1 closing brace) is replaced by `val (bg, fg) = tokenForStatus(status, colors)` + `val label = statusLabel(status)` (2 lines).
  - **Lines 37-45 (render block — UNCHANGED):** `Text(text = label, modifier = modifier.background(bg, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp).semantics { contentDescription = "Status: $label" }, style = MaterialTheme.typography.labelMedium, color = fg)` is byte-identical to pre-migration. `bg`, `fg`, and `label` are still in scope with the same values.

## Decisions Made

- **Sequence kept as `colors → tokens → label`** — three declaration statements at 4-space indent, matching the plan §103-107 target snippet verbatim. The alternative (fold both helper calls into one line, or co-locate them with the `colors` declaration via destructuring tricks) was rejected: declaration-order intuition (`colors` lookup first, then `tokens` consume it, then independent `label`) reads cleanly and matches how `severityColor(severity, colors)` is consumed elsewhere in the codebase.
- **No new imports beyond the two helpers** — explicitly verified `androidx.compose.ui.graphics.Color` is NOT in the import block (plan §120). The `bg`/`fg` types are inferred from `Pair<Color, Color>`'s component types via destructuring; no explicit `Color` reference appears in the body.
- **KDoc paragraph 1 preserved verbatim** — the Phase 3 D-01 explanation + UI-03 calm-by-default rationale carry semantic load (they document WHY the visual model is "color only on flagged rows", not just WHAT the code does). Plan §118 explicitly required keeping it.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Initial Edit targeted main repo instead of worktree (worktree-path-safety #3099)**
- **Found during:** Task 1, immediately after the second Edit call.
- **Issue:** The objective + plan files were passed in the conversation context with main-repo absolute paths (e.g., `C:\ResearchCommons\aegis-health\android\app\src\main\java\com\aegis\health\ui\reportreader\StatusBadge.kt`). When I issued the first two Edit calls using that path, the Edit tool reported "updated successfully" but the changes landed in the **main repository's** working tree, NOT the worktree at `C:\ResearchCommons\aegis-health\.claude\worktrees\agent-aa88c1fc93462617b\`. The worktree's copy of `StatusBadge.kt` was still the original. This is precisely the worktree-path-safety issue called out by GSD reference doc + #3099 — absolute paths constructed from prior `pwd`-of-orchestrator context resolve to the main repo, silently bypassing the worktree.
- **Detection:** After the (purportedly successful) edits, `cat -n android/.../StatusBadge.kt` run from the worktree's cwd showed the original file, while a Read of the main-repo absolute path showed the "edited" content. `git status` in the main repo confirmed: ` M android/app/src/main/java/com/aegis/health/ui/reportreader/StatusBadge.kt`.
- **Fix:** Reverted the main repo's accidental modification with `git checkout -- android/app/src/main/java/com/aegis/health/ui/reportreader/StatusBadge.kt` run from the main repo's toplevel (`C:\ResearchCommons\aegis-health`). Re-issued both Edit calls using a worktree-rooted absolute path (`C:/ResearchCommons/aegis-health/.claude/worktrees/agent-aa88c1fc93462617b/android/...`). Subsequent `git diff` in the worktree showed the expected refactor; main repo's `git status` confirmed it was clean of any rogue edits from this session.
- **Files modified:** Reverted accidental main-repo edit on `StatusBadge.kt`; then applied the intended edit to the worktree's `StatusBadge.kt`.
- **Verification:** `cd main-repo && git status --short` → clean (no rogue main-repo modifications); `cd worktree && git status --short` → only the intended `M android/app/src/main/java/com/aegis/health/ui/reportreader/StatusBadge.kt`. Diff content is byte-identical to the plan §103-107 target.
- **Committed in:** Task 1 commit (`7be4105`) lives in the worktree, not the main repo.

---

**Total deviations:** 1 auto-fixed (Rule 3 - blocking; recovery from worktree-path-safety mishap before any commits landed)
**Impact on plan:** None to the deliverable — the final on-disk content matches the plan's target byte-for-byte. The recovery was caught before any commit happened, so no rollback artifacts exist in git history; only the main-repo working tree was briefly polluted and immediately reverted.

## Issues Encountered

- **Worktree was behind main on first inspection** — `git merge-base HEAD main` returned `5cff363`, while `git rev-parse main` returned `39d907c` (Plans 08-03 / 08-04 / 08-05 had been merged after worktree creation). The orchestrator note + `<worktree_branch_check>` block flagged this. Resolved by rebasing the worktree branch onto `main` via `git rebase main` (clean rebase, no conflicts — no edits had been made yet). Post-rebase, `git merge-base HEAD main == git rev-parse main`.
- **Pre-existing Kotlin compile warnings persist** — three deprecation warnings emitted during `compileDebugKotlin`:
  - `CameraPipeline.kt:74:26` — `LocalLifecycleOwner` moved to `lifecycle-runtime-compose`.
  - `OpenApiToolDefs.kt:103:30` — named-parameter mismatch with supertype.
  - `ConsentReaderScreen.kt:374:42` — `ClickableText` deprecated in favor of `Text` + `LinkAnnotation`.
  All three are pre-existing (the second was explicitly noted as out-of-scope in Plan 08-01 SUMMARY); none originated from this plan's diff. Logged here for traceability; deferred per Rule 4 scope boundary.
- **No `android/local.properties` in worktree** — gitignored, not inherited from main. Resolved by exporting `ANDROID_HOME` + `ANDROID_SDK_ROOT` to `C:/Users/amanr/AppData/Local/Android/Sdk` for the build invocation only (same workaround pattern documented in Plan 08-01 SUMMARY). No per-worktree `local.properties` was written.
- **JVM test count is 205, not 160 (Plan 08-01's baseline) or 200/205 (Plan 08-02's stated expectation).** Wave 1 plans (08-03 warm-ink tokens, 08-04 SummaryCard hierarchy, 08-05 LabRow GhostButton CTA) added ~45 JVM tests since Plan 08-01 closed. Suite is fully green at the new count: 205/205, 26 classes.

## User Setup Required

None — no external service configuration required. All changes are local Kotlin source.

## Next Phase Readiness

**Phase 8 Wave 2 (this plan, 08-02) closes the POLISH-02 work.** ROADMAP SC #2 is now satisfied:
- The inline `when` block at `StatusBadge.kt:34-39` is gone (replaced by two helper calls).
- The mapping logic has one canonical home: `Theme.kt:131-143`.

**Plan 08-06** (phase-close visual smoke on SM-S918B) is the next downstream activity that touches `StatusBadge` indirectly — it will verify the four canonical status codes render with the same color + label as pre-migration. No standalone visual checkpoint in this plan per plan §134.

**No blockers introduced.** Pre-existing TEST-FRAMEWORK-01 (Compose UI androidTest on SM-S918B + BOM 2026.05.00) remains Phase 10 P1; this plan deliberately routes around it by living on the JVM path + a non-Compose source edit.

## Threat Flags

None — pure visual refactor, same package + module visibility, no new I/O, no new external inputs. STRIDE register from plan §150-154 reaffirmed: no trust boundary crossed.

## Self-Check: PASSED

**Modified file contains expected content (worktree path):**
- FOUND: `import com.aegis.health.ui.theme.tokenForStatus` at `StatusBadge.kt:15`
- FOUND: `import com.aegis.health.ui.theme.statusLabel` at `StatusBadge.kt:14`
- FOUND: `val (bg, fg) = tokenForStatus(status, colors)` at `StatusBadge.kt:35`
- FOUND: `val label = statusLabel(status)` at `StatusBadge.kt:36`
- FOUND: Render block preserved at `StatusBadge.kt:37-45` (Text + RoundedCornerShape(6.dp) + padding(horizontal=8.dp, vertical=3.dp) + labelMedium + contentDescription = "Status: $label")
- ABSENT (as required): `Triple(` (0 occurrences); `"OUTSIDE_RANGE"` / `"BORDERLINE"` (0 occurrences); `androidx.compose.ui.graphics.Color` (0 occurrences)

**Commit exists on worktree branch:**
- FOUND: `7be4105` — refactor(08-02): migrate StatusBadge to tokenForStatus + statusLabel helpers

**Grep gates (Task 1 — 11/11 PASS):**
- AC1 `Triple(` count: 0 (expect 0) — PASS
- AC2 `tokenForStatus(status, colors)` count: 1 (expect 1) — PASS
- AC3 `statusLabel(status)` count: 1 (expect 1) — PASS
- AC4 `import …tokenForStatus` count: 1 (expect 1) — PASS
- AC5 `import …statusLabel` count: 1 (expect 1) — PASS
- AC6 `RoundedCornerShape(6.dp)` count: 1 (expect 1) — PASS
- AC7 `contentDescription = "Status: $label"` count: 1 (expect 1) — PASS
- AC8 `labelMedium` count: 1 (expect 1) — PASS
- AC9 status string literals count: 0 (expect 0) — PASS
- AC10 LOC: 46 (expect ≤ 55) — PASS
- AC11 `androidx.compose.ui.graphics.Color` import count: 0 (expect 0) — PASS

**Build assertions (worktree, with ANDROID_HOME + ANDROID_SDK_ROOT exported):**
- `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL in 12s — PASS
- `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL in 6s — PASS
- JVM test aggregate: 205 tests / 26 classes / 0 failures / 0 errors / 0 skipped — PASS

**Main-repo safety check:**
- `cd C:\ResearchCommons\aegis-health && git status --short` → no `StatusBadge.kt` modification leaked outside the worktree — PASS (deviation #1 recovery confirmed)

---

*Phase: 08-reportreader-visual-polish*
*Completed: 2026-05-16*
