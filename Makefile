.PHONY: kb tools data train rl export eval demo clean

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
	python -m datagen.teacher --all

data-drugsafe:
	python -m datagen.teacher --mode drugsafe

data-consent:
	python -m datagen.teacher --mode consent

data-healthpartner:
	python -m datagen.teacher --mode healthpartner

data-pills:
	python -m datagen.pill_renderer

# --- SFT training ---
train:
	python -m training.sft --config training/configs/sft_e4b.yaml

train-e2b:
	python -m training.sft --config training/configs/sft_e2b.yaml

train-merge:
	python -m training.merge_export

# --- GRPO RL ---
rl:
	python -m rl.grpo --config rl/configs/grpo_e4b.yaml

# --- Export / quantize ---
export:
	python -m export.quantize

benchmark:
	python -m export.benchmark

# --- Evaluation ---
eval:
	python -m eval.runner --checkpoint training/checkpoints/aegis-sft-merged/

eval-rl:
	python -m eval.runner --checkpoint rl/checkpoints/aegis-grpo-merged/

eval-report:
	python -m eval.report

# --- Web demo ---
demo:
	cd demo && docker-compose up --build

demo-backend:
	cd demo/backend && uvicorn main:app --reload --host 0.0.0.0 --port 8000

# --- Cleanup ---
clean:
	find . -type d -name __pycache__ -exec rm -rf {} +
	rm -f kb/output/*.sqlite
	rm -f datagen/output/*.jsonl
	rm -rf datagen/output/pill_images/*.png
	rm -rf eval/reports/*
