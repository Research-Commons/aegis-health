"""Group C: KB-grounded DrugSafe knowledge accuracy metrics.

Compares model output against check_warnings() ground truth from the knowledge
base. Only applies to drugsafe-mode cases that have a drug_list field.

Requires kb/output/aegis_kb.sqlite to exist (make kb).
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

from pydantic import ValidationError

from eval.metrics import AegisResponse, _parse_response
from eval.content_metrics import HIGH_SEVERITY_KEYWORDS, LOW_SEVERITY_KEYWORDS

_KB_PATH = "kb/output/aegis_kb.sqlite"

KB_THRESHOLDS: dict[str, float] = {
    "kb_severity_calibration": 0.85,
    "kb_interaction_recall": 0.90,
    "hallucination_check": 0.95,
}


def _kb_available(db_path: str = _KB_PATH) -> bool:
    return Path(db_path).exists()


def _get_kb_ground_truth(case: dict[str, Any], db_path: str = _KB_PATH) -> dict | None:
    """Run check_warnings() with the case's drug_list, age, and conditions."""
    drug_list = case.get("drug_list")
    if not drug_list:
        return None

    try:
        from tools.tools.check_warnings import check_warnings
    except ImportError:
        return None

    age = case.get("age")
    conditions = case.get("conditions", [])
    return check_warnings(drug_list, age=age, conditions=conditions, db_path=db_path)


def _model_max_severity(output: str) -> int | None:
    """Return model's maximum flag severity, or None if not parseable."""
    parsed = _parse_response(output)
    if parsed is None:
        return None
    try:
        resp = AegisResponse(**parsed)
    except (ValidationError, TypeError):
        return None
    if not resp.flags:
        return 0
    return max(f.severity for f in resp.flags)


def _prose_severity_estimate(output: str) -> int:
    """Rough severity estimate from prose keywords (1–5 scale)."""
    output_lower = output.lower()
    if any(kw in output_lower for kw in ["life-threatening", "fatal", "lethal", "critical", "death", "deadly"]):
        return 5
    if any(kw in output_lower for kw in ["serious", "dangerous", "severe", "emergency", "high risk"]):
        return 4
    if any(kw in output_lower for kw in ["moderate", "significant risk", "caution"]):
        return 3
    if any(kw in output_lower for kw in ["minor", "low risk", "minimal"]):
        return 2
    if any(kw in output_lower for kw in ["no significant", "generally safe", "no known"]):
        return 1
    return 3


def kb_severity_calibration(output: str, kb_result: dict) -> float:
    """Check that model's severity is within ±1 of KB ground truth.

    Returns 1.0 within ±1, 0.5 if off by 2, 0.0 if off by 3 or more.
    Returns 1.0 if KB has no flags (nothing to calibrate against).
    """
    kb_flags = kb_result.get("flags", [])
    if not kb_flags:
        kb_max = 0
    else:
        kb_max = max(f.get("severity", 1) if isinstance(f, dict) else f.severity for f in kb_flags)

    model_max = _model_max_severity(output)
    if model_max is None:
        model_max = _prose_severity_estimate(output)

    diff = abs(model_max - kb_max)
    if diff <= 1:
        return 1.0
    if diff == 2:
        return 0.5
    return 0.0


def kb_interaction_recall(output: str, kb_result: dict) -> float:
    """Check that model identifies interactions the KB has found.

    When KB has significant interactions (severity ≥ 3): model must also flag
    something. When KB has no significant interactions: trivially passes.
    """
    kb_flags = kb_result.get("flags", [])
    significant_kb_flags = [
        f for f in kb_flags
        if (f.get("severity", 1) if isinstance(f, dict) else f.severity) >= 3
    ]

    if not significant_kb_flags:
        return 1.0

    model_max = _model_max_severity(output)
    if model_max is None:
        output_lower = output.lower()
        model_signals = any(kw in output_lower for kw in HIGH_SEVERITY_KEYWORDS + ["interaction", "concern", "risk", "warning"])
        return 1.0 if model_signals else 0.0

    return 1.0 if model_max >= 2 else 0.0


def hallucination_check(output: str, kb_result: dict) -> float:
    """Penalise when model claims high-severity interaction that KB doesn't have.

    If KB max severity ≤ 2 (low-risk case) and model claims severity ≥ 4, that is
    a hallucinated interaction. Returns 0.0 on clear hallucination, 1.0 otherwise.
    """
    kb_flags = kb_result.get("flags", [])
    if kb_flags:
        kb_max = max(f.get("severity", 1) if isinstance(f, dict) else f.severity for f in kb_flags)
    else:
        kb_max = 0

    if kb_max >= 3:
        return 1.0

    model_max = _model_max_severity(output)
    if model_max is None:
        output_lower = output.lower()
        hallucination_signals = ["life-threatening", "fatal", "dangerous", "serious risk", "severe interaction"]
        low_risk_signals = ["no significant", "generally safe", "no known", "low risk", "minor"]
        if any(kw in output_lower for kw in hallucination_signals) and not any(kw in output_lower for kw in low_risk_signals):
            return 0.0
        return 1.0

    if model_max >= 4 and kb_max <= 1:
        return 0.0
    return 1.0


def compute_kb_metrics(output: str, case: dict[str, Any], db_path: str = _KB_PATH) -> dict[str, float] | None:
    """Compute all Group C metrics for a single case.

    Returns None if the case has no drug_list or the KB is unavailable.
    """
    if case.get("mode") != "drugsafe":
        return None
    if not case.get("drug_list"):
        return None
    if not _kb_available(db_path):
        return None

    kb_result = _get_kb_ground_truth(case, db_path)
    if kb_result is None:
        return None

    return {
        "kb_severity_calibration": kb_severity_calibration(output, kb_result),
        "kb_interaction_recall": kb_interaction_recall(output, kb_result),
        "hallucination_check": hallucination_check(output, kb_result),
    }


def run_kb_accuracy(cases_path: str, db_path: str = _KB_PATH) -> dict:
    """Run Group C metrics over all anchor cases with drug_list. Prints a summary."""
    import json as _json
    cases = _json.loads(Path(cases_path).read_text())

    if not _kb_available(db_path):
        print(f"KB not found at {db_path}. Run `make kb` first.", file=sys.stderr)
        return {}

    results: list[dict] = []
    scores: dict[str, list[float]] = {"kb_severity_calibration": [], "kb_interaction_recall": [], "hallucination_check": []}

    for case in cases:
        kb_result = _get_kb_ground_truth(case, db_path)
        if kb_result is None:
            continue
        results.append({
            "id": case["id"],
            "category": case["category"],
            "drug_list": case.get("drug_list", []),
            "kb_defer": kb_result.get("defer_to_professional"),
            "kb_flags": len(kb_result.get("flags", [])),
            "kb_max_severity": max((f.get("severity", 1) if isinstance(f, dict) else f.severity for f in kb_result.get("flags", [])), default=0),
            "kb_explanation": kb_result.get("explanation", "")[:120],
        })

    print(f"\nKB Ground Truth Summary ({len(results)} DrugSafe cases with drug_list)")
    print("-" * 80)
    print(f"{'ID':<22} {'Drugs':<35} {'MaxSev':>6} {'Defer':>6} {'Flags':>5}")
    print("-" * 80)
    for r in results:
        drugs_str = ", ".join(r["drug_list"])[:33]
        print(f"{r['id']:<22} {drugs_str:<35} {r['kb_max_severity']:>6} {str(r['kb_defer']):>6} {r['kb_flags']:>5}")

    return {"kb_ground_truth": results}


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="Run KB ground-truth accuracy check on anchor cases")
    parser.add_argument("--cases", default="eval/eval/anchor_cases.json")
    parser.add_argument("--db", default=_KB_PATH)
    args = parser.parse_args()
    run_kb_accuracy(args.cases, args.db)
