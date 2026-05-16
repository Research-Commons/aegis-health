---
phase: 08-reportreader-visual-polish
plan: 03
subsystem: ui
tags: [compose, color-tokens, theme, hex-literal-migration, calm-by-default]

# Dependency graph
requires:
  - phase: 08-reportreader-visual-polish
    provides: "Phase 8 token surface and decisions (D-04a..D-04d) — onWarmSurface naming convention + grep-gate scope"
provides:
  - "Two new AegisColors fields (onWarmSurface + onWarmSurfaceMuted) with light warm-tinted values (0xFF1A1816 / 0xFF3B3733) and dark aliases (AegisOnSurfaceDark / AegisOnSurfaceMutedDark)"
  - "9 hex-conditional call sites across 6 non-theme UI files collapsed onto the new tokens"
  - "Phase-close grep gate (Plan 08-06 owns the official run) structurally guaranteed empty for non-theme ui/ sources"
affects: [08-06, 09-home-startup-polish, 10-test-framework-and-stretch]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Warm-ink token pair (onWarmSurface / onWarmSurfaceMuted) for sevModBg / sevCritBg card body text"
    - "Asymmetric site collapse with inline 2-line justification comment (no `Color(0x` literal in the comment prose, so the phase-close grep gate stays green)"

key-files:
  created: []
  modified:
    - "android/app/src/main/java/com/aegis/health/ui/theme/Color.kt (4 edits: 2 top-level constants + 2 data-class fields + 2 light bindings + 2 dark aliased bindings)"
    - "android/app/src/main/java/com/aegis/health/ui/common/DeferralBanner.kt (2 site collapses + Color import removed)"
    - "android/app/src/main/java/com/aegis/health/ui/common/OcrFailBanner.kt (1 site collapse + Color import removed)"
    - "android/app/src/main/java/com/aegis/health/ui/common/SeverityCard.kt (2 site collapses; Color import RETAINED for Color.White / Color.Transparent / typed `fg: Color` param)"
    - "android/app/src/main/java/com/aegis/health/ui/consentreader/ConsentReaderScreen.kt (1 asymmetric site collapse + 2-line inline justification comment)"
    - "android/app/src/main/java/com/aegis/health/ui/deferral/DeferralScreen.kt (2 site collapses + Color import removed)"
    - "android/app/src/main/java/com/aegis/health/ui/healthpartner/HealthPartnerScreen.kt (1 site collapse — inline FQN form; no top-level import to touch)"

key-decisions:
  - "ConsentReaderScreen.kt:417 asymmetric site collapsed to colors.onWarmSurfaceMuted per PATTERNS.md Option 1; dark-mode body text byte-shifts from full-ink to muted-ink. Documented inline + flagged here for Plan 08-06 on-device visual smoke."
  - "Inline justification comment rewritten to omit the literal `Color(0x` token in its prose (initial draft contained `Color(0xFF3B3733)` in backticks, which the phase-close grep gate would have caught — `grep -rEn 'Color\\(0x'` matches comment lines too). Replaced with the standalone hex `0xFF3B3733`, preserving the historical record."
  - "DeferralBanner / OcrFailBanner / DeferralScreen each had their `import androidx.compose.ui.graphics.Color` dropped after the site collapses left no residual `Color(` or `Color.X` references. SeverityCard retained its Color import (uses Color.White at line 91, Color.Transparent at line 149, and a typed `fg: Color` parameter at line 148)."

patterns-established:
  - "onWarmSurface / onWarmSurfaceMuted: warm-tinted body-ink tokens for the sevModBg / sevCritBg card family. Light = the historical warm-ink hex values (0xFF1A1816 / 0xFF3B3733); dark = aliased to the canonical onSurface / onSurfaceMuted tokens (no warm-mode dark treatment, per CONTEXT.md §139)."
  - "Asymmetric-site collapse template: when the dark branch is `colors.onSurface` and the light branch is the muted warm hex, collapse to `colors.onWarmSurfaceMuted` plus an inline 2-line comment recording the pre-migration shape (using a non-grep-gate-tripping prose form for the hex value)."

requirements-completed: [POLISH-04]

# Metrics
duration: 7m 42s
completed: 2026-05-16
---

# Phase 8 Plan 03: Warm-ink tokens migration Summary

**Two new AegisColors fields (`onWarmSurface` + `onWarmSurfaceMuted`) land, and all 9 `if (colors.isDark) X else Color(0xFF…)` hex-conditional sites across 6 non-theme UI files collapse onto those tokens — C3 severity-color-drift mitigation locked, phase-close grep gate structurally empty.**

## Performance

- **Duration:** 7m 42s (462 sec)
- **Started:** 2026-05-16T06:30:56Z
- **Completed:** 2026-05-16T06:38:38Z
- **Tasks:** 3
- **Files modified:** 7

## Accomplishments

- `onWarmSurface` + `onWarmSurfaceMuted` added to `AegisColors` (2 constants, 2 data-class fields, 2 light bindings, 2 dark aliased bindings — 8 source insertions total in Color.kt). Both factories compile; both new tokens reachable via `LocalAegisColors.current.onWarmSurface*`.
- 8 symmetric `if (colors.isDark) colors.onSurface(Muted) else Color(0xFF1A1816|0xFF3B3733)` conditionals across `DeferralBanner.kt`, `OcrFailBanner.kt`, `SeverityCard.kt`, `DeferralScreen.kt`, `HealthPartnerScreen.kt` collapsed to single `colors.onWarmSurface(Muted)` token references.
- 9th asymmetric site at `ConsentReaderScreen.kt:417` (lone dark = `onSurface` / light = `0xFF3B3733` mismatch) collapsed to `colors.onWarmSurfaceMuted` per PATTERNS.md Option 1, with an inline 2-line justification comment documenting the intentional dark-mode byte-shift. (See "Decisions Made" for the comment-prose grep-gate-avoidance discovery.)
- Phase-close grep gate verified empty post-edit: `grep -rEn 'Color\(0x' android/app/src/main/java/com/aegis/health/ui/ | grep -v ui/theme/` returns 0 lines.
- Import cleanup: 3 files dropped `import androidx.compose.ui.graphics.Color` (DeferralBanner, OcrFailBanner, DeferralScreen); SeverityCard retained the import (Color.White / Color.Transparent / typed param); HealthPartnerScreen had no top-level import to touch (inline-FQN form).
- Build green (`./gradlew :app:compileDebugKotlin BUILD SUCCESSFUL`) at every task boundary; JVM unit-test suite green after Tasks 2 and 3 (`./gradlew :app:testDebugUnitTest BUILD SUCCESSFUL`).

## Task Commits

Each task was committed atomically:

1. **Task 1: Add `onWarmSurface` + `onWarmSurfaceMuted` tokens to `AegisColors`** — `154e995` (feat)
2. **Task 2: Collapse 8 symmetric hex-conditional sites in 5 files** — `dda70c0` (refactor)
3. **Task 3: Collapse the asymmetric `ConsentReaderScreen.kt:417` site with inline justification comment** — `b8c48b5` (refactor)

(Per executor-protocol the worktree only creates per-task commits; the orchestrator handles the metadata commit + STATE/ROADMAP updates after the wave completes.)

## Files Created/Modified

- `android/app/src/main/java/com/aegis/health/ui/theme/Color.kt` — 4 additive edits: top-level `AegisOnWarmSurfaceLight = Color(0xFF1A1816)` + `AegisOnWarmSurfaceMutedLight = Color(0xFF3B3733)` constants (inserted at line 24-25 after `AegisOnSurfaceMutedLight`); `AegisColors` data-class fields `val onWarmSurface: Color,` + `val onWarmSurfaceMuted: Color,` (after `onSurfaceMuted`); `LightAegisColors` bindings to the warm-tinted constants; `DarkAegisColors` bindings aliased to `AegisOnSurfaceDark` / `AegisOnSurfaceMutedDark`.
- `android/app/src/main/java/com/aegis/health/ui/common/DeferralBanner.kt` — 2 site collapses (lines 72 + 78 → `colors.onWarmSurface` / `colors.onWarmSurfaceMuted`); `import androidx.compose.ui.graphics.Color` removed (no residual usage in file).
- `android/app/src/main/java/com/aegis/health/ui/common/OcrFailBanner.kt` — 1 site collapse (line 58 → `colors.onWarmSurfaceMuted`); `Color` import removed (no residual usage).
- `android/app/src/main/java/com/aegis/health/ui/common/SeverityCard.kt` — 2 site collapses (lines 110 + 118 → `colors.onWarmSurface` / `colors.onWarmSurfaceMuted`); `Color` import **retained** — file still uses `Color.White` (line 91 inside the 28dp icon chip), `Color.Transparent` (line 149 inside `SeverityLabelPill`), and a typed `fg: Color` parameter on `SeverityLabelPill`.
- `android/app/src/main/java/com/aegis/health/ui/consentreader/ConsentReaderScreen.kt` — 1 asymmetric site collapse at line 417 inside `BindingClauseCard`; 2-line inline justification comment inserted immediately above the new color binding.
- `android/app/src/main/java/com/aegis/health/ui/deferral/DeferralScreen.kt` — 2 site collapses (lines 112 + 119 → `colors.onWarmSurface` / `colors.onWarmSurfaceMuted`); `Color` import removed.
- `android/app/src/main/java/com/aegis/health/ui/healthpartner/HealthPartnerScreen.kt` — 1 site collapse at line 599; the inline-FQN literal `androidx.compose.ui.graphics.Color(0xFF3B3733)` is gone; no top-level `Color` import existed in this file.

### Per-file grep-gate confirmation (post-edit)

| File | `Color(0x` hits | New token references | Notes |
|------|------------------|----------------------|-------|
| `Color.kt`                 | 23 (theme-token defs — excluded from gate) | n/a                       | `AegisOnWarmSurfaceLight` / `AegisOnWarmSurfaceMutedLight` defined here |
| `DeferralBanner.kt`        | 0  | `colors.onWarmSurface` ×1, `colors.onWarmSurfaceMuted` ×1 | Color import dropped     |
| `OcrFailBanner.kt`         | 0  | `colors.onWarmSurfaceMuted` ×1                            | Color import dropped     |
| `SeverityCard.kt`          | 0  | `colors.onWarmSurface` ×1, `colors.onWarmSurfaceMuted` ×1 | Color import retained (Color.White / Color.Transparent / typed param) |
| `ConsentReaderScreen.kt`   | 0  | `colors.onWarmSurfaceMuted` ×1                            | 2-line justification comment present; FQN form gone |
| `DeferralScreen.kt`        | 0  | `colors.onWarmSurface` ×1, `colors.onWarmSurfaceMuted` ×1 | Color import dropped     |
| `HealthPartnerScreen.kt`   | 0  | `colors.onWarmSurfaceMuted` ×1                            | Inline FQN literal gone  |

### ConsentReader asymmetric-site treatment (full inline comment text)

```kotlin
            // Phase 8 D-04a (asymmetric site collapse): pre-migration this site used `colors.onSurface` in dark and the warm-muted hex `0xFF3B3733` in light.
            // The dark branch byte-shifts from full-ink → muted-ink. BindingClauseCard body text is italicized + already muted in light; the dark-mode shift is intentional and visually subordinate to the card's sevModBg tint.
            color = colors.onWarmSurfaceMuted,
```

The comment intentionally omits the literal `Color(0x…)` prefix so the phase-close grep gate (which matches the literal pattern on every line including comments) stays empty. The standalone hex `0xFF3B3733` is preserved for historical traceability.

## Decisions Made

- **Comment-prose grep-gate avoidance (Task 3).** First-pass justification comment included the literal `Color(0xFF3B3733)` in backticks (verbatim recording of the pre-migration code). Discovered during Task 3 acceptance-criteria verification: the phase-close grep gate is `grep -rEn 'Color\(0x'` — it matches comment lines, not just code. Rewrote the comment to use the standalone hex `0xFF3B3733` (no `Color(` prefix), preserving historical fidelity while keeping the gate empty. No subsequent grep-gate trip risk on this site. (Recorded in Task 3 commit message body.)
- **Worktree rebase before any edits.** The worktree was initially forked from 5cff363 (pre-Phase-7), per the orchestrator's note. Rebased onto current main HEAD (23d267b) before reading the target files — the post-07-04-revert `DeferralScreen.kt` shape and the Phase 7 `warningFg` / `warningBg` additions to `AegisColors` are present on the current branch. Verified pre-edit hex-literal grep baseline still returned the expected 9 sites at the documented line numbers.
- **SeverityCard `Color` import retention.** Confirmed by inspection: the file uses `Color.White` (line 91, icon tint inside the 28dp severity chip), `Color.Transparent` (line 149, dark-mode `SeverityLabelPill` background), and a `fg: Color` typed parameter on `SeverityLabelPill` (line 148). Import is mandatory — explicitly diverges from the PATTERNS.md §380 default cleanup heuristic, but matches PATTERNS.md §390 "DO NOT remove" guidance. Documented inline in the Task 2 commit message body.

## Deviations from Plan

None — plan executed exactly as written.

The one minor finding above (grep-gate-trip risk in the verbatim quote inside the Task 3 inline comment) was caught during Task 3's own source-assertion verification before commit, so it required only a single-line comment rewrite (no new task, no new commit type, no scope deviation). The plan's `<output>` block already required "full quote of the new inline comment" in the SUMMARY — the SUMMARY records the final, gate-safe version verbatim.

## Issues Encountered

- **Worktree base was stale (5cff363, pre-Phase-7).** Rebased cleanly onto current main HEAD (23d267b) with no merge conflicts. Verified the rebase via `git merge-base HEAD main` == `git rev-parse main` post-rebase, plus a pre-edit re-run of the 9-site hex-literal grep baseline. All 9 line numbers in the plan matched the current main HEAD exactly — no drift.
- **`android/local.properties` was missing from the worktree.** Gradle's first `:app:compileDebugKotlin` call after Task 1 failed with `SDK location not found`. Copied the file from the parent repo (`C:\ResearchCommons\aegis-health\android\local.properties` → worktree). The file is `.gitignore`'d (correctly — it carries a local SDK path), so the copy is not staged or committed.
- **Pre-existing deprecation warnings.** `CameraPipeline.kt:74` (LocalLifecycleOwner), `OpenApiToolDefs.kt:103` (named-arg supertype mismatch), `ConsentReaderScreen.kt:374` (ClickableText) all warn during compile but are entirely unrelated to this plan's edits. Logged here for visibility; not in scope to fix.

## User Setup Required

None — no external service configuration touched. Pure compile-time / visual-token additions.

## Next Phase Readiness

- **Plan 08-06 (the phase-close verifier)** can run `grep -rEn 'Color\(0x' android/app/src/main/java/com/aegis/health/ui/ | grep -v ui/theme/` and expect 0 hits. Verified empty post-edit in this plan's final task; structurally guaranteed.
- **On-device visual smoke (SM-S918B, deferred to Plan 08-06)** — per the plan's `acceptance_criteria` blocks, the verifier should spot-check:
  - `DeferralBanner` / `OcrFailBanner` / `SeverityCard` / `DeferralScreen` / `HealthPartnerScreen` warm-card body text in light mode reads visually-identical to pre-migration (8 of 9 sites are byte-identical light-mode + byte-identical dark-mode behavior).
  - **`BindingClauseCard` body text in dark mode** (`ConsentReaderScreen.kt:417` site) — intentional byte-shift from full-ink → muted-ink. Plan 08-06 should explicitly enumerate this site; if the dark-mode visual reads wrong, revert to PATTERNS.md Option 2 (asymmetric token addition).
- **C3 severity-color-drift mitigation locked.** No new `Color(0x…)` literal can land in non-theme `ui/` sources without tripping the phase-close grep gate. Future phases (e.g., Phase 9 Home/Startup polish) inherit this constraint automatically.
- **No blockers for Plan 08-06 or downstream Phase 9 work.**

## Self-Check: PASSED

Verified that all claims in this summary correspond to disk state:

- **File `android/app/src/main/java/com/aegis/health/ui/theme/Color.kt` exists** — FOUND
- **File `android/app/src/main/java/com/aegis/health/ui/common/DeferralBanner.kt` exists** — FOUND
- **File `android/app/src/main/java/com/aegis/health/ui/common/OcrFailBanner.kt` exists** — FOUND
- **File `android/app/src/main/java/com/aegis/health/ui/common/SeverityCard.kt` exists** — FOUND
- **File `android/app/src/main/java/com/aegis/health/ui/consentreader/ConsentReaderScreen.kt` exists** — FOUND
- **File `android/app/src/main/java/com/aegis/health/ui/deferral/DeferralScreen.kt` exists** — FOUND
- **File `android/app/src/main/java/com/aegis/health/ui/healthpartner/HealthPartnerScreen.kt` exists** — FOUND
- **Commit `154e995` (Task 1: feat add onWarmSurface tokens)** — FOUND in `git log --all`
- **Commit `dda70c0` (Task 2: refactor collapse 8 symmetric sites)** — FOUND in `git log --all`
- **Commit `b8c48b5` (Task 3: refactor collapse asymmetric ConsentReader site)** — FOUND in `git log --all`
- **Phase-close grep gate** — verified empty in the final Task 3 post-commit check

---
*Phase: 08-reportreader-visual-polish*
*Completed: 2026-05-16*
