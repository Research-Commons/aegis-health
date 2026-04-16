"""GRPO training script for Aegis Health.

Uses Unsloth + TRL's GRPOTrainer to fine-tune an SFT checkpoint with
reinforcement learning guided by the composite reward function.

Usage:
    python -m rl.grpo --config rl/configs/grpo_e4b.yaml
"""

from __future__ import annotations

import argparse
import json
import logging
import sys
from pathlib import Path
from typing import Any

import yaml
from datasets import Dataset

log = logging.getLogger(__name__)


def load_config(path: str) -> dict[str, Any]:
    with open(path) as f:
        return yaml.safe_load(f)


def load_prompts(prompt_data_path: str) -> Dataset:
    """Load anchor cases (or any JSON list) and convert to a HF Dataset.

    Each row needs a ``"prompt"`` column for GRPOTrainer.  We build the
    prompt from the anchor case ``input`` field, wrapping it in the chat
    template the SFT model was trained with.
    """
    path = Path(prompt_data_path)
    if not path.exists():
        log.error("Prompt data not found: %s", path)
        sys.exit(1)

    with open(path) as f:
        cases = json.load(f)

    rows: list[dict[str, Any]] = []
    for case in cases:
        mode = case.get("mode", "drugsafe")
        system_msg = (
            "You are Aegis Health, an on-device medical safety assistant. "
            "Respond ONLY with valid JSON matching the AegisResponse schema. "
            f"Mode: {mode}."
        )
        prompt = (
            f"<start_of_turn>user\n"
            f"[system] {system_msg}\n\n"
            f"{case['input']}<end_of_turn>\n"
            f"<start_of_turn>model\n"
        )
        rows.append({
            "prompt": prompt,
            "case_id": case["id"],
            "category": case.get("category", ""),
            "expected": json.dumps(case.get("expected", {})),
        })

    return Dataset.from_list(rows)


def build_reward_fn(weights: dict[str, float]):
    """Create the composite reward callable for GRPOTrainer.

    GRPOTrainer calls each reward function with ``(completions, **kwargs)``
    where ``completions`` is a list of strings.  We wrap CompositeReward
    so it operates per-completion and returns a list of floats.
    """
    from rl.rewards.composite import CompositeReward

    composite = CompositeReward(weights=weights)

    def reward_fn(completions: list[str], **kwargs: Any) -> list[float]:
        scores: list[float] = []
        for completion in completions:
            score = composite(completion)
            scores.append(score)
        return scores

    return reward_fn


def main(config_path: str) -> None:
    config = load_config(config_path)

    log.info("Loading SFT checkpoint from %s", config["base_model"])

    from unsloth import FastLanguageModel

    model, tokenizer = FastLanguageModel.from_pretrained(
        model_name=config["base_model"],
        max_seq_length=config["max_seq_length"],
        load_in_4bit=config.get("load_in_4bit", True),
    )

    model = FastLanguageModel.get_peft_model(
        model,
        r=16,
        lora_alpha=16,
        lora_dropout=0,
        target_modules=[
            "q_proj", "k_proj", "v_proj", "o_proj",
            "gate_proj", "up_proj", "down_proj",
        ],
        bias="none",
        use_gradient_checkpointing="unsloth",
        random_state=42,
    )

    prompts_dataset = load_prompts(config["prompt_data"])
    log.info("Loaded %d training prompts", len(prompts_dataset))

    reward_weights = config.get("reward", {}).get("weights", None)
    reward_fn = build_reward_fn(reward_weights)

    grpo_cfg = config.get("grpo", {})
    train_cfg = config.get("training", {})

    from trl import GRPOTrainer, GRPOConfig

    training_args = GRPOConfig(
        output_dir=config.get("output_dir", "rl/checkpoints/grpo-e4b"),
        per_device_train_batch_size=train_cfg.get("batch_size", 2),
        gradient_accumulation_steps=train_cfg.get("gradient_accumulation_steps", 8),
        num_train_epochs=train_cfg.get("epochs", 1),
        learning_rate=float(train_cfg.get("lr", 5e-6)),
        lr_scheduler_type=train_cfg.get("lr_scheduler", "cosine"),
        warmup_ratio=train_cfg.get("warmup_ratio", 0.05),
        optim=train_cfg.get("optimizer", "adamw_8bit"),
        logging_steps=train_cfg.get("logging_steps", 5),
        save_steps=train_cfg.get("save_steps", 50),
        bf16=True,
        # GRPO-specific
        beta=grpo_cfg.get("beta", 0.1),
        loss_type=grpo_cfg.get("loss_type", "dapo"),
        num_generations=grpo_cfg.get("num_generations", 4),
        max_completion_length=grpo_cfg.get("max_new_tokens", 512),
        temperature=grpo_cfg.get("temperature", 0.7),
    )

    trainer = GRPOTrainer(
        model=model,
        processing_class=tokenizer,
        reward_funcs=[reward_fn],
        args=training_args,
        train_dataset=prompts_dataset,
    )

    log.info("Starting GRPO training …")
    trainer.train()

    log.info("Saving final checkpoint to %s", training_args.output_dir)
    trainer.save_model(training_args.output_dir)
    tokenizer.save_pretrained(training_args.output_dir)

    log.info("GRPO training complete.")


def cli() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s  %(levelname)-8s  %(name)s  %(message)s",
    )
    parser = argparse.ArgumentParser(description="Aegis Health GRPO training")
    parser.add_argument(
        "--config",
        type=str,
        default="rl/configs/grpo_e4b.yaml",
        help="Path to GRPO config YAML",
    )
    args = parser.parse_args()
    main(args.config)


if __name__ == "__main__":
    cli()
