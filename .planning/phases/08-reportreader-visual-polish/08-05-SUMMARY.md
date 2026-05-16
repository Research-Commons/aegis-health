---
phase: 08-reportreader-visual-polish
plan: 05
subsystem: ui-reportreader
tags: [visual-polish, labrow, cta-hierarchy, ghost-button, n2-cta-fatigue]
requirements: [POLISH-03]
roadmap-sc: ["#3 (per-row CTA visibility + visual-weight subordination; on-device visual smoke deferred to Plan 08-06)"]
dependency-graph:
  requires:
    - "Phase 8 Plan 04 (SummaryCard's PrimaryButton stays loud — the loud sibling against which this ghost is now subordinate)"
  provides:
    - "Unambiguous per-row vs. card-level CTA hierarchy: GhostButton (subordinate) on flagged rows; PrimaryButton (loud) at card top"
  affects: []
tech-stack:
  added: []
  patterns:
    - "v1.0 D-05 visibility predicate preserved byte-identical: flagged + unknown rows still surface the CTA (D-03a)"
    - "Phase 8 D-01d hierarchy resolution: loud action lives once per card; per-row reinforcements use Ghost variant"
key-files:
  created: []
  modified:
    - android/app/src/main/java/com/aegis/health/ui/reportreader/LabRow.kt
decisions:
  - "D-01d: per-row PrimaryButton → GhostButton; SummaryCard keeps PrimaryButton"
  - "D-03a: visibility predicate `if (row.status != \"IN_RANGE\")` preserved verbatim — unknown rows still get the CTA"
  - "D-03b: single CTA copy 'Discuss with your doctor' preserved verbatim — single source of truth"
  - "D-03c: variant unity — all non-IN_RANGE rows use the same GhostButton (no three-tier ladder for unknown vs flagged)"
  - "Claude's Discretion (CONTEXT.md §74): kept fillMaxWidth(); no leading icon — minimal-diff drop-in swap"
metrics:
  duration: "≈ 6 min (read plan → 2 edits → grep gates → compile/test → commit)"
  completed: 2026-05-16
  tasks-completed: 1
  files-modified: 1
  lines-changed: "+3 / -2 (net +1)"
  build: "BUILD SUCCESSFUL (compileDebugKotlin 12s + testDebugUnitTest 5s)"
---

# Phase 8 Plan 05: Per-Row CTA Migration Summary

**One-liner:** `LabRow.kt:194` `PrimaryButton` → `GhostButton` (one symbol substitution + matching import swap + 1 cross-reference comment); SummaryCard's clinician CTA stays loud, per-row CTAs become subordinate ghosts — N2 CTA-fatigue mitigation, hierarchy now unambiguous, v1.0 D-05 visibility unchanged.

## What Shipped

1 file, 2 source edits (1 import swap + 1 call-site swap) plus 1 anchor comment insertion. All three changes localized to a 6-line window of `LabRow.kt`.

### Per-anchor before/after

| Anchor | Before | After | Decision |
|---|---|---|---|
| Import block (line 34) | `import com.aegis.health.ui.common.PrimaryButton` | `import com.aegis.health.ui.common.GhostButton` | D-01d / D-03c |
| Call site (line 194) | `PrimaryButton(` | `GhostButton(` | D-01d |
| Anchor comment (between lines 191 and 192) | _(absent)_ | `// Phase 8 D-01d: per-row subordinate variant (GhostButton); SummaryCard CTA stays PrimaryButton (loud, card-level).` | D-01d documentation |

### v1.0 invariants confirmed byte-identical

- D-05 visibility predicate `if (row.status != "IN_RANGE")` at line 192 — unchanged. Flagged (OUTSIDE_RANGE + BORDERLINE) **and** unknown rows still surface the CTA per D-03a — the reading is "IN_RANGE never gets CTA", not "only OUTSIDE_RANGE + BORDERLINE ever".
- D-03b CTA copy `"Discuss with your doctor"` — unchanged (line 195).
- `Spacer(Modifier.height(AegisSpacing.md))` row-rhythm spacer at line 193 — unchanged.
- `modifier = Modifier.fillMaxWidth()` full-width hit-target — unchanged (line 197).
- `onClick = { onDiscuss(row) }` callback wiring + `row` payload — unchanged.
- D-05 KDoc header at lines 41-57 (calling out "OUTSIDE_RANGE / BORDERLINE / unknown") — unchanged.
- All other lines (1-33, 35-190, 200-208) — untouched.

### Claude's Discretion call (CONTEXT.md §74)

Kept `fillMaxWidth()` exactly as `PrimaryButton` had — same hit-target, same row-rhythm — and **did not** add a leading `Icons.AutoMirrored.Filled.ChatBubbleOutline` argument. Rationale: the safe drop-in swap is one-symbol-only; adding an icon would have expanded the diff surface, triggered a separate Material Icons import, and risked icon-bikeshed. PATTERNS.md §209-210 explicitly recommended this default.

## Acceptance Criteria — Grep Gate Results

All 7 source assertions pass.

| # | Gate | Expected | Actual | Pass |
|---|------|----------|--------|------|
| 1 | `grep -cE '^\s*PrimaryButton\(' LabRow.kt` | exactly 0 | 0 | ✓ |
| 2 | `grep -cE '^\s*GhostButton\(' LabRow.kt` | exactly 1 | 1 | ✓ |
| 3 | `grep -c "import com.aegis.health.ui.common.PrimaryButton" LabRow.kt` | 0 | 0 | ✓ |
| 4 | `grep -c "import com.aegis.health.ui.common.GhostButton" LabRow.kt` | exactly 1 | 1 | ✓ |
| 5 | `grep -c 'if (row.status != "IN_RANGE")' LabRow.kt` | exactly 1 | 1 | ✓ |
| 6 | `grep -c '"Discuss with your doctor"' LabRow.kt` | exactly 1 | 1 | ✓ |
| 7 | `grep -c "modifier = Modifier.fillMaxWidth()" LabRow.kt` | ≥ 1 | 1 | ✓ |
| 8 | `grep -c "Phase 8 D-01d" LabRow.kt` | exactly 1 | 1 | ✓ |

Bare-symbol counts (informational, not acceptance criteria — the plan deliberately uses anchored regex for the call-site assertion because the D-01d cross-reference comment mentions both `PrimaryButton` and `GhostButton` by name):
- `PrimaryButton` total mentions: 1 (the D-01d comment cross-reference only)
- `GhostButton` total mentions: 3 (import + comment cross-reference + call site)

## Build + Test Outcomes

| Command | Result | Duration |
|---|---|---|
| `./gradlew :app:compileDebugKotlin` | **BUILD SUCCESSFUL** | 12s |
| `./gradlew :app:testDebugUnitTest` | **BUILD SUCCESSFUL** | 5s |

Same pre-existing warning as Plan 08-04 (`OpenApiToolDefs.kt:103:30` named-argument mismatch) — out of scope; untouched.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Worktree missing `android/local.properties`**
- **Found during:** First `./gradlew :app:compileDebugKotlin` run.
- **Issue:** Build failed with "SDK location not found". The worktree was spawned without the gitignored `local.properties` that the main repo holds. (Same blocker Plan 08-04's executor hit — solved via env vars; here I solved it slightly differently.)
- **Fix:** Wrote a worktree-local `android/local.properties` with `sdk.dir=C\:\\Users\\amanr\\AppData\\Local\\Android\\Sdk` (copied verbatim from main repo). The file is gitignored, so it is **not** staged or committed — it exists only for this worktree's gradle invocations and dies with the worktree.
- **Files modified:** `android/local.properties` (gitignored, worktree-local stub; not committed)
- **Commit:** n/a (no source change)

Otherwise: plan executed exactly as written. No Rule-1 / Rule-2 / Rule-4 deviations.

## Auth Gates

None. Pure UI variant swap; no network, no I/O, no credentials.

## Known Stubs

None. The `onDiscuss(row)` callback was already wired in v1.0 by D-05; the variant swap is visual-only and does not introduce any placeholder data flow.

## Threat Flags

None. The plan's `<threat_model>` block marks T-08-05 as `n/a` — pure visual-variant swap, no new exported surface, no new data flow. Post-change diff confirmed: same `onClick = { onDiscuss(row) }` payload signature, no new I/O, no new permissions, no new dependencies.

## Commits

| Hash | Type | Subject |
|---|---|---|
| `d739dca` | feat | feat(08-05): swap LabRow per-row CTA to GhostButton (D-01d) |

## On-device verification (deferred to Plan 08-06)

ROADMAP SC #3 visual smoke is deferred to Plan 08-06's phase-close visual smoke pass on SM-S918B. Expected post-Phase-8 visual hierarchy:

- **SummaryCard's "Bring this to your clinician"** — `PrimaryButton`: terracotta-filled, full-width, loud. Single card-level action.
- **Per-row "Discuss with your doctor"** on each flagged / unknown row — `GhostButton`: outlined / text-only / no terracotta fill, full-width hit-target preserved. Subordinate to the card-level CTA.

Pre-migration, both buttons rendered identically and competed for attention — N2 CTA-fatigue risk. Post-migration: one loud, the rest quiet.

## Success Criteria

| SC | Status | Evidence |
|---|---|---|
| ROADMAP SC #3 (per-row CTA visibility + hierarchy) | Source-side: ✓ ; on-device visual smoke: deferred to 08-06 | grep gates 5 (predicate) + 6 (copy) + 2 (Ghost call site) green; on-device verification deferred per CONTEXT.md §145 (TEST-FRAMEWORK-01 carry-over). |
| D-01d implemented exactly as decided | ✓ | Import + call-site swap landed at the exact anchor (LabRow.kt:194); SummaryCard's PrimaryButton (Plan 08-04) untouched by this plan. |
| No new imports beyond the swap | ✓ | Single import line replaced 1:1 (`PrimaryButton` → `GhostButton`, both inside `com.aegis.health.ui.common.*`). No new packages introduced. |
| No new files | ✓ | `git diff --stat` shows exactly 1 file changed, 0 added. |

## Self-Check: PASSED

- File `android/app/src/main/java/com/aegis/health/ui/reportreader/LabRow.kt` — **FOUND** (208 LOC, was 207; net +1 from comment line).
- Commit `d739dca` — **FOUND** on `worktree-agent-acfa736b3b6ca3cc8` (`git log --oneline -1` returns `d739dca feat(08-05): swap LabRow per-row CTA to GhostButton (D-01d)`).
- All 8 grep gates green (table above).
- `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest` — BUILD SUCCESSFUL.
- v1.0 D-05 visibility predicate and D-03b CTA copy preserved byte-identical (grep-confirmed).
- Threat surface unchanged (per the plan's `<threat_model>` n/a disposition; post-change inspection confirms).
