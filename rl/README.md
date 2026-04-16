# GRPO RL (separable workstream)

Reinforcement learning with **Unsloth + TRL's `GRPOTrainer`** using four custom reward functions that target the safety-critical behaviors our anchor cases measure.

## Inputs required

| File | Produced by |
|------|-------------|
| `training/checkpoints/aegis-sft-merged/` | [`training/`](../training/) |
| `eval/eval/anchor_cases.json` | [`eval/`](../eval/) |

This module defines its own `AegisResponse` schema locally in the reward code, so it has zero runtime dependency on the `tools/` package.

## What you can build in parallel with SFT

The four reward functions in [`rl/rl/rewards/`](rl/rl/rewards/) are pure functions that take a string and return a float. They can be fully developed and unit-tested before the SFT checkpoint exists:

- `json_validity.py` — does the output parse as a valid `AegisResponse`?
- `citation_presence.py` — are citations present and non-empty?
- `deferral_accuracy.py` — does the model correctly set `defer_to_professional`? (asymmetric: +2.0 correct, -1.0 fabrication)
- `safety_boundary.py` — does the model refuse dosage/diagnosis probes? (-2.0 for violations)
- `composite.py` — weighted combination (default: 0.2/0.2/0.3/0.3)

## Hardware

| Model | Min VRAM | Recommended |
|-------|----------|-------------|
| Gemma 4 E4B SFT → GRPO | 12 GB | Colab A100 or Kaggle T4 x2 |

## Run

```bash
make install
export HF_TOKEN=hf_xxx

# Train (~1-2 hrs on a T4)
make rl

# Merge GRPO LoRA into a standalone model
make rl-merge
# produces rl/checkpoints/aegis-grpo-merged/
```

## Run on Kaggle / Colab

Use [`rl/notebooks/grpo_colab.ipynb`](rl/notebooks/grpo_colab.ipynb).

## Config

[`rl/configs/grpo_e4b.yaml`](rl/configs/grpo_e4b.yaml):

```yaml
base_model: training/checkpoints/aegis-sft-merged/
grpo:
  beta: 0.1
  loss_type: dapo
  num_generations: 4
  max_new_tokens: 512
training:
  lr: 5e-6          # much lower than SFT
  epochs: 1
reward:
  weights: {json: 0.2, citation: 0.2, deferral: 0.3, safety: 0.3}
```

## Outputs

| Path | Contents |
|------|----------|
| `rl/checkpoints/grpo-e4b/` | GRPO LoRA adapters |
| `rl/checkpoints/aegis-grpo-merged/` | **Full merged model — consumed by export** |
| `eval/reports/grpo_*.json` | Anchor-case eval after GRPO |

## Hand-off

Point [`export/`](../export/) at `rl/checkpoints/aegis-grpo-merged/`:

```bash
make export CHECKPOINT=rl/checkpoints/aegis-grpo-merged
```

If GRPO regresses vs SFT on the anchor set, fall back to SFT-only:

```bash
make export CHECKPOINT=training/checkpoints/aegis-sft-merged
```
