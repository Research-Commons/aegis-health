# SFT Training (separable workstream)

Supervised fine-tuning of Gemma 4 with LoRA via **Unsloth**. This module is fully self-contained — you only need the training data JSONL and the anchor cases.

## Inputs required

| File | Produced by |
|------|-------------|
| `datagen/output/combined_sft.jsonl` | [`datagen/`](../datagen/) |
| `eval/eval/anchor_cases.json` | [`eval/`](../eval/) |
| Hugging Face token with Gemma 4 access | (manual — accept the license on HF) |

## Hardware

| Model | Min VRAM | Recommended |
|-------|----------|-------------|
| Gemma 4 E4B | 10 GB | Kaggle T4 x2 free, Colab A100 paid |
| Gemma 4 E2B (fallback) | 8 GB | Kaggle T4 free |

## Run

```bash
make install
export HF_TOKEN=hf_xxx

# E4B (primary)
make train

# E2B fallback
make train-e2b
```

Checkpoints land in `training/checkpoints/sft-e4b/` as LoRA adapters (small). Merge them into the base model before GRPO or export:

```bash
make train-merge
# produces training/checkpoints/aegis-sft-merged/  (~8 GB FP16)
```

## Run on Kaggle / Colab

Use [`training/notebooks/sft_colab.ipynb`](training/notebooks/sft_colab.ipynb):

1. Upload `combined_sft.jsonl` + `anchor_cases.json`
2. Set `HF_TOKEN` in secrets
3. Run all cells
4. Download `aegis-sft-merged/`

## Config

[`training/configs/sft_e4b.yaml`](training/configs/sft_e4b.yaml) — LoRA r=16, α=32, q/k/v/o projections, 3 epochs, cosine LR, eval every 50 steps.

## Outputs

| Path | Contents |
|------|----------|
| `training/checkpoints/sft-e4b/` | LoRA adapters + tokenizer + training logs |
| `training/checkpoints/aegis-sft-merged/` | **Full merged model — consumed by GRPO and export** |
| `eval/reports/sft_*.json` | Anchor-case eval metrics |

## Hand-off

Pass `training/checkpoints/aegis-sft-merged/` to the RL workstream. They'll point [`rl/configs/grpo_e4b.yaml`](../rl/configs/grpo_e4b.yaml) `base_model:` at that directory.
