"""Post-quantization validation for Aegis Health models.

Loads a quantized model, runs all 50 anchor cases, computes every metric, and
compares against a pre-quantization baseline report.  Any metric regression
>2% is flagged as a warning.

Usage:
    python -m export.validate_on_device \
        --model export/output/aegis_model.task \
        --anchor-cases eval/eval/anchor_cases.json \
        --baseline-report eval/reports/eval_results_run_*.json
"""

from __future__ import annotations

import argparse
import json
import logging
import sys
import time
from pathlib import Path
from typing import Any

log = logging.getLogger(__name__)

METRIC_NAMES = [
    "json_validity",
    "deferral_accuracy",
    "citation_presence",
    "safety_boundary",
    "severity_accuracy",
]
REGRESSION_THRESHOLD = 0.02


def _load_model(model_path: str) -> Any:
    """Load either a .task file via litert_lm or a HF checkpoint."""
    if model_path.endswith(".task"):
        try:
            import litert_lm  # type: ignore[import-untyped]
        except ImportError:
            log.error(
                "litert_lm is required for validating .task files. "
                "Install with: pip install litert-lm"
            )
            sys.exit(1)
        return litert_lm.load(model_path), "litert"
    else:
        try:
            from transformers import AutoModelForCausalLM, AutoTokenizer
        except ImportError:
            log.error(
                "transformers is required for validating HF checkpoints. "
                "Install with: pip install transformers torch"
            )
            sys.exit(1)
        tokenizer = AutoTokenizer.from_pretrained(model_path)
        model = AutoModelForCausalLM.from_pretrained(model_path)
        return (model, tokenizer), "hf"


def _infer(model: Any, backend: str, prompt: str) -> str:
    """Run inference and return the raw output string."""
    if backend == "litert":
        tokens = list(model.generate(prompt, max_tokens=1024))
        return "".join(tokens)
    else:
        import torch
        hf_model, tokenizer = model
        inputs = tokenizer(prompt, return_tensors="pt")
        if hasattr(hf_model, "device"):
            inputs = {k: v.to(hf_model.device) for k, v in inputs.items()}
        with torch.no_grad():
            outputs = hf_model.generate(**inputs, max_new_tokens=1024, do_sample=False)
        return tokenizer.decode(outputs[0], skip_special_tokens=True)


def _import_metrics() -> Any:
    """Import eval.metrics, adjusting sys.path if needed."""
    try:
        from eval.metrics import compute_all_metrics
        return compute_all_metrics
    except ImportError:
        pass

    project_root = Path(__file__).resolve().parent.parent.parent
    eval_path = project_root / "eval"
    if eval_path.exists() and str(eval_path) not in sys.path:
        sys.path.insert(0, str(eval_path))
        try:
            from eval.metrics import compute_all_metrics
            return compute_all_metrics
        except ImportError:
            pass

    log.error(
        "Cannot import eval.metrics. Make sure the eval package is installed: "
        "pip install -e '.[eval]'"
    )
    sys.exit(1)


def load_anchor_cases(cases_path: Path) -> list[dict[str, Any]]:
    if not cases_path.exists():
        log.error("Anchor cases file not found: %s", cases_path)
        sys.exit(1)
    with open(cases_path) as f:
        return json.load(f)


def load_baseline_report(report_path: Path) -> dict[str, Any] | None:
    if report_path is None:
        return None
    if not report_path.exists():
        log.warning("Baseline report not found: %s — skipping comparison", report_path)
        return None
    with open(report_path) as f:
        return json.load(f)


def run_validation(
    model_path: str,
    cases: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    """Run all anchor cases through the model and compute metrics."""
    compute_all_metrics = _import_metrics()
    model, backend = _load_model(model_path)

    results: list[dict[str, Any]] = []

    for i, case in enumerate(cases, 1):
        case_id = case["id"]
        prompt = case["input"]
        log.info("[%d/%d] Validating %s...", i, len(cases), case_id)

        t0 = time.perf_counter()
        try:
            output = _infer(model, backend, prompt)
        except Exception:
            log.error("Inference failed on %s", case_id, exc_info=True)
            output = ""
        elapsed = time.perf_counter() - t0

        scores = compute_all_metrics(output, case)

        results.append({
            "case_id": case_id,
            "category": case.get("category", "unknown"),
            "scores": scores,
            "inference_time_s": round(elapsed, 3),
        })

        avg = sum(scores.values()) / len(scores) if scores else 0.0
        log.info("  %s -> avg=%.2f  (%.1fs)", case_id, avg, elapsed)

    return results


def compute_aggregate(results: list[dict[str, Any]]) -> dict[str, float]:
    """Compute average score for each metric across all results."""
    if not results:
        return {}
    totals: dict[str, float] = {m: 0.0 for m in METRIC_NAMES}
    for r in results:
        for m in METRIC_NAMES:
            totals[m] += r["scores"].get(m, 0.0)
    return {m: v / len(results) for m, v in totals.items()}


def compare_to_baseline(
    current: dict[str, float],
    baseline_data: dict[str, Any],
) -> list[str]:
    """Compare current aggregate scores to baseline and return warnings."""
    baseline_summary = baseline_data.get("summary", {}).get("overall", {})
    if not baseline_summary:
        log.warning("Baseline report has no summary.overall — cannot compare")
        return []

    warnings: list[str] = []

    print("\n" + "=" * 70)
    print("  Post-Quantization Comparison")
    print("=" * 70)
    print(f"  {'Metric':<25} {'Baseline':>10} {'Current':>10} {'Delta':>10} {'Status':>8}")
    print(f"  {'-' * 25} {'-' * 10} {'-' * 10} {'-' * 10} {'-' * 8}")

    for m in METRIC_NAMES:
        base_val = baseline_summary.get(m, 0.0)
        curr_val = current.get(m, 0.0)
        delta = curr_val - base_val

        status = "OK"
        if delta < -REGRESSION_THRESHOLD:
            status = "WARN"
            warnings.append(
                f"{m}: dropped {abs(delta) * 100:.1f}% "
                f"(baseline={base_val * 100:.1f}%, current={curr_val * 100:.1f}%)"
            )

        print(
            f"  {m:<25} {base_val * 100:>9.1f}% {curr_val * 100:>9.1f}% "
            f"{delta * 100:>+9.1f}% {status:>8}"
        )

    print("=" * 70)
    return warnings


def print_results_table(aggregate: dict[str, float]) -> None:
    """Print current aggregate scores."""
    print("\n" + "=" * 50)
    print("  Quantized Model — Aggregate Scores")
    print("=" * 50)
    for m in METRIC_NAMES:
        val = aggregate.get(m, 0.0)
        print(f"  {m:<25} {val * 100:>10.1f}%")
    grand_avg = sum(aggregate.values()) / len(aggregate) if aggregate else 0.0
    print(f"  {'AVERAGE':<25} {grand_avg * 100:>10.1f}%")
    print("=" * 50)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Post-quantization validation on all anchor cases",
    )
    parser.add_argument(
        "--model",
        type=str,
        required=True,
        help="Path to the quantized .task file or HF checkpoint",
    )
    parser.add_argument(
        "--anchor-cases",
        type=str,
        default="eval/eval/anchor_cases.json",
        help="Path to the anchor cases JSON file",
    )
    parser.add_argument(
        "--baseline-report",
        type=str,
        default=None,
        help="Path to a pre-quantization eval results JSON for comparison",
    )
    parser.add_argument(
        "--output",
        type=str,
        default=None,
        help="Path to save validation results JSON",
    )
    parser.add_argument(
        "--verbose", "-v",
        action="store_true",
        help="Enable debug-level logging",
    )
    args = parser.parse_args()

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s  %(levelname)-8s  %(message)s",
    )

    cases = load_anchor_cases(Path(args.anchor_cases))
    log.info("Loaded %d anchor cases", len(cases))

    results = run_validation(args.model, cases)
    aggregate = compute_aggregate(results)
    print_results_table(aggregate)

    warnings: list[str] = []
    if args.baseline_report:
        baseline = load_baseline_report(Path(args.baseline_report))
        if baseline:
            warnings = compare_to_baseline(aggregate, baseline)

    if warnings:
        print(f"\n  WARNING: {len(warnings)} metric(s) regressed >2%:")
        for w in warnings:
            print(f"    - {w}")
        print()

    output_data = {
        "model": args.model,
        "num_cases": len(cases),
        "aggregate": aggregate,
        "warnings": warnings,
        "results": results,
    }

    if args.output:
        out_path = Path(args.output)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        with open(out_path, "w") as f:
            json.dump(output_data, f, indent=2)
        print(f"\nValidation results saved to {out_path}")

    if warnings:
        sys.exit(1)


if __name__ == "__main__":
    main()
