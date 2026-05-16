---
phase: 08-reportreader-visual-polish
plan: 01
subsystem: ui
tags: [compose, theme, reportreader, status-tokens, polish-02]

# Dependency graph
requires:
  - phase: 03-ui-without-model
    provides: "AegisColors token surface (sevCritFg/Bg, sevModFg/Bg, sevLowFg/Bg, surfaceAlt, onSurfaceMuted); StatusBadge.kt:34-39 inline Triple<Color,Color,String> when block (the migration target)"
  - phase: 07-toolstepper-ui-latency-honest-skeletons
    provides: "severityColor(severity, colors) / severityBackgroundColor(severity, colors) sibling-helper precedent at Theme.kt:100-112"
provides:
  - "tokenForStatus(status: String, colors: AegisColors): Pair<Color, Color> in ui/theme/Theme.kt"
  - "statusLabel(status: String): String in ui/theme/Theme.kt"
  - "ThemeStatusHelpersTest.kt — 5 JVM cases pinning the helper contract"
affects: [08-02, 08-03, 08-04, future-row-tint-consumers]

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "String-keyed status → (bg, fg) token pair via colors-parameterized helper (mirrors severityColor pattern)"
    - "Calm-by-default fall-back: unrecognized status strings resolve to IN_RANGE tokens (surfaceAlt + onSurfaceMuted), never red (D-02c)"
    - "Strict-case match for canonical schema strings — no .uppercase()/.lowercase() normalization (D-02d)"

key-files:
  created:
    - "android/app/src/test/java/com/aegis/health/ui/theme/ThemeStatusHelpersTest.kt"
  modified:
    - "android/app/src/main/java/com/aegis/health/ui/theme/Theme.kt (lines 122-145 appended)"

key-decisions:
  - "Used org.junit.Assert.assertEquals (matches in-tree convention) instead of kotlin.test.assertEquals — kotlin-test dependency is not on the JVM test classpath; only junit:4.13.2 is. Rule 3 (blocking) deviation; behavioral contract preserved."
  - "Pair<Color, Color> returned via Kotlin `to` infix (not a data class) — D-02a literally mandates Pair shape; the data-class variant was already rejected as SC drift."
  - "Comment text re-worded from '.uppercase() normalization' to 'case-normalization' so the grep gate `grep -c \"\\.uppercase()\" Theme.kt` returns 0 (D-02d AC4)."

patterns-established:
  - "Theme.kt status helper family: tokenForStatus + statusLabel co-located after severityColor/severityBackgroundColor/severityLabel under a 'ReportReader status helpers (Phase 8 D-02)' region header."
  - "Helpers consume AegisColors directly (same package — no import needed) and emit Compose Color / Kotlin String. No Composable annotation; pure functions are JVM-testable."

requirements-completed: [POLISH-02]

# Metrics
duration: ~22min
completed: 2026-05-16
---

# Phase 8 Plan 01: Theme.kt Foundation Helpers Summary

**Two top-level helpers — `tokenForStatus(status, colors): Pair<Color, Color>` and `statusLabel(status): String` — landed in `ui/theme/Theme.kt` with a 5-case JVM regression test; unblocks Plan 08-02 StatusBadge migration.**

## Performance

- **Duration:** ~22 min
- **Started:** 2026-05-16T05:35Z (approx)
- **Completed:** 2026-05-16T05:57Z
- **Tasks:** 2 / 2
- **Files modified:** 1 modified + 1 created

## Accomplishments

- **POLISH-02 requirement closed.** Single source of mapping for the four canonical `EvaluatedRow.status` codes now lives in `Theme.kt`; future row-tint and chip-strip consumers can call the helpers directly without re-implementing the `when` block.
- **Strict-case calm-by-default semantics locked.** Unrecognized status strings (including drift like `"UPPERCASE_UNKNOWN"`, `""`, schema-mutated values) resolve to IN_RANGE tokens + "In range" label — never red, never crashes. D-02c + D-02d codified as both source comment and test assertions.
- **TalkBack contract pinned.** `statusLabel("OUTSIDE_RANGE")` etc. emit the exact strings the existing 9 androidTests assert via `contentDescription = "Status: $label"`. Plan 08-02's `StatusBadge` migration can replace the inline `Triple` block with two helper calls without changing any contentDescription.
- **Regression-protected without an androidTest dependency.** The 5-case JVM test bypasses TEST-FRAMEWORK-01 (Compose UI androidTest framework regression on SM-S918B + BOM 2026.05.00) entirely. Plan 08-02 inherits the green test as its acceptance signal.

## Task Commits

Each task was committed atomically (no `git add -A`; per-file staging only):

1. **Task 1: Add tokenForStatus + statusLabel helpers to Theme.kt** — `d39cb56` (feat)
2. **Task 2: Add ThemeStatusHelpersTest with 5 JVM cases** — `6b037c5` (test)

_Plan metadata commit (this SUMMARY) follows in a separate `docs(08-01)` commit per the per-task commit protocol._

## Files Created/Modified

- **Modified — `android/app/src/main/java/com/aegis/health/ui/theme/Theme.kt`** (lines 122-145 appended after `severityLabel`): adds `tokenForStatus(status: String, colors: AegisColors): Pair<Color, Color>` and `statusLabel(status: String): String` under a new `// ── ReportReader status helpers (Phase 8 D-02) ──` region header. Mirrors the `severityColor(severity, colors)` precedent at lines 100-112. No imports added (`AegisColors` is same-package; `Color` already imported).
- **Created — `android/app/src/test/java/com/aegis/health/ui/theme/ThemeStatusHelpersTest.kt`** (76 lines, 5 `@Test` methods): pins the helper contract for all four canonical codes (`OUTSIDE_RANGE`, `BORDERLINE`, `unknown`, `IN_RANGE`/fall-back) + drift cases (`UPPERCASE_UNKNOWN`, empty string, `"garbage_value"`). JUnit 4 + `org.junit.Assert.assertEquals`. Uses `LightAegisColors` as the test fixture; pure JVM (no Android dependencies).

## Decisions Made

- **`org.junit.Assert.assertEquals` instead of `kotlin.test.assertEquals`** — plan §162 specified the latter, but `kotlin-test` is not declared in `android/app/build.gradle.kts:115` (only `junit:junit:4.13.2`). Adding the dependency would push edits outside `files_modified` scope; using JUnit's assertion matches the in-tree convention (see sibling `DeferReasonCopyTest.kt`, all three `inference/*Test.kt` files). Behavioral contract is preserved byte-identically. Logged as Rule 3 deviation below.
- **`Pair<Color, Color>` via Kotlin `to` infix** — `colors.sevCritBg to colors.sevCritFg` returns `Pair<Color, Color>`, matches SC #2 literally. Data-class variant (`StatusTokens(bg, fg)`) was pre-rejected as SC drift in CONTEXT.md D-02a.
- **Comment text "case-normalization" not "`.uppercase()` normalization"** — original draft included the literal string `.uppercase()` in a doc-comment explaining D-02d, which tripped AC4's `grep -c "\.uppercase()"` (the grep doesn't distinguish code vs. comment). Reworded to "case-normalization (D-02d)" — preserves the decision reference, satisfies the grep gate. Substantive intent unchanged.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] `kotlin.test.assertEquals` not on JVM test classpath**
- **Found during:** Task 2 (ThemeStatusHelpersTest authoring).
- **Issue:** Plan §162 specified `import kotlin.test.assertEquals`, but `android/app/build.gradle.kts:115` only declares `testImplementation("junit:junit:4.13.2")`. Adding `kotlin-test-junit` would have pushed edits outside the plan's stated `files_modified` array (which only lists Theme.kt + the test file). Compiling against `kotlin.test` would have failed at `compileDebugUnitTestKotlin`.
- **Fix:** Used `import org.junit.Assert.assertEquals` (the JUnit 4 assertion already on the classpath). Matches in-tree convention used by every existing JVM test in this repo, including the sibling `DeferReasonCopyTest.kt` under `app/src/test/java/com/aegis/health/ui/reportreader/`. The behavioral contract (5 `@Test` methods, each asserting the expected `Pair` or `String`) is preserved byte-identically — only the import statement differs.
- **Files modified:** `ThemeStatusHelpersTest.kt` (the change is the import; assertion call sites read identically).
- **Verification:** `./gradlew :app:testDebugUnitTest --tests "com.aegis.health.ui.theme.ThemeStatusHelpersTest"` reports 5 tests / 0 failures / 0 errors / 0 skipped (XML at `app/build/test-results/testDebugUnitTest/TEST-com.aegis.health.ui.theme.ThemeStatusHelpersTest.xml`).
- **Committed in:** `6b037c5` (Task 2 commit).

**2. [Rule 1 - Bug] Comment-text `.uppercase()` tripped AC4 grep gate**
- **Found during:** Task 1 (Theme.kt edit, immediate AC4 re-check).
- **Issue:** Initial draft of the region-header comment read "Strict-case match — no `.uppercase()` normalization (D-02d)." The literal `.uppercase()` substring appears in source even though it's a comment, so `grep -c "\.uppercase()" Theme.kt` returned 1 instead of the required 0 (Task 1 AC4 build assertion).
- **Fix:** Reworded to "Strict-case match — no case-normalization (D-02d)." The D-02d decision reference is preserved; the intent (no case-folding) is preserved; only the literal token `.uppercase()` is removed from the file. No semantic change.
- **Files modified:** `android/app/src/main/java/com/aegis/health/ui/theme/Theme.kt` (one comment line).
- **Verification:** Post-fix `grep -c "\.uppercase()" Theme.kt` returns 0.
- **Committed in:** `d39cb56` (Task 1 commit — fix was applied before staging).

---

**Total deviations:** 2 auto-fixed (1 Rule 3 blocking, 1 Rule 1 bug)
**Impact on plan:** Both auto-fixes were required to satisfy the plan's own acceptance criteria (Task 2 wouldn't compile without the JVM-classpath fix; Task 1 AC4 wouldn't pass without the comment-text fix). No scope creep — both changes stay strictly within the plan's `files_modified` array.

## Issues Encountered

- **Initial `compileDebugKotlin` failed with "SDK location not found".** The worktree was spawned without inheriting `android/local.properties` from the main repo (correctly gitignored). Resolved by exporting `ANDROID_HOME` / `ANDROID_SDK_ROOT` to `C:/Users/amanr/AppData/Local/Android/Sdk` for the build invocations only — no per-worktree `local.properties` was written (to avoid an accidental commit later). Pure environment-only workaround; not a code or plan issue.
- **Pre-existing Kotlin compile warning in `OpenApiToolDefs.kt:103`** ("named parameter mismatch with supertype `paramsJsonString`"). Pre-existing from Phase 6, unrelated to this plan's diff. Out of scope per Rule 4 — logged as a deferred-items candidate if it ever bites.
- **Plan §155 referenced `FlagsStreamParserTest.kt` as a style precedent**, but that file does not exist under `android/app/src/test/`. Mirrored the JUnit 4 + KDoc-header + named-`@Test`-method style of `DeferReasonCopyTest.kt` instead (same package tree under `ui/`, same project). Stylistic substitution; no behavior change.
- **Plan's `<success_criteria>` referenced "200/200 baseline; 205/205 after"**, but the actual local JVM baseline is **155 tests / 18 classes** (the planner's count appears to have included Compose UI androidTest cases that are TEST-FRAMEWORK-01-blocked and never join the JVM path). Actual post-plan total: **160 tests / 19 classes / 0 failures / 0 errors / 0 skipped**. The delta (+5) and the all-green outcome match the plan's intent.

## User Setup Required

None — no external service configuration required. All changes are local Kotlin source.

## Next Phase Readiness

**Plan 08-02 (StatusBadge migration, Wave 2) is unblocked.** It can:

```kotlin
import com.aegis.health.ui.theme.tokenForStatus
import com.aegis.health.ui.theme.statusLabel

val (bg, fg) = tokenForStatus(status, colors)
val label = statusLabel(status)
```

…replacing the current `Triple<Color, Color, String>` `when` block at `StatusBadge.kt:34-39` and preserving the TalkBack `contentDescription` strings byte-identically.

**Plans 08-03 / 08-04** (SummaryCard hierarchy + hex-literal collapse) are independent and not affected by this plan's diff.

**No blockers introduced.** Pre-existing TEST-FRAMEWORK-01 (Compose UI androidTest on SM-S918B + BOM 2026.05.00) remains Phase 10 P1; this plan deliberately routes around it by living on the JVM path.

## Self-Check: PASSED

**Created files exist:**
- FOUND: `android/app/src/test/java/com/aegis/health/ui/theme/ThemeStatusHelpersTest.kt`

**Modified files contain expected content:**
- FOUND: `tokenForStatus` signature line in `Theme.kt` at line 131
- FOUND: `statusLabel` signature line in `Theme.kt` at line 138

**Commits exist on worktree branch:**
- FOUND: `d39cb56` — feat(08-01): add tokenForStatus + statusLabel helpers to Theme.kt
- FOUND: `6b037c5` — test(08-01): pin tokenForStatus + statusLabel helpers with 5 JVM cases

**Grep gates (Task 1):**
- AC1 `tokenForStatus` signature: 1 line (expect exactly 1) — PASS
- AC2 `statusLabel` signature: 1 line (expect exactly 1) — PASS
- AC3 strict-case literal count: 7 (expect ≥ 6) — PASS
- AC4 `\.uppercase()` count: 0 (expect 0) — PASS

**Grep gates (Task 2):**
- AC1 file exists: PASS
- AC2 `@Test` count: 5 (expect exactly 5) — PASS
- AC3 class declaration: 1 line — PASS
- AC4 helper references: 18 (expect ≥ 10) — PASS

**Build assertions:**
- `./gradlew :app:compileDebugKotlin` — BUILD SUCCESSFUL (1m 58s)
- `./gradlew :app:testDebugUnitTest --tests ".ThemeStatusHelpersTest"` — BUILD SUCCESSFUL, 5/5 passed (41s)
- `./gradlew :app:testDebugUnitTest` (full JVM suite) — BUILD SUCCESSFUL, 160/160 passed across 19 classes (7s cached)

---

*Phase: 08-reportreader-visual-polish*
*Completed: 2026-05-16*
