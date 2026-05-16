# Phase 8: ReportReader Visual Polish - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-05-16
**Phase:** 8-reportreader-visual-polish
**Areas discussed:** Summary card hierarchy (POLISH-01), `tokenForStatus()` helper shape (POLISH-02), Per-row CTA visibility (POLISH-03), Hex literal migration (POLISH-04)

---

## Summary card hierarchy (POLISH-01)

### Q1 — Count headline typography

| Option | Description | Selected |
|--------|-------------|----------|
| Bump to titleLarge (Recommended) | One step up. Reads as the card's hero without competing with the chip strip + CTA below. Matches Material3 card-header convention. | ✓ |
| Stay at titleMedium | Status quo. Minimal change; relies on the count being the only headline on the card to do the visual work. | |
| Bump to headlineSmall | Two steps up. Strong hero feel but risks the card looking 'shouty' — may fight the calm-by-default mandate. | |

### Q2 — Outer + inter-zone spacing

| Option | Description | Selected |
|--------|-------------|----------|
| Loosen to xl outer / lg between zones (Recommended) | More breathing room. Reinforces 'calm-by-default'. Adds ~16dp vertical height to the card — still within an Android phone fold. | ✓ |
| Keep current lg outer / md between | Status quo. Card stays compact, more rows visible above the fold. | |
| Tighten to md outer / sm between | Denser. Card becomes a smaller header band. Loses the 'this is the executive summary' visual weight. | |

### Q3 — All-clear (X=0) state

| Option | Description | Selected |
|--------|-------------|----------|
| Neutral subline text 'All values in range' (Recommended) | Subdued bodySmall in onSurfaceMuted. Honest affirmation without celebration. No emoji, no ✓. Respects 'no good/bad copy' mandate. | ✓ |
| Keep the invisible spacer | Card stays silent in the all-clear case. Strict interpretation of D-04 — the CTA below carries the message. | |
| Muted 'All in range' chip / pill | Visible affirmation as a soft pill. Risk: reads as a positive signal where the spec says no good/bad framing. | |

### Q4 — CTA visual hierarchy

| Option | Description | Selected |
|--------|-------------|----------|
| Summary stays PrimaryButton; per-row becomes GhostButton (Recommended) | Clear hierarchy: the card-level CTA is the loud action; per-row CTAs are subordinate. Keeps the existing GhostButton component, no new variants needed. | ✓ |
| Both stay PrimaryButton | Status quo. Strong CTA visibility on every flagged row. Trade-off: visual redundancy and decision fatigue (N2 mitigation territory). | |
| Summary stays PrimaryButton; per-row becomes inline text-link 'Discuss →' | Maximum subordination of per-row CTAs. Risk: discoverability suffers — text links read less actionable than buttons. | |

**User's choice:** All four "Recommended" options.
**Notes:** Clean recommendations-aligned set. No follow-up clarifications needed.

---

## `tokenForStatus()` helper shape (POLISH-02)

### Q1 — Helper return type

| Option | Description | Selected |
|--------|-------------|----------|
| Pair<Color, Color> + separate statusLabel(status) helper (Recommended) | Two tiny helpers. Honors SC literal verbatim. Composable for non-StatusBadge consumers that don't need the label (chip strip, row tint). Mirrors Theme.kt's existing severityColor + severityBackgroundColor pattern. | ✓ |
| Return a StatusTokens data class with bg, fg, label | One when, broader contract. Slight SC drift (SC says Pair). Callers always get all three even when they only need colors. | |
| Pair<Color, Color> only; StatusBadge keeps its own label when block inline | Strictest SC reading. Status → label stays a presentation concern of StatusBadge, not Theme. Downside: 'Review' / 'In range' wording lives in the badge file. | |

### Q2 — Helper signature

| Option | Description | Selected |
|--------|-------------|----------|
| status: String non-null (Recommended) | Matches SC literal `tokenForStatus(status: String, colors: AegisColors)`. EvaluatedRow.status is non-null in the model (Phase 3 schema). Defensive null-handling lives at the call site if needed. | ✓ |
| status: String? nullable | Defensive against future schema changes. Adds one more null fall-through case to the when. Cost: SC drift. | |

### Q3 — Unknown / unrecognized status handling

| Option | Description | Selected |
|--------|-------------|----------|
| Fall back to IN_RANGE tokens (Recommended) | Matches current StatusBadge.kt:38 `else → (surfaceAlt, onSurfaceMuted, 'In range')`. Calm-by-default: any unparseable status defaults to neutral, never red. Safest behavior. | ✓ |
| Fall back to 'unknown' / Review tokens | Treat unrecognized strings as deferrable. Risk: garbage data lights up a 'Review' chip that the user might think is meaningful. | |
| Throw IllegalArgumentException | Strict: contract violation → crash. Catches schema drift fast in dev but is unforgiving in production with model-generated outputs. | |

### Q4 — Case sensitivity

| Option | Description | Selected |
|--------|-------------|----------|
| Strict case: exact match on canonical strings (Recommended) | Matches current StatusBadge behavior verbatim. Anything else falls through to IN_RANGE per the unknown-handling decision above. No silent normalization. Drift-detector grep gates have a stable surface. | ✓ |
| Case-insensitive (status.uppercase()) | Lenient. 'in_range' or 'In_Range' would resolve to IN_RANGE tokens. Risk: hides upstream casing bugs that should be caught. | |

**User's choice:** All four "Recommended" options.
**Notes:** No follow-up clarifications. The non-uppercase "unknown" canonical string was flagged as schema-correct (Phase 3) — not a bug.

---

## Per-row CTA visibility (POLISH-03)

### Q1 — CTA visibility on `unknown` rows

| Option | Description | Selected |
|--------|-------------|----------|
| Show CTA on unknown rows too — keep current `status != IN_RANGE` logic (Recommended) | Unknown rows have defer_reason — they're literally rows that need clinician interpretation. Hiding the CTA on them would tell the user 'no action needed' on the rows the system itself can't evaluate. Matches the spirit of the deferral mandate. | ✓ |
| Strict SC: only OUTSIDE_RANGE + BORDERLINE get CTA; unknown rows hide it | Literal SC reading. Trade-off: unknown rows are the exact case where the user has the LEAST information — hiding the CTA there contradicts the deferral story. | |
| Show CTA on unknown but with different copy ('Bring this to your clinician for review') | Split label per status. More work, more copy variants, more test churn. Risks the M8 Compose-test-invalidation pitfall (SC #5). | |

### Q2 — CTA wording on unknown rows

| Option | Description | Selected |
|--------|-------------|----------|
| Same copy: 'Discuss with your doctor' (Recommended) | One CTA string across all flagged + unknown rows. Single source of truth in LabRow.kt:195. Matches the per-row CTA contract — the user's action ('have a conversation') is identical regardless of why the row was flagged. | ✓ |
| Unknown rows use different copy ('Ask your doctor about this value') | Subtle distinction. Reads marginally more honest because we don't know the value is bad — just unparseable. Cost: two strings to maintain + test. | |

### Q3 — Variant button style on flagged vs unknown rows

| Option | Description | Selected |
|--------|-------------|----------|
| All non-IN_RANGE rows use GhostButton (Recommended) | One variant across all flagged + unknown rows. Visual consistency. Avoids a third button style on the screen. | ✓ |
| OUTSIDE_RANGE + BORDERLINE = GhostButton; unknown = inline text-link | Three-tier visual ladder. Risk: discoverability dip on the rows that need the user's attention most. | |

**User's choice:** All three "Recommended" options.
**Notes:** SC #3 read as "IN_RANGE never gets CTA", not "only these two ever get CTA" — kept the more permissive current logic.

---

## Hex literal migration (POLISH-04)

### Q1 — Migration approach

| Option | Description | Selected |
|--------|-------------|----------|
| Add two new AegisColors tokens for warm-card ink (Recommended) | New tokens `onWarmSurface` (light 0xFF1A1816, dark = onSurface) + `onWarmSurfaceMuted` (light 0xFF3B3733, dark = onSurfaceMuted). All 9 sites collapse to a single token reference — no conditional. Preserves the intentional warm-mode ink, drops the hex literal. Mirrors the existing token pattern in Color.kt. | ✓ |
| Collapse to existing onSurface tokens + update light-mode token values | Replace all 9 conditionals with plain `colors.onSurface` / `colors.onSurfaceMuted`, and change AegisOnSurfaceLight to 0xFF1A1816 + AegisOnSurfaceMutedLight to 0xFF3B3733 in Color.kt. Trade-off: changes the ink on EVERY light-mode surface. Risks a global visual delta. | |
| Drop the warm-mode override entirely; both modes use onSurface | Strip the conditional and just use `colors.onSurface` / `colors.onSurfaceMuted`. Loses the deliberate warm-card ink design. Fastest but a visual regression. | |

### Q2 — Token naming

| Option | Description | Selected |
|--------|-------------|----------|
| onWarmSurface / onWarmSurfaceMuted (Recommended) | Mirrors `onSurface` / `onSurfaceMuted`. 'Warm' qualifier signals the intent. Pairs naturally with `surfaceAlt` (the warm surface token). Discoverable in IDE autocomplete next to existing onSurface tokens. | ✓ |
| warmInk / warmInkMuted | Shorter. Less consistent with the `on{Surface}` convention. Could read as a paint color rather than a text-on-surface token. | |
| onSurfaceAlt / onSurfaceAltMuted | Pairs explicitly with `surfaceAlt`. Risk: implies these are ONLY for surfaceAlt contexts, but the actual usage spans several warm surfaces. | |

### Q3 — Grep gate scope

| Option | Description | Selected |
|--------|-------------|----------|
| Scope the gate to exclude ui/theme/ (Recommended) | The gate's spirit is 'no NEW hex literals in non-theme UI code'. Token definitions in ui/theme/Color.kt + Theme.kt are legitimate and must stay literal. Plan codifies `grep -rEn 'Color\(0x' ui/ | grep -v ui/theme/` as the actual gate. | ✓ |
| Strict SC: gate covers all of ui/ including ui/theme/ | Forces ui/theme/Color.kt to use a different mechanism. Big refactor, scope creep risk — the v1.0 token system is established and tested. | |
| Strict SC + ui/theme/ files are 'token definitions, not literals' — grep with a regex that detects assignment to a `Color` literal binding vs an inline use | Smart filter via regex. Costs cleverness budget and might miss edge cases. | |

### Q4 — Migration testing strategy

| Option | Description | Selected |
|--------|-------------|----------|
| JVM-only: grep gate + visual smoke on-device by hand (Recommended) | Grep gate fires green at phase close (machine-checkable). On-device visual confirmation by you on SM-S918B — pull each affected screen up and confirm the warm-card ink looks right. No new androidTest until Phase 10 P1 fixes the framework. | ✓ |
| Land @Ignore'd androidTest for token usage now | Lands the verification surface even if it doesn't execute. Phase 10 P1 framework migration un-Ignores it for free. Cost: writing tests against a broken framework with limited verification value right now. | |
| JVM source-scan test (regression gate at compile + test time) | Pure JVM test that greps the UI tree for `Color\(0x` patterns and fails the build. Permanent regression guard. Heavier mechanism than a phase-close grep, but lasts forever. | |

**User's choice:** All four "Recommended" options.
**Notes:** JVM source-scan regression test deferred as a candidate (captured in CONTEXT.md `<deferred>`) — revisit if a hex-literal regression actually slips through to production.

---

## Claude's Discretion

The following decisions were left to planner / researcher judgement (captured in CONTEXT.md `<decisions> → Claude's Discretion`):

- CONVENTIONS.md subsection placement / wording for the new `tokenForStatus` / `statusLabel` documentation block.
- `tokenForStatus` / `statusLabel` placement within `Theme.kt` (group near `severityColor` family vs new region).
- Whether the migrated `GhostButton` in `LabRow.kt` keeps `fillMaxWidth()` or shrinks to wrap-content with an icon.
- Whether to ship a small JVM unit test for the new helpers (4 cases: one per canonical status code + unrecognized fall-back).

## Deferred Ideas

Captured in CONTEXT.md `<deferred>`:

- POLISH-05 (P1) — Reference-range visual bar. Phase 10 stretch or v2.
- Mid-stream status badge animation when a row's status flips. Future polish phase / v2.
- `ui/theme/` token-system overhaul (XML resources or `ColorBuilder` mechanism). v2 candidate.
- JVM source-scan regression test for `Color(0x` patterns outside `ui/theme/`. Revisit if a regression slips through.
- Per-status CTA copy variants. Revisit if user research surfaces confusion.
