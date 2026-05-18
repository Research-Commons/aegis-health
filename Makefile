.PHONY: help install kb kb-validate tools-test data data-drugsafe data-consent data-healthpartner data-pills train train-e2b train-merge rl rl-merge export export-base benchmark validate-export eval eval-sft eval-rl eval-report eval-base eval-base-with-tools eval-compare eval-baseline eval-external eval-judge eval-external-setup demo demo-backend assemble-android clean

help:
	@echo "Aegis Health — top-level targets"
	@echo ""
	@echo "  make install          Install all Python dependencies"
	@echo ""
	@echo "  make kb               Build the SQLite knowledge base  -> kb/output/aegis_kb.sqlite"
	@echo "  make kb-validate      Run integrity checks on the KB"
	@echo "  make tools-test       Run the tool-layer test suite"
	@echo ""
	@echo "  make data             Generate all synthetic SFT data  -> datagen/output/combined_sft.jsonl"
	@echo "  make data-pills       Render synthetic pill-bottle images"
	@echo ""
	@echo "  make train            SFT on Gemma 4 E4B                -> training/checkpoints/sft-e4b/"
	@echo "  make train-e2b        SFT fallback on Gemma 4 E2B"
	@echo "  make train-merge      Merge SFT LoRA into base model    -> training/checkpoints/aegis-sft-merged/"
	@echo ""
	@echo "  make rl               GRPO on SFT checkpoint            -> rl/checkpoints/grpo-e4b/"
	@echo "  make rl-merge         Merge GRPO LoRA into base model   -> rl/checkpoints/aegis-grpo-merged/"
	@echo ""
	@echo "  make export           Quantize best checkpoint to INT4  -> export/output/aegis_model.task"
	@echo "  make export-base      Download + quantize base Gemma E4B (no fine-tuning) -> export/output/aegis_base_model.task"
	@echo "  make benchmark        Benchmark the quantized model"
	@echo "  make validate-export  Validate quantized model against anchor cases"
	@echo ""
	@echo "  make eval-sft         Evaluate SFT-merged model"
	@echo "  make eval-rl          Evaluate GRPO-merged model"
	@echo "  make eval-report      Render markdown eval report"
	@echo "  make eval-base        Run base Gemma 4 E4B (no fine-tuning, no tools) — historical baseline"
	@echo "  make eval-base-with-tools  Run base Gemma 4 E4B with tool dispatch — fair reference for SFT"
	@echo "  make eval-baseline    Score submission/baseline_outputs.json (no GPU)"
	@echo "  make eval-compare     Compare all eval reports (base/sft/grpo) side by side"
	@echo "  make eval-external-setup  Download external benchmark datasets (once)"
	@echo "  make eval-external    Run MedSafetyBench + DDI SemEval external benchmarks"
	@echo "  make eval-judge       LLM-as-judge Tier 3 eval (requires OPENROUTER_API_KEY)"
	@echo ""
	@echo "  make demo             Run the web demo via Docker Compose"
	@echo "  make demo-backend     Run the demo backend locally"
	@echo ""
	@echo "  make assemble-android Build the Android debug APK (fine-tuned model)"
	@echo "  make assemble-android APK_MODEL=export/output/aegis_base_model.task  Build with base model"
	@echo "  make clean            Remove generated artifacts"

install:
	pip install -e ".[kb,tools,datagen,eval,training,rl,export,dev]"

# --- Knowledge Base ---
kb:
	python -m kb.build

kb-validate:
	python -m kb.validate

# --- Tool layer tests ---
tools-test:
	pytest tools/tests/ -v

# --- Data generation ---
data:
	python -m datagen.teacher --mode all

data-drugsafe:
	python -m datagen.teacher --mode drugsafe

data-consent:
	python -m datagen.teacher --mode consent

data-healthpartner:
	python -m datagen.teacher --mode healthpartner

data-pills:
	python -m datagen.pill_renderer --db-path kb/output/aegis_kb.sqlite --output-dir datagen/output/pill_images

# --- SFT training ---
train:
	python -m training.training sft --config training/configs/sft_e4b.yaml

train-e2b:
	python -m training.training sft --config training/configs/sft_e2b.yaml

train-merge:
	python -m training.training merge \
		--checkpoint-dir training/checkpoints/sft-e4b \
		--output-dir training/checkpoints/aegis-sft-merged

# --- GRPO RL ---
rl:
	python -m rl.grpo --config rl/configs/grpo_e4b.yaml

rl-merge:
	python -m training.training merge \
		--checkpoint-dir rl/checkpoints/grpo-e4b \
		--output-dir rl/checkpoints/aegis-grpo-merged

# --- Export / quantize ---
# Defaults to the GRPO-merged checkpoint; override CHECKPOINT=... for SFT-only.
CHECKPOINT ?= rl/checkpoints/aegis-grpo-merged

export:
	python -m export quantize \
		--checkpoint $(CHECKPOINT) \
		--output export/output/aegis_model.task \
		--quantization int4

export-base:
	python scripts/export_base_model.py \
		--output export/output/aegis_base_model.task

benchmark:
	python -m export benchmark --model export/output/aegis_model.task --device cpu --num-runs 20

validate-export:
	python -m export validate \
		--model export/output/aegis_model.task \
		--anchor-cases eval/eval/anchor_cases.json

# --- Evaluation ---
# ANCHOR selects the anchor case set: 'dev' (default, anchor_cases.json) or 'heldout' (anchor_cases_heldout.json).
# The held-out set is the publishable SFT-vs-base delta; dev is for training-adjacent monitoring.
ANCHOR ?= dev
ifeq ($(ANCHOR),heldout)
ANCHOR_PATH := eval/eval/anchor_cases_heldout.json
else
ANCHOR_PATH := eval/eval/anchor_cases.json
endif

# SFT uses run_base.py with --fp16 and --tag sft so both base and SFT are scored through
# the same code path and all three metric groups (A/B/C), with matched precision.
SFT_CHECKPOINT ?= rescommons/aegis-sft-e4b-merged-v4

eval-sft:
	python -m eval.run_base --model-id $(SFT_CHECKPOINT) --tag sft --fp16 --enable-tools --anchor-path $(ANCHOR_PATH)

eval-rl:
	python -m eval.runner --checkpoint rl/checkpoints/aegis-grpo-merged/ --tag grpo --enable-tools --anchor-path $(ANCHOR_PATH)

eval-report:
	python -m eval.report

# Tier 0: Score the 3 provided baseline_outputs.json without any GPU
eval-baseline:
	python -m eval.baseline_scorer \
		--input submission/baseline_outputs.json \
		--output submission/baseline_scores.json

# Tier 1: Run base Gemma 4 E4B (no fine-tuning) on all anchor cases (GPU required)
# Use --fp16 to match the SFT precision for fair head-to-head.
MODEL_ID ?= google/gemma-4-e4b-it

eval-base:
	python -m eval.run_base --model-id $(MODEL_ID) --fp16 --anchor-path $(ANCHOR_PATH)

# Same model as eval-base, but with the tool-dispatch loop enabled. The base model
# (almost always) won't emit valid <tool_call> JSON, so the loop exits on turn 1
# and produces the same single-shot output. The point: gives an apples-to-apples
# reference for SFT (which IS evaluated with --enable-tools).
eval-base-with-tools:
	python -m eval.run_base --model-id $(MODEL_ID) --fp16 --enable-tools --tag base-with-tools --anchor-path $(ANCHOR_PATH)

# Compare all available eval reports (base + base-with-tools + sft + grpo + export)
eval-compare:
	python -m eval.compare \
		eval/reports/base_eval_*.json \
		eval/reports/eval_results_base-with-tools_*.json \
		eval/reports/eval_results_sft_*.json \
		eval/reports/eval_results_grpo_*.json

# Tier 2: Download external benchmark datasets (run once, commits the filtered cases)
eval-external-setup:
	python eval/benchmarks/medsafety/setup.py
	python eval/benchmarks/ddi_semeval/setup.py

# Tier 2: Run external benchmarks against a model checkpoint
# Usage: make eval-external CHECKPOINT=rl/checkpoints/aegis-grpo-merged TAG=grpo
TAG ?= run
eval-external:
	python -m eval.benchmarks.eval_external \
		--tag $(TAG) \
		--checkpoint $(CHECKPOINT)

# Tier 3: LLM-as-judge (requires OPENROUTER_API_KEY and a completed eval report)
# Usage: make eval-judge REPORT=eval/reports/base_eval_*.json TAG=base
REPORT ?= eval/reports/base_eval_*.json
eval-judge:
	python -m eval.llm_judge \
		--report $(REPORT) \
		--tag $(TAG)

# --- Web demo ---
demo:
	cd demo && docker compose up --build

demo-backend:
	cd demo/backend && uvicorn main:app --reload --host 0.0.0.0 --port 8000

# --- Android APK ---
# Override APK_MODEL to use a different .task file, e.g.:
#   make assemble-android APK_MODEL=export/output/aegis_base_model.task
APK_MODEL ?= export/output/aegis_model.task

assemble-android:
	mkdir -p android/app/src/main/assets
	cp kb/output/aegis_kb.sqlite  android/app/src/main/assets/aegis_kb.sqlite
	cd android && ./gradlew assembleDebug
	@echo ""
	@echo "APK built. The model is NOT bundled (too large for APK ZIP format)."
	@echo "After installing the APK, sideload the model with:"
	@echo "  adb push $(APK_MODEL) /sdcard/Android/data/com.aegis.health/files/aegis_model.task"

# --- Cleanup ---
clean:
	find . -type d -name __pycache__ -exec rm -rf {} +
	rm -f kb/output/*.sqlite
	rm -f datagen/output/*.jsonl
	rm -rf datagen/output/pill_images/*.png
	rm -rf eval/reports/*
	rm -f export/output/*.task
