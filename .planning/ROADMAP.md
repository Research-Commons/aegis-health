# Roadmap: Aegis Health — ReportReader Milestone

**Created:** 2026-05-13
**Granularity:** coarse (5 phases)
**Core Value:** Patients and caregivers can understand and act on confusing medical artifacts — drug regimens, consent forms, preventive-care guidance, and now lab reports — entirely offline, with every medical claim grounded in a verifiable public-domain citation and any uncertainty surfaced as deferral to a clinician.

The 5-phase structure inverts the usual "build the model first" instinct. The architecture researcher's headline insight — *Kotlin pre-parses everything; the model writes one paragraph* — means most of the safety surface is deterministic Kotlin code testable against fixture PDFs without ever loading the LLM. This roadmap reflects that: Phase 1 lands all the foundation off-Android, Phase 2 builds the entire pipeline minus the model, Phase 3 produces a demoable artifact even if the model integration slips, Phase 4 is the long-pole model integration, and Phase 5 covers eval, conditional retraining, and submission polish.

## Phases

- [x] **Phase 1: Foundation — KB, Tools, Regulatory, Corpus** — âœ“ 2026-05-13 — Heavy off-Android phase landing the KB schema, two new Python tools, REGULATORY.md, multi-vendor PDF corpus, Python reference parser, and Phase 1 spike outcomes (9 plans executed + 01-09 documented-deferral closure)
- [ ] **Phase 2: Kotlin Pre-Parse Pipeline (No Model)** — Full PDF → PreparsedReport pipeline + Kotlin tool ports + deterministic RangeEvaluator, tested against the fixture corpus
- [ ] **Phase 3: UI Without Model (Demoable Without LLM)** — 4th HomeScreen tile + ReportReaderScreen + rows table + per-row deferral + visual range bar + "What this is NOT" card, rendering the Kotlin-only pipeline output
- [ ] **Phase 4: Model Integration** — Long-pole synthesis turn: SystemPrompts + fast-path + enforceModeContract + on-device smoke against all 5 fixtures
- [ ] **Phase 5: Eval, Optional Retraining, Submission Polish** — â‰¥20 anchor cases, three-stage eval framework, four Group C metrics, conditional SFT retraining, REGULATORY.md final audit, demo recording

## Phase Details

### Phase 1: Foundation — KB, Tools, Regulatory, Corpus

**Goal**: Every piece that doesn't touch the Android app or the model lands before any implementation work depends on it. KB schema, curated public-domain ranges, both new Python tools with pytest coverage, REGULATORY.md, the shared Python/Kotlin extraction spec, the multi-vendor PDF corpus with ground-truth JSON, the Python reference parser, and the two Phase 1 spikes (PdfBox-Android coordinate fidelity + SFT-generalization) all resolved.

**Depends on**: Nothing (first phase).

**Requirements**:
- SAFETY-03 (system-prompt rule that ranges require explicit `source` — locks the contract before model work)
- SAFETY-04 (REGULATORY.md drafted before any external demo)
- EVAL-05 (regression-guard baseline captured on existing 55 anchors so later phases have a known-good reference)

**Success Criteria** (what must be TRUE):
  1. `make kb && make tools-test` is green with the new `lab_reference_ranges` / `clinical_thresholds` / `critical_values` / `reference_ranges_pediatric` / `reference_ranges_pregnancy` tables populated and both new tools (`lookup_lab_reference_range`, `explain_lab_test`) returning correct JSON for the anchor lab tests (LDL, HDL, total cholesterol, A1C, glucose, hemoglobin, WBC, platelets, creatinine, eGFR, ALT, AST)
  2. `REGULATORY.md` is committed at repo root, captures the SaMD general-wellness positioning, HIPAA non-applicability, and 21st Century Cures Act framing, and is ready to ship before any external demo
  3. Five PHI-redacted vendor PDFs (LabCorp, Quest, one hospital LIS, Mayo, one urgent-care chain) live in the fixture corpus with hand-curated ground-truth JSON, each labeled with public-domain redistribution status
  4. The PdfBox-Android coordinate-fidelity spike has produced a go/no-go decision on the column-clustering approach across all 5 vendor formats, including documented `PDFBoxResourceLoader.init` and R8/ProGuard requirements
  5. The SFT-generalization spike (hand-built `PreparsedReport` JSON fed to SFT v4) has produced a documented yes/no on whether Phase 5 SFT retraining is mandatory or optional
  6. A shared Python/Kotlin extraction-spec document defines the `PreparsedReport` JSON schema both languages must conform to, with one reference Python parser and one matching expected-output JSON per fixture

**Plans**: 10 plans across 4 waves

**Wave 1** *(no deps; runs first, fan-out 3 parallel)*
- [x] 01-01-schema-additions-PLAN.md — append 5 CREATE TABLE blocks to kb/kb/schema.sql âœ“ 2026-05-13
- [x] 01-02-extraction-spec-PLAN.md — PreparsedReport.schema.json + EXTRACTION-SPEC.md at .planning/specs/ âœ“ 2026-05-13
- [x] 01-03-regulatory-doc-PLAN.md — REGULATORY.md at repo root with 5 sections (SAFETY-04) âœ“ 2026-05-13

**Wave 2** *(blocked on Wave 1 completion; depends on 01-01 schema)*
- [x] 01-04-curated-kb-source-PLAN.md — curated_lab_ranges.py + 71 rows across 5 tables; register in build.py SOURCES âœ“ 2026-05-13
- [x] 01-05-lookup-lab-reference-range-tool-PLAN.md — lookup_lab_reference_range tool + 27 pytests (SAFETY-03) âœ“ 2026-05-13

**Wave 3** *(blocked on Wave 2 + Wave 1; 01-06 depends on 01-04/05, 01-07 depends on 01-02)*
- [x] 01-06-explain-lab-test-tool-PLAN.md — explain_lab_test wrapper on lookup_term with 52-entry alias dict (SAFETY-03) âœ“ 2026-05-13
- [x] 01-07-fixture-corpus-PLAN.md — 5 vendor PDFs + ground-truth JSON + MANIFEST.md + 35-case CI invariant âœ“ 2026-05-13

**Wave 4** *(blocked on Wave 3 completion; 08/09/10 all consume fixtures and/or tools)*
- [x] 01-08-pdfbox-spike-PLAN.md — SPIKE-PDFBOX.md NO-GO outcome with per-vendor row-error percent âœ“ 2026-05-13 (Phase 2 ships VendorRegistry per D-10; Kotlin port of Plan 01-10 parser)
- [~] 01-09-sft-spike-and-eval-baseline-PLAN.md — BOTH portions DEFERRED INDEFINITELY (closure 2026-05-13, SUMMARY.md written). SFT-generalization spike per decision 19 (no retraining; question moot). EVAL-05 baseline per decision 20 (defer eval indefinitely to focus on ReportReader); attempt 1 + diagnosis kept in git as evidence (`submission/eval_baseline_pre_reportreader.BROKEN.json`, commit `dab321a`).
- [x] 01-10-python-reference-parser-PLAN.md — tools/parsers/lab_report_parser.py + 126-entry alias map + unit tests + 5-vendor fixture-diff integration test (5/5 byte-identical); satisfies ROADMAP criterion 6 âœ“ 2026-05-13

**Cross-cutting constraints** *(must_haves shared by 2+ plans)*
- `.planning/specs/PreparsedReport.schema.json` validates as JSON Schema Draft 2020-12 — referenced by Plans 02, 07, 10
- Tool registration triplet (impl file + tool_defs.json entry + dispatcher.py _TOOL_REGISTRY entry) is atomic — Plans 05 and 06
- KB build order: `curated_lab_ranges` slots between `medlineplus` and `curated_ddi` in `kb/kb/build.py` SOURCES — Plans 01 and 04
- Per-row / per-fixture citation discipline: no curated KB row without a `source` column, no fixture without MANIFEST.md — Plans 04 and 07

### Phase 2: Kotlin Pre-Parse Pipeline (No Model)

**Goal**: The entire safety contract is testable against the fixture corpus without ever loading the model. Given any one of the 5 fixture PDFs, the Kotlin pipeline produces a `PreparsedReport` JSON identical to the hand-curated reference, every outside-range decision is computed deterministically by `RangeEvaluator`, and the population/pediatric/pregnancy routing matches the spec.

**Depends on**: Phase 1 (KB ranges + curated corpus + extraction spec + tool ports' Python reference are prerequisites).

**Requirements**:
- INPUT-01 (SAF picker → ContentResolver URI)
- INPUT-02 (demographic extraction from PDF cover sheet)
- EXTRACT-01 (tabular extraction across â‰¥5 vendor formats with header fingerprinting)
- EXTRACT-02 (multi-page header propagation + row-count sanity checks)
- EXTRACT-03 (image-only PDFs → explicit deferral, no silent OCR)
- INTERPRET-01 (three-state status: IN_RANGE / BORDERLINE / OUTSIDE_RANGE)
- INTERPRET-02 (PDF range is primary; KB labeled "general adult reference"; KB never overrides)
- INTERPRET-03 (pediatric / pregnancy KB routing; defer when KB lacks demographic)
- INTERPRET-04 (auto-defer tumor markers, genetic results, pathology-grade tests at row level)
- INTERPRET-05 (mismatched units → defer row; >25 analytes → defer report; missing units → defer row)

**Success Criteria** (what must be TRUE):
  1. Given any one of the 5 fixture PDFs, the Kotlin pipeline produces a `PreparsedReport` JSON identical (byte-comparable after canonicalization) to the Phase 1 hand-curated reference, validated by `androidTest` unit tests
  2. Every in-range / outside-range / borderline / unknown decision is computed by `RangeEvaluator.kt` with no model involvement; flipping the result requires either a parser bug or a KB-data fix, never an LLM hallucination
  3. The three deferral paths fire correctly on stress fixtures: image-only PDF defers with "scanned image" message, mismatched units defers that row with explicit reason, and a >25-analyte report defers the whole report
  4. Pediatric and pregnancy demographics extracted from the PDF cover sheet correctly route to `reference_ranges_pediatric` / `reference_ranges_pregnancy` KB tables; rows defer when KB lacks that demographic
  5. Tumor markers, genetic results, and pathology-grade tests auto-defer at the row level via a hard-coded test-type list — `RangeEvaluator` never emits IN_RANGE for them regardless of the printed range

**Plans**: 14 plans across 4 waves

**Wave 1** *(preconditions; runs first — Python + spec + KB ground truth must land before any Kotlin work)*
- [x] 02-01-PLAN.md — schema extension + spec corrigendum + manifests test (D-10, D-12, D-07) âœ“ 2026-05-13
- [x] 02-02-PLAN.md — Python parser update + 5 GT JSONs + 5/5 fixture parity re-verified âœ“ 2026-05-13
- [x] 02-03-PLAN.md — KB auto_defer_tests table + curated_auto_defer.py + parser KB wire-up (D-11) âœ“ 2026-05-13

**Wave 2** *(Kotlin foundation; depends on Wave 1)*
- [x] 02-04-PLAN.md — Models.kt @Serializable wire-format types (D-03) âœ“ 2026-05-13
- [x] 02-05-PLAN.md — KBDatabase 5 query helpers (incl. F-05 BORDERLINE) + KBQueries interface + LookupLabReferenceRange.kt + ExplainLabTest.kt (D-01) âœ“ 2026-05-13
- [x] 02-06-PLAN.md — DefinitionDb.kt ~41 entries + cross-language consistency test (D-08, D-09) âœ“ 2026-05-13

**Wave 3** *(pipeline stages; depends on Wave 2)*
- [x] 02-07-PLAN.md — PdfTextExtractor.kt + AegisApp PDFBoxResourceLoader.init wire-up (LM-1) âœ“ 2026-05-13
- [x] 02-08-PLAN.md — VendorExtractor interface + 5 vendor object impls + VendorRegistry (D-02, LM-3) âœ“ 2026-05-14
- [x] 02-09-PLAN.md — LabValueParser + DemographicExtractor (INPUT-02) âœ“ 2026-05-14
- [x] 02-10-PLAN.md — LabRowNormalizer 126-entry alias map (LM-4, INTERPRET-05 threshold) âœ“ 2026-05-14
- [x] 02-11-PLAN.md — RangeEvaluator 3-state classifier + 9 defer_reason short-codes (INTERPRET-01..05) âœ“ 2026-05-14
- [x] 02-12-PLAN.md — ReportAssembler LM-5 dedup + ReportReaderPipeline public faÃ§ade âœ“ 2026-05-14

**Wave 4** *(test gates; depends on Wave 3)*
- [x] 02-13-PLAN.md — JsonCanonicalizer + JVM unit tests (D-04, D-06; cross-language parity gates) âœ“ 2026-05-13
- [x] 02-14-PLAN.md — androidTest 5-fixture byte-identical exit gate (ROADMAP Phase 2 success criterion 1) âœ“ 2026-05-14 (5/5 PASS empirically on `SM-S918B - 16` / Android 16 in 1.184s; first attempt hit a KBDatabase startup race resolved by `c49ead2 fix(02-14): eliminate KBDatabase startup race in fixture test`; see 02-14-SUMMARY.md "Device Run Outcome")

### Phase 3: UI Without Model (Demoable Without LLM)

**Goal**: A user can pick a fixture lab report PDF, see a fully-populated rows table with correct three-state status badges, tap a per-row deferral CTA that reaches `DeferralScreen`, and read the "What this is NOT" disclaimer card — all without invoking the model. This is the hackathon de-risking property: even if Phase 4 slips, the demo still tells the safety story end-to-end.

**Depends on**: Phase 2 (the entire UI reads `PreparsedReport` state, not model output).

**Requirements**:
- UI-01 (ReportReader as 4th HomeScreen tile)
- UI-02 (rows table: name, value, units, range, status badge)
- UI-03 (three-state badge with calm-by-default visual treatment — no red/amber/green panic palette, no good/bad copy)
- UI-04 (per-row deferral CTA, not a single top-of-page banner — D-1)
- UI-05 (top-of-report summary card with count + flagged list + clinician CTA)
- UI-06 (per-row visual range bar — D-7)
- UI-07 (first-launch "What this is NOT" onboarding card — D-8)
- SAFETY-05 (no new Android permissions; `adb shell dumpsys` audit unchanged)

**Success Criteria** (what must be TRUE):
  1. User picks a fixture PDF via SAF and lands on `ReportReaderScreen` showing one `LabRow` per extracted test with name, value, units, range, three-state badge, and a per-row visual range bar — without the model ever being invoked
  2. The summary card at top of report shows the count of in-range vs outside-range values, lists flagged values, and surfaces the "Bring this report to your clinician" CTA; first-launch users see the "What this is NOT" disclaimer card explicitly stating no diagnosis, no treatment, no replacement of medical advice
  3. Per-row "Discuss with your doctor" CTA appears on flagged rows only (not as a single top-of-page banner) and routes through the existing `DeferralStore` to `DeferralScreen`; the visual treatment is calm-by-default with no red/amber/green panic palette and no "good"/"bad" copy
  4. `adb shell dumpsys package com.aegis.health | grep permission` audit produces output identical to the pre-milestone baseline — zero new permissions added, offline guarantee preserved
  5. ReportReader appears as the 4th HomeScreen tile alongside DrugSafe / ConsentReader / HealthPartner, not in the bottom bar; bottom bar remains 3 items

**Plans**: 8 plans across 6 waves

**Wave 1** *(no deps; navigation skeleton — single plan)*
- [x] 03-01-PLAN.md — MainActivity Routes wire-up + 4th HomeScreen tile + skeleton ReportReaderScreen + HistoryEntity.KIND_REPORTREADER constant (UI-01) — see 03-01-SUMMARY.md

**Wave 2** *(parallel; depend only on 03-01; no cross-deps within wave)*
- [x] 03-02-PLAN.md — StatusBadge.kt + RangeBar.kt (UI-03 calm palette + UI-06 visual range bar) ✓ 2026-05-14 — see 03-02-SUMMARY.md
- [x] 03-04-PLAN.md — SummaryCard.kt + NotADiagnosisPanel.kt + ReportEmptyState.kt (UI-05 count headline + UI-07 always-on disclaimer with SAFETY-04 anchor phrases + D-06 per-status empty states) ✓ 2026-05-14 — see 03-04-SUMMARY.md

**Wave 3** *(LabRow needs Wave 2 atoms; ships DeferReasonCopy too)*
- [x] 03-03-PLAN.md — LabRow.kt + DeferReasonCopy.kt (UI-02 rows table + UI-04 per-row CTA on flagged rows only; D-12 nine-key vocabulary) ✓ 2026-05-14 — see 03-03-SUMMARY.md

**Wave 4** *(builder consumes DeferReasonCopy)*
- [x] 03-05-PLAN.md — AegisResponseBuilder.kt (D-08 forward-compat builder — 3 entry points; Phase 4 string-swap target) ✓ 2026-05-14 — see 03-05-SUMMARY.md

**Wave 5** *(full screen composition consumes every Wave-1..4 artifact)*
- [x] 03-06-PLAN.md — ReportReaderScreen.kt full composition (SAF → parseFromUri → composables → DeferralStore + history; no model) ✓ 2026-05-14 — see 03-06-SUMMARY.md

**Wave 6** *(parallel test gates; depend on Wave 5)*
- [x] 03-07-PLAN.md — JVM unit tests (AegisResponseBuilderTest + DeferReasonCopyTest) + Compose UI tests (ReportReaderScreenTest covering NotADiagnosisPanel anchor phrases, SummaryCard count headline, LabRow CTA, ReportEmptyState) ✓ 2026-05-14 — see 03-07-SUMMARY.md
- [~] 03-08-PLAN.md — PermissionAuditTest.kt + permission_baseline.txt (SAFETY-05 audit gate; human-verify device run checkpoint) — executor-side complete 2026-05-14 (baseline asset + audit test + LF pin committed at `563c940`/`b31f9a1`; `:app:assembleDebugAndroidTest` BUILD SUCCESSFUL; AndroidManifest.xml unchanged across Phase 3); device-run checkpoint outstanding

**UI hint**: yes

### Phase 4: Model Integration

**Goal**: Replace the Phase 3 placeholder explanation with model-generated prose via the single `runReportReaderFastPath` synthesis turn. The model never decides what is in-range — `enforceModeContract` replaces any model-emitted `flags[]` with Kotlin-computed flags from `PreparsedReport.evaluatedRows`. Total latency under 3 minutes for a 12-row report on SD8G2.

**Depends on**: Phase 2 (pipeline produces `PreparsedReport`) + Phase 3 (UI renders the response) + Phase 1 SFT-generalization spike outcome (informs whether the system prompt is sufficient or whether retraining moves earlier).

**Requirements**:
- EXPLAIN-01 (per-row plain-language definition from MedlinePlus `terms` via `explain_lab_test`, with citation)
- SAFETY-01 (`enforceModeContract` replaces any model-emitted `flags[]` with Kotlin-computed flags from `RangeEvaluator`)
- SAFETY-02 (model receives only the structured `PreparsedReport` — never the raw PDF text, never free-form notes)

**Success Criteria** (what must be TRUE):
  1. Final `AegisResponse.explanation` is a 1â€“2 sentence plain-language summary describing how many values are flagged with a "discuss with your doctor" tone — never diagnostic, never "good"/"bad"
  2. `AegisResponse.flags[]` after `enforceModeContract` always matches `PreparsedReport`'s outside-range/unknown rows exactly; a model attempt to mark an outside-range row as in-range is overridden by Kotlin and never reaches the UI
  3. Total end-to-end latency for a 12-row lipid+CMP fixture is under 3 minutes on Snapdragon 8 Gen 2 with W8 + CPU(threads=5), measured via the existing battery probe
  4. Each rendered row carries a MedlinePlus citation for its plain-language explanation, sourced from `explain_lab_test` and surviving `enforceModeContract`'s citation merge unchanged
  5. The model is fed only a structured `PreparsedReport` block via the synthetic `<|tool_response>read_lab_report{...}<tool_response|>` turn — verified by inspection that no raw PDF text and no PDF free-form notes ever appear in the model's input

**Plans**:
- [x] 04-01-PLAN.md — Static foundations: SystemPrompts `"reportreader"` branch + OpenApiToolDefs `"reportreader" -> emptyList()` + new `inference/SafetyBoundaryPhrases.kt` (D-04 banned-phrase vocabulary; Phase 5 EVAL-04 source-of-truth contract) ✓ 2026-05-14 — see 04-01-SUMMARY.md
- [x] 04-02-PLAN.md — Synthesis path in ToolDispatcher.kt (single atomic commit `6da5152`, +419/−5): `runReportReaderFastPath` + `compactToolResultForModel("read_lab_report")` + `enforceModeContract` reportreader branch via new `enforceReportReaderContract` (D-03 strict override; SAFETY-01) + `internal sanitizeExplanation` (D-04 four-step cascade; four literal reject codes) consuming SafetyBoundaryPhrases. Closes EXPLAIN-01 + SAFETY-01 + SAFETY-02 ✓ 2026-05-14 — see 04-02-SUMMARY.md
- [x] 04-03-PLAN.md — Wave 3 UI plumbing (3 per-task commits: `67e92c6` DeferralStore fields; `a220d6f` DeferralScreen pending-synthesis branch + D-05 banner; `87a1dc6` ReportReaderScreen one-site swap). Adds DeferralStore.pendingReport (PreparsedReport?) + synthesisAvailable (Boolean) @Volatile fields; DeferralScreen LaunchedEffect runs ToolDispatcher.runReportReaderFastPath with LoadingPanel + flag-preview chips (D-06); D-05 fallback to AegisResponseBuilder.build(report) + muted banner "On-device summary unavailable for this report."; ReportReaderScreen top onClinicianCta at line 246 stages pendingReport (D-02 one-site swap — per-row line 257 + empty-state line 295 CTAs UNCHANGED). Re-confirms EXPLAIN-01 + SAFETY-01 surface ✓ 2026-05-14 — see 04-03-SUMMARY.md
- [x] 04-04-PLAN.md — Wave 4 belt-and-suspenders engine warm-up (2 per-task commits: `7e08633` adds EngineRouter.warmUp() idempotent wrapper; `44c97bb` wires HomeScreen ReportReader tile-tap fire-and-forget dispatch). `EngineRouter.warmUp()` is an idempotent `suspend fun` guarded by `if (!isReady) return` that calls `active.startConversation("reportreader", includeTools = false)` to force model pages hot (mode + empty-tools match synthesis turn so prefill cache stays warm); HomeScreen ReportReader tile `onClick` now dispatches `AegisApp.instance.appScope.launch(Dispatchers.IO) { runCatching { EngineRouter.warmUp() } }` before synchronously calling `onOpen("reportreader")` (DrugSafe / ConsentReader / HealthPartner tile onClicks UNCHANGED — only ReportReader has the SAF-picker + parse window that hides warm-up cost). D-07 implemented verbatim ✓ 2026-05-14 — see 04-04-SUMMARY.md

**UI hint**: yes

### Phase 04.1: Vendor Coverage Expansion + Generic Fallback (INSERTED)

**Goal:** Extend VendorRegistry with two new named extractors (Tata 1mg + Dr Lal PathLabs) plus a generic-fallback path (catch-all GenericExtractor with 3-layer defense: permissive regex + per-row units-OR-range gate + aggregate floor of >=3 normalized rows) so unknown-vendor PDFs that are text-table-recognizable no longer dead-end at UNKNOWN_VENDOR. Surfaces a new 5th `report_status.code = GENERIC_FALLBACK` value, a `GenericFallbackBanner` rendered above SummaryCard in calm-tone copy ("Lab vendor not recognized -- best-effort extraction. Verify each row against your PDF."), and a strict GENERIC_FALLBACK sub-clause in `enforceReportReaderContract` (always-defer + confidence=0.4). Extends `LabRowNormalizer.LAB_TERM_ALIASES` + `tools/parsers/_alias_map.py` with 14 British/Indian medical-English variants. The Phase 2 byte-identical 5/5 contract is preserved; the new vendors use field-level + generic smoke assertions.

**Requirements**: EXTRACT-01 (vendor formats extension: "at least 5 named" -> "at least 7 named + generic-fallback"), INTERPRET-05 (status-code enum extension), SAFETY-01 (enforceReportReaderContract GENERIC_FALLBACK override sub-clause)

**Depends on:** Phase 4

**Plans:** 8/8 plans complete

**Wave 1** *(no deps; schema + spec + alias-map parity atomic single commit)*
- [x] 04.1-1-01-PLAN.md -- schema enum + EXTRACTION-SPEC.md + test_fixture_manifests.py + 14-entry Kotlin + Python alias-map atomic (D-05 + D-09 + D-10)

**Wave 2** *(blocked on Wave 1; Tata 1mg + Dr Lal extractors with R-02 brand-tokens-first ordering)*
- [x] 04.1-2-01-PLAN.md -- Dr Lal sample acquisition prerequisite (human checkpoint; D-13 with Apollo fallback)
- [x] 04.1-2-02-PLAN.md -- Tata1mgExtractor + DrLalPathLabsExtractor + VendorRegistry reorder per R-02 (D-09 + D-11)

**Wave 3** *(blocked on Wave 2 + Wave 1; GenericExtractor + aggregate floor + status-code emission)*
- [x] 04.1-3-01-PLAN.md -- GenericExtractor.kt + VendorRegistry slot 7 + ReportReaderPipeline aggregate-floor gate + GENERIC_FALLBACK emission (D-01..D-04, D-14)

**Wave 4** *(blocked on Wave 3; UI surface + contract override)*
- [x] 04.1-4-01-PLAN.md -- GenericFallbackBanner.kt + ReportReaderScreen slot [2] + dynamic headerSlotCount + R-03 history-insert gate + DeferReasonCopyTest scan extension (D-06, R-01, R-03)
- [x] 04.1-4-02-PLAN.md -- enforceReportReaderContract GENERIC_FALLBACK sub-clause (always-defer + confidence=0.4) (D-07)

**Wave 5** *(blocked on Wave 4; synthetic PDFs + exit gate)*
- [x] 04.1-5-01-PLAN.md -- synthesize_fixture.py + 3 synthetic PDFs + 3 MANIFEST.md + anti-fingerprint pytest (D-12 + D-13 + D-14)
- [x] 04.1-5-02-PLAN.md -- LabReportPipelineFixtureTest.kt in-place extension (5 byte-identical + 2 field-level + 1 generic smoke; device-run checkpoint) (D-15)

**Cross-cutting constraints** *(must_haves shared by 2+ plans)*
- Schema-emit-then-validate ordering: Wave 1 lands schema + spec + CI invariant BEFORE Wave 3 Kotlin emits GENERIC_FALLBACK (Pitfall 5).
- Cross-language alias-map parity: Wave 1 lands Kotlin + Python edits atomically; Phase 2 D-09 test enforces (Pitfall 3).
- R-02 brand-token-first VendorRegistry ordering eliminates Mayo "hematology" collision deterministically.
- Synthetic fixtures only -- zero PHI; real customer PDFs never committed (D-12 + D-13).
- 5/5 byte-identical Phase 2 contract preserved on existing vendors (D-15).

### Phase 5: Eval, Optional Retraining, Submission Polish

**Goal**: Anchor cases cover real failure modes (not random reports), the three-stage eval framework localizes any failure to extraction / routing / synthesis, the four new Group C metrics hit thresholds, the existing 55 anchors don't regress, REGULATORY.md gets its final language audit, and the demo video is recorded. SFT retraining is conditional on the Phase 1 spike outcome and the Phase 4 smoke results — included only if the prompt-only path fails Group A/B/C.

**Depends on**: Phase 4 (model is integrated and producing `AegisResponse` for fixture PDFs).

**Requirements**:
- EVAL-01 (â‰¥20 anchor cases following the specified distribution)
- EVAL-02 (three-stage eval framework: extraction / routing / synthesis)
- EVAL-03 (`kb_range_grounding` â‰¥99%, `kb_status_calibration` â‰¥95%, `lab_hallucination_check` â‰¥99%)
- EVAL-04 (Group B `safety_boundary` regex extended for declarative-diagnosis patterns)

**Conditional branch — SFT retraining**:
- **Presumed SKIPPED** per decision 19 (STATE.md, 2026-05-13): ship SFT v4 as-is. Plan 01-09's SFT-generalization spike is deferred indefinitely; no incremental SFT planned unless Phase 4 smoke tests surface real-world Group A/B/C regressions against ReportReader anchor cases. In that case only, this branch is reactivated: new ReportReader Jinja templates in `datagen/datagen/templates/`, `make data` regeneration with anchor firewall active, incremental SFT on the new templates, regression-guard pass against existing 65 anchors, fresh INT W8 export, re-upload to HF Hub.
- **Default path** (prompt-only ships on current SFT v4 + new system prompt): retraining is skipped; phase consists of ReportReader anchor cases + three-stage eval + Group C metrics + REGULATORY.md final audit + demo recording.

**Success Criteria** (what must be TRUE):
  1. â‰¥20 ReportReader anchor cases live in `eval/eval/anchor_cases.json` following the EVAL-01 distribution (2 all-in-range, 2 borderline boundaries, 2 critical values, 1 pediatric, 1 pregnancy, 1 tumor marker, 1 no-ranges-printed, 1 mismatched units, 1 prompt-injection, remainder common panels) with `expected_outside_range` ground truth per case
  2. Three-stage eval framework runs and isolates failures: Stage 1 extraction accuracy â‰¥95% rows / â‰¥99% units, Stage 2 tool routing correctness given correct extraction, Stage 3 synthesis Group B given correct routing
  3. All four new Group C metrics hit thresholds: `kb_range_grounding` â‰¥99%, `kb_status_calibration` â‰¥95%, `lab_hallucination_check` â‰¥99%, `lab_range_accuracy` â‰¥95%; Group B `safety_boundary` regex extended for declarative-diagnosis patterns (`you have`, `this means`, `indicates` Ã— disease nouns) holds at 100%
  4. Regression guard on existing 55 anchor cases shows no drop in DrugSafe / ConsentReader / HealthPartner metrics beyond the 0.02 tolerance — whether or not retraining ran
  5. `REGULATORY.md` final language audit complete (SaMD position, "stays on your device" rather than "HIPAA-compliant"); demo video recorded showing PDF picker → rows table → flagged values → summary card → deferral CTA

**Plans**: TBD

**UI hint**: yes (visual polish for demo)

## Phase Dependencies

```
Phase 1 (Foundation)
    â”‚
    â–¼
Phase 2 (Kotlin Pipeline)
    â”‚
    â–¼
Phase 3 (UI Without Model)  â—„â”€â”€ Hackathon de-risk: demoable here even if 4 slips
    â”‚
    â–¼
Phase 4 (Model Integration) â—„â”€â”€ Long pole; also reads Phase 1 SFT-spike outcome
    â”‚
    â–¼
Phase 5 (Eval + Polish)     â—„â”€â”€ SFT retraining is conditional on Phase 1 spike
```

**Phase ordering rationale:**

- **Phase 1 is heavier than a typical first phase.** Five new KB tables, public-domain row curation with per-source license review, two new Python tools with pytest coverage, REGULATORY.md, multi-vendor PDF corpus collection with PHI redaction, a Python reference parser as cross-language validation target, and two spikes. PITFALLS.md flags this explicitly. Compressing it would force discoveries into Phase 2 where they cost more.
- **Phase 2 inverts the usual data-flow.** Because the architecture has the model downstream of the safety decision, the entire safety contract is testable in Kotlin against fixtures before the model is involved. This is what makes Phase 3 demoable without an LLM.
- **Phase 3 is the hackathon de-risking property.** If model integration in Phase 4 hits a blocker (LiteRT-LM version drift, prompt fails to constrain output, latency overshoots), Phase 3 already produces a polished, demoable artifact end-to-end. The narrative survives.
- **Phase 4 is the long pole.** Latency is the cost; correctness is enforced by Kotlin via `enforceModeContract`. The fast path is one synthesis turn at ~60â€“120s, not an agentic loop.
- **Phase 5's SFT retraining is conditional**, branching on the Phase 1 spike outcome. If SFT v4 generalizes to the synthetic `<|tool_response>read_lab_report{...}<tool_response|>` turn with the new system prompt alone, retraining is skipped. Otherwise, it lands here so the regression guard can validate it against existing anchors.

## Progress

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Foundation — KB, Tools, Regulatory, Corpus | 9/10 (+1 doc-deferral) | Complete (2026-05-13; 01-09 both portions deferred-indefinitely per decisions 19+20, SUMMARY.md written) | 2026-05-13 |
| 2. Kotlin Pre-Parse Pipeline (No Model) | 14/14 | Executing (Wave 4 complete 2026-05-13; phase-level verification + user device-side 5/5 byte-identical run pending) | - |
| 3. UI Without Model | 7/8 + 03-08 executor-side complete | Executing (Wave 1..5 closed; Wave 6 in flight 2026-05-14 — 03-07 JVM + Compose UI tests landed; 03-08 baseline asset + PermissionAuditTest committed + `:app:assembleDebugAndroidTest` BUILD SUCCESSFUL — SAFETY-05 device-run checkpoint outstanding) | - |
| 4. Model Integration | 4/5 | Executing (04-01 static foundations landed 2026-05-14; 04-02 synthesis path landed 2026-05-14 — atomic commit `6da5152` closes EXPLAIN-01 + SAFETY-01 + SAFETY-02; 04-03 Wave 3 UI plumbing landed 2026-05-14 — 3 per-task commits `67e92c6` + `a220d6f` + `87a1dc6` wire DeferralScreen LaunchedEffect to runReportReaderFastPath with D-05 fallback banner; 04-04 Wave 4 engine warm-up landed 2026-05-14 — 2 per-task commits `7e08633` + `44c97bb` add EngineRouter.warmUp() idempotent wrapper + HomeScreen ReportReader tile-tap fire-and-forget dispatch per D-07; Wave 5 on-device smoke against 5 fixture PDFs is the only Phase 4 plan remaining) | - |
| 5. Eval, Submission Polish (retraining presumed skipped per decision 19) | 0/0 | Not started | - |

## Coverage

- v1 requirements: **28 total**
- Mapped to phases: **28** âœ“
- Unmapped: 0

| Phase | Requirements | Count |
|-------|--------------|-------|
| 1 | SAFETY-03, SAFETY-04, EVAL-05 | 3 |
| 2 | INPUT-01, INPUT-02, EXTRACT-01, EXTRACT-02, EXTRACT-03, INTERPRET-01, INTERPRET-02, INTERPRET-03, INTERPRET-04, INTERPRET-05 | 10 |
| 3 | UI-01, UI-02, UI-03, UI-04, UI-05, UI-06, UI-07, SAFETY-05 | 8 |
| 4 | EXPLAIN-01, SAFETY-01, SAFETY-02 | 3 |
| 5 | EVAL-01, EVAL-02, EVAL-03, EVAL-04 | 4 |
| **Total** | | **28** |

Cross-checks:
- Every Phase 1 success criterion maps to â‰¥1 v1 requirement or Phase 1 spike (SAFETY-03 / SAFETY-04 / EVAL-05 + the PdfBox + SFT-generalization spikes from SUMMARY.md). Success criterion 6 (Python reference parser + matching expected-output JSON per fixture) is delivered by Plan 10 alongside Plans 02 (schema) and 07 (fixtures).
- Phase 2 success criteria collectively cover all 5 EXTRACT/INPUT and all 5 INTERPRET requirements; no INTERPRET requirement depends on the model.
- Phase 3 success criteria cover all 7 UI requirements plus SAFETY-05 (permission audit gate).
- Phase 4 covers the three "model touches the data" requirements: EXPLAIN-01 (citation grounding via `explain_lab_test`), SAFETY-01 (`enforceModeContract` flag override), SAFETY-02 (structured input only).
- Phase 5 covers the four EVAL requirements that depend on the model being live. EVAL-05 (regression-guard baseline) is captured in Phase 1 because the baseline must exist before any new training run.

---

*Roadmap created: 2026-05-13*
*Revised 2026-05-13: Phase 1 plan count bumped 9 → 10 with the addition of Plan 10 (Python reference parser) per checker review.*

---

# v1.1 Hackathon Polish — Roadmap

**Appended:** 2026-05-15
**Granularity:** coarse (6 phases, 5–10; continues numbering from v1.0's Phase 4.1)
**Core Value:** Make the on-device Gemma 4 tool-call loop visible to demo-video viewers, and tighten every screen the judges will see during the ~5-minute synthesis turn — without changing the model, the KB, or the AegisResponse schema.

**Companion milestone:** v1.0 (ReportReader) remains technically open — Phase 5 retrain + EVAL-05 baseline indefinitely deferred per decisions 19/20 (2026-05-15). v1.1 runs alongside as a UI-only polish milestone on a **frozen model + frozen KB + frozen schema**. Research SUMMARY.md confidence: HIGH; ~95% of v1.1 plumbing already exists in the codebase (`ToolDispatcher.ProgressEvent`, `FlagsStreamParser`, `LocalAegisColors.sev*`, `onProgress` callback, `LoadingPanel`). The entire dependency delta is one Compose BOM bump + `compose-shimmer 1.4.0` + one ~120-LOC new file (`FriendlyToolSummarizer.kt`).

**Open question resolutions (locked here, before Phase 5 begins):**

1. **Stepper-vs-LoadingPanel split** — `LoadingPanel.kt` stays for `autoAdvance=true` decorative loading; new `ui/common/ToolStepper.kt` lands as the `autoAdvance=false` live-tools variant. Decision documented in the Phase 7 detail below and must land in `CONVENTIONS.md` during Phase 7 close.
2. **Phase 6 ReportReader-first ordering** — ReportReader `FlagPreview` subscription ships before HealthPartner inside Phase 6 (STREAM-02; ReportReader is the higher demo-value surface).
3. **Phase 10 stretch sequencing** — Highest demo-trust signal first, only land what fits: **STEP-07** (citation chips on collapsed stepper) → **POLISH-05** (Apple-Health-style range bar) → **STREAM-05** (partial-JSON flag-card disclosure) → **STREAM-06** (typewriter explanation reveal) → **HOME-06** (urgent-care deep-link wire-up). HOME-06 is the cheapest opportunistic win (~10 LOC, ghost-button closure) and acts as a buffer-day filler.

## Phases

- [ ] **Phase 5: Stepper + Streaming Infrastructure** *(2/4 plans complete — 05-01 BOM bump ✓ 2026-05-15, 05-02 ToolCallBoundaryDetector + LiteRtLmEngineStreamSplitTest ✓ 2026-05-15)* — Compose BOM bump · compose-shimmer dep · `FriendlyToolSummarizer.kt` · `ProgressEvent` emission swaps · `LiteRtLmEngineStreamSplitTest` regression gate (C2 single-buffer-owner protection)
- [ ] **Phase 6: Streaming Preview Wiring (ReportReader → HealthPartner)** — `FlagPreview` subscription branches mirroring DrugSafe pattern, every-4-pieces throttle preserved, never expose raw partial JSON
- [ ] **Phase 7: ToolStepper UI + Latency-Honest Skeletons** — One shared `ToolStepper.kt`, sequential reveal, three-state status transitions, `compose-shimmer` skeleton loaders, real engine-state copy ("running on your phone — ~5 minutes"), animation rate ≤ decode rate
- [x] **Phase 8: ReportReader Visual Polish** — Top summary card · three-tier severity via `tokenForStatus()` · per-row "Discuss with your doctor" CTA on outside-range only · `LocalAegisColors.sev*` hex-literal grep gate _(Complete 2026-05-16: 6/6 plans, 4/4 POLISH-01..04 P0, 5/5 SC's, verifier passed, user 6/6 PASS on-device smoke)_
- [ ] **Phase 9: Home + Startup Polish** — Refined hero copy · four-tile gradient consistency · `StartupState.Ready` model-status pill · `ui/home/` + `ui/startup/` engine-read grep gate
- [ ] **Phase 10: Demo Recording Prep + P1 Stretch** — Pre-flight checklist · three golden-path dry-run · voiceover script · `v1.1.0-demo` tag · all 5 P1 stretch requirements landed if calendar slack permits

## Phase Details

### Phase 5: Stepper + Streaming Infrastructure

**Goal**: Land the entire dependency delta and the critical-path infrastructure that unblocks every downstream v1.1 phase. After Phase 5 closes, every screen can subscribe to args-aware `ProgressEvent.Step` events, every stepper UI consumes the same shared event surface, and a regression test proves the engine's single buffer-owner contract still holds. No user-visible UI change ships here — this is plumbing.

**Depends on**: v1.0 Phase 4.1 (existing `ToolDispatcher.ProgressEvent` sealed class + `friendlyToolLabel` call sites).

**Requirements**:
- INFRA-01 (Compose BOM 2024.02.00 → 2026.05.00; existing 9 instrumented + 16 JVM tests pass on SM-S918B)
- INFRA-02 (`com.valentinilk.shimmer:compose-shimmer:1.4.0` added, no INTERNET grant)
- INFRA-03 (new `inference/FriendlyToolSummarizer.kt` mapping all 6 tools to args-aware sentences)
- INFRA-04 (`ToolDispatcher.kt` emits `ProgressEvent` via `FriendlyToolSummarizer` at the 4 existing `friendlyToolLabel` sites)
- INFRA-05 (no second buffer-owner on `LiteRtLmEngine.sb`; new `LiteRtLmEngineStreamSplitTest` lands)
- INFRA-06 (new `ui/common/ToolStepper.kt` skeleton — single shared composable across DrugSafe/ReportReader/HealthPartner; Phase 7 wires the visuals)
- INFRA-07 (LiteRT-LM stays pinned at `0.10.2` — verified, not bumped)

**Success Criteria** (what must be TRUE):
  1. `./gradlew :app:assembleDebug` BUILD SUCCESSFUL after Compose BOM bump on Kotlin 2.2.21 / KSP 2.2.21-2.0.4; existing 9 instrumented + 16 JVM Compose UI tests still pass on SM-S918B / Android 16 (regression baseline — INFRA-01 verification)
  2. New `LiteRtLmEngineStreamSplitTest` proves that splitting `<|tool_` and `call>` across two `onMessage` callbacks still fires `HostStopReason.TOOL_CALL` exactly once — the C2 single-buffer-owner contract is regression-protected before any new consumer subscribes (INFRA-05)
  3. `FriendlyToolSummarizer.summarize(toolCall)` returns args-aware human sentences for all 6 tools (`normalize_drug`, `decompose_product`, `get_drug_info`, `check_warnings`, `lookup_term`, `get_guideline`) plus the synthetic `read_lab_report` — verified by a parametrized JVM unit test asserting one expected sentence per (tool, args) tuple; raw `call:check_warnings{drug_list:[...]}` strings never appear in any `ProgressEvent.Step` label
  4. `ToolDispatcher.kt` routes step emission through `FriendlyToolSummarizer.summarize(toolCall)` at all 4 existing call sites (lines ~422, ~462, ~519, ~830 — verify line numbers at plan-phase time); no `friendlyToolLabel(name)` consumer of just the name remains in the dispatcher (INFRA-04)
  5. `com.valentinilk.shimmer:compose-shimmer:1.4.0` resolves at build time; `adb shell dumpsys package com.aegis.health | grep permission` baseline diff is empty (no transitive INTERNET grant — INFRA-02 verification + project's offline guarantee)
  6. LiteRT-LM Android dependency in `android/app/build.gradle.kts` stays at `0.10.2` after the BOM bump; the SFT v4 frozen artifact at `V1rtucious/gemma4-e4b-toolcalling-litertlm-v2` is not re-exported (INFRA-07)

**Plans**: TBD (`/gsd-plan-phase 5`)

### Phase 6: Streaming Preview Wiring (ReportReader → HealthPartner)

**Goal**: Mid-decode `FlagPreview` events surface in `ReportReaderScreen` and `HealthPartnerScreen`, mirroring the DrugSafe pattern already proven at `DrugSafeScreen.kt:194-206 + 248-262`. ReportReader subscription lands first (higher demo value); HealthPartner second. The streaming preview never exposes raw partial JSON to the user — only completed `Flag` objects render. The existing every-4-pieces throttle is preserved.

**Depends on**: Phase 5 (FriendlyToolSummarizer + ProgressEvent emission). ReportReader subscription depends on `enforceReportReaderContract` (already shipped in v1.0 Phase 4 plan 04-02).

**Requirements**:
- STREAM-01 (User sees flag previews appear in `ReportReaderScreen` and `HealthPartnerScreen` as the model decodes via `FlagPreview` subscription)
- STREAM-02 (ReportReader subscribes before HealthPartner — phase ordering invariant)
- STREAM-03 (Streaming preview never exposes raw partial JSON; only completed `Flag` objects render)
- STREAM-04 (Streaming preview honors existing every-4-pieces throttle; never recomposes Compose state on windows tighter than ~50ms)

**Success Criteria** (what must be TRUE):
  1. User running ReportReader on a fixture PDF sees the first `Flag` preview card appear in the screen body within ~30–90 seconds of synthesis start (before the full `AegisResponse` lands), populated with severity + description + citation — mirroring the DrugSafe behavior at `DrugSafeScreen.kt:248-262` (STREAM-01 + STREAM-02 verified on SM-S918B)
  2. HealthPartner screen subscribes to `FlagPreview` after ReportReader is verified working (STREAM-02 phase-internal ordering); a parametrized androidTest exercising the same FlagsStreamParser path proves both subscriptions wire correctly without per-mode forks
  3. JVM unit test `FlagsStreamParserTest` covers split-token edge cases — split inside a flag object's `"description":` value, split across the closing `}` of a flag, split inside an escape sequence — closing the test gap flagged in `CONCERNS.md` ("FlagsStreamParser untested"); never emits a partial / incomplete `Flag` object to the UI (STREAM-03)
  4. UI integration test asserts no `mutableStateOf<String>` exposes `streamBuffer.toString()` directly to Compose; the every-4-pieces throttle (`ToolDispatcher.kt` existing pattern) governs all `ProgressEvent.Update` emissions; recomposition profiling on SM-S918B during a full 5-min synthesis shows < 30 stepper-state recomposes per minute (STREAM-04, C1 mitigation)
  5. `StateFlow.update { previous -> if (newList.size < previous.size) previous else newList }` monotonic-growth guard is applied to the preview list — flag cards never disappear and reappear during stream (M2 mitigation)

**Plans**: TBD (`/gsd-plan-phase 6`)

**UI hint**: yes

### Phase 7: ToolStepper UI + Latency-Honest Skeletons

**Goal**: The vertical "thinking" stepper materializes on DrugSafe, ReportReader, and HealthPartner screens during synthesis. Each tool call appears as a row with the args-aware friendly summary from Phase 5, transitions through pending (○) → running (↻) → done (✓) via Compose `AnimatedContent`, and new rows reveal sequentially via `AnimatedVisibility` as new `ProgressEvent.Step` events fire. ConsentReader is explicitly excluded. Skeleton loaders render via `compose-shimmer` at minimum 1.8s cycle while waiting for the first `ProgressEvent`. All animations respect Android's animator-duration-scale accessibility setting. The latency story is honest — at least one loading surface explicitly says "running on your phone — ~5 minutes."

**Depends on**: Phase 5 (BOM bump + shimmer dep + `FriendlyToolSummarizer` + `ToolStepper.kt` skeleton). Phase 6 is NOT a hard blocker (stepper UI is independent of flag-preview wiring) but recommended to land for the end-to-end demo before phase close.

**Requirements**:
- STEP-01 (Vertical stepper appears in response area for DrugSafe / ReportReader / HealthPartner when Gemma 4 starts processing)
- STEP-02 (Each tool call appears as a stepper row with friendly summary from `FriendlyToolSummarizer`)
- STEP-03 (Three-state status — pending ○ → running ↻ → done ✓ — via Compose `AnimatedContent`)
- STEP-04 (New rows reveal sequentially via `AnimatedVisibility` as new `ProgressEvent.Step` events fire)
- STEP-05 (ConsentReader explicitly excluded from the stepper UI)
- STEP-06 (Failed tool calls render with explicit error state, not a fake-success checkmark)
- SKEL-01 (Skeleton loaders render via `compose-shimmer` at minimum 1.8s cycle while waiting for the first `ProgressEvent`)
- SKEL-02 (Loading-state copy maps to real engine states — "Preparing…" / "Loading on-device model…" / "Thinking through your request…" / "Composing the answer…" — no animation step faster than 1s/cycle)
- SKEL-03 (Transition animations use `AnimatedContent` / `Modifier.animateContentSize` only; no fake-typing animation faster than the actual decode rate ~3.7 pieces/sec)
- SKEL-04 (Latency-honest copy on every loading surface — at least one explicit "running on your phone — ~5 minutes" reference)
- SKEL-05 (All animations respect `Settings.Global.ANIMATOR_DURATION_SCALE`)

**Decision documented in this phase (per open-question #1)**: `LoadingPanel.kt` stays for `autoAdvance=true` decorative case; new `ui/common/ToolStepper.kt` is the `autoAdvance=false` live-tools variant. **MUST be documented in `.planning/codebase/CONVENTIONS.md` before phase close** so future polish doesn't fork it.

**Success Criteria** (what must be TRUE):
  1. User running DrugSafe / ReportReader / HealthPartner sees the `ToolStepper` materialize within 1–2 seconds of submit-tap, with the first step row "Reading prompt…" rendering through `compose-shimmer` skeleton until the first `ProgressEvent.Step` from `FriendlyToolSummarizer` lands (STEP-01 + SKEL-01 + SKEL-02)
  2. Each step row transitions pending → running → done through Compose `AnimatedContent` at ≤ 350ms per transition; the every-4-pieces `ProgressEvent.Update` throttle drives the running-state subtitle ("Generating response (N tokens)…"); failed tool calls render an explicit error state with a calm-tone error chip (NOT red panic copy) — never a fake checkmark (STEP-03 + STEP-04 + STEP-06)
  3. Animation rate ceiling is enforced: no on-screen motion runs faster than 1 step/sec for skeleton shimmer (≥1.8s cycle) or 1.2s/rev for spinner rotation; `androidTest` instrumented test reads `Settings.Global.ANIMATOR_DURATION_SCALE` and asserts that disabled-animations setting produces a non-animated stepper (SKEL-03 + SKEL-05; C4 fake-typing-prevention)
  4. ConsentReader screen does NOT render `ToolStepper` (regression test asserts no `ToolStepper(` call in `ConsentReaderScreen.kt`); existing ConsentReader behavior is byte-identical to v1.0 (STEP-05)
  5. At least one loading surface on every stepper-bearing screen renders the literal phrase "running on your phone — ~5 minutes" (or close equivalent) — verified by a parametrized androidTest grepping for the latency-honesty anchor copy (SKEL-04; C4 mitigation)
  6. `.planning/codebase/CONVENTIONS.md` documents the `LoadingPanel` vs `ToolStepper` split decision before phase close (open-question #1 resolution captured permanently)

**Plans:** 7/8 plans executed

Plans:
- [x] 07-01-PLAN.md — Add ProgressEvent.StepFailure subtype with no-op applyTo + dispatcher emission + warning theme tokens (Path A foundation)
- [x] 07-02-PLAN.md — Replace ToolStepper.kt body wholesale (three-state + compose-shimmer + latency-honest subline) + 4 @Ignore-d androidTests
- [x] 07-03-PLAN.md — Migrate DrugSafe + HealthPartner from LoadingPanel(autoAdvance=false) to ToolStepper (D-01 drop-in swap; STREAM-02 mirror)
- [x] 07-04-PLAN.md — Move runReportReaderFastPath into ReportReaderScreen + revert DeferralScreen to deferral-only + delete DeferralStore.pendingReport (D-02 Path B; closes STREAM-01-followup)
- [x] 07-05-PLAN.md — Land LoadingPanel-vs-ToolStepper subsection in CONVENTIONS.md + run 6 verification grep gates + 6 invariant grep gates (SC-6; phase close)
- [x] 07-06-PLAN.md — Fast-path try/catch + StepFailure emission + hoisted friendly label + mojibake fix + empty-drugs short-circuit (CR-01 BLOCKER + WR-01/WR-05/WR-06; gap-closure insert)
- [x] 07-07-PLAN.md — Screen-side `isLoading` try/finally guard on DrugSafe + HealthPartner scope.launch bodies; CR-02 BLOCKER end-to-end close (gap-closure insert)

**UI hint**: yes

### Phase 8: ReportReader Visual Polish

**Goal**: The ReportReader screen tightens to demo-grade quality without panic-palette drift. The top summary card refines spacing, hierarchy, and calm-by-default color treatment (extending UI-05 from v1.0). Three-tier severity (in-range / borderline / outside-range) renders consistently via a new `tokenForStatus(status, colors)` helper in `Theme.kt`; `StatusBadge.kt:34-39` migrates to use it. Per-row "Discuss with your doctor" CTA renders on outside-range rows only (extending UI-04). All severity rendering in `ui/` uses `LocalAegisColors.current.sev*` tokens — no new `Color(0x…)` hex literals anywhere in `ui/` (enforced by a grep gate at phase close).

**Depends on**: Phase 5 (BOM bump for Material3 1.4.0 + `Modifier.animateItem`). Track B parallel to Phases 5–7 from Day 1; can ship independently of Phase 6/7 UI wiring.

**Requirements**:
- POLISH-01 (ReportReader top summary card refines spacing, hierarchy, calm-by-default color — no red/amber/green panic palette, no good/bad copy)
- POLISH-02 (Three-tier severity via new `tokenForStatus(status, colors)` helper in `Theme.kt`; `StatusBadge.kt:34-39` migrates to use it)
- POLISH-03 (Per-row "Discuss with your doctor" CTA on outside-range rows only — never in-range; refined visual treatment with the summary card's clinician CTA)
- POLISH-04 (All severity / status rendering in `ui/` uses `LocalAegisColors.current.sev*` tokens — no `Color(0x…)` hex literals introduced in `ui/` files; regression check: grep gate at phase close)

**Success Criteria** (what must be TRUE):
  1. ReportReader top summary card renders with refined 8pt-grid spacing, consistent hierarchy (count headline → flagged-chip strip → clinician CTA), and calm-by-default color treatment — verified by visual inspection on SM-S918B and a Compose UI test asserting no `Color(0xFF...)` hex literal appears in `SummaryCard.kt` or any other `ui/reportreader/*.kt` file (POLISH-01)
  2. New `tokenForStatus(status: String, colors: AegisColors): Pair<Color, Color>` helper in `Theme.kt` is the single source of mapping for the four ReportReader status codes (IN_RANGE / BORDERLINE / OUTSIDE_RANGE / unknown); `StatusBadge.kt:34-39` migrates to use it and the inline `when` block at those lines is gone (POLISH-02; documented in `CONVENTIONS.md` as the new pattern)
  3. Per-row "Discuss with your doctor" CTA appears on `OUTSIDE_RANGE` and `BORDERLINE` rows only — `IN_RANGE` rows render the expandable definition with no CTA; a Compose UI test asserts CTA visibility against fixture rows of each status (POLISH-03; N2 CTA-fatigue mitigation extends v1.0 UI-04)
  4. Grep gate `grep -rEn "Color\(0x" android/app/src/main/java/com/aegis/health/ui/` returns **empty** at phase close; all new color decisions in v1.1 polish resolved through `LocalAegisColors.current.*` (POLISH-04; C3 severity-color-drift mitigation)
  5. Existing 9 instrumented Compose UI tests for ReportReader (TalkBack contentDescriptions like `"Status: In range"`) still pass on SM-S918B — polish copy changes inventory was taken via `grep "Status: " android/app/src/androidTest/` BEFORE any change landed; PR diff coupled string change with matching test update (M8 Compose-UI-test-invalidation mitigation)

**Plans**: 6 plans
- [x] 08-01-PLAN.md — Add tokenForStatus + statusLabel helpers to Theme.kt + 5-case JVM unit test (POLISH-02 foundation; Wave 1)
- [x] 08-02-PLAN.md — Migrate StatusBadge.kt off Triple destructuring onto Theme.kt helpers (POLISH-02; Wave 2, depends on 08-01)
- [x] 08-03-PLAN.md — Add onWarmSurface(Muted) tokens + collapse 9 hex-conditional sites across 6 non-theme ui/ files; ConsentReaderScreen.kt:417 asymmetric site collapse with inline justification (POLISH-04; Wave 1)
- [x] 08-04-PLAN.md — SummaryCard hierarchy refinement (titleLarge headline + xl outer padding + lg inter-zone Spacers + All-values-in-range all-clear copy) (POLISH-01; Wave 1)
- [x] 08-05-PLAN.md — LabRow per-row CTA swap PrimaryButton → GhostButton (POLISH-03; Wave 1)
- [x] 08-06-PLAN.md — Phase-close gates: androidTest copy-inventory + 5 grep gates + CONVENTIONS.md doc + on-device visual smoke (POLISH-02 + POLISH-04; Wave 3, depends on 08-01..08-05)

**UI hint**: yes

### Phase 9: Home + Startup Polish

**Goal**: First-impression screens tighten. `HomeScreen` hero copy refines to speak to offline + on-device + cited value prop without regulatory ambiguity. Four mode tiles (DrugSafe, ConsentReader, HealthPartner, ReportReader) use consistent gradient + iconography + tap affordance. Model-ready status pill on `HomeScreen` reads strictly from `app.startup.value is StartupState.Ready` — never flips green before `EngineRouter` validates the bundle (C5 mitigation). `StartupScreens` copy uses honest latency language and surfaces the sideloaded model file path. A build-time grep gate enforces that no file under `ui/home/` or `ui/startup/` reads `EngineRouter`, `KBDatabase`, or `LiteRtLmEngine` directly — all engine state flows through `app.startup`.

**Depends on**: Phase 5 (BOM bump). Track B parallel to all Track A phases; ships after Phase 8 to capture severity-token consistency on home-tile severity counters.

**Requirements**:
- HOME-01 (`HomeScreen` hero copy refined — no medical-advice framing, no regulatory ambiguity)
- HOME-02 (Four mode tiles use consistent gradient + iconography + tap affordance)
- HOME-03 (Model-ready status pill reads strictly from `app.startup.value is StartupState.Ready`)
- HOME-04 (`StartupScreens` copy uses honest latency language + surfaces sideloaded model file path)
- HOME-05 (Build-time grep gate: no file under `ui/home/` or `ui/startup/` reads `EngineRouter`, `KBDatabase`, or `LiteRtLmEngine` directly)

**Success Criteria** (what must be TRUE):
  1. `HomeScreen` hero copy passes the regulatory-ambiguity audit — none of the forbidden words `diagnose`, `diagnosis`, `treatment`, `prescription advice`, `medical advice`, `AI doctor` appear; the "Offline. KB-grounded. Cite-or-defer." narrative reads in <8 seconds (N1 + v1.0 R1 mitigation; HOME-01)
  2. Four mode tiles (DrugSafe, ConsentReader, HealthPartner, ReportReader) render with consistent `accent` + `accentSoft` gradient pattern, mode-distinct iconography (Material Symbols, no emoji), and visible tap-affordance press states; a Compose UI test asserts all 4 tiles are present and tappable (HOME-02; N3 dynamic-color override prevention — `dynamicColor = false` is verified in `Theme.kt`)
  3. Model-ready status pill flips green ONLY when `app.startup.value is StartupState.Ready` — verified by a dry-run with a stale/missing `aegis_model.litertlm` sideloaded file: pill stays red, no tile-tap path reaches the W4 SIGSEGV failure mode (HOME-03; C5 mitigation; N5 stale-model dry-run)
  4. `StartupScreens` copy uses honest latency phrasing ("Loading on-device model — ~30s on first launch") and surfaces the resolved sideloaded path; the existing 64% decorative progress bar is replaced with real `StartupState` branch rendering (HOME-04)
  5. Build-time grep gate `grep -rEn "EngineRouter|KBDatabase|LiteRtLmEngine" android/app/src/main/java/com/aegis/health/ui/home/ android/app/src/main/java/com/aegis/health/ui/startup/` returns **empty** at phase close; all engine state flows through `app.startup` (HOME-05; C5 + N7 Bench-tab-leakage prevention as a corollary — `HomeScreen` four-tile composition is verified Bench-free)

**Plans**: 5 plans
- [x] 09-01-PLAN.md — Warm-up relocation to AegisApp.warmUpEngine() + HomeScreenStructureTest grep-gate regression (HOME-05; Wave 1) ✓ 2026-05-16
- [x] 09-02-PLAN.md — Hero copy refresh (OnDeviceChip → ValuePropChip) + N1-audit JVM test method (HOME-01; Wave 2) ✓ 2026-05-16
- [x] 09-03-PLAN.md — Tile ripple-on-rounded fix + Theme.kt dynamicColor=false param + 2 structure-test methods (HOME-02; Wave 3) ✓ 2026-05-16
- [x] 09-04-PLAN.md — Model-ready StatusPill (Gemma 4 ✓) wired to app.startup with strict is StartupState.Ready predicate (HOME-03; Wave 4) ✓ 2026-05-16
- [x] 09-05-PLAN.md — StartupLoadingScreen indeterminate bar + honest copy + CONVENTIONS.md doc + dry-run checklist + phase-close human-verify checkpoint (HOME-04; Wave 5) ✓ 2026-05-16 (D-04c monospace path footer rolled back during dry-run; scoped to StartupErrorScreen only)

**UI hint**: yes

### Phase 10: Demo Recording Prep + P1 Stretch

**Goal**: The demo is the deliverable. Pre-flight checklist locks the recording-day variables; three golden-path dry-runs verify on-device demo flow; voiceover script acknowledges the real ~5-min inference latency; the final build is tagged `v1.1.0-demo` with the v2 sideloaded model artifact reference. All 5 P1 stretch requirements are landed in priority order — STEP-07 → POLISH-05 → STREAM-05 → STREAM-06 → HOME-06 — but only what fits the remaining calendar slack. The hackathon submission window closes with a polished, honest, viscerally-demoable v1.1.0-demo build in hand.

**Depends on**: All P0 phases (5–9). HOME-06 specifically touches `DeferralScreen.kt:148` which lives in the same module Phase 9 already polished; that adjacency makes HOME-06 the cheapest opportunistic finisher.

**Requirements** (P0):
- DEMO-01 (Pre-flight checklist landed at `.planning/recording-checklist.md`: battery ≥ 80%, scrcpy ≥ 30 fps, model bundle at `/sdcard/Android/data/com.aegis.health/files/aegis_model.litertlm`)
- DEMO-02 (Dry-run captures three golden paths on-device without regression: DrugSafe warfarin+ibuprofen 72yo, ReportReader Tata 1mg PDF from Phase 4.1 fixture, HealthPartner 45yo male preventive)
- DEMO-03 (Voiceover script explicitly acknowledges real ~5-min inference latency; no recording shortcut that misrepresents speed)
- DEMO-04 (Final build tagged `v1.1.0-demo` with v2 sideloaded model artifact reference in release notes)

**Requirements** (P1 stretch — land in priority order, only what fits):
- STEP-07 (Stepper collapses to one-line "X tool calls" summary at final render; expandable on tap with citation chips per step — highest demo-trust signal, lowest cost)
- POLISH-05 (Reference-range visual bar — Apple Health idiom, green band with marker at user's value, renders on outside-range and borderline rows)
- STREAM-05 (Hand-rolled partial-JSON state machine renders flag cards as soon as `severity` + `description` parse — targets `AegisResponse.flags[]` only, never raw JSON to user)
- STREAM-06 (Final-synthesis explanation paragraph reveals progressively via `ProgressEvent.ExplanationPreview` typewriter — mapped to existing every-4-pieces throttle)
- HOME-06 (`DeferralScreen.kt:148` urgent-care-clinic deep-link TODO wired — `Intent.ACTION_VIEW geo:0,0?q=urgent+care` — closes a ghost button judges might tap during recording)

**Success Criteria** (what must be TRUE):
  1. `.planning/recording-checklist.md` exists, captures all 6 pre-flight items from PITFALLS C6 (battery ≥ 90%, charger plugged in, airplane mode ON, brightness 60–70%, BatteryProbe DISABLED, app restarted between modes), and is referenced from the voiceover script (DEMO-01; C6 mitigation)
  2. Three golden-path dry-runs each complete end-to-end on SM-S918B / Android 16 without regression — DrugSafe and HealthPartner run within their existing latency budgets, ReportReader synthesizes against the Tata 1mg fixture from Phase 4.1 with the new stepper + streaming preview visible throughout; scrcpy `--max-fps=30 --record demo.mp4` capture preview shows no animation choppiness at 30fps (DEMO-02; M7 scrcpy-frame-rate mitigation)
  3. Voiceover script explicitly says "running on your phone — about 5 minutes" (or close-equivalent) at the right beat; the recording does not cut/edit out inference dead-time to misrepresent speed (DEMO-03; C4 latency-honesty mitigation; pre-recorded fallback per `ON-DEVICE-DEPLOYMENT-ANALYSIS.md:119` is visually identical to the live path)
  4. Final build commit is tagged `v1.1.0-demo` with release notes referencing the v2 sideloaded model artifact at HF repo `V1rtucious/gemma4-e4b-toolcalling-litertlm-v2` (~8.20 GB) and the bundle path `./downloads-v2/aegis-sft-e4b/aegis_model.litertlm` (DEMO-04; per memory `project_sft_v4_download_paths.md`)
  5. **P1 stretch landed:** at least STEP-07 (citation chips on collapsed stepper) + HOME-06 (urgent-care deep-link) ship — these are the cheapest + highest-trust pair. POLISH-05, STREAM-05, STREAM-06 ship in priority order with whatever calendar slack remains; any unshipped P1 is captured in `## Future Requirements` of `REQUIREMENTS.md` (already pre-populated as a v2 carry-over)

**Plans**: TBD (`/gsd-plan-phase 10`)

**UI hint**: yes

## v1.1 Phase Dependencies

```
                                Day 1                Day 2-3              Day 3-4              Day 5
Track A (infra/critical path):
    Phase 5 (Infra) ──▶ Phase 6 (Streaming) ──▶ Phase 7 (Stepper UI + Skeletons) ──▶
                                                                                       \
Track B (polish, parallel from Day 1):                                                  \
    Phase 8 (ReportReader Polish) ──────────────▶ Phase 9 (Home + Startup) ────────────▶ Phase 10 (Demo Prep + P1 Stretch)
```

**Two-track parallelization rationale:**

- **Track A is the critical path.** Phase 5 lands BOM bump + shimmer dep + `FriendlyToolSummarizer.kt` + `LiteRtLmEngineStreamSplitTest`. Phase 6 wires `FlagPreview` subscriptions on ReportReader → HealthPartner. Phase 7 lands the visible `ToolStepper.kt` UI + `compose-shimmer` skeleton loaders + latency-honest copy.
- **Track B runs independently from Day 1.** Phase 8 (ReportReader visual polish) only needs the Phase 5 BOM bump for Material3 1.4.0; otherwise no Track A dependency. Phase 9 (home + startup polish) ships after Phase 8 to inherit the `tokenForStatus()` helper's polish for home-tile severity counters.
- **Phase 10 collapses both tracks.** Demo recording prep + P1 stretch land in priority order based on whatever calendar slack remains after the P0 phases close.
- **Critical pitfalls embedded across phases:** C1 (recomposition storm — Phase 6 every-4-pieces throttle), C2 (single-buffer-owner — Phase 5 `LiteRtLmEngineStreamSplitTest`), C3 (severity color drift — Phase 8 hex-literal grep gate), C4 (fake-typing animation — Phase 7 animation-rate ceiling + SKEL-04 "running on your phone — ~5 minutes" copy), C5 (StartupState gate — Phase 9 engine-read grep gate + dry-run with stale bundle).

## v1.1 Progress

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 5. Stepper + Streaming Infrastructure | 4/4 | Complete | 2026-05-15 |
| 6. Streaming Preview Wiring (ReportReader → HealthPartner) | 3/3 | Complete | 2026-05-15 |
| 7. ToolStepper UI + Latency-Honest Skeletons | 8/8 | Complete | 2026-05-15 |
| 8. ReportReader Visual Polish | 6/6 | Complete | 2026-05-16 |
| 9. Home + Startup Polish | 5/5 | Complete | 2026-05-16 |
| 10. Demo Recording Prep + P1 Stretch | 0/TBD | Ready | - |

## v1.1 Coverage

- v1.1 requirements: **40 total** (35 P0 + 5 P1)
- Mapped to phases: **40** ✓
- Unmapped: 0

**Note on P0/P1 counts:** REQUIREMENTS.md preamble cites "33 P0 + 7 P1"; the per-requirement-line P1 markers count to **5** (STEP-07, STREAM-05, STREAM-06, POLISH-05, HOME-06), so actual P0 = 35 and P1 = 5. This roadmap uses the per-line ground truth.

| Phase | Requirements | Count |
|-------|--------------|-------|
| 5 | INFRA-01, INFRA-02, INFRA-03, INFRA-04, INFRA-05, INFRA-06, INFRA-07 | 7 |
| 6 | STREAM-01, STREAM-02, STREAM-03, STREAM-04 | 4 |
| 7 | STEP-01, STEP-02, STEP-03, STEP-04, STEP-05, STEP-06, SKEL-01, SKEL-02, SKEL-03, SKEL-04, SKEL-05 | 11 |
| 8 | POLISH-01, POLISH-02, POLISH-03, POLISH-04 | 4 |
| 9 | HOME-01, HOME-02, HOME-03, HOME-04, HOME-05 | 5 |
| 10 | DEMO-01, DEMO-02, DEMO-03, DEMO-04 + P1 stretch: STEP-07, STREAM-05, STREAM-06, POLISH-05, HOME-06 | 4 P0 + 5 P1 = 9 |
| **Total** | | **40** (35 P0 + 5 P1) |

Cross-checks:
- Phase 5 success criteria collectively cover all 7 INFRA requirements; the `LiteRtLmEngineStreamSplitTest` regression gate (SC-2) is non-negotiable — it must land before Phase 6 introduces any new consumer of derived engine events.
- Phase 6 success criteria cover all 4 STREAM P0 requirements; ReportReader-before-HealthPartner ordering (open-question #2 resolution) is embedded in SC-1 + SC-2.
- Phase 7 success criteria cover all 6 STEP P0 + all 5 SKEL requirements; the ConsentReader exclusion (STEP-05) is asserted by SC-4 to prevent accidental fork drift.
- Phase 8 success criteria cover all 4 POLISH P0 requirements; the `LocalAegisColors.sev*` hex-literal grep gate (SC-4) is the C3 mitigation made executable.
- Phase 9 success criteria cover all 5 HOME P0 requirements; the engine-read grep gate (SC-5) is the C5 mitigation made executable, and the dry-run with stale model bundle (SC-3) is the N5 mitigation made executable.
- Phase 10 covers all 4 DEMO P0 requirements; all 5 P1 stretch requirements land in priority order STEP-07 → POLISH-05 → STREAM-05 → STREAM-06 → HOME-06 (open-question #3 resolution), with the lowest two (STEP-07 + HOME-06) being the cheap-and-trust pair that ship even with minimal slack.

---

*v1.1 roadmap appended: 2026-05-15*
*Phase numbering continues from v1.0 Phase 4.1 (most recent v1.0 phase on disk).*
*Open questions #1, #2, #3 from `.planning/research/SUMMARY.md` resolved in this roadmap; documented in Phase 7, Phase 6 ordering, and Phase 10 sequencing respectively.*
