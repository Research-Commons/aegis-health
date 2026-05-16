# Phase 8: ReportReader Visual Polish — Context

**Gathered:** 2026-05-16
**Status:** Ready for planning

<domain>
## Phase Boundary

Refine ReportReader's visual layer to demo-grade without scope creep into model, KB, or schema changes. Four mechanical-feeling requirements (POLISH-01..04) sit on top of an already-mostly-clean v1.0 foundation: `SummaryCard.kt:58-117` already uses `LocalAegisColors.current` with proper count framing and a fixed CTA; `StatusBadge.kt:34-39` already has the three-tier-plus-Review inline `when` block; `LabRow.kt:192` already gates the per-row "Discuss with your doctor" CTA on flagged rows.

After Phase 8 closes:

- `SummaryCard.kt` reads with refined visual hierarchy — the count headline is the visual hero (`titleLarge`, not `titleMedium`), spacing breathes (`xl` outer / `lg` between zones), and the all-clear (X=0) case carries a calm muted subline ("All values in range") instead of an invisible Spacer placeholder.
- The four ReportReader status codes (IN_RANGE / BORDERLINE / OUTSIDE_RANGE / unknown) resolve through a single `tokenForStatus(status: String, colors: AegisColors): Pair<Color, Color>` helper in `Theme.kt`, with a sibling `statusLabel(status: String): String`. `StatusBadge.kt:34-39`'s inline `when` is gone; the helpers are documented in `CONVENTIONS.md` as the new pattern.
- Per-row `LabRow` "Discuss with your doctor" CTAs render as `GhostButton` (subordinate); the `SummaryCard` "Bring this to your clinician" CTA stays `PrimaryButton` (loud action). Visual hierarchy is unambiguous; N2 CTA-fatigue mitigation extends v1.0 UI-04.
- All severity / status rendering in `ui/` (excluding `ui/theme/`) resolves through `LocalAegisColors.current.*` tokens. Two new tokens land in `AegisColors`: `onWarmSurface` (light `0xFF1A1816`, dark = `onSurface`) and `onWarmSurfaceMuted` (light `0xFF3B3733`, dark = `onSurfaceMuted`), letting the 9 existing `if (colors.isDark) X else Color(0xFF…)` overrides in `DeferralBanner`, `OcrFailBanner`, `SeverityCard`, `ConsentReaderScreen`, `DeferralScreen`, and `HealthPartnerScreen` collapse to a single token reference.
- Phase-close grep gate `grep -rEn 'Color\(0x' android/app/src/main/java/com/aegis/health/ui/ | grep -v ui/theme/` returns empty. C3 severity-color-drift mitigation locked.

**Concretely, Phase 8 lands:**

- `ui/reportreader/SummaryCard.kt` edited: `titleMedium` → `titleLarge` on the count headline; outer padding `lg` → `xl`; inter-zone Spacers `md` → `lg`; the X=0 invisible Spacer replaced with a muted `bodySmall` "All values in range" line.
- `ui/reportreader/LabRow.kt` edited: the per-row `PrimaryButton` at lines ~194-198 swaps to `GhostButton` (same `text`, same `onClick`, same `fillMaxWidth`); CTA visibility logic (`status != "IN_RANGE"`) is unchanged — flagged + unknown rows still get the CTA.
- `ui/theme/Theme.kt` gains two new top-level helpers: `tokenForStatus(status: String, colors: AegisColors): Pair<Color, Color>` (returns `(bg, fg)`) and `statusLabel(status: String): String`. Both use a strict-case `when` over the four canonical status codes with an `else` fall-back to IN_RANGE tokens / "In range" label.
- `ui/reportreader/StatusBadge.kt:34-39` migrates to consume the helpers: `val (bg, fg) = tokenForStatus(status, colors); val label = statusLabel(status)`. The inline `when` block is gone. Other consumers (chip strip in `SummaryCard`, future row tinting) call the helpers directly without going through `StatusBadge`.
- `ui/theme/Color.kt` gains two new `AegisColors` fields: `onWarmSurface: Color` and `onWarmSurfaceMuted: Color`. Light theme: `0xFF1A1816` / `0xFF3B3733`. Dark theme: aliased to the existing `AegisOnSurfaceDark` / `AegisOnSurfaceMutedDark`. Both theme factories in `Color.kt` updated.
- `ui/common/DeferralBanner.kt:72,78`, `ui/common/OcrFailBanner.kt:58`, `ui/common/SeverityCard.kt:110,118`, `ui/consentreader/ConsentReaderScreen.kt:417`, `ui/deferral/DeferralScreen.kt:112,119`, `ui/healthpartner/HealthPartnerScreen.kt:599` — each `if (colors.isDark) colors.onSurface(Muted) else Color(0xFF…)` conditional collapses to `colors.onWarmSurface` or `colors.onWarmSurfaceMuted`. The FQN form `androidx.compose.ui.graphics.Color(0xFF…)` in ConsentReader + HealthPartner goes away with the literal.
- `.planning/codebase/CONVENTIONS.md` gains a new subsection documenting the `tokenForStatus` / `statusLabel` pattern as the single source of mapping for ReportReader status codes (mirrors the `severityColor(severity, colors)` precedent at `Theme.kt:100-112`).

**Out of scope for Phase 8:**

- POLISH-05 (P1) — Reference-range visual bar (Apple Health idiom) on outside-range / borderline rows. Lives in `RangeBar.kt`. Phase 10 stretch if calendar slack permits.
- Animation polish for row state changes when a status badge flips mid-stream (not in any POLISH-XX scope).
- `ui/theme/Color.kt` token-system overhaul (e.g., migrating from `Color(0xFF…)` definitions to Material color resources). The current token system stays — only `onWarmSurface` / `onWarmSurfaceMuted` are additive.
- ConsentReader, HealthPartner, DrugSafe visual polish beyond the hex-literal migration. Phase 9 owns Home + Startup polish; Phase 8 only touches non-ReportReader UI as a side effect of cleaning up `Color(0x…)` literals.
- TEST-FRAMEWORK-01 — the Compose UI instrumented-test framework regression on SM-S918B + BOM 2026.05.00 stays Phase 10 P1. Phase 8 verifies through grep gates + on-device visual smoke, not androidTest.
- Any model, KB, schema, training, RL, export, or build-system change. v1.1 visual polish only.

</domain>

<decisions>
## Implementation Decisions

### POLISH-01 — Summary card hierarchy refinement

- **D-01a (Headline scale):** `SummaryCard.kt:79` count headline migrates from `titleMedium` → `titleLarge`. One Material3 step up; the "X of N values are outside the printed range" line becomes the card's visual hero without competing with the chip strip or CTA below. `headlineSmall` was considered and rejected as too "shouty" against the calm-by-default mandate.
- **D-01b (Spacing):** Outer padding `AegisSpacing.lg` (16dp) → `AegisSpacing.xl` (20dp); inter-zone Spacers `AegisSpacing.md` (12dp) → `AegisSpacing.lg` (16dp). Adds ~16dp vertical card height (still within an SM-S918B viewport). Reinforces calm-by-default through breathing room. Tighter variants (`md` outer / `sm` between) were rejected as compressing the executive-summary feel.
- **D-01c (All-clear copy):** The X=0 case at `SummaryCard.kt:90` replaces the hard-coded `Spacer(Modifier.height(28.dp))` with a muted `Text("All values in range", style = bodySmall, color = colors.onSurfaceMuted)`. Honest affirmation, no `✓`, no celebratory copy, no chip — respects v1.0 D-04 ("no good/bad copy"). The Spacer-only invisible-slot variant was rejected as silent and confusing on the rows-already-show-no-flag case.
- **D-01d (Per-row CTA hierarchy):** `LabRow.kt:194-198` `PrimaryButton` migrates to `GhostButton`. SummaryCard's clinician CTA stays `PrimaryButton`. Hierarchy: card-level loud, per-row subordinate. The "both stay PrimaryButton" status quo was rejected as visual redundancy + N2 CTA-fatigue. The text-link variant ("Discuss →") was rejected as discoverability-dipping.

### POLISH-02 — `tokenForStatus()` helper shape

- **D-02a (Helper split):** `tokenForStatus(status: String, colors: AegisColors): Pair<Color, Color>` returns `(bg, fg)`. A sibling `statusLabel(status: String): String` returns the human label. Two tiny helpers mirror `Theme.kt:100-112`'s `severityColor(sev, colors)` + `severityBackgroundColor(sev, colors)` split. SC #2 mandates `Pair<Color, Color>` literally; the data-class variant was rejected as SC drift; the "label stays in StatusBadge" variant was rejected because the chip strip in `SummaryCard` (and future row-tint consumers) also needs the label.
- **D-02b (Signature):** `status: String` non-null (matches SC literal). `EvaluatedRow.status` is non-null in the Phase 3 schema. Defensive nullability would have added an extra fall-through case without semantic value.
- **D-02c (Unknown / unrecognized strings):** Fall back to IN_RANGE tokens (`surfaceAlt` + `onSurfaceMuted`, label "In range"). Matches the current `StatusBadge.kt:38` `else →` behavior verbatim. Calm-by-default: unparseable status defaults to neutral, never red. The "fall back to Review" alternative was rejected as it lights up a chip on schema drift; the "throw" alternative was rejected as too brittle for model-generated outputs.
- **D-02d (Casing):** Strict case match — no `.uppercase()` normalization. Canonical strings are `IN_RANGE` / `BORDERLINE` / `OUTSIDE_RANGE` / `unknown` (note: "unknown" is intentionally lowercase per the Phase 3 schema). Anything else hits the IN_RANGE fall-back. Drift detection happens at the grep / log layer, not silently in the helper.

### POLISH-03 — Per-row "Discuss with your doctor" CTA visibility

- **D-03a (Visibility logic):** `LabRow.kt:192` keeps `if (row.status != "IN_RANGE")` — the CTA renders on OUTSIDE_RANGE, BORDERLINE, AND unknown rows. ROADMAP SC #3 says "OUTSIDE_RANGE and BORDERLINE rows only"; this discussion reads the SC as "IN_RANGE never gets CTA", not as "only these two ever". Rationale: unknown rows (defer_reason != null) are exactly the rows where the system can't evaluate the value — hiding the deferral action on rows that need clinician interpretation contradicts the deferral mandate.
- **D-03b (Copy):** Single CTA string "Discuss with your doctor" across all flagged + unknown rows. Single source of truth at `LabRow.kt:195`. Per-status copy variants (e.g., "Ask your doctor about this value" for unknown) were considered and rejected as M8-test-invalidation risk + maintenance burden for marginal honesty gain.
- **D-03c (Variant unity):** All non-IN_RANGE rows use `GhostButton` (combined with D-01d). No three-tier visual ladder (e.g., GhostButton for flagged, text-link for unknown). Visual consistency wins; discoverability for unknown rows is preserved.

### POLISH-04 — Hex literal migration

- **D-04a (Migration approach):** Add two new `AegisColors` tokens — `onWarmSurface` and `onWarmSurfaceMuted` — and collapse all 9 light-mode-override conditionals (`if (colors.isDark) colors.onSurface(Muted) else Color(0xFF…)`) to a single token reference. The "collapse to existing onSurface + update light values" alternative was rejected because it changes the ink on every light-mode surface, not just warm cards — global visual regression risk. The "drop the warm override entirely" alternative was rejected as a deliberate visual regression.
- **D-04b (Token naming):** `onWarmSurface` (light `0xFF1A1816`, dark = aliased to `AegisOnSurfaceDark`) and `onWarmSurfaceMuted` (light `0xFF3B3733`, dark = aliased to `AegisOnSurfaceMutedDark`). Mirrors the `on{Surface}` Material convention; pairs naturally with the existing `surfaceAlt` token. `warmInk` / `warmInkMuted` and `onSurfaceAlt` / `onSurfaceAltMuted` were considered; the former drifts from the `on{Surface}` convention, the latter implies surfaceAlt-only when usage spans multiple warm surfaces.
- **D-04c (Grep gate scope):** Phase-close grep gate is `grep -rEn 'Color\(0x' android/app/src/main/java/com/aegis/health/ui/ | grep -v ui/theme/` and must return empty. `ui/theme/Color.kt` legitimately defines tokens via `Color(0xFF…)` (`AegisCoral`, `AegisChip`, etc.) and stays out of the gate. Reading ROADMAP SC #4 literally would have forced a token-system overhaul (XML resources or similar) — out of scope and unnecessary scope creep.
- **D-04d (Verification strategy):** JVM grep gate at phase close + on-device visual smoke by the user on SM-S918B / RZCW70XRTGE. No new androidTest until Phase 10 P1 fixes TEST-FRAMEWORK-01 (Compose BOM 2026.05.00 broke the framework). A JVM source-scan regression test (permanent grep at test time) was considered; deferred unless a regression actually fires post-Phase-8.

### Claude's Discretion

- **CONVENTIONS.md subsection placement / wording.** Adding the new `tokenForStatus` / `statusLabel` documentation block to `.planning/codebase/CONVENTIONS.md` under the existing `## Jetpack Compose Conventions` heading (or a new "## Color Tokens" subsection if more appropriate). Planner picks the exact placement.
- **`tokenForStatus` / `statusLabel` placement within `Theme.kt`.** Group near the existing `severityColor(severity: Int, colors: AegisColors)` family at `Theme.kt:100-112` (logical sibling), or in a new clearly-named region. Planner picks.
- **CTA padding / icon trailing on the migrated `GhostButton`** in `LabRow.kt`. The current `PrimaryButton` is plain text-only with `fillMaxWidth()`; whether the migrated `GhostButton` keeps `fillMaxWidth()` or shrinks to wrap-content with an `Icons.AutoMirrored.Filled.ChatBubbleOutline` leading icon is a small visual call left to planner.
- **Test fixture parity for the helper.** Whether `Theme.kt`'s new helpers ship with a small JVM unit test (4 cases — one per canonical status code, plus an "unrecognized" fall-back assertion) is a planner call. Useful regression guard but not required by any SC.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### v1.1 Roadmap & Requirements
- `.planning/ROADMAP.md` §428-448 — Phase 8 goal, depends-on, requirements POLISH-01..04, success criteria #1-#5
- `.planning/REQUIREMENTS.md` §42-47 — POLISH-01..05 line-item requirements (POLISH-05 P1 deferred to Phase 10)
- `.planning/REQUIREMENTS.md` §89-90 — F4 reference-range visual bar deferred-ideas entry (POLISH-05 P1, Phase 8 close stretch, else v2)

### v1.0 ReportReader Anchors
- `android/app/src/main/java/com/aegis/health/ui/reportreader/SummaryCard.kt` — current SummaryCard implementation (POLISH-01 target)
- `android/app/src/main/java/com/aegis/health/ui/reportreader/StatusBadge.kt:34-39` — inline `when` block migrating to `tokenForStatus` (POLISH-02 target)
- `android/app/src/main/java/com/aegis/health/ui/reportreader/LabRow.kt:192-199` — per-row CTA visibility + button variant (POLISH-03 target)
- `android/app/src/main/java/com/aegis/health/ui/theme/Theme.kt:100-112` — `severityColor(severity, colors)` / `severityBackgroundColor(severity, colors)` precedent pattern for the new String-keyed sibling
- `android/app/src/main/java/com/aegis/health/ui/theme/Color.kt:76-133` — `AegisColors` data class + light/dark factory builders (POLISH-04 target for the two new tokens)

### Prior Phase Decisions That Carry Forward
- `.planning/phases/03-ui-without-model/03-CONTEXT.md` — D-01 calm-by-default mandate ("IN_RANGE = neutral surfaceAlt + onSurfaceMuted, no color"); D-03 count framing; D-04 chip strip = OUTSIDE_RANGE only + fixed CTA text
- `.planning/phases/07-toolstepper-ui-latency-honest-skeletons/07-CONTEXT.md` §decisions — D-03c calm-tone warning palette (`warningBg`/`warningFg`); severity tokens reserved for actual data severity, not decorative warnings; hex literal anti-pattern enforcement
- `.planning/STATE.md` Locked decisions §174-183 — TEST-FRAMEWORK-01 carry-over (Phase 10 P1); SFT v4 frozen, no retrain in v1.1; AegisResponse schema frozen

### Conventions / Codebase Maps
- `.planning/codebase/CONVENTIONS.md` — existing Jetpack Compose conventions (target for the new `tokenForStatus` / `statusLabel` documentation block)
- `.planning/codebase/STRUCTURE.md` — `ui/reportreader/` and `ui/theme/` module structure
- `android/app/src/main/java/com/aegis/health/ui/theme/Dimens.kt:8-13` — `AegisSpacing.{xs, sm, md, lg, xl}` (verified `xl = 20.dp` exists for the D-01b spacing decision)

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets

- **`AegisSpacing.xl = 20.dp`** at `ui/theme/Dimens.kt:13` — verified to exist; D-01b loosens outer padding to this value.
- **`AegisColors` data class** at `ui/theme/Color.kt:76` — additive new fields (`onWarmSurface`, `onWarmSurfaceMuted`) extend the existing token surface without breaking the v1.0 token contract.
- **Sibling helper precedent** at `Theme.kt:100-112` — `severityColor(severity: Int, colors: AegisColors): Color` + `severityBackgroundColor(severity: Int, colors: AegisColors): Color` are the exact shape the new String-keyed `tokenForStatus` / `statusLabel` will mirror.
- **`GhostButton`** in `ui/common/` (referenced via D-01d) — existing variant. Planner verifies signature matches `PrimaryButton`'s `(text, onClick, modifier)` so the swap is a one-line edit at `LabRow.kt:194-198`.
- **`AegisChip`** at `ui/common/AegisChip.kt` — the SummaryCard chip strip (`SummaryCard.kt:97-102`) already uses this; unaffected by Phase 8.

### Established Patterns

- **Calm-by-default**: IN_RANGE rows render with `surfaceAlt` + `onSurfaceMuted` — no warm tones, no chips, no CTAs. Only flagged + unknown rows light up. Phase 8 preserves this through D-02c (unknown strings fall back to IN_RANGE tokens) and D-03c (GhostButton variant unity).
- **Token-only colors in `ui/`** (excluding `ui/theme/`): everywhere except theme definitions reads colors from `LocalAegisColors.current.*`. The 9 existing hex literals are the last violations; D-04a closes that gap permanently.
- **Strict-case status string handling**: `EvaluatedRow.status` is one of four canonical strings — case-mutated drift is caught at extraction time, not silently absorbed. D-02d preserves this.
- **`if (colors.isDark) X else Y` light-mode override pattern**: the entire reason the 9 hex literals exist. After D-04a, this pattern disappears from `ui/` non-theme files entirely; warm-card sites just reference `onWarmSurface` / `onWarmSurfaceMuted`.

### Integration Points

- **`StatusBadge.kt`** — the original consumer. Migrates to `val (bg, fg) = tokenForStatus(...); val label = statusLabel(...)`. Same render shape; the `when` block at lines 34-39 is gone after the swap.
- **`SummaryCard.kt` chip strip** at lines 96-103 — currently uses `colors.sevCritFg` for the chip tint. Not a `tokenForStatus` consumer (chips are OUTSIDE_RANGE-only per v1.0 D-04, so single tint is correct). Phase 8 doesn't touch this.
- **`LabRow.kt` row tint** — if Phase 8 (or a future phase) adds row-background tinting per status, `tokenForStatus(status, colors).first` is the bg source. Phase 8 itself does not add row tinting.
- **The 7 hex-literal files** (`DeferralBanner`, `OcrFailBanner`, `SeverityCard`, `ConsentReaderScreen`, `DeferralScreen`, `HealthPartnerScreen`) — each gets exactly one conditional collapsed to one or two token references. No structural changes to those files; the diff is local and per-line.

</code_context>

<specifics>
## Specific Ideas

- **Hex values are deliberate, not drift.** The 9 hex literals carry distinct warm-tinted inks (`0xFF1A1816` vs the canonical `0xFF0A0A0A`; `0xFF3B3733` vs the canonical `0xFF5A5A5A`). These are intentional design choices for warm-card surfaces, not bugs. The migration preserves them in light-mode while consolidating the pattern under named tokens. Verified inline at Color.kt:22-23 vs the hex-literal grep outputs.
- **`onWarmSurface` dark-mode value aliases to `AegisOnSurfaceDark`.** No special warm-mode treatment in dark theme — current code uses `colors.onSurface` in the dark branch of every `isDark` conditional, so the new token's dark value preserves that behavior byte-identical.
- **`unknown` status is lowercase.** Not a typo. `EvaluatedRow.status` is `"unknown"` (vs `"IN_RANGE"` / `"BORDERLINE"` / `"OUTSIDE_RANGE"` uppercase). D-02d preserves this asymmetry; the helper's `when` matches the actual schema strings.
- **The grep gate exclusion (`grep -v ui/theme/`) is intentional and acceptable.** Token definitions need hex literals; non-token UI files don't. This is the established convention for color-token systems (see Material Design 3 reference) — the gate enforces "no hex outside the token layer".

</specifics>

<deferred>
## Deferred Ideas

- **POLISH-05 (P1) — Reference-range visual bar (Apple Health idiom).** Already captured in `REQUIREMENTS.md:47` and `:89-90`. Phase 10 stretch if calendar slack permits; else v2. Not in Phase 8 scope; planner should not pull it in.
- **Mid-stream status badge animation.** When a row's status flips during the synthesis turn (e.g., model updates a borderline to outside-range), does the badge animate the color/label transition? Not in any POLISH-XX scope. Future polish phase or v2.
- **`ui/theme/` token-system overhaul.** Replacing `Color(0xFF…)` definitions in `Color.kt` with XML resources or a separate `ColorBuilder` mechanism. Big refactor, not justified by Phase 8 scope. v2 candidate if the token system ever needs to support a third theme variant.
- **JVM source-scan regression test** for `Color(0x` patterns in `ui/` (excluding `ui/theme/`). Stronger than a phase-close grep gate — permanent guard at test time. Captured here as a deferred candidate; revisit if a hex-literal regression actually slips through to production after Phase 8.
- **Per-status CTA copy variants** (e.g., "Ask your doctor about this value" for unknown rows). Considered and rejected during D-03b. Could revisit if user research surfaces confusion with the single-string approach.

</deferred>

---

*Phase: 8-reportreader-visual-polish*
*Context gathered: 2026-05-16*
