"""Merge LoRA adapters back into the base model and export.

Usage:
    python -m training.merge_export \
        --checkpoint-dir training/checkpoints/sft-e4b \
        --output-dir training/checkpoints/aegis-sft-merged
"""

from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

logger = logging.getLogger(__name__)


def setup_logging(verbose: bool = False) -> None:
    level = logging.DEBUG if verbose else logging.INFO
    logging.basicConfig(
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
        level=level,
        handlers=[logging.StreamHandler(sys.stdout)],
    )


def merge_and_export(checkpoint_dir: str, output_dir: str) -> None:
    from unsloth import FastLanguageModel

    ckpt = Path(checkpoint_dir)
    if not ckpt.exists():
        raise FileNotFoundError(f"Checkpoint directory not found: {ckpt}")

    out = Path(output_dir)
    out.mkdir(parents=True, exist_ok=True)

    logger.info("Loading LoRA checkpoint from %s...", ckpt)
    model, tokenizer = FastLanguageModel.from_pretrained(
        model_name=str(ckpt),
        max_seq_length=2048,
        load_in_4bit=False,
    )

    logger.info("Merging LoRA adapters into base model...")
    model = model.merge_and_unload()

    logger.info("Saving merged model to %s...", out)
    model.save_pretrained(str(out))
    tokenizer.save_pretrained(str(out))

    logger.info("Export complete — merged model at %s", out)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Merge LoRA adapters into the base model and save",
    )
    parser.add_argument(
        "--checkpoint-dir",
        type=str,
        required=True,
        help="Path to the LoRA checkpoint directory",
    )
    parser.add_argument(
        "--output-dir",
        type=str,
        default="training/checkpoints/aegis-sft-merged",
        help="Directory to save the merged model (default: training/checkpoints/aegis-sft-merged)",
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
    merge_and_export(args.checkpoint_dir, args.output_dir)


if __name__ == "__main__":
    main()
