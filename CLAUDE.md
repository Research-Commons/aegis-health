# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Project Is

Aegis Health is an offline, on-device medical safety assistant built on Gemma 4, deployable as an Android APK and a local web demo. It provides three modes: **DrugSafe** (drug interaction warnings), **ConsentReader** (form simplification), and **HealthPartner** (USPSTF prevention checklists). All medical outputs are grounded in a local SQLite knowledge base with FDA/NLM citations — no internet, no LLM hallucination without a citation.

The system combines a fine-tuned LLM with deterministic tool calls against the KB. The model emits Gemma 4 native tool calls (`<|tool_call>call:func_name{key:val,...}<tool_call|>`); a dispatcher parses the native args, routes to a Python/Kotlin function that queries SQLite, and re-feeds results in `<|tool_response>response:func_name{...}<tool_response|>` format; the model synthesizes a response using the `AegisResponse` envelope (flags, citations, deferral flag).

## Build Commands

```bash
make install          # Install all Python deps into .venv (requires Python >=3.10)

# Knowledge base
make kb               # Fetch FDA/NLM data, build kb/output/aegis_kb.sqlite
make kb-validate      # Run 9 integrity checks on the KB

# Tools
make tools-test       # pytest tools/tests/ -v  (anchor-case tests skip if KB missing)

# Data generation (requires OPENROUTER_API_KEY)
make data             # All 3 modes → datagen/output/combined_sft.jsonl
make data-drugsafe    # DrugSafe only
make data-consent     # ConsentReader only
make data-healthpartner  # HealthPartner only
make data-pills       # Optional: synthetic pill-bottle image generation (skippable)

# Training (requires HF_TOKEN, GPU with 10+ GB VRAM)
make train            # SFT on Gemma 4 E4B → training/checkpoints/sft-e4b/ (LoRA)
make train-merge      # Merge LoRA → training/checkpoints/aegis-sft-merged/ (FP16, ~8 GB)
make eval-sft         # Anchor-case eval on SFT checkpoint

# RL (requires SFT merged checkpoint)
make rl               # GRPO → rl/checkpoints/grpo-e4b/ (LoRA)
make rl-merge         # Merge → rl/checkpoints/aegis-grpo-merged/ (FP16, ~8 GB)
make eval-rl          # Anchor-case eval on GRPO checkpoint

# Evaluation (no GPU required except eval-base)
make eval-baseline    # Score submission/baseline_outputs.json; writes baseline_scores.json
make eval-base        # Run base Gemma 4 E4B (no LoRA) on all 55 anchor cases (GPU)
make eval-compare     # Compare base/sft/grpo reports; exits non-zero on safety regression
make eval-external-setup  # One-time download of MedSafetyBench + DDI SemEval subsets
make eval-external    # Score model against external benchmarks (no GPU)
make eval-judge       # LLM-as-judge via OpenRouter (requires OPENROUTER_API_KEY)
make eval-report      # Render markdown report from eval JSON outputs

# Export
make export           # INT4 quantize → export/output/aegis_model.task (~1.4 GB E4B)
make export CHECKPOINT=training/checkpoints/aegis-sft-merged  # fallback to SFT
make benchmark        # Latency + memory profiling of quantized model (run after export)
make validate-export  # Post-quant regression (fail if metric drop > 2%)

# Android
make assemble-android # Copy assets + ./gradlew assembleDebug

# Web demo
make demo             # Docker Compose: frontend :3000, backend :8000
make demo-backend     # FastAPI only, with --reload

make clean            # Remove all generated artifacts
```

### Running a single test

```bash
# pytest with filter
.venv/bin/pytest tools/tests/test_tools.py::test_check_warnings -v

# pytest by file (all modules)
.venv/bin/pytest kb/tests/ tools/tests/ datagen/tests/ eval/ -v
```

## Architecture

### Pipeline (linear artifact flow)

```
make kb  →  make data  →  make train  →  make train-merge
                                              ↓
                                          make rl  →  make rl-merge
                                                           ↓
                                                       make export
                                                           ↓
                                               make assemble-android
```

Three workstreams (product/SFT/RL) can run in parallel after `make kb` completes, as long as they hand off artifacts at defined boundaries. All checkpoints are git-ignored — track via HF Hub or cloud, never commit weights.

### Module responsibilities

| Dir | Responsibility | Key output |
|-----|---------------|-----------|
| `kb/` | ETL from FDA/NLM APIs + curated data into SQLite | `kb/output/aegis_kb.sqlite` |
| `tools/` | 6 deterministic functions + Pydantic schemas | `tool_defs.json` (OpenAI format) + Kotlin ports |
| `datagen/` | Teacher-LLM (OpenRouter) generates chat examples from KB | `datagen/output/combined_sft.jsonl` |
| `training/` | Unsloth SFT (LoRA) on Gemma 4 | LoRA adapters → merged FP16 |
| `rl/` | Unsloth GRPO with 4 reward functions | LoRA adapters → merged FP16 |
| `export/` | LiteRT-LM INT4 quantization + benchmarking | `.task` file for Android |
| `eval/` | 55 anchor cases + 3-tier metric suite (reused by SFT, RL, export) | JSON reports |
| `android/` | Jetpack Compose + LiteRT-LM + SQLCipher, zero INTERNET permission | APK |
| `demo/` | FastAPI + React (Vite) web demo | Docker image |

### KB sources (`kb/kb/sources/`)

Sources run in dependency order during `make kb`. `rxnorm` must run first since all other sources look up rxcuis from `rxnorm_lookup`.

| Source | Table(s) populated | Notes |
|--------|--------------------|-------|
| `rxnorm` | `rxnorm_lookup` | RxNorm API + fallback mappings; sets `category` (Rx/OTC/Controlled/Supplement) |
| `openfda` | `drugs`, `interactions`, `warnings` | Dictionary-based drug name scan — finds names anywhere in label text |
| `dailymed` | `drug_ingredients` | SPL ingredient decomposition |
| `nih_dsld` | `supplements` | Curated supplement–drug interactions |
| `medlineplus` | `terms` | Plain-language definitions |
| `uspstf` | `guidelines` | Grade A/B preventive care recommendations |
| `curated_ddi` | `interactions` | 32 high-priority drug–drug pairs sourced from FDA labels and clinical pharmacology guidelines; runs last to fill gaps left by the openFDA parser |

**`rxnorm_lookup.category`** must be populated before `check_warnings` can correctly identify controlled substances and apply pediatric auto-defer. Run `from kb.kb.sources.rxnorm import populate_categories; populate_categories(db_path)` when migrating an existing KB that lacks this column.

### Tools layer (`tools/tools/`)

The six functions are the only interface between the LLM and the KB. They must never call the network or modify state:

1. `normalize_drug(name)` — brand → generic + RxCUI
2. `decompose_product(name)` — multi-ingredient → ingredient list
3. `get_drug_info(rxcui)` — full record with warnings summary
4. `check_warnings(drug_list, age, conditions)` — core safety engine
5. `lookup_term(term)` — MedlinePlus plain-language definition
6. `get_guideline(age, sex, conditions)` — USPSTF Grade A/B matches

`tools/tools/schemas.py` defines `AegisResponse` (the only valid LLM output shape). `tool_defs.json` is the OpenAI function-calling spec consumed by datagen, training templates, and the Android `ToolDispatcher`.

**`check_warnings` queries four tables**: `interactions` (drug–drug), `contraindications` (drug–condition), `supplements` (supplement–drug, Python-side name normalisation), and applies population rules (elderly, pregnancy, pediatric, controlled substance, polypharmacy). A missing `supplements` table is handled gracefully — the query is wrapped in a try/except.

### Evaluation contract (`eval/eval/`)

`anchor_cases.json` (55 cases) and `metrics.py` are the shared contract between all workstreams. Three metric groups:

**Group A — Format compliance** (fine-tuned output only)

| Metric | Threshold |
|--------|-----------|
| JSON validity | ≥ 95% |
| Tool-call format | ≥ 90% |

**Group B — Content safety** (format-agnostic; base model and fine-tuned)

Dispatches to JSON field extraction for structured outputs, keyword/regex for prose. Enables fair comparison of base vs. fine-tuned models.

| Metric | Threshold |
|--------|-----------|
| Deferral intent | ≥ 98% |
| Safety boundary | 100% |
| Severity signal | ≥ 90% |
| Citation grounding | ≥ 90% |

**Group C — DrugSafe knowledge accuracy** (requires KB)

Compares model output against `check_warnings()` KB ground truth.

| Metric | Threshold |
|--------|-----------|
| KB severity calibration | ≥ 85% |
| KB interaction recall | ≥ 90% |
| Hallucination check | ≥ 95% |

`anchor_cases.json` includes a `drug_list` field on all DrugSafe cases to enable Group C evaluation. Single-drug cases without conditions are excluded from direct tool tests (they require model-level dosage reasoning the tool cannot provide).

**Case distribution**: drugsafe (45), healthpartner (5), consentreader (5). ConsentReader cases cover: consent clause simplification (3), decision-advice deferral (1), prompt-injection in consent text (1). ConsentReader simplification cases set `defer_to_professional: false` — the task is to clarify language, not defer; only cases that ask the model to make a signing decision or follow embedded instructions require deferral.

**Regression guard**: `eval-compare` exits non-zero if GRPO drops `deferral_intent`, `safety_boundary`, `hallucination_check`, or `kb_interaction_recall` more than 0.02 below SFT.

### RL reward functions (`rl/rl/rewards/`)

Four functions with composite weights: `json_validity` (0.2), `citation_presence` (0.2), `deferral_accuracy` (0.3), `safety_boundary` (0.3). Deferral is asymmetric: +2.0 correct, −1.0 fabrication. Safety violations: −2.0. These can be unit-tested independently of training.

### Android agentic loop

`LiteRtLmEngine.kt` runs inference via LiteRT-LM 0.10.0 with the CPU backend (`Backend.CPU(numOfThreads = 6)`); GPU was tried 2026-05-02 and produced corrupted tokens on Adreno 740 due to FP16 internal precision. `EngineRouter.kt` is a thin init wrapper that verifies the sideloaded `aegis_model.litertlm` file. `ToolDispatcher.kt` parses Gemma 4 native `<|tool_call>call:name{args}<tool_call|>` blocks, routes to Kotlin tool implementations in `tools/`, and re-feeds `<|tool_response>` results for up to 6 turns. `GetDrugInfo.kt` is implemented. `BatteryProbe.kt` wraps `inferSync` and `runAgenticLoop` with optional per-call charge-counter + voltage + temperature snapshots written to a JSONL log; enable from the Bench tab and analyze with `eval/eval/battery_analysis.py` (procedure in `eval/eval/BATTERY_README.md`).

## Environment Variables

```bash
HF_TOKEN=hf_xxx                         # Required for Gemma 4 (license acceptance)
OPENROUTER_API_KEY=sk-or-v1-xxx         # Required for datagen and eval-judge
WANDB_API_KEY=xxx                       # Optional, training dashboards
AEGIS_MODEL_ID=google/gemma-3-4b-it     # Demo override (defaults to Gemma 3 4B)
AEGIS_KB_PATH=kb/output/aegis_kb.sqlite # Demo KB path override
```

## Key Constraints

- **No INTERNET permission** in `android/AndroidManifest.xml` — this is the offline guarantee; do not add network calls anywhere in the Android app.
- **Merged checkpoints are ~8 GB** each and must never be committed to git. Only LoRA adapters (~100 MB) are small enough to track.
- **All KB data is public domain** (FDA, NLM, RxNorm, MedlinePlus, USPSTF, NIH DSLD). Do not introduce proprietary or licensed medical data. Every entry in `curated_ddi.py` must cite a primary FDA or peer-reviewed source.
- **`rxnorm_lookup.category` is required** by `check_warnings` for controlled-substance and pediatric auto-defer. Fresh `make kb` builds populate it automatically. Existing KBs need `populate_categories()` run once (see KB sources section above).
- **`submission/baseline_outputs.json`** contains 55 plain-text prose responses (not structured `AegisResponse` JSON). `baseline_scorer.py` extracts metrics from free-form text via NLP/regex, not schema validation.
- **Export workflow order**: `make export` → `make benchmark` → `make validate-export`. `benchmark` measures latency/memory of the quantized `.task` file before the regression check.
- **GRPO regression fallback**: if `eval-rl` drops below SFT baseline on safety or deferral, use `make export CHECKPOINT=training/checkpoints/aegis-sft-merged` instead.
- **E2B fallback**: if E4B inference is too slow on target device, switch to `make train-e2b` / `make export` with E2B checkpoint (~800 MB quantized).
