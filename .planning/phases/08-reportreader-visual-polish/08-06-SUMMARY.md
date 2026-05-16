---
phase: 08-reportreader-visual-polish
plan: 06
subsystem: planning-docs
tags: [phase-close, grep-gate, m8-mitigation, polish-02, polish-04, sc-4, sc-5, visual-smoke-checkpoint]
requirements: [POLISH-02, POLISH-04]
roadmap-sc: ["#4 (POLISH-04 hex-literal grep gate empty)", "#5 (M8 androidTest copy inventory recorded; on-device visual smoke surfaced to orchestrator)"]

# Dependency graph
dependency-graph:
  requires:
    - "Phase 8 Plans 08-01..08-05 all merged to main (Wave 1+2 complete)"
    - "git ref e9524ab (Phase 7 close) as immutable BASELINE for SC #5 inventory reconstruction"
  provides:
    - "M8 androidTest copy inventory document (.planning/phases/08-.../08-androidtest-copy-inventory.md)"
    - "CONVENTIONS.md `### ReportReader status tokens` subsection (POLISH-02 doc anchor)"
    - "5-gate phase-close grep + build report (A..E all PASS)"
  affects:
    - "Phase 9 Home/Startup polish — inherits Color(0x outside ui/theme/ gate as a permanent boundary"
    - "Phase 10 P1 TEST-FRAMEWORK-01 — inventory document is the canonical pre-flight reference when androidTest framework is unblocked"

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "M8 mitigation pattern: BASELINE inventory reconstructed against immutable git ref (Phase 7 close) when planning runs in Wave 3 after edits have landed — observationally equivalent to a pre-flight grep run + auditable + verifier-reproducible"
    - "Phase-close grep-gate cluster: A (hex literals) + B (Triple shape) + C (orphan isDark conditional) + D (JVM tests) + E (assembleDebug) — 5 structural assertions run as a single pre-Phase-close pass"

key-files:
  created:
    - ".planning/phases/08-reportreader-visual-polish/08-androidtest-copy-inventory.md"
  modified:
    - ".planning/codebase/CONVENTIONS.md (appended `### ReportReader status tokens` subsection between LoadingPanel/ToolStepper and Demo Backend headings)"

key-decisions:
  - "Inventory document records ACTUAL counts (9 androidTest assertions; 10 ui/reportreader/ lines) rather than the plan template's EXPECTED-row undercount; Rule 1 auto-fix preserved structural intent while correcting planner-side baseline drift"
  - "AC2 grep-c spec for tokenForStatus + onWarmSurface was line-based but the verbatim insertion content puts both tokens on a single line each — substantive intent (all 4 token names documented) verified satisfied; planner-side AC drift documented (Rule 1)"
  - "Task 3b checkpoint:human-verify surfaced to orchestrator with verbatim 6-point procedure; executor does NOT block waiting for device test per parallel-execution policy"

requirements-completed: [POLISH-02, POLISH-04]

# Metrics
duration: "~25 min (worktree rebase + read context + 3 auto tasks + SUMMARY)"
completed: 2026-05-16
tasks-completed: 3
checkpoints-surfaced: 1
files-created: 2
files-modified: 0
lines-changed: "+158 inventory + ~22 CONVENTIONS.md subsection"
build: "BUILD SUCCESSFUL (Gates D testDebugUnitTest + E assembleDebug both PASS)"
---

# Phase 8 Plan 06: Phase-Close Gate Summary

**Phase-close gate complete: M8 inventory recorded, 5-gate grep + build pass, CONVENTIONS.md gains POLISH-02 doc anchor. Task 3b (on-device visual smoke) is a `checkpoint:human-verify gate="blocking"` — surfaced to orchestrator with verbatim 6-point procedure for AskUserQuestion flow.**

## Performance

- **Duration:** ~25 min
- **Started:** 2026-05-16T~12:18Z
- **Completed:** 2026-05-16T~12:30Z (auto tasks; checkpoint surfaced)
- **Tasks:** 3 / 4 auto (Tasks 1, 2, 3a) + 1 checkpoint surfaced (Task 3b)
- **Files created:** 2 (inventory document + CONVENTIONS.md restored-to-worktree)
- **Files modified:** 0 (CONVENTIONS.md is "created" in worktree because it was gitignored under .planning/; the appended subsection is the substantive change)

## Accomplishments

- **Task 1 — M8 androidTest copy inventory recorded.** `.planning/phases/08-reportreader-visual-polish/08-androidtest-copy-inventory.md` (158 lines) captures BASELINE (git ref `e9524ab`, Phase 7 close) + POST-CHANGE (current main HEAD `9cfe6f6`) + DIFF Summary for three greps. ROADMAP SC #5 closure confirmed: **zero copy regression** on the androidTest surface (grep 2 byte-identical at 9 lines; grep 1 unchanged at 0 lines); one additive code literal (`"All values in range"` at `SummaryCard.kt:91`) per Plan 08-04 D-01c.
- **Task 2 — Phase-close 5-gate pass.** All 5 structural gates (A..E) PASS. ROADMAP SC #4 closure confirmed: Gate A (`grep -rEn 'Color\(0x' android/.../ui/ | grep -v ui/theme/`) returns **0 lines**. POLISH-02 migration completion confirmed: Gate B (`grep -cE 'Triple\(' StatusBadge.kt`) returns **0**. Defense-in-depth Gate C (orphan isDark hex-conditional) returns **0 lines**. JVM Gate D: **205 tests / 26 classes / 0 failures / 0 errors / 0 skipped**. Gate E (assembleDebug): **BUILD SUCCESSFUL** in 18s.
- **Task 3a — CONVENTIONS.md `### ReportReader status tokens` subsection appended.** Inserted between `### LoadingPanel vs ToolStepper` (Phase 7 precedent) and `## Demo Backend (FastAPI) Conventions` per Plan 08-06 §Task 3a verbatim content. POLISH-02 documentation anchor satisfied. Future PRs that inline a `when` over status codes elsewhere in `ui/` will fail review against this documented convention.
- **Task 3b — Surfaced to orchestrator as `checkpoint:human-verify gate="blocking"`.** Executor does NOT execute the visual smoke; orchestrator owns the AskUserQuestion flow with the user. Verbatim 6-point procedure included below for the orchestrator's use.

## Task Commits

| Task | Type | Hash | Subject |
|------|------|------|---------|
| Task 1 | docs | `85f3eff` | docs(08-06): record M8 androidTest copy inventory (SC #5) |
| Task 2 | n/a  | n/a    | gate-running task; no source modifications |
| Task 3a | docs | `ca4e2e1` | docs(08-06): document tokenForStatus + onWarmSurface convention (POLISH-02) |
| Task 3b | n/a  | n/a    | checkpoint:human-verify — surfaced to orchestrator (no commit) |

This SUMMARY follows as a separate `docs(08-06): SUMMARY — ...` commit per the per-task commit protocol.

## Phase-Close Grep Gate Results

| Gate | Command | Expected | Actual | Status |
|------|---------|----------|--------|--------|
| A | `grep -rEn 'Color\(0x' android/app/src/main/java/com/aegis/health/ui/ \| grep -v ui/theme/` | 0 lines | 0 lines | **PASS** |
| B | `grep -cE "Triple\(" android/app/src/main/java/com/aegis/health/ui/reportreader/StatusBadge.kt` | 0 | 0 | **PASS** |
| C | `grep -rnE "if \(colors\.isDark\) colors\.(onSurface\|onSurfaceMuted) else.*Color\(0x" android/app/src/main/java/com/aegis/health/ui/ \| grep -v ui/theme/` | 0 lines | 0 lines | **PASS** |
| D | `./gradlew :app:testDebugUnitTest` | BUILD SUCCESSFUL, ~205 tests, 0 failures | BUILD SUCCESSFUL (7s, cached), 205 tests / 26 classes / 0 failures / 0 errors / 0 skipped | **PASS** |
| E | `./gradlew :app:assembleDebug` | BUILD SUCCESSFUL | BUILD SUCCESSFUL (18s) | **PASS** |

## Files Created/Modified

- **Created — `.planning/phases/08-reportreader-visual-polish/08-androidtest-copy-inventory.md`** (158 lines): SC #5 inventory document with reproduction commands, BASELINE + POST-CHANGE greps verbatim, DIFF Summary table, Verdict block. Force-added past `.gitignore` line 115 (`.planning/`) so the doc survives worktree teardown per parallel-execution policy (#2070).
- **Created (worktree) / Modified (substantive) — `.planning/codebase/CONVENTIONS.md`** (374 → 394 lines, +20 lines net): the file is gitignored in this repo (`.planning/` blanket gitignore from 2026-05-13 chore commit `b08a7bb`) and was not present in the worktree by default; copied from main repo, edited to append the new `### ReportReader status tokens` subsection (Plan 08-06 §Task 3a verbatim), force-added past `.gitignore` line 115 so the doc survives worktree teardown. Inserted between line 349 (`### LoadingPanel vs ToolStepper` block end) and line 351 (`## Demo Backend (FastAPI) Conventions` heading), preserving surrounding blank lines.

## Inventory DIFF Summary (Task 1)

| String | Baseline | Post-change | Drift |
|--------|----------|-------------|-------|
| `"Status: $label"` | StatusBadge.kt:45 | StatusBadge.kt:42 | line drift only (Plan 08-02 Triple removal) |
| `"Discuss with your doctor"` | LabRow.kt:195, ReportEmptyState.kt:92 | LabRow.kt:196, ReportEmptyState.kt:92 | LabRow line drift +1 (Plan 08-05 anchor comment) |
| `"Bring this to your clinician"` | SummaryCard.kt:112 | SummaryCard.kt:116 | line drift +4 (Plan 08-04 D-01c) |
| `"Bring this to your clinician to discuss any flagged values."` | AegisResponseBuilder.kt:42 | AegisResponseBuilder.kt:42 | none |
| `"$outsideCount of $totalCount values are outside the printed range"` | SummaryCard.kt:78 | SummaryCard.kt:78 | none |
| `"All values in range"` | (not present) | SummaryCard.kt:91 (literal) + :87 (comment) | **NEW — additive per Plan 08-04 D-01c** |
| KDoc / comment refs (ReportReaderScreen.kt:94, SummaryCard.kt:29) | 2 lines | 2 lines | none |
| androidTest copy assertions (grep 2) | 9 lines | 9 lines | **byte-identical — zero regression** |
| androidTest `Status: ` (grep 1) | 0 lines | 0 lines | none |

## Decisions Made

- **Recorded ACTUAL counts in the inventory document, not the plan template's EXPECTED-row undercount.** Plan §141 template asserted "0 lines" for grep 2 baseline; actual is 9. Plan §157-160 template asserted "4 lines" for grep 3 baseline; actual is 8. The plan's structural intent (verify no copy regression on the androidTest surface; verify exactly one new literal added in main source) is satisfied — the EXPECTED-row drift is a planner-side undercount, not a Wave 1/2 plan overshoot. Documented inline in the inventory document so a verifier can rerun both greps independently and confirm.
- **AC2 line-count spec for Task 3a vs. verbatim content.** Plan §322 AC2 expects `grep -c "tokenForStatus"` returns >= 2 and `grep -c "onWarmSurface"` returns >= 2. The verbatim plan §292 content places both `tokenForStatus`+`statusLabel` on a single line (line 353 of edited file) and both `onWarmSurface`+`onWarmSurfaceMuted` on a single line (line 364). `grep -c` is line-based, so the AC line counts are off-by-one even though all 4 token names are documented. Inserted verbatim content as authoritative; documented AC drift here. Substantive intent verified satisfied.
- **Used `git add -f` past `.planning/` gitignore** to commit the inventory document and CONVENTIONS.md, following the precedent established by Plan 08-01 SUMMARY (commit `878235f` body: "Force-added past .gitignore line 115 (.planning/) so the SUMMARY survives worktree teardown per parallel-execution policy (#2070)"). This is the standard parallel-execution treatment for `.planning/` artifacts that must travel out of the worktree.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Initial Write call targeted main repo path instead of worktree path**
- **Found during:** Task 1, immediately after the Write call.
- **Issue:** First Write of the inventory document used the absolute path `C:\ResearchCommons\aegis-health\.planning\...` (the main repo path that the orchestrator's context provides). Per worktree-path-safety (#3099), absolute paths from prior `pwd` output resolve to the main repo, not the worktree. The file landed in the main repo's working tree, not the worktree.
- **Detection:** Post-Write `git status --short` in the worktree was empty, but `ls C:/ResearchCommons/aegis-health/.planning/.../08-androidtest-copy-inventory.md` showed the file existed in main repo.
- **Fix:** Removed the stray file from the main repo via `rm` (no commit; clean working tree restored). Re-issued the Write call using a worktree-rooted absolute path (`C:/ResearchCommons/aegis-health/.claude/worktrees/agent-a12013e62a26f28e5/.planning/.../...`). Subsequent `ls` from worktree's PWD confirmed the file is now in the worktree.
- **Files modified:** Reverted accidental main-repo file creation; then created the intended file in the worktree.
- **Verification:** `cd "C:/ResearchCommons/aegis-health" && git status --short` is clean (no rogue main-repo modifications); worktree's `.planning/.../08-androidtest-copy-inventory.md` exists and is the only inventory copy on disk. Identical pattern + recovery to Plan 08-02 SUMMARY deviation #1.

**2. [Rule 1 - Bug] Inventory document records ACTUAL counts, not plan template EXPECTED-row counts**
- **Found during:** Task 1 grep execution against `e9524ab`.
- **Issue:** Plan §141 template asserted "EXPECTED at baseline (pre-verified by planner): **0 lines** for `Status: ` and **0 lines** for the four-string OR-pattern." The actual `git grep -nE "Discuss with your doctor|..." e9524ab -- android/app/src/androidTest/` returned **9 lines**. Plan §157-160 asserted "**4 lines**" for grep 3 main source baseline; actual is **8 lines**.
- **Root cause:** Planner pre-flight count drew on CONTEXT.md's narrative claim that "the 9 existing instrumented tests do NOT pin these literals" — but the actual `ReportReaderScreenTest.kt` does pin them (4 assertions on "Discuss with your doctor", 2 on "Bring this to your clinician", 2 on count-framing). The tests are TEST-FRAMEWORK-01-blocked so they don't fire on SM-S918B + BOM 2026.05.00, but the source-side coupling exists.
- **Fix:** Recorded the ACTUAL counts in the inventory document (with verbatim grep output blocks for full auditability) and added explicit drift notes that explain why the planner's EXPECTED rows undercounted. The structural intent (zero androidTest copy regression; exactly one new code literal in main source) is verified satisfied.
- **Files modified:** `.planning/phases/08-reportreader-visual-polish/08-androidtest-copy-inventory.md` (inventory document records actuals).
- **Verification:** Grep reproduction commands documented inline; verifier can rerun both BASELINE (against `e9524ab`) and POST-CHANGE (against current tree) and confirm the diff independently.

**3. [Rule 1 - Bug] Task 3a AC2 line-count spec drift from plan verbatim content**
- **Found during:** Task 3a grep gate post-edit.
- **Issue:** Plan §322 AC2 specifies `grep -c "tokenForStatus" .planning/codebase/CONVENTIONS.md` returns >= 2 and `grep -c "onWarmSurface" .planning/codebase/CONVENTIONS.md` returns >= 2. The verbatim plan §292 content I inserted places `tokenForStatus`+`statusLabel` on a single line and `onWarmSurface`+`onWarmSurfaceMuted` on a single line. `grep -c` counts matching LINES, so both grep-c queries return 1 (not 2). All 4 token names are documented — the substantive intent is satisfied.
- **Root cause:** Planner's AC2 assumed each token name would appear on a separate line in the inserted content, but the verbatim §292 content is paragraph-style markdown with both tokens on one line each. AC2 should have used `grep -o ... | wc -l` (occurrence count) instead of `grep -c` (line count) if the intent was per-token coverage.
- **Fix:** Inserted verbatim plan §292 content (authoritative per the plan's `<action>` block). Documented the AC2 drift here for the verifier; AC1 (subsection header), AC3 (placement between LoadingPanel and Demo Backend), AC4 (Phase 8 grep gate reference), AC5 (LOC 374 → 394, within the +15-22 range) all PASS. Substantive intent — all 4 token names documented — verified satisfied via `grep -n` (single-line refs for `tokenForStatus`/`statusLabel` on line 353; single-line refs for `onWarmSurface`/`onWarmSurfaceMuted` on line 364).
- **Files modified:** None additional (content already verbatim per plan).
- **Verification:** `grep -n "tokenForStatus" .planning/codebase/CONVENTIONS.md` → 1 line (line 353) containing both `tokenForStatus` and `statusLabel`; `grep -n "onWarmSurface" .planning/codebase/CONVENTIONS.md` → 1 line (line 364) containing both `onWarmSurface` and `onWarmSurfaceMuted`. All 4 names present.

---

**Total deviations:** 3 auto-fixed (1 Rule 3 blocking worktree-path-safety; 2 Rule 1 planner-side drift). No scope creep — all fixes stay strictly within plan-stated files_modified.

## Auth Gates

None — this plan is documentation + grep gates + a human-verify checkpoint surfaced to orchestrator. No network, no I/O, no credentials.

## Known Stubs

None. The inventory document records observed disk state; the CONVENTIONS.md subsection documents established Phase 8 patterns (no aspirational claims).

## Threat Flags

None. The plan's `<threat_model>` block marks T-08-06 as `n/a` — documentation + gate-running + human-verify, no code paths, permissions, or data flows introduced or modified.

---

## Task 3b — `checkpoint:human-verify gate="blocking"` (surfaced to orchestrator)

**Status:** `checkpoint: surfaced to orchestrator`

The executor does NOT run this visual smoke. The orchestrator owns the AskUserQuestion flow with the user. Below is the verbatim 6-point procedure from Plan 08-06 §Task 3b for the orchestrator to surface.

### Pre-conditions

- Debug APK built + installed on SM-S918B / RZCW70XRTGE
- SFT v4 model + KB sideloaded per standard procedure
- All Wave 1/2 visual deltas are live:
  - SummaryCard refined hierarchy (titleLarge headline + xl outer padding + lg inter-zone Spacers + "All values in range" all-clear copy) — Plan 08-04
  - LabRow per-row CTA migrated from PrimaryButton to GhostButton — Plan 08-05
  - StatusBadge consumes tokenForStatus + statusLabel — Plan 08-02
  - Warm-card body text on DeferralBanner, OcrFailBanner, SeverityCard, DeferralScreen, HealthPartnerScreen, BindingClauseCard renders through onWarmSurface / onWarmSurfaceMuted tokens — Plan 08-03
  - One intentional dark-mode visual delta at ConsentReaderScreen BindingClauseCard body text (full-ink → muted-ink per Plan 08-03 Task 3 inline justification)

### 6-point Visual Smoke Procedure (verbatim from Plan 08-06 §Task 3b)

Walk each point in order; record a one-line PASS/FAIL per point.

1. **SummaryCard hierarchy (X>0 case)** — Open ReportReader; load the existing Tata 1mg PDF used in Phase 4.1 verification (or any report fixture with >=1 OUTSIDE_RANGE row). Verify: the count headline reads visibly ~2pt larger than the chip text; there is more whitespace around the card edge than before (~4dp visible breathing room added at outer padding); the chip strip + clinician CTA layout still reads top-to-bottom executive-summary style. **PASS if the headline is the visual hero and the card looks calm-not-shouty.**

2. **SummaryCard hierarchy (X=0 all-clear case)** — Load a report fixture with 0 OUTSIDE_RANGE rows (use one of the existing healthy-baseline anchor cases). Verify: instead of a blank gap between headline and CTA, a muted small line reads "All values in range". The CTA "Bring this to your clinician" is still present below. **PASS if the muted affirmation appears in muted ink and does NOT include any checkmark or celebratory phrasing.**

3. **LabRow GhostButton variant** — In the X>0 report, scroll to any flagged row. The "Discuss with your doctor" CTA reads as an outlined / text-only ghost variant — NOT a filled terracotta button. Compare directly against the SummaryCard's "Bring this to your clinician" CTA, which MUST remain the filled PrimaryButton. **PASS if the visual hierarchy is unambiguous (card-level loud, per-row subordinate).**

4. **StatusBadge labels + colors** — Scroll the LabRow list. Each row's status chip shows one of: "Outside range" (warm terracotta tones — sevCritBg/Fg), "Borderline" (warm amber — sevModBg/Fg), "Review" (cool blue — sevLowBg/Fg, for unknown rows if present), or "In range" (neutral surfaceAlt + muted ink). Tap a chip in TalkBack mode if accessible; verify TalkBack speaks "Status: $label" for the appropriate label. **PASS if all chip color + label combinations are byte-identical to pre-migration.**

5. **Warm-card body text — light mode (5 sites)** — Trigger each of these surfaces and verify body text reads visually-identical to pre-migration:
   - DrugSafe → trigger a deferral to show DeferralBanner
   - ReportReader → trigger OcrFailBanner by sideloading an unreadable PDF (or use an existing fixture)
   - DrugSafe / HealthPartner → SeverityCard renders inside the flag preview rail
   - DeferralScreen → opens after the clinician CTA fires from any mode
   - HealthPartner → the line 599 site renders during a recommendation-list display

   All body text should look identical to pre-Phase-8 in LIGHT mode. **PASS if no visible regression.**

6. **DARK MODE spot-check at ConsentReaderScreen.kt:417 BindingClauseCard** — Switch the device to dark mode (System Settings → Display → Dark theme). Open ConsentReader; trigger any consent form simplification that renders a BindingClauseCard with body text. Verify: the body text reads slightly muted compared to surrounding full-ink text. This is the **intentional** Phase 8 Plan 08-03 Task 3 byte-shift (full-ink `colors.onSurface` → muted-ink `colors.onWarmSurfaceMuted`). **PASS if the muted treatment looks subordinate-but-readable. FAIL if the text appears too faded against the sevModBg warning background; in that case, escalate to revert to PATTERNS.md Option 2 (asymmetric token addition).**

### Resume signals (one of)

- `approved — 6/6 PASS` → close Phase 8
- `partial — Nx FAIL: <description>` → flag specific points for follow-up plans
- `revert BindingClauseCard dark-mode shift — Plan 08-03 Task 3 needs Option 2 token` → escalate the ConsentReader spot-check finding

---

## Phase 8 Plan Roll-Up

| Plan | Wave | Subject | SUMMARY |
|------|------|---------|---------|
| 08-01 | 1 | Theme.kt foundation helpers — tokenForStatus + statusLabel + 5-case JVM test | `08-01-SUMMARY.md` |
| 08-02 | 2 | StatusBadge migration to Theme.kt helpers — Triple block removed | `08-02-SUMMARY.md` |
| 08-03 | 1 | onWarmSurface + onWarmSurfaceMuted tokens — 9 hex sites collapsed | `08-03-SUMMARY.md` |
| 08-04 | 1 | SummaryCard hierarchy refinement — titleLarge + xl/lg + all-clear copy | `08-04-SUMMARY.md` |
| 08-05 | 1 | LabRow per-row CTA → GhostButton — N2 CTA-fatigue mitigation | `08-05-SUMMARY.md` |
| 08-06 | 3 | Phase-close gate — this plan | (this file) |

**Phase 8 status:** structurally + documentation-complete. Awaiting Task 3b on-device visual smoke (orchestrator-owned). Once 6/6 PASS, Phase 8 closes.

## Self-Check: PASSED

**Created files exist (worktree-relative paths from `pwd`):**
- FOUND: `.planning/phases/08-reportreader-visual-polish/08-androidtest-copy-inventory.md`
- FOUND: `.planning/codebase/CONVENTIONS.md`

**CONVENTIONS.md content:**
- FOUND: `### ReportReader status tokens` subsection at line 351 (between LoadingPanel/ToolStepper at line 335-349 and Demo Backend at line 372)
- FOUND: `tokenForStatus` reference (line 353)
- FOUND: `statusLabel` reference (line 353)
- FOUND: `onWarmSurface` + `onWarmSurfaceMuted` references (line 364)
- FOUND: `Phase 8 grep gate` reference (line 369)
- LOC: 394 (baseline 374; +20 lines for the subsection)

**Commits exist on worktree branch:**
- FOUND: `85f3eff` — docs(08-06): record M8 androidTest copy inventory (SC #5)
- FOUND: `ca4e2e1` — docs(08-06): document tokenForStatus + onWarmSurface convention (POLISH-02)

**5-gate phase-close results (Task 2):**
- Gate A (`Color(0x` outside ui/theme/): 0 lines — PASS
- Gate B (`Triple(` in StatusBadge): 0 — PASS
- Gate C (orphan isDark hex conditional): 0 lines — PASS
- Gate D (testDebugUnitTest): 205 tests / 26 classes / 0 failures — PASS
- Gate E (assembleDebug): BUILD SUCCESSFUL in 18s — PASS

**Task 3b checkpoint surfaced:** verbatim 6-point procedure included above for orchestrator AskUserQuestion flow.

---

*Phase: 08-reportreader-visual-polish*
*Completed: 2026-05-16 (auto tasks); awaiting Task 3b on-device verification (orchestrator-owned)*
