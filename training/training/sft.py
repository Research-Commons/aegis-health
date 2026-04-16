"""Supervised fine-tuning script for Aegis Health using Unsloth + SFTTrainer.

Usage:
    python -m training.sft --config training/configs/sft_e4b.yaml
    python -m training.sft --config training/configs/sft_e2b.yaml

Loads a Gemma 4 model via Unsloth for memory-efficient 4-bit training with
LoRA, trains on the combined SFT dataset, and evaluates periodically using the
anchor-case callback.
"""

from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

import yaml

logger = logging.getLogger(__name__)

PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent


def load_config(config_path: str) -> dict:
    path = Path(config_path)
    if not path.exists():
        raise FileNotFoundError(f"Config not found: {path}")
    with open(path) as f:
        config = yaml.safe_load(f)
    logger.info("Loaded config from %s", path)
    return config


def setup_logging(verbose: bool = False) -> None:
    level = logging.DEBUG if verbose else logging.INFO
    logging.basicConfig(
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
        level=level,
        handlers=[logging.StreamHandler(sys.stdout)],
    )


def resolve_data_path(config: dict, cli_data: str | None) -> Path:
    if cli_data:
        return Path(cli_data)
    default = PROJECT_ROOT / "datagen" / "output" / "combined_sft.jsonl"
    return default


def train(config: dict, data_path: Path, resume_from: str | None = None) -> None:
    from unsloth import FastLanguageModel
    from trl import SFTConfig, SFTTrainer

    from training.training.data_loader import load_dataset_from_jsonl
    from training.training.eval_callback import AegisEvalCallback

    model_name = config["model_name"]
    max_seq_length = config["max_seq_length"]
    load_in_4bit = config.get("load_in_4bit", True)

    logger.info("Loading model %s (4-bit=%s, seq_len=%d)...", model_name, load_in_4bit, max_seq_length)
    model, tokenizer = FastLanguageModel.from_pretrained(
        model_name=model_name,
        max_seq_length=max_seq_length,
        load_in_4bit=load_in_4bit,
    )

    lora_cfg = config["lora"]
    logger.info(
        "Applying LoRA — r=%d, alpha=%d, targets=%s, dropout=%.3f",
        lora_cfg["r"],
        lora_cfg["alpha"],
        lora_cfg["target_modules"],
        lora_cfg["dropout"],
    )
    model = FastLanguageModel.get_peft_model(
        model,
        r=lora_cfg["r"],
        lora_alpha=lora_cfg["alpha"],
        target_modules=lora_cfg["target_modules"],
        lora_dropout=lora_cfg["dropout"],
    )

    logger.info("Loading training data from %s...", data_path)
    train_dataset, val_dataset = load_dataset_from_jsonl(
        path=str(data_path),
        max_seq_length=max_seq_length,
    )

    train_cfg = config["training"]
    eval_cfg = config.get("eval", {})
    output_dir = str(PROJECT_ROOT / config["output_dir"])

    sft_args = SFTConfig(
        output_dir=output_dir,
        per_device_train_batch_size=train_cfg["batch_size"],
        gradient_accumulation_steps=train_cfg["gradient_accumulation_steps"],
        num_train_epochs=train_cfg["epochs"],
        learning_rate=float(train_cfg["lr"]),
        lr_scheduler_type=train_cfg["lr_scheduler"],
        warmup_ratio=train_cfg["warmup_ratio"],
        optim=train_cfg["optimizer"],
        max_steps=train_cfg["max_steps"],
        logging_steps=train_cfg["logging_steps"],
        save_steps=train_cfg["save_steps"],
        eval_steps=eval_cfg.get("eval_steps", 50),
        eval_strategy="steps",
        save_strategy="steps",
        max_seq_length=max_seq_length,
        dataset_text_field="text",
        fp16=True,
        seed=42,
        report_to="wandb",
        run_name=f"aegis-sft-{Path(config['model_name']).name}",
        load_best_model_at_end=True,
        metric_for_best_model="eval_loss",
    )

    callbacks = []
    anchor_path = eval_cfg.get("anchor_cases")
    metric_names = eval_cfg.get("metrics", [])
    if anchor_path and metric_names:
        callbacks.append(
            AegisEvalCallback(
                anchor_cases_path=anchor_path,
                metric_names=metric_names,
                tokenizer=tokenizer,
                eval_tag=Path(config["model_name"]).name,
            )
        )

    trainer = SFTTrainer(
        model=model,
        tokenizer=tokenizer,
        train_dataset=train_dataset,
        eval_dataset=val_dataset,
        args=sft_args,
        callbacks=callbacks,
    )

    logger.info("Starting training...")
    trainer.train(resume_from_checkpoint=resume_from)

    logger.info("Saving final checkpoint to %s", output_dir)
    trainer.save_model(output_dir)
    tokenizer.save_pretrained(output_dir)

    logger.info("Training complete.")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Aegis Health SFT — supervised fine-tuning with Unsloth",
    )
    parser.add_argument(
        "--config",
        type=str,
        required=True,
        help="Path to YAML config file (e.g. training/configs/sft_e4b.yaml)",
    )
    parser.add_argument(
        "--data",
        type=str,
        default=None,
        help="Path to combined_sft.jsonl (overrides default location)",
    )
    parser.add_argument(
        "--resume",
        type=str,
        default=None,
        help="Path to a checkpoint directory to resume training from",
    )
    parser.add_argument(
        "--verbose",
        action="store_true",
        help="Enable debug-level logging",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    setup_logging(verbose=args.verbose)

    config = load_config(args.config)
    data_path = resolve_data_path(config, args.data)

    if not data_path.exists():
        logger.error(
            "Training data not found at %s. "
            "Run the data-generation pipeline first, or pass --data <path>.",
            data_path,
        )
        sys.exit(1)

    train(config, data_path, resume_from=args.resume)


if __name__ == "__main__":
    main()
