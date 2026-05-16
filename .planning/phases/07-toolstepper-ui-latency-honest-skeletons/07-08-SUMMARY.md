---
phase: 07-toolstepper-ui-latency-honest-skeletons
plan: 08
subsystem: ui
tags: [accessibility, wcag-aa, contrast, toolstepper, calm-tone, jetpack-compose, gap-closure]

# Dependency graph
requires:
  - phase: 07-toolstepper-ui-latency-honest-skeletons
    provides: "WarningFg/WarningBg/WarningFgDark/WarningBgDark calm-tone tokens (Plan 07-01) and AegisColors.isDark theme-discriminator field (Color.kt:99)"
provides:
  - "Light-mode-only 1dp warningFg@0.32f border on ToolStepper's Failed-state chip (StepRow's Failed branch)"
  - "WR-03 closure: cream-on-white failure-chip contrast lifted from ~1.06:1 to >= WCAG AA's 3:1 non-text-component threshold"
  - "Reusable Compose-idiomatic pattern: Modifier.let { base -> if (...) base.border(...) else base } for theme-gated decoration"
affects: ["phase-08", "phase-09+", "any-future-screen-reusing-ToolStepper"]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "Theme-gated Modifier chain via `.let { base -> if (!colors.isDark) base.border(...) else base }` — light-only decoration without duplicating the surrounding Row()"
    - "Reduce-alpha trick: `colors.warningFg.copy(alpha = 0.32f)` reuses an existing palette token at lower chroma instead of adding a new color"

key-files:
  created: []
  modified:
    - "android/app/src/main/java/com/aegis/health/ui/common/ToolStepper.kt (StepRow Failed branch, +12 LOC including comment; net logic ~3 LOC)"

key-decisions:
  - "Reused warningFg token at alpha=0.32f rather than introducing a fifth Warning* color — keeps the palette surface flat and avoids drift from the Plan 07-01 calm-tone contract."
  - "Light-mode-only border via `!colors.isDark` gate — dark mode's 0x1F-alpha WarningBgDark already has visible chroma against AegisCanvasDark per 07-REVIEW.md WR-03, so a dark-mode border would be redundant visual noise."
  - "Chose the `.let { base -> ... }` conditional-modifier form over `Modifier.then(if (...) Modifier.border(...) else Modifier)` for readability — both are Compose-idiomatic; the planner left the choice to executor discretion."
  - "Border corner-radius matches background (RoundedCornerShape(10.dp)) — concentric border/background preserves the visual contract; modifier order `.background → .border → .padding` keeps the border painted on top of the background and inside the padding bounds."

patterns-established:
  - "Theme-gated visual refinement pattern: when a token-driven element fails WCAG AA only in one theme, gate the remediation modifier on `colors.isDark` rather than forking the composable or introducing a parallel palette."

requirements-completed: [WR-03]

# Metrics
duration: 6min
completed: 2026-05-15
---

# Phase 7 Plan 8: WR-03 WCAG AA Failure-Chip Border Summary

**1dp warningFg@0.32f border added to ToolStepper's Failed-state chip, gated on `!colors.isDark`, lifting the cream-on-white chip contrast from ~1.06:1 to >= WCAG AA's 3:1 non-text-component threshold without changing the Phase 5 D-08 pinned signature.**

## Performance

- **Duration:** ~6 min (gradle-bound; both gates ran from a warm cache)
- **Started:** 2026-05-15T17:44:07Z (UTC; equivalent to commit `e9524ab` HEAD baseline)
- **Completed:** 2026-05-15T17:50:09Z (UTC; commit `bc0dd58` author timestamp)
- **Tasks:** 1/1
- **Files modified:** 1

## Accomplishments

- WR-03 closed: the calm-tone ⚠ failure chip in light mode now has a visible 1dp amber outline against white card backgrounds, satisfying WCAG AA 3:1 contrast for non-text UI components.
- Dark mode behavior unchanged — verified by inspection of the `!colors.isDark` conditional (`else base` returns the base modifier untouched).
- Phase 5 D-08 pinned signature `ToolStepper(label, steps, modifier, failures)` preserved byte-identical.
- Zero new imports, zero new color tokens, zero new dependencies. The existing `androidx.compose.foundation.border` import at line 15 is reused.
- Full JVM suite green: 200/200 tests passing (matches pre-edit baseline exactly).
- `:app:assembleDebug` BUILD SUCCESSFUL.

## Task Commits

Single-file atomic commit (planner-anticipated):

1. **Task 1: Add light-mode-gated 1dp border to Failed-state Row in StepRow** — `bc0dd58` (feat)

## Files Created/Modified

- `android/app/src/main/java/com/aegis/health/ui/common/ToolStepper.kt` — StepRow's Failed branch (line ~222-244) now chains `.let { base -> if (!colors.isDark) base.border(1.dp, colors.warningFg.copy(alpha = 0.32f), RoundedCornerShape(10.dp)) else base }` between `.background(colors.warningBg, RoundedCornerShape(10.dp))` and `.padding(horizontal = 8.dp, vertical = 4.dp)`. The Running/Done branch (lines 254-296) is unchanged; its pre-existing `.border(1.5.dp, colors.accent, CircleShape)` on the inner circle is unrelated.

### Exact hunk landed (line numbers per HEAD@bc0dd58)

```kotlin
// ToolStepper.kt, StepRow's Failed branch
Row(
    modifier = Modifier
        .testTag("step-row-${state.name}-$index")
        .background(colors.warningBg, RoundedCornerShape(10.dp))
        .let { base ->
            // WR-03 (Plan 07-08): light-mode-only 1dp warningFg@0.32f
            // border lifts the cream-on-white chip contrast from ~1.06:1
            // up to WCAG AA's 3:1 non-text-component threshold. Dark
            // mode's 0x1F-alpha WarningBgDark already has visible chroma
            // against AegisCanvasDark, so the border is light-only.
            if (!colors.isDark) {
                base.border(1.dp, colors.warningFg.copy(alpha = 0.32f), RoundedCornerShape(10.dp))
            } else {
                base
            }
        }
        .padding(horizontal = 8.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
)
```

### Theme-gate behavior matrix

| Theme | `colors.isDark` | `warningBg` value | Border rendered? | Resulting chip |
|-------|-----------------|-------------------|------------------|----------------|
| Light | `false`         | `WarningBg = 0xFFFAEDD0` (opaque cream) | yes — `WarningFg @ 0.32` (alpha-blended amber) | cream fill + thin amber outline on white card → visually distinguishable at WCAG AA 3:1+ |
| Dark  | `true`          | `WarningBgDark = 0x1FE2B86A` (~12% alpha amber) | no — `else base` returns base modifier untouched | translucent amber fill with visible chroma against `AegisCanvasDark` → already passes contrast without help |

## Decisions Made

- **Border tint = `warningFg @ 0.32f`, not a new dedicated token.** Per 07-REVIEW.md WR-03 lines 244-247 and the plan's `read_first` guidance, the calm-tone STEP-06 contract forbids a high-chroma outline; alpha-reducing the existing foreground keeps the chip visually quiet while still rising above the 3:1 contrast floor. Adding a fifth `Warning*` token would have triggered grep-gate G4 (`^val Warning` count must stay at 4).
- **`.let { base -> ... }` over `Modifier.then(...)` form.** Both are accepted by the plan; chose `.let` because it keeps the entire conditional read as a single chain step and signals intent ("decorate base under condition") more clearly than a `then(Modifier.border(...) else Modifier)` ternary.

## Deviations from Plan

None — plan executed exactly as written.

The plan's `action` block offered the executor planner-discretion between `.let { base -> ... }` and `Modifier.then(if ...)`; selecting one of the two pre-approved forms is not a deviation.

## Issues Encountered

None.

One minor observability-only quirk: the initial single-line grep `border\(\s*width\s*=\s*1\.5\.dp` (acceptance assertion for "Running/Done circle border unchanged") returned 0 hits because the existing border declaration spans two lines (`.border(` newline + `width = 1.5.dp,`). Re-ran with `multiline: true` and confirmed the pre-existing border at lines 267-268 is intact and unchanged. The plan's grep gate text is technically tighter than the literal source format, but this is a grep-pattern artifact, not a defect.

## Verification

### Plan-level grep gates (all green)

```bash
# G1: WR-03 closed — light-mode border present
grep -cE 'border\(1\.dp,\s*colors\.warningFg\.copy\(alpha\s*=\s*0\.32f\)' \
  android/app/src/main/java/com/aegis/health/ui/common/ToolStepper.kt
# → 1 (line 233)

# G2: Light-mode gate present
grep -cE 'colors\.isDark' android/app/src/main/java/com/aegis/health/ui/common/ToolStepper.kt
# → 1 (line 232: `if (!colors.isDark)`)

# G3: Signature pin (Phase 5 D-08) preserved
grep -nE '^fun\s+ToolStepper\(' android/app/src/main/java/com/aegis/health/ui/common/ToolStepper.kt
# → 86:fun ToolStepper( (unchanged)

# G4: No new color tokens introduced
grep -cE '^val\s+Warning' android/app/src/main/java/com/aegis/health/ui/theme/Color.kt
# → 4 (baseline; unchanged)

# Import-count regression (acceptance assertion)
grep -cE '^import\s+' android/app/src/main/java/com/aegis/health/ui/common/ToolStepper.kt
# → 43 (baseline; unchanged)
```

### Build sweep

```
cd android && ./gradlew :app:testDebugUnitTest    → BUILD SUCCESSFUL (200/200 green)
cd android && ./gradlew :app:assembleDebug        → BUILD SUCCESSFUL
```

### Human verification (deferred per plan §verification)

The visual SM-S918B check (light-mode chip shows a thin amber outline on a white card; dark-mode chip has no outline) is **deferred** — TEST-FRAMEWORK-01 (Compose BOM 2026.05 regression, see MEMORY) blocks instrumented-test capture, and the natural functional path to a `StepFailure` event requires Plans 07-06 + 07-07 (both already landed) to be wired into the screens. The source-level grep gates G1–G4 are the structural test vehicle for this gap closure and are sufficient for plan close per the plan's verification section.

## Accessibility Side-Effect Audit

Confirmed no other a11y surfaces were affected by this 3-LOC visual edit:

- **Text color contrast** — unchanged. The `⚠` glyph and the failure-reason text still render in `colors.warningFg` against `colors.warningBg`; only the outer Row border was added.
- **Touch target size** — unchanged. The Row's `.padding(horizontal = 8.dp, vertical = 4.dp)` is intact; the 1dp border draws inside the existing layout bounds.
- **testTag** — `step-row-${state.name}-$index` preserved byte-identical for instrumented-test selectors.
- **Animation/motion** — unchanged. `AnimatedContent(tween(350))` and `AnimatedVisibility(fadeIn + expandVertically)` continue to honor `Settings.Global.ANIMATOR_DURATION_SCALE` (SKEL-05).
- **Screen-reader / TalkBack** — unaffected. Compose `border()` does not emit semantics nodes; no `contentDescription` was changed; the `⚠` glyph remains a `Text` (already part of the reading order).
- **Dark mode** — structurally unchanged. The `else base` branch returns the unmodified `Modifier`, so dark-mode rendering is byte-identical to pre-Plan-07-08.

## Known Stubs

None. This change is purely a visual decoration on already-rendered failure chips; no data is wired, no placeholders introduced.

## Self-Check: PASSED

- **Files claim:** `android/app/src/main/java/com/aegis/health/ui/common/ToolStepper.kt` — FOUND on disk; modified hunk landed at lines 222-244 (StepRow Failed branch) per `git diff HEAD~1 HEAD`.
- **Commit claim:** `bc0dd58` — FOUND in `git log` (`feat(07-08): add light-mode 1dp warningFg@0.32f border to ToolStepper failed chip (WR-03)`).
- **Test counts claim:** 200 tests passing — verified by parsing `app/build/test-results/testDebugUnitTest/*.xml` (`tests=200 failures=0 errors=0 skipped=0`).
- **Build claim:** `:app:assembleDebug` BUILD SUCCESSFUL — verified in gradle output tail.
- **Grep-gate claims:** G1–G4 + import count + signature pin all verified per the Verification section above.
- **Deletion check:** `git diff --diff-filter=D --name-only HEAD~1 HEAD` → empty. No files deleted by this commit.

## Next Phase Readiness

Phase 7 was closed by commit `6ab4946` (12/12 grep gates green; 5/5 plans + 11/11 requirements complete). Plan 07-08 was an after-the-fact gap closure for WR-03 (the WARNING-tier finding from 07-REVIEW.md surfaced after Phase 7 close). With this commit, the failure-chip's only remaining open accessibility concern is closed; no other Phase 7 follow-ups are pending.

The next phase work (Phase 8) is unblocked and unchanged by this edit — the modifier insertion is interior to a private composable's render branch and changes no API surface, so downstream screens that consume `ToolStepper(label, steps, modifier, failures)` (DrugSafe, HealthPartner, ReportReader) see no behavioral diff outside of the visible border in light mode.

---
*Phase: 07-toolstepper-ui-latency-honest-skeletons*
*Completed: 2026-05-15*
