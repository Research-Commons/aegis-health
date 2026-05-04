"""Download and filter MedSafetyBench (NeurIPS 2024) for Aegis Health evaluation.

Source: https://github.com/AI4LIFE-GROUP/med-safety-bench
License: MIT

Downloads the MedSafety-Eval split and filters to cases in Aegis Health's scope:
  - Drug advice requests (dosing, interactions)
  - Diagnosis requests
  - Prescription requests

Saves filtered subset to eval/benchmarks/medsafety/cases.json.
Run once; the output is committed and used by eval_external.py.

Usage:
    python eval/benchmarks/medsafety/setup.py
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

OUTPUT_PATH = Path(__file__).parent / "cases.json"

MEDSAFETY_URL = "https://raw.githubusercontent.com/AI4LIFE-GROUP/med-safety-bench/main/data/med_safety_eval.json"

IN_SCOPE_KEYWORDS = [
    "dosage", "dose", "mg", "milligram",
    "prescription", "prescribe",
    "drug", "medication", "medicine",
    "diagnosis", "diagnose",
    "interact", "interaction",
    "side effect", "overdose",
    "take", "administer",
]

MAX_CASES = 100


def download_and_filter() -> None:
    try:
        import urllib.request
        print(f"Downloading MedSafetyBench from GitHub...")
        with urllib.request.urlopen(MEDSAFETY_URL, timeout=30) as r:
            raw_data = json.loads(r.read().decode())
    except Exception as exc:
        print(f"ERROR: Could not download MedSafetyBench: {exc}", file=sys.stderr)
        print("Manual download: https://github.com/AI4LIFE-GROUP/med-safety-bench", file=sys.stderr)
        sys.exit(1)

    if isinstance(raw_data, dict):
        items = raw_data.get("data", raw_data.get("examples", list(raw_data.values())[0] if raw_data else []))
    else:
        items = raw_data

    print(f"Total MedSafetyBench cases: {len(items)}")

    filtered = []
    for item in items:
        prompt = (item.get("prompt") or item.get("instruction") or item.get("input") or "").lower()
        label = item.get("label") or item.get("safe") or item.get("response_type") or ""

        if any(kw in prompt for kw in IN_SCOPE_KEYWORDS):
            filtered.append({
                "id": item.get("id") or f"msb-{len(filtered):03d}",
                "source": "MedSafetyBench",
                "prompt": item.get("prompt") or item.get("instruction") or item.get("input") or "",
                "expected_safe": _parse_label(label),
                "category": _infer_category(prompt),
            })
            if len(filtered) >= MAX_CASES:
                break

    print(f"Filtered to {len(filtered)} in-scope cases")
    OUTPUT_PATH.write_text(json.dumps(filtered, indent=2))
    print(f"Saved to {OUTPUT_PATH}")


def _parse_label(label: Any) -> bool:
    """Parse the safety label — True means 'should refuse/defer'."""
    if isinstance(label, bool):
        return not label
    s = str(label).lower()
    return s in ("unsafe", "harmful", "1", "true", "yes", "refuse")


def _infer_category(prompt: str) -> str:
    if any(w in prompt for w in ["dosage", "dose", "mg", "milligram"]):
        return "dosing_query"
    if any(w in prompt for w in ["prescribe", "prescription"]):
        return "prescription_request"
    if any(w in prompt for w in ["diagnose", "diagnosis"]):
        return "diagnosis_request"
    if any(w in prompt for w in ["interact", "interaction"]):
        return "interaction_query"
    return "general_drug_advice"


if __name__ == "__main__":
    download_and_filter()
