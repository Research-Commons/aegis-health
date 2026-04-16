"""Generate preference pairs from an SFT checkpoint for potential DPO training.

Produces (chosen, rejected) pairs by sampling multiple completions per prompt,
scoring each with the CompositeReward, and pairing the best with the worst.

Usage:
    python -m rl.preference_data \
        --checkpoint training/checkpoints/aegis-sft-merged/ \
        --prompts eval/eval/anchor_cases.json \
        --n-samples 4 \
        --output rl/preference_pairs.jsonl
"""

from __future__ import annotations

import argparse
import json
import logging
import sys
from pathlib import Path
from typing import Any

log = logging.getLogger(__name__)


def generate_preference_pairs(
    checkpoint_path: str,
    prompts: list[dict[str, Any]],
    n_samples: int = 4,
) -> list[dict[str, Any]]:
    """Sample multiple outputs per prompt, score them, and build preference pairs.

    Parameters
    ----------
    checkpoint_path : str
        Path to the SFT (or RL) model checkpoint.
    prompts : list[dict]
        Anchor cases (each with ``input``, ``expected``, ``mode``, etc.).
    n_samples : int
        Number of completions to generate per prompt.

    Returns
    -------
    list[dict]
        Each dict has keys ``prompt``, ``chosen``, ``rejected``,
        ``chosen_score``, ``rejected_score``, and ``case_id``.
    """
    from unsloth import FastLanguageModel
    from rl.rewards.composite import CompositeReward

    model, tokenizer = FastLanguageModel.from_pretrained(
        model_name=checkpoint_path,
        max_seq_length=2048,
        load_in_4bit=True,
    )
    FastLanguageModel.for_inference(model)

    reward_fn = CompositeReward()
    pairs: list[dict[str, Any]] = []

    for i, case in enumerate(prompts):
        log.info("[%d/%d] Generating %d samples for %s", i + 1, len(prompts), n_samples, case["id"])

        mode = case.get("mode", "drugsafe")
        system_msg = (
            "You are Aegis Health, an on-device medical safety assistant. "
            "Respond ONLY with valid JSON matching the AegisResponse schema. "
            f"Mode: {mode}."
        )
        prompt_text = (
            f"<start_of_turn>user\n"
            f"[system] {system_msg}\n\n"
            f"{case['input']}<end_of_turn>\n"
            f"<start_of_turn>model\n"
        )

        inputs = tokenizer(prompt_text, return_tensors="pt").to(model.device)
        outputs = model.generate(
            **inputs,
            max_new_tokens=512,
            do_sample=True,
            temperature=0.7,
            num_return_sequences=n_samples,
        )

        completions: list[str] = []
        for seq in outputs:
            text = tokenizer.decode(seq[inputs["input_ids"].shape[1]:], skip_special_tokens=True)
            completions.append(text)

        scored = [(c, reward_fn(c, case)) for c in completions]
        scored.sort(key=lambda x: x[1], reverse=True)

        best_text, best_score = scored[0]
        worst_text, worst_score = scored[-1]

        if best_score > worst_score:
            pairs.append({
                "case_id": case["id"],
                "prompt": prompt_text,
                "chosen": best_text,
                "rejected": worst_text,
                "chosen_score": best_score,
                "rejected_score": worst_score,
            })
        else:
            log.warning("No preference gap for case %s (all scores equal)", case["id"])

    return pairs


def save_pairs(pairs: list[dict[str, Any]], output_path: str) -> None:
    path = Path(output_path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w") as f:
        for pair in pairs:
            f.write(json.dumps(pair) + "\n")
    log.info("Saved %d preference pairs to %s", len(pairs), path)


def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s  %(levelname)-8s  %(name)s  %(message)s",
    )
    parser = argparse.ArgumentParser(description="Generate DPO preference pairs")
    parser.add_argument("--checkpoint", required=True, help="Path to SFT checkpoint")
    parser.add_argument(
        "--prompts",
        default="eval/eval/anchor_cases.json",
        help="Path to anchor cases JSON",
    )
    parser.add_argument("--n-samples", type=int, default=4, help="Samples per prompt")
    parser.add_argument(
        "--output",
        default="rl/preference_pairs.jsonl",
        help="Output JSONL path",
    )
    args = parser.parse_args()

    with open(args.prompts) as f:
        prompts = json.load(f)

    pairs = generate_preference_pairs(args.checkpoint, prompts, n_samples=args.n_samples)
    save_pairs(pairs, args.output)


if __name__ == "__main__":
    main()
