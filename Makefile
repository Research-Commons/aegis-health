.PHONY: help install kb kb-validate tools-test data data-drugsafe data-consent data-healthpartner data-pills train train-e2b train-merge rl rl-merge export benchmark validate-export eval eval-sft eval-rl eval-report demo demo-backend assemble-android clean

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
	@echo "  make benchmark        Benchmark the quantized model"
	@echo "  make validate-export  Validate quantized model against anchor cases"
	@echo ""
	@echo "  make eval-sft         Evaluate SFT-merged model"
	@echo "  make eval-rl          Evaluate GRPO-merged model"
	@echo "  make eval-report      Render markdown eval report"
	@echo ""
	@echo "  make demo             Run the web demo via Docker Compose"
	@echo "  make demo-backend     Run the demo backend locally"
	@echo ""
	@echo "  make assemble-android Build the Android debug APK"
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

benchmark:
	python -m export benchmark --model export/output/aegis_model.task --device cpu --num-runs 20

validate-export:
	python -m export validate \
		--model export/output/aegis_model.task \
		--anchor-cases eval/eval/anchor_cases.json

# --- Evaluation ---
eval-sft:
	python -m eval.runner --checkpoint training/checkpoints/aegis-sft-merged/ --tag sft

eval-rl:
	python -m eval.runner --checkpoint rl/checkpoints/aegis-grpo-merged/ --tag grpo

eval-report:
	python -m eval.report

# --- Web demo ---
demo:
	cd demo && docker compose up --build

demo-backend:
	cd demo/backend && uvicorn main:app --reload --host 0.0.0.0 --port 8000

# --- Android APK ---
assemble-android:
	cp kb/output/aegis_kb.sqlite  android/app/src/main/assets/aegis_kb.sqlite
	cp export/output/aegis_model.task android/app/src/main/assets/aegis_model.task
	cd android && ./gradlew assembleDebug

# --- Cleanup ---
clean:
	find . -type d -name __pycache__ -exec rm -rf {} +
	rm -f kb/output/*.sqlite
	rm -f datagen/output/*.jsonl
	rm -rf datagen/output/pill_images/*.png
	rm -rf eval/reports/*
	rm -f export/output/*.task
