# Phase 8 — androidTest Copy Inventory (M8 Mitigation / SC #5)

**Generated:** 2026-05-16 (Plan 08-06 Task 1)
**Purpose:** ROADMAP SC #5 mandates an inventory of `Status: ` and other TalkBack / role-content / copy-coupled assertion strings under `android/app/src/androidTest/` BEFORE any Phase 8 copy change lands. This document captures BASELINE + POST-CHANGE so a verifier can confirm no unintentional copy-string regression.

## Method

**SC #5 temporal compliance note.** ROADMAP SC #5 mandates the inventory was taken "BEFORE any change landed." Plan 08-06 runs in Wave 3 (after Wave 1/2 edits land), so the BASELINE is reconstructed by running the same greps against the immutable git ref `e9524ab` (Phase 7 close, HEAD just before Phase 8 Wave 1). The worktree state at `e9524ab` is byte-identical to the pre-Wave-1 worktree by construction; the reconstruction is therefore observationally equivalent to a pre-flight grep run. The M8 mitigation intent ("snapshot the copy surface so post-change diff is structured, not vibes") is satisfied — the reconstruction is auditable, deterministic, and the verifier can rerun both greps independently to confirm the diff.

Three greps run twice — once against the BASELINE git ref (Phase 7 close commit `e9524ab`, just before Phase 8 Wave 1) and once against the current tree (Wave 1/2 complete, Wave 3 in progress):

1. `grep -rEn "Status: " android/app/src/androidTest/`
2. `grep -rEn "Discuss with your doctor|Bring this to your clinician|All values in range|values are outside the printed range" android/app/src/androidTest/`
3. `grep -rEn "Status: |Discuss with your doctor|Bring this to your clinician|All values in range|values are outside the printed range" android/app/src/main/java/com/aegis/health/ui/reportreader/`

**Reproduction commands (verifier-runnable):**

```bash
# BASELINE (against immutable git ref e9524ab)
git grep -nE "Status: " e9524ab -- android/app/src/androidTest/
git grep -nE "Discuss with your doctor|Bring this to your clinician|All values in range|values are outside the printed range" e9524ab -- android/app/src/androidTest/
git grep -nE "Status: |Discuss with your doctor|Bring this to your clinician|All values in range|values are outside the printed range" e9524ab -- android/app/src/main/java/com/aegis/health/ui/reportreader/

# POST-CHANGE (against current main / worktree tree)
grep -rEn "Status: " android/app/src/androidTest/
grep -rEn "Discuss with your doctor|Bring this to your clinician|All values in range|values are outside the printed range" android/app/src/androidTest/
grep -rEn "Status: |Discuss with your doctor|Bring this to your clinician|All values in range|values are outside the printed range" android/app/src/main/java/com/aegis/health/ui/reportreader/
```

## BASELINE (Phase 7 close, before Phase 8 Wave 1 — git ref `e9524ab`)

Run from the worktree at git ref `e9524ab` (HEAD of branch as of Plan 07-07 close per STATE.md).

### androidTest scan — grep 1 (`Status: `)

```
(empty — 0 lines)
```

Confirms: the literal `"Status: $label"` (the StatusBadge `contentDescription`) is **not** asserted by any androidTest. The four label strings ("In range", "Outside range", "Borderline", "Review") are asserted indirectly through TalkBack-flow tests that may or may not exist; the prefix-form `"Status: …"` is NOT pinned.

### androidTest scan — grep 2 (4-string OR in androidTest)

```
e9524ab:android/app/src/androidTest/java/com/aegis/health/ui/reportreader/ReportReaderScreenTest.kt:133:        // D-03 count framing: "2 of 4 values are outside the printed range".
e9524ab:android/app/src/androidTest/java/com/aegis/health/ui/reportreader/ReportReaderScreenTest.kt:135:        composeRule.onNodeWithText("2 of 4 values are outside the printed range").assertIsDisplayed()
e9524ab:android/app/src/androidTest/java/com/aegis/health/ui/reportreader/ReportReaderScreenTest.kt:136:        composeRule.onNodeWithText("Bring this to your clinician").assertIsDisplayed()
e9524ab:android/app/src/androidTest/java/com/aegis/health/ui/reportreader/ReportReaderScreenTest.kt:159:        composeRule.onNodeWithText("0 of 1 values are outside the printed range").assertIsDisplayed()
e9524ab:android/app/src/androidTest/java/com/aegis/health/ui/reportreader/ReportReaderScreenTest.kt:160:        composeRule.onNodeWithText("Bring this to your clinician").assertIsDisplayed()
e9524ab:android/app/src/androidTest/java/com/aegis/health/ui/reportreader/ReportReaderScreenTest.kt:180:        composeRule.onAllNodesWithText("Discuss with your doctor").assertCountEquals(0)
e9524ab:android/app/src/androidTest/java/com/aegis/health/ui/reportreader/ReportReaderScreenTest.kt:194:        composeRule.onNodeWithText("Discuss with your doctor").assertIsDisplayed()
e9524ab:android/app/src/androidTest/java/com/aegis/health/ui/reportreader/ReportReaderScreenTest.kt:216:        composeRule.onNodeWithText("Discuss with your doctor").assertIsDisplayed()
e9524ab:android/app/src/androidTest/java/com/aegis/health/ui/reportreader/ReportReaderScreenTest.kt:237:        composeRule.onNodeWithText("Discuss with your doctor").assertIsDisplayed()
```

**ACTUAL at baseline:** 9 lines (1 comment + 8 `composeRule.onNodeWithText(...)` assertions).

> **Drift from plan template's EXPECTED:** The plan §141 template asserted "**0 lines** for the four-string OR-pattern." That undercount was authored from CONTEXT.md's claim that "the 9 existing instrumented tests do NOT pin these literals." The actual baseline shows the opposite: **8 androidTest assertions pin the four-string literals** (4 `"Discuss with your doctor"`, 2 `"Bring this to your clinician"`, 2 `"X of N values are outside the printed range"`). These tests are TEST-FRAMEWORK-01-blocked on SM-S918B + BOM 2026.05.00 (per `.planning/STATE.md`) so they don't currently fire, but the source-side coupling exists. The M8 mitigation purpose is preserved — the post-change diff below confirms zero copy regression on these literals.

### Main source scan — grep 3 (`ui/reportreader/`)

```
e9524ab:android/app/src/main/java/com/aegis/health/ui/reportreader/AegisResponseBuilder.kt:42:        "Bring this to your clinician to discuss any flagged values."
e9524ab:android/app/src/main/java/com/aegis/health/ui/reportreader/LabRow.kt:195:                        text = "Discuss with your doctor",
e9524ab:android/app/src/main/java/com/aegis/health/ui/reportreader/ReportEmptyState.kt:92:                text = "Discuss with your doctor",
e9524ab:android/app/src/main/java/com/aegis/health/ui/reportreader/ReportReaderScreen.kt:94: *   1. SummaryCard "Bring this to your clinician" → builder.build(report)
e9524ab:android/app/src/main/java/com/aegis/health/ui/reportreader/StatusBadge.kt:45:            .semantics { contentDescription = "Status: $label" },
e9524ab:android/app/src/main/java/com/aegis/health/ui/reportreader/SummaryCard.kt:29: *   1. Count headline: "X of N values are outside the printed range".
e9524ab:android/app/src/main/java/com/aegis/health/ui/reportreader/SummaryCard.kt:78:            text = "$outsideCount of $totalCount values are outside the printed range",
e9524ab:android/app/src/main/java/com/aegis/health/ui/reportreader/SummaryCard.kt:112:            text = "Bring this to your clinician",
```

**ACTUAL at baseline:** 8 lines (5 code literals + 3 KDoc / comment references).

> **Drift from plan template's EXPECTED:** The plan §157-160 template asserted **"4 lines"** at baseline. That count was for the code-literal-only lines; the planner missed `ReportEmptyState.kt:92` (a v1.0 `"Discuss with your doctor"` call site for the empty-state surface) and the KDoc / comment references at `ReportReaderScreen.kt:94` and `SummaryCard.kt:29`. The structural intent is preserved — the diff against POST-CHANGE below confirms exactly one new code literal (`"All values in range"`) and no other copy drift.

EXPECTED at baseline (corrected): **5 code-literal lines** (`AegisResponseBuilder.kt:42`, `LabRow.kt:195`, `ReportEmptyState.kt:92`, `StatusBadge.kt:45`, `SummaryCard.kt:78`, `SummaryCard.kt:112`) + **3 KDoc / comment references**. No `"All values in range"` literal (Plan 08-04 D-01c adds it).

## POST-CHANGE (Wave 1/2 complete — current main HEAD `9cfe6f6`)

Re-run the same three greps against the current worktree.

### androidTest scan — grep 1 (`Status: `)

```
(empty — 0 lines)
```

**Diff from baseline:** identical (0 → 0). No new `Status: $label` assertion landed in androidTest.

### androidTest scan — grep 2 (4-string OR in androidTest)

```
android/app/src/androidTest/java/com/aegis/health/ui/reportreader/ReportReaderScreenTest.kt:133:        // D-03 count framing: "2 of 4 values are outside the printed range".
android/app/src/androidTest/java/com/aegis/health/ui/reportreader/ReportReaderScreenTest.kt:135:        composeRule.onNodeWithText("2 of 4 values are outside the printed range").assertIsDisplayed()
android/app/src/androidTest/java/com/aegis/health/ui/reportreader/ReportReaderScreenTest.kt:136:        composeRule.onNodeWithText("Bring this to your clinician").assertIsDisplayed()
android/app/src/androidTest/java/com/aegis/health/ui/reportreader/ReportReaderScreenTest.kt:159:        composeRule.onNodeWithText("0 of 1 values are outside the printed range").assertIsDisplayed()
android/app/src/androidTest/java/com/aegis/health/ui/reportreader/ReportReaderScreenTest.kt:160:        composeRule.onNodeWithText("Bring this to your clinician").assertIsDisplayed()
android/app/src/androidTest/java/com/aegis/health/ui/reportreader/ReportReaderScreenTest.kt:180:        composeRule.onAllNodesWithText("Discuss with your doctor").assertCountEquals(0)
android/app/src/androidTest/java/com/aegis/health/ui/reportreader/ReportReaderScreenTest.kt:194:        composeRule.onNodeWithText("Discuss with your doctor").assertIsDisplayed()
android/app/src/androidTest/java/com/aegis/health/ui/reportreader/ReportReaderScreenTest.kt:216:        composeRule.onNodeWithText("Discuss with your doctor").assertIsDisplayed()
android/app/src/androidTest/java/com/aegis/health/ui/reportreader/ReportReaderScreenTest.kt:237:        composeRule.onNodeWithText("Discuss with your doctor").assertIsDisplayed()
```

**ACTUAL post-change:** 9 lines — **byte-identical** to baseline. Line numbers unchanged. Literals unchanged. **Zero copy regression on the androidTest surface.**

Because Plans 08-01..08-05 preserve every copy string byte-identical EXCEPT for the additive `"All values in range"` line (which appears only in main source, not androidTest), the androidTest surface sees zero copy regression. The TEST-FRAMEWORK-01 BOM-induced framework regression on the 9 instrumented tests is independent of Phase 8 — those tests remain Phase 10 P1.

### Main source scan — grep 3 (`ui/reportreader/`)

```
android/app/src/main/java/com/aegis/health/ui/reportreader/AegisResponseBuilder.kt:42:        "Bring this to your clinician to discuss any flagged values."
android/app/src/main/java/com/aegis/health/ui/reportreader/LabRow.kt:196:                        text = "Discuss with your doctor",
android/app/src/main/java/com/aegis/health/ui/reportreader/ReportEmptyState.kt:92:                text = "Discuss with your doctor",
android/app/src/main/java/com/aegis/health/ui/reportreader/ReportReaderScreen.kt:94: *   1. SummaryCard "Bring this to your clinician" → builder.build(report)
android/app/src/main/java/com/aegis/health/ui/reportreader/StatusBadge.kt:42:            .semantics { contentDescription = "Status: $label" },
android/app/src/main/java/com/aegis/health/ui/reportreader/SummaryCard.kt:29: *   1. Count headline: "X of N values are outside the printed range".
android/app/src/main/java/com/aegis/health/ui/reportreader/SummaryCard.kt:78:            text = "$outsideCount of $totalCount values are outside the printed range",
android/app/src/main/java/com/aegis/health/ui/reportreader/SummaryCard.kt:87:        // muted bodySmall "All values in range" line (honest affirmation, no celebration,
android/app/src/main/java/com/aegis/health/ui/reportreader/SummaryCard.kt:91:                text = "All values in range",
android/app/src/main/java/com/aegis/health/ui/reportreader/SummaryCard.kt:116:            text = "Bring this to your clinician",
```

**ACTUAL post-change:** 10 lines (+2 vs. baseline).

The added lines are:

1. **`SummaryCard.kt:91`** — `text = "All values in range",` — the new D-01c muted bodySmall affirmation line (Plan 08-04). This is the planned additive string.
2. **`SummaryCard.kt:87`** — the comment line documenting the new D-01c affirmation. Comment-only; matches the new literal's documentation thread.

Line-number drifts from baseline (all literal byte-identical):

- `LabRow.kt:195` → `LabRow.kt:196` (+1 line due to Plan 08-05 D-01d anchor comment insertion).
- `StatusBadge.kt:45` → `StatusBadge.kt:42` (−3 lines — Plan 08-02 removed the `Triple<>` `when` block; render block shrank by 4 lines, gained 2 helper-call lines = net −3 line drift up).
- `SummaryCard.kt:112` → `SummaryCard.kt:116` (+4 lines — Plan 08-04 D-01c added the 4-line affirmation block).
- `ReportEmptyState.kt:92` — unchanged.
- `AegisResponseBuilder.kt:42` — unchanged.
- `SummaryCard.kt:78` — unchanged.

## DIFF Summary

| String | Baseline locations | Post-change locations | Drift |
|--------|---------------------|------------------------|-------|
| `"Status: $label"` | StatusBadge.kt:45 | StatusBadge.kt:42 | line drift only — literal byte-identical (Plan 08-02 Triple removal) |
| `"Discuss with your doctor"` | LabRow.kt:195, ReportEmptyState.kt:92 | LabRow.kt:196, ReportEmptyState.kt:92 | LabRow line drift +1 from Plan 08-05 anchor comment — both literals byte-identical |
| `"Bring this to your clinician"` | SummaryCard.kt:112 | SummaryCard.kt:116 | line drift +4 only — literal byte-identical (Plan 08-04 D-01c expansion) |
| `"Bring this to your clinician to discuss any flagged values."` | AegisResponseBuilder.kt:42 | AegisResponseBuilder.kt:42 | none |
| `"$outsideCount of $totalCount values are outside the printed range"` | SummaryCard.kt:78 | SummaryCard.kt:78 | none |
| `"All values in range"` | (not present) | SummaryCard.kt:91 (literal) + SummaryCard.kt:87 (KDoc comment) | **NEW — additive per Plan 08-04 D-01c** |
| KDoc / comment references (ReportReaderScreen.kt:94, SummaryCard.kt:29) | 2 lines | 2 lines (line numbers unchanged) | none |
| androidTest copy assertions (grep 2) | 9 lines (8 assertions + 1 comment) | 9 lines (8 assertions + 1 comment) | **none — byte-identical** |
| androidTest `Status: ` assertions (grep 1) | 0 lines | 0 lines | none |

## Verdict

**SC #5 closure confirmed:** zero copy-string regression on the androidTest surface (grep 2 byte-identical, grep 1 unchanged at 0 lines); one additive string ("All values in range") introduced in main source per Plan 08-04 D-01c. No coupled-test-update required by any Wave 1/2 plan. M8 (Compose-UI-test-invalidation) mitigation honored — the structured diff above is the auditable record.

**The plan template's EXPECTED-row drift (grep 2 expected 0 vs. actual 9; grep 3 expected 4 vs. actual 8 at baseline)** is a planner-side undercount, not a Wave 1/2 plan overshoot. The structural diff (zero androidTest copy regression; exactly one new code literal) confirms the Phase 8 plans were faithful to their scope. The corrected counts are recorded in this document so a future verifier can reproduce both greps and validate the diff independently.
