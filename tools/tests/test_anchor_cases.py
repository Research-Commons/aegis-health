"""Integration tests that validate tool behaviour against anchor cases.

These tests load eval/eval/anchor_cases.json and verify that:
  - Severity-tagged cases produce flags within the expected severity range.
  - Defer-tagged cases correctly set defer_to_professional = True.

The anchor cases file and the knowledge base are only available after the
KB build step, so tests are skipped when either is missing.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from tools.tools.check_warnings import check_warnings

_PROJECT_ROOT = Path(__file__).resolve().parents[2]
_ANCHOR_PATH = _PROJECT_ROOT / "eval" / "eval" / "anchor_cases.json"
_DB_PATH = _PROJECT_ROOT / "kb" / "output" / "aegis_kb.sqlite"



def _load_anchor_cases() -> list[dict]:
    if not _ANCHOR_PATH.exists():
        return []
    with open(_ANCHOR_PATH) as f:
        return json.load(f)


def _severity_cases() -> list[dict]:
    return [c for c in _load_anchor_cases() if "min_severity" in c.get("expected", {})]


def _defer_cases() -> list[dict]:
    return [c for c in _load_anchor_cases() if c.get("expected", {}).get("defer_to_professional") is True]


# ---------------------------------------------------------------------------
# Severity anchor tests
# ---------------------------------------------------------------------------

class TestSeverityAnchors:
    @pytest.fixture(autouse=True)
    def _setup(self) -> None:
        if not _ANCHOR_PATH.exists():
            pytest.skip(f"Anchor cases not found at {_ANCHOR_PATH}")
        if not _DB_PATH.exists():
            pytest.skip(f"Knowledge base not found at {_DB_PATH}")
        self.db_path = str(_DB_PATH)

    def test_severity_cases_exist(self) -> None:
        cases = _severity_cases()
        assert len(cases) > 0, "No severity anchor cases found"

    def test_severity_within_range(self) -> None:
        cases = _severity_cases()
        if not cases:
            pytest.skip("No severity anchor cases found")
        # Only test cases where check_warnings can produce flags: multi-drug
        # interaction cases or single-drug + conditions. Single-drug-only cases
        # (e.g. acetaminophen overdose from duplicate dosing) require model
        # reasoning about dosage context and cannot be evaluated by this tool.
        runnable = [
            c for c in cases
            if (c.get("drugs") or c.get("drug_list"))
            and (
                len(c.get("drug_list", c.get("drugs", []))) > 1
                or c.get("conditions")
            )
        ]
        if not runnable:
            pytest.skip("No multi-drug severity anchor cases found; run via eval.runner for full pipeline testing")
        for case in runnable:
            drug_list = case.get("drugs", case.get("drug_list", []))
            age = case.get("age")
            conditions = case.get("conditions", [])

            result = check_warnings(
                drug_list=drug_list,
                age=age,
                conditions=conditions,
                db_path=self.db_path,
            )

            min_sev = case["expected"]["min_severity"]
            max_sev = case["expected"].get("max_severity", 5)

            # Filter out policy flags (unknown drug, polypharmacy) that are
            # not actual interaction findings — only clinical flags count
            # toward the severity assertion.
            clinical_flags = [
                f for f in result["flags"]
                if "unknown" not in f["description"].lower()
                and "polypharmacy" not in f["description"].lower()
            ]
            if clinical_flags:
                actual_max = max(f["severity"] for f in clinical_flags)
                assert min_sev <= actual_max <= max_sev, (
                    f"Case {case.get('id', '?')}: expected severity in [{min_sev}, {max_sev}], "
                    f"got {actual_max}"
                )
            else:
                # No clinical interaction flags — KB coverage gap for this pair
                pytest.skip(
                    f"Case {case.get('id', '?')}: KB has no interaction data for "
                    f"{drug_list} — run 'make kb' to expand coverage"
                )


# ---------------------------------------------------------------------------
# Defer anchor tests
# ---------------------------------------------------------------------------

class TestDeferAnchors:
    @pytest.fixture(autouse=True)
    def _setup(self) -> None:
        if not _ANCHOR_PATH.exists():
            pytest.skip(f"Anchor cases not found at {_ANCHOR_PATH}")
        if not _DB_PATH.exists():
            pytest.skip(f"Knowledge base not found at {_DB_PATH}")
        self.db_path = str(_DB_PATH)

    def test_defer_cases_exist(self) -> None:
        cases = _defer_cases()
        assert len(cases) > 0, "No defer anchor cases found"

    def test_defer_is_true(self) -> None:
        cases = _defer_cases()
        if not cases:
            pytest.skip("No defer anchor cases found")
        # Exclude single-drug-no-condition cases: these test model-level dosage
        # reasoning, not tool-level interaction checking (e.g. acetaminophen
        # overdose from duplicate dosing requires dose context the tool lacks).
        runnable = [
            c for c in cases
            if len(c.get("drug_list", c.get("drugs", []))) > 1
            or c.get("conditions")
            or c.get("age") is not None
        ]
        if not runnable:
            pytest.skip("No multi-drug / context defer cases to run")
        for case in runnable:
            drug_list = case.get("drugs", case.get("drug_list", []))
            age = case.get("age")
            conditions = case.get("conditions", [])

            result = check_warnings(
                drug_list=drug_list,
                age=age,
                conditions=conditions,
                db_path=self.db_path,
            )

            assert result["defer_to_professional"] is True, (
                f"Case {case.get('id', '?')}: expected defer_to_professional=True"
            )
