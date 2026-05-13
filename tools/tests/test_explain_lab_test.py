"""Unit tests for tools/tools/explain_lab_test.py.

The wrapper is a thin alias-translator on top of `lookup_term`. Tests seed
a minimal `terms` table whose schema matches what `lookup_term` SELECTs
(`term, plain_language_definition, citation`). The production KB's `terms`
table uses different column names; that pre-existing schema drift is
surfaced separately by the Task 3 integration smoke and triaged outside
Plan 06.
"""
from __future__ import annotations

import sqlite3
from pathlib import Path

import pytest

from tools.tools.explain_lab_test import _LAB_TERM_ALIASES, explain_lab_test


def _seed_terms_db(db_path: str) -> None:
    """Seed the minimal `terms` table that explain_lab_test reads via lookup_term."""
    conn = sqlite3.connect(db_path)
    conn.executescript(
        """
        CREATE TABLE terms (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            term TEXT NOT NULL UNIQUE,
            plain_language_definition TEXT NOT NULL,
            citation TEXT NOT NULL
        );
        """
    )
    conn.executemany(
        "INSERT INTO terms (term, plain_language_definition, citation) VALUES (?, ?, ?)",
        [
            (
                "LDL cholesterol",
                "LDL cholesterol carries cholesterol from the liver to the rest of the body.",
                "https://medlineplus.gov/ldlhdlcholesterol.html",
            ),
            (
                "HDL cholesterol",
                "HDL cholesterol carries cholesterol away from the body's tissues.",
                "https://medlineplus.gov/ldlhdlcholesterol.html",
            ),
            (
                "Hemoglobin A1C",
                "Measures average blood sugar over the past 3 months.",
                "https://medlineplus.gov/a1c.html",
            ),
            (
                "Hemoglobin",
                "Hemoglobin is the protein in red blood cells that carries oxygen.",
                "https://medlineplus.gov/lab-tests/hemoglobin-test/",
            ),
            (
                "White blood cell count",
                "Measures the number of white blood cells in the blood.",
                "https://medlineplus.gov/lab-tests/complete-blood-count-cbc/",
            ),
            (
                "Creatinine",
                "Creatinine is a waste product filtered by the kidneys.",
                "https://medlineplus.gov/lab-tests/creatinine-test/",
            ),
        ],
    )
    conn.commit()
    conn.close()


@pytest.fixture()
def test_db(tmp_path: Path) -> str:
    db_path = str(tmp_path / "test_kb.sqlite")
    _seed_terms_db(db_path)
    return db_path


def test_alias_map_has_required_entries() -> None:
    """SAFETY: changing alias map must not lose the anchor analytes."""
    required = {
        "ldl",
        "ldl-c",
        "hba1c",
        "a1c",
        "wbc",
        "hgb",
        "creatinine",
        "egfr",
        "tsh",
    }
    missing = required - set(_LAB_TERM_ALIASES.keys())
    assert not missing, f"alias map missing required keys: {missing}"
    # All alias values must be non-empty stripped canonical names.
    for k, v in _LAB_TERM_ALIASES.items():
        assert v == v.strip() and v, f"empty/whitespace value for alias key {k!r}"
    # Plan must_have: alias map ≥ 20 entries.
    assert len(_LAB_TERM_ALIASES) >= 20, (
        f"alias map has only {len(_LAB_TERM_ALIASES)} entries; need ≥ 20"
    )


def test_alias_hit_ldl_c(test_db: str) -> None:
    r = explain_lab_test("LDL-C", db_path=test_db)
    assert r.get("error") is None, r
    assert r["test_name"] == "LDL cholesterol"
    assert "LDL cholesterol" in r["plain_language_definition"]
    assert r["citation"].startswith("https://medlineplus.gov/")


def test_alias_hit_ldl_bare(test_db: str) -> None:
    r = explain_lab_test("LDL", db_path=test_db)
    assert r["test_name"] == "LDL cholesterol"


def test_alias_hit_hba1c_case_insensitive(test_db: str) -> None:
    r = explain_lab_test("HbA1c", db_path=test_db)
    assert r["test_name"] == "Hemoglobin A1C"
    r2 = explain_lab_test("hba1c", db_path=test_db)
    assert r2["test_name"] == "Hemoglobin A1C"


def test_alias_hit_wbc(test_db: str) -> None:
    r = explain_lab_test("WBC", db_path=test_db)
    assert r["test_name"] == "White blood cell count"


def test_passthrough_canonical_name(test_db: str) -> None:
    """No alias hit, but the canonical name exists in terms — passthrough works."""
    r = explain_lab_test("Hemoglobin", db_path=test_db)
    assert r["test_name"] == "Hemoglobin"


def test_unknown_test_returns_error(test_db: str) -> None:
    r = explain_lab_test("UnknownLabName", db_path=test_db)
    assert "error" in r
    assert "UnknownLabName" in r["error"]


def test_empty_test_name_returns_error() -> None:
    assert explain_lab_test("") == {"error": "Empty test_name provided"}
    assert explain_lab_test(None) == {"error": "Empty test_name provided"}  # type: ignore[arg-type]


def test_missing_db_returns_error() -> None:
    r = explain_lab_test("LDL", db_path="/nonexistent/path.sqlite")
    assert "error" in r


def test_success_path_has_citation(test_db: str) -> None:
    """SAFETY-03 mechanical floor — every success returns a non-empty citation."""
    r = explain_lab_test("LDL", db_path=test_db)
    assert "citation" in r
    assert r["citation"] and isinstance(r["citation"], str)
