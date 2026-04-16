"""Data loading and formatting for Aegis Health SFT training.

Reads combined_sft.jsonl, formats examples using the Gemma 4 chat template,
tokenizes them, and produces train/val HuggingFace Dataset splits.
"""

from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Any

from datasets import Dataset

logger = logging.getLogger(__name__)

GEMMA_CHAT_TEMPLATE = (
    "<start_of_turn>user\n{user_message}<end_of_turn>\n"
    "<start_of_turn>model\n{model_response}<end_of_turn>"
)


def _format_chat(example: dict[str, Any]) -> str:
    """Convert a single SFT example into a Gemma 4 chat-formatted string.

    Expects each example to have either:
      - ``messages``: list of {role, content} dicts (multi-turn), or
      - ``input`` / ``output`` top-level keys (single-turn).
    """
    messages = example.get("messages")
    if messages:
        parts: list[str] = []
        for msg in messages:
            role = msg["role"]
            content = msg["content"]
            if role == "user":
                parts.append(f"<start_of_turn>user\n{content}<end_of_turn>")
            elif role in ("model", "assistant"):
                parts.append(f"<start_of_turn>model\n{content}<end_of_turn>")
            elif role == "system":
                parts.append(f"<start_of_turn>user\n[System] {content}<end_of_turn>")
        return "\n".join(parts)

    user_msg = example.get("input", "")
    model_resp = example.get("output", "")
    return GEMMA_CHAT_TEMPLATE.format(user_message=user_msg, model_response=model_resp)


def load_dataset_from_jsonl(
    path: str,
    tokenizer: Any | None = None,
    max_seq_length: int = 2048,
    val_ratio: float = 0.1,
    seed: int = 42,
) -> tuple[Dataset, Dataset]:
    """Load a JSONL file and return tokenized train/val HuggingFace Datasets.

    Parameters
    ----------
    path:
        Path to the combined_sft.jsonl file.
    tokenizer:
        HuggingFace-compatible tokenizer. When ``None`` the raw text column is
        returned without tokenisation (useful for ``SFTTrainer`` which can
        accept a text column directly).
    max_seq_length:
        Maximum token sequence length for truncation.
    val_ratio:
        Fraction of data to hold out for validation (default 10 %).
    seed:
        Random seed for the train/val split.

    Returns
    -------
    (train_dataset, val_dataset)
    """
    jsonl_path = Path(path)
    if not jsonl_path.exists():
        raise FileNotFoundError(f"Training data not found: {jsonl_path}")

    records: list[dict[str, Any]] = []
    with open(jsonl_path) as fh:
        for line_no, line in enumerate(fh, 1):
            line = line.strip()
            if not line:
                continue
            try:
                records.append(json.loads(line))
            except json.JSONDecodeError as exc:
                logger.warning("Skipping malformed line %d: %s", line_no, exc)

    if not records:
        raise ValueError(f"No valid examples found in {jsonl_path}")

    logger.info("Loaded %d examples from %s", len(records), jsonl_path)

    formatted_texts = [_format_chat(r) for r in records]
    dataset = Dataset.from_dict({"text": formatted_texts})

    if tokenizer is not None:
        def _tokenize(batch: dict[str, list]) -> dict[str, list]:
            return tokenizer(
                batch["text"],
                truncation=True,
                max_length=max_seq_length,
                padding=False,
            )

        dataset = dataset.map(_tokenize, batched=True, remove_columns=["text"])

    split = dataset.train_test_split(test_size=val_ratio, seed=seed)
    train_dataset = split["train"]
    val_dataset = split["test"]

    logger.info(
        "Split: %d train, %d val (%.0f%% holdout)",
        len(train_dataset),
        len(val_dataset),
        val_ratio * 100,
    )
    return train_dataset, val_dataset
