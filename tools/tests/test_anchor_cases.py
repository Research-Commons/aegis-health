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

_skip_no_anchors = pytest.mark.skipif(
    not _ANCHOR_PATH.exists(),
    reason=f"Anchor cases not found at {_ANCHOR_PATH}",
)
_skip_no_db = pytest.mark.skipif(
    not _DB_PATH.exists(),
    reason=f"Knowledge base not found at {_DB_PATH}",
)


def _load_anchor_cases() -> list[dict]:
    if not _ANCHOR_PATH.exists():
        return []
    with open(_ANCHOR_PATH) as f:
        return json.load(f)


def _severity_cases() -> list[dict]:
    return [c for c in _load_anchor_cases() if "expected_severity" in c]


def _defer_cases() -> list[dict]:
    return [c for c in _load_anchor_cases() if c.get("expect_defer") is True]


# ---------------------------------------------------------------------------
# Severity anchor tests
# ---------------------------------------------------------------------------

@_skip_no_anchors
@_skip_no_db
class TestSeverityAnchors:
    @pytest.fixture(autouse=True)
    def _setup(self) -> None:
        self.db_path = str(_DB_PATH)

    def test_severity_cases_exist(self) -> None:
        cases = _severity_cases()
        assert len(cases) > 0, "No severity anchor cases found"

    @pytest.mark.parametrize(
        "case",
        _severity_cases(),
        ids=lambda c: c.get("id", c.get("name", "unnamed")),
    )
    def test_severity_within_range(self, case: dict) -> None:
        drug_list = case.get("drugs", case.get("drug_list", []))
        age = case.get("age")
        conditions = case.get("conditions", [])

        result = check_warnings(
            drug_list=drug_list,
            age=age,
            conditions=conditions,
            db_path=self.db_path,
        )

        expected = case["expected_severity"]
        if isinstance(expected, list):
            min_sev, max_sev = expected
        else:
            min_sev = max_sev = int(expected)

        if result["flags"]:
            actual_max = max(f["severity"] for f in result["flags"])
            assert min_sev <= actual_max <= max_sev, (
                f"Case {case.get('id', '?')}: expected severity in [{min_sev}, {max_sev}], "
                f"got {actual_max}"
            )
        else:
            pytest.fail(
                f"Case {case.get('id', '?')}: expected flags with severity "
                f"[{min_sev}, {max_sev}] but got no flags"
            )


# ---------------------------------------------------------------------------
# Defer anchor tests
# ---------------------------------------------------------------------------

@_skip_no_anchors
@_skip_no_db
class TestDeferAnchors:
    @pytest.fixture(autouse=True)
    def _setup(self) -> None:
        self.db_path = str(_DB_PATH)

    def test_defer_cases_exist(self) -> None:
        cases = _defer_cases()
        assert len(cases) > 0, "No defer anchor cases found"

    @pytest.mark.parametrize(
        "case",
        _defer_cases(),
        ids=lambda c: c.get("id", c.get("name", "unnamed")),
    )
    def test_defer_is_true(self, case: dict) -> None:
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
