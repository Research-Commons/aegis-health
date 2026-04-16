"""Evaluate an RL-trained checkpoint against all anchor cases.

Compares metrics side-by-side with an optional SFT-only baseline report.

Usage:
    python -m rl.eval_rl --checkpoint rl/checkpoints/grpo-e4b
    python -m rl.eval_rl --checkpoint rl/checkpoints/grpo-e4b \
        --baseline-report eval/reports/eval_results_sft_*.json
"""

from __future__ import annotations

import argparse
import json
import logging
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from rl.rewards.composite import CompositeReward

log = logging.getLogger(__name__)

CASES_PATH = Path(__file__).resolve().parent.parent.parent / "eval" / "eval" / "anchor_cases.json"
REPORTS_DIR = Path(__file__).resolve().parent.parent / "reports"


def load_cases() -> list[dict[str, Any]]:
    if not CASES_PATH.exists():
        log.error("Anchor cases not found at %s", CASES_PATH)
        sys.exit(1)
    with open(CASES_PATH) as f:
        return json.load(f)


def infer(model, tokenizer, prompt: str, mode: str) -> str:
    system_msg = (
        "You are Aegis Health, an on-device medical safety assistant. "
        "Respond ONLY with valid JSON matching the AegisResponse schema. "
        f"Mode: {mode}."
    )
    full_prompt = (
        f"<start_of_turn>user\n"
        f"[system] {system_msg}\n\n"
        f"{prompt}<end_of_turn>\n"
        f"<start_of_turn>model\n"
    )
    inputs = tokenizer(full_prompt, return_tensors="pt").to(model.device)
    outputs = model.generate(**inputs, max_new_tokens=1024, do_sample=False)
    return tokenizer.decode(outputs[0][inputs["input_ids"].shape[1]:], skip_special_tokens=True)


def run_eval(checkpoint: str) -> list[dict[str, Any]]:
    from unsloth import FastLanguageModel

    log.info("Loading RL checkpoint from %s", checkpoint)
    model, tokenizer = FastLanguageModel.from_pretrained(
        model_name=checkpoint,
        max_seq_length=2048,
        load_in_4bit=True,
    )
    FastLanguageModel.for_inference(model)

    cases = load_cases()
    reward_fn = CompositeReward()
    results: list[dict[str, Any]] = []

    for i, case in enumerate(cases, 1):
        log.info("[%d/%d] %s", i, len(cases), case["id"])
        output = infer(model, tokenizer, case["input"], case.get("mode", "drugsafe"))
        composite_score = reward_fn(output, case)
        component_scores = reward_fn.detailed(output, case)

        results.append({
            "case_id": case["id"],
            "category": case["category"],
            "output": output,
            "composite_score": composite_score,
            "component_scores": component_scores,
        })
        log.info("  composite=%.3f  components=%s", composite_score, component_scores)

    return results


def load_baseline(path: str) -> dict[str, Any] | None:
    p = Path(path)
    if not p.exists():
        log.warning("Baseline report not found: %s", p)
        return None
    with open(p) as f:
        return json.load(f)


def print_comparison(rl_results: list[dict[str, Any]], baseline: dict[str, Any] | None) -> str:
    """Build a human-readable comparison report and return it as a string."""
    lines: list[str] = []
    lines.append("=" * 72)
    lines.append("  Aegis Health — RL vs SFT Comparison Report")
    lines.append("=" * 72)

    # RL aggregate
    rl_avg = sum(r["composite_score"] for r in rl_results) / len(rl_results)
    component_names = list(rl_results[0]["component_scores"].keys())
    rl_comp_avg = {
        name: sum(r["component_scores"][name] for r in rl_results) / len(rl_results)
        for name in component_names
    }

    lines.append(f"\nRL checkpoint — {len(rl_results)} cases")
    lines.append(f"  Composite avg : {rl_avg:.4f}")
    for name, val in rl_comp_avg.items():
        lines.append(f"  {name:20s}: {val:.4f}")

    if baseline:
        sft_summary = baseline.get("summary", {}).get("overall", {})
        lines.append(f"\nSFT baseline")
        for name, val in sft_summary.items():
            lines.append(f"  {name:20s}: {val:.4f}")

        lines.append(f"\nDelta (RL − SFT)")
        for name in component_names:
            sft_val = sft_summary.get(name, 0.0)
            delta = rl_comp_avg[name] - sft_val
            marker = "↑" if delta > 0 else "↓" if delta < 0 else "="
            lines.append(f"  {name:20s}: {delta:+.4f}  {marker}")

    # Per-category breakdown
    categories: dict[str, list[dict]] = {}
    for r in rl_results:
        categories.setdefault(r["category"], []).append(r)

    lines.append(f"\nPer-category composite scores (RL)")
    for cat, cat_results in sorted(categories.items()):
        avg = sum(r["composite_score"] for r in cat_results) / len(cat_results)
        lines.append(f"  {cat:30s}: {avg:.4f}  (n={len(cat_results)})")

    lines.append("=" * 72)
    report = "\n".join(lines)
    return report


def save_report(rl_results: list[dict[str, Any]], report_text: str) -> Path:
    REPORTS_DIR.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out_path = REPORTS_DIR / f"rl_eval_{timestamp}.json"

    payload = {
        "timestamp": timestamp,
        "num_cases": len(rl_results),
        "results": rl_results,
        "report": report_text,
    }
    with open(out_path, "w") as f:
        json.dump(payload, f, indent=2)
    log.info("Report saved to %s", out_path)
    return out_path


def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s  %(levelname)-8s  %(name)s  %(message)s",
    )
    parser = argparse.ArgumentParser(description="Evaluate RL checkpoint on anchor cases")
    parser.add_argument("--checkpoint", required=True, help="Path to RL checkpoint")
    parser.add_argument(
        "--baseline-report",
        default=None,
        help="Path to SFT baseline eval results JSON for comparison",
    )
    args = parser.parse_args()

    rl_results = run_eval(args.checkpoint)

    baseline = None
    if args.baseline_report:
        baseline = load_baseline(args.baseline_report)

    report_text = print_comparison(rl_results, baseline)
    print(report_text)

    save_report(rl_results, report_text)


if __name__ == "__main__":
    main()
