"""Training-time evaluation callback using Aegis Health anchor cases.

Runs inference on anchor cases at each eval step and logs structured metrics
to the trainer (and optionally to Weights & Biases). Persists per-step eval
reports to ``eval/reports/``.
"""

from __future__ import annotations

import json
import logging
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import torch
from transformers import TrainerCallback, TrainerControl, TrainerState, TrainingArguments

logger = logging.getLogger(__name__)

PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
REPORTS_DIR = PROJECT_ROOT / "eval" / "reports"


def _load_anchor_cases(cases_path: str | Path) -> list[dict[str, Any]]:
    cases_path = Path(cases_path)
    if not cases_path.is_absolute():
        cases_path = PROJECT_ROOT / cases_path
    if not cases_path.exists():
        raise FileNotFoundError(f"Anchor cases not found: {cases_path}")
    with open(cases_path) as f:
        cases = json.load(f)
    logger.info("Loaded %d anchor cases from %s", len(cases), cases_path)
    return cases


def _run_inference(model: Any, tokenizer: Any, prompt: str, max_new_tokens: int = 1024) -> str:
    """Run greedy inference on a single prompt and return the decoded output."""
    inputs = tokenizer(prompt, return_tensors="pt", truncation=True, max_length=2048)
    inputs = {k: v.to(model.device) for k, v in inputs.items()}
    with torch.no_grad():
        output_ids = model.generate(
            **inputs,
            max_new_tokens=max_new_tokens,
            do_sample=False,
            temperature=1.0,
        )
    input_len = inputs["input_ids"].shape[1]
    generated = output_ids[0][input_len:]
    return tokenizer.decode(generated, skip_special_tokens=True)


def _compute_metrics(output: str, case: dict[str, Any], metric_names: list[str]) -> dict[str, float]:
    """Compute the requested subset of eval metrics for a single case."""
    sys.path.insert(0, str(PROJECT_ROOT))
    try:
        from eval.eval.metrics import (
            citation_presence,
            deferral_accuracy,
            json_validity,
            safety_boundary,
        )
    finally:
        sys.path.pop(0)

    available = {
        "json_validity": lambda: json_validity(output),
        "deferral_accuracy": lambda: deferral_accuracy(output, case.get("expected", {})),
        "citation_presence": lambda: citation_presence(output),
        "safety_boundary": lambda: safety_boundary(output, case.get("expected", {})),
    }

    scores: dict[str, float] = {}
    for name in metric_names:
        fn = available.get(name)
        if fn is not None:
            scores[name] = fn()
        else:
            logger.warning("Unknown metric requested: %s (skipped)", name)
    return scores


class AegisEvalCallback(TrainerCallback):
    """Evaluate on anchor cases during training and log metrics.

    Parameters
    ----------
    anchor_cases_path:
        Path to the anchor_cases.json file (absolute or relative to project root).
    metric_names:
        List of metric function names to compute per case.
    tokenizer:
        The tokenizer used for inference.
    eval_tag:
        A short tag included in the saved report filenames.
    """

    def __init__(
        self,
        anchor_cases_path: str | Path,
        metric_names: list[str],
        tokenizer: Any,
        eval_tag: str = "sft",
    ) -> None:
        super().__init__()
        self.cases = _load_anchor_cases(anchor_cases_path)
        self.metric_names = metric_names
        self.tokenizer = tokenizer
        self.eval_tag = eval_tag

    def on_evaluate(
        self,
        args: TrainingArguments,
        state: TrainerState,
        control: TrainerControl,
        model: Any = None,
        **kwargs: Any,
    ) -> None:
        if model is None:
            logger.warning("Model not available in callback; skipping anchor eval.")
            return

        logger.info(
            "Running anchor-case eval at step %d (epoch %.2f)...",
            state.global_step,
            state.epoch or 0.0,
        )

        was_training = model.training
        model.eval()

        results: list[dict[str, Any]] = []
        aggregated: dict[str, float] = {m: 0.0 for m in self.metric_names}

        for i, case in enumerate(self.cases, 1):
            prompt = case["input"]
            try:
                output = _run_inference(model, self.tokenizer, prompt)
            except Exception:
                logger.exception("Inference failed on case %s", case["id"])
                output = ""

            scores = _compute_metrics(output, case, self.metric_names)
            for m, v in scores.items():
                aggregated[m] += v

            results.append({
                "case_id": case["id"],
                "category": case["category"],
                "output": output,
                "scores": scores,
            })

        n = len(self.cases)
        aggregated = {m: v / n for m, v in aggregated.items()}
        avg_score = sum(aggregated.values()) / len(aggregated) if aggregated else 0.0

        log_payload: dict[str, float] = {}
        for m, v in aggregated.items():
            log_payload[f"anchor/{m}"] = v
        log_payload["anchor/avg_score"] = avg_score

        if state.log_history is not None:
            state.log_history.append({"step": state.global_step, **log_payload})

        try:
            import wandb

            if wandb.run is not None:
                wandb.log(log_payload, step=state.global_step)
        except ImportError:
            pass

        logger.info(
            "Anchor eval step %d — avg=%.3f  %s",
            state.global_step,
            avg_score,
            {m: f"{v:.3f}" for m, v in aggregated.items()},
        )

        self._save_report(state.global_step, aggregated, results)

        if was_training:
            model.train()

    def _save_report(
        self,
        step: int,
        aggregated: dict[str, float],
        results: list[dict[str, Any]],
    ) -> None:
        REPORTS_DIR.mkdir(parents=True, exist_ok=True)
        timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        filename = f"anchor_eval_{self.eval_tag}_step{step}_{timestamp}.json"
        out_path = REPORTS_DIR / filename
        payload = {
            "timestamp": timestamp,
            "step": step,
            "tag": self.eval_tag,
            "num_cases": len(results),
            "aggregated": aggregated,
            "results": results,
        }
        with open(out_path, "w") as f:
            json.dump(payload, f, indent=2)
        logger.info("Anchor eval report saved to %s", out_path)
