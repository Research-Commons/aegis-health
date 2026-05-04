"""Regression guard for KB expansion work.

Ensures every table meets or exceeds its pre-expansion row count, and catches
known-bad rows (e.g., the USPSTF error-message row currently in guidelines).

Run against the built KB:
    pytest kb/tests/test_kb_expansion.py -v
Skips gracefully if kb/output/aegis_kb.sqlite is missing.
"""
from __future__ import annotations

import json
import os
import sqlite3

import pytest

KB_PATH = os.path.join(os.path.dirname(__file__), "..", "output", "aegis_kb.sqlite")
BASELINE_PATH = os.path.join(os.path.dirname(__file__), "baseline_counts.json")


@pytest.fixture(scope="module")
def conn():
    if not os.path.exists(KB_PATH):
        pytest.skip(f"KB not built: {KB_PATH}")
    c = sqlite3.connect(KB_PATH)
    yield c
    c.close()


@pytest.fixture(scope="module")
def baseline():
    with open(BASELINE_PATH) as f:
        return json.load(f)


def _count(conn: sqlite3.Connection, table: str) -> int:
    return conn.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]


@pytest.mark.parametrize(
    "table",
    ["rxnorm_lookup", "drugs", "drug_ingredients", "interactions",
     "warnings", "supplements", "terms", "guidelines"],
)
def test_table_meets_baseline(conn, baseline, table):
    """No table may shrink below its pre-expansion row count."""
    actual = _count(conn, table)
    expected_min = baseline["row_counts"][table]
    assert actual >= expected_min, (
        f"{table} shrank: {actual} < baseline {expected_min}"
    )


def test_no_uspstf_error_rows_in_guidelines(conn):
    """Step 1 success criterion: the error-message row must be gone."""
    bad = conn.execute(
        "SELECT COUNT(*) FROM guidelines "
        "WHERE title LIKE '%API key%' OR title LIKE '%Please contact%' "
        "OR description LIKE '%API key%'"
    ).fetchone()[0]
    assert bad == 0, f"guidelines still has {bad} error-message rows"


def test_guideline_grades_valid(conn):
    """All guideline grades must be A/B/C/D/I."""
    bad = conn.execute(
        "SELECT COUNT(*) FROM guidelines "
        "WHERE grade NOT IN ('A','B','C','D','I')"
    ).fetchone()[0]
    assert bad == 0, f"{bad} guidelines have invalid grade"
