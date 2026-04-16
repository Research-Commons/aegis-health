"""Generic eval runner for Aegis Health anchor cases.

Supports local checkpoint inference via HuggingFace transformers or remote API
evaluation via HTTP POST.

Usage:
    python -m eval.runner --checkpoint path/to/model
    python -m eval.runner --api-url http://localhost:8000/v1/check
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from eval.metrics import compute_all_metrics

CASES_PATH = Path(__file__).parent / "anchor_cases.json"
REPORTS_DIR = Path(__file__).resolve().parent.parent / "reports"


def load_cases() -> list[dict[str, Any]]:
    with open(CASES_PATH) as f:
        return json.load(f)


def infer_checkpoint(model_path: str, prompt: str) -> str:
    """Run inference on a local HuggingFace checkpoint."""
    try:
        from transformers import AutoModelForCausalLM, AutoTokenizer
    except ImportError:
        print("ERROR: transformers library required for checkpoint mode.", file=sys.stderr)
        print("Install with: pip install transformers torch", file=sys.stderr)
        sys.exit(1)

    tokenizer = AutoTokenizer.from_pretrained(model_path)
    model = AutoModelForCausalLM.from_pretrained(model_path)

    inputs = tokenizer(prompt, return_tensors="pt")
    outputs = model.generate(**inputs, max_new_tokens=1024, do_sample=False)
    return tokenizer.decode(outputs[0], skip_special_tokens=True)


def infer_api(api_url: str, prompt: str, mode: str) -> str:
    """Send a POST request to a remote API endpoint."""
    try:
        import requests
    except ImportError:
        print("ERROR: requests library required for API mode.", file=sys.stderr)
        print("Install with: pip install requests", file=sys.stderr)
        sys.exit(1)

    response = requests.post(
        api_url,
        json={"message": prompt, "mode": mode},
        timeout=60,
    )
    response.raise_for_status()
    return json.dumps(response.json())


def run_eval(
    cases: list[dict[str, Any]],
    checkpoint: str | None = None,
    api_url: str | None = None,
) -> list[dict[str, Any]]:
    """Run evaluation on all cases and return per-case results."""
    results: list[dict[str, Any]] = []

    for i, case in enumerate(cases, 1):
        case_id = case["id"]
        prompt = case["input"]
        mode = case.get("mode", "drugsafe")

        print(f"[{i}/{len(cases)}] Evaluating {case_id}...")

        try:
            if checkpoint:
                output = infer_checkpoint(checkpoint, prompt)
            elif api_url:
                output = infer_api(api_url, prompt, mode)
            else:
                output = ""
        except Exception as exc:
            print(f"  ERROR on {case_id}: {exc}", file=sys.stderr)
            output = ""

        scores = compute_all_metrics(output, case)

        results.append({
            "case_id": case_id,
            "category": case["category"],
            "mode": mode,
            "input": prompt,
            "output": output,
            "scores": scores,
        })

        avg = sum(scores.values()) / len(scores) if scores else 0.0
        print(f"  -> avg score: {avg:.2f}  {scores}")

    return results


def save_results(results: list[dict[str, Any]], tag: str) -> Path:
    """Persist raw results JSON to the reports directory."""
    REPORTS_DIR.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    filename = f"eval_results_{tag}_{timestamp}.json"
    out_path = REPORTS_DIR / filename

    summary = compute_summary(results)
    payload = {
        "timestamp": timestamp,
        "tag": tag,
        "num_cases": len(results),
        "summary": summary,
        "results": results,
    }

    with open(out_path, "w") as f:
        json.dump(payload, f, indent=2)

    print(f"\nResults saved to {out_path}")
    return out_path


def compute_summary(results: list[dict[str, Any]]) -> dict[str, Any]:
    """Compute aggregate metric averages overall and per category."""
    if not results:
        return {}

    metric_names = list(results[0]["scores"].keys())

    overall: dict[str, float] = {m: 0.0 for m in metric_names}
    for r in results:
        for m in metric_names:
            overall[m] += r["scores"].get(m, 0.0)
    overall = {m: v / len(results) for m, v in overall.items()}

    categories: dict[str, dict[str, float]] = {}
    for r in results:
        cat = r["category"]
        if cat not in categories:
            categories[cat] = {m: 0.0 for m in metric_names}
            categories[cat]["_count"] = 0
        categories[cat]["_count"] += 1
        for m in metric_names:
            categories[cat][m] += r["scores"].get(m, 0.0)

    for cat, scores in categories.items():
        count = scores.pop("_count")
        for m in metric_names:
            scores[m] /= count

    return {"overall": overall, "per_category": categories}


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Aegis Health eval runner — evaluate model on anchor cases",
    )
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument(
        "--checkpoint",
        type=str,
        help="Path to a local HuggingFace model checkpoint",
    )
    group.add_argument(
        "--api-url",
        type=str,
        help="URL of the Aegis Health API endpoint (POST)",
    )
    parser.add_argument(
        "--tag",
        type=str,
        default="run",
        help="Tag for the results file (default: 'run')",
    )
    args = parser.parse_args()

    cases = load_cases()
    print(f"Loaded {len(cases)} anchor cases from {CASES_PATH}\n")

    results = run_eval(cases, checkpoint=args.checkpoint, api_url=args.api_url)
    results_path = save_results(results, tag=args.tag)

    from eval.report import generate_report
    generate_report(results_path)


if __name__ == "__main__":
    main()
