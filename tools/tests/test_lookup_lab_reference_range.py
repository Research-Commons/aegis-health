"""Unit tests for tools/tools/lookup_lab_reference_range.py."""
from __future__ import annotations

import sqlite3
from pathlib import Path

import pytest

from tools.tools.lookup_lab_reference_range import (
    _classify_population,
    _lookup_pregnancy_range,
    lookup_lab_reference_range,
)

# Repo-relative path to the canonical schema (Plan 01 appended the 5 lab tables).
# Helper reads this file fresh per test so the helper never falls behind a schema
# evolution — single source of truth.
_REPO_ROOT = Path(__file__).parent.parent.parent
_SCHEMA_SQL = _REPO_ROOT / "kb" / "kb" / "schema.sql"


@pytest.mark.parametrize(
    "age,sex,expected",
    [
        (None, None,     "adult"),
        (42,   None,     "adult"),
        (42,   "male",   "adult_male"),
        (42,   "MALE",   "adult_male"),
        (42,   "m",      "adult_male"),
        (42,   "female", "adult_female"),
        (42,   "f",      "adult_female"),
        (None, "female", "adult_female"),
        (17,   None,     "pediatric"),
        (17,   "female", "pediatric"),
        (0,    None,     "pediatric"),
        (18,   None,     "adult"),
        (65,   "male",   "adult_male"),
    ],
)
def test_classify_population_truth_table(age, sex, expected):
    assert _classify_population(age, sex) == expected


def _seed_db(db_path: str) -> None:
    """Apply the canonical kb/kb/schema.sql then insert hermetic test rows.

    Schema-load pattern (warning 3 / PATTERNS.md): schema.sql is the single
    source of truth for table shape. Inline DDL is forbidden in this helper
    so that the test never falls behind a schema migration.
    """
    schema_sql = _SCHEMA_SQL.read_text(encoding="utf-8")
    conn = sqlite3.connect(db_path)
    conn.executescript(schema_sql)
    # Insert hermetic test rows. Schema (Plan 01) defines the column lists;
    # we provide values for the non-PK / non-default columns only.
    conn.executemany(
        "INSERT INTO lab_reference_ranges (test_name, ref_low, ref_high, units, population, citation) VALUES (?, ?, ?, ?, ?, ?)",
        [
            ("LDL cholesterol", None, 100.0, "mg/dL", "adult",        "NHLBI ATP III"),
            ("hemoglobin a1c",  None, 5.6,   "%",     "adult",        "ADA A1C"),
            ("hemoglobin",      13.5, 17.5,  "g/dL",  "adult_male",   "MedlinePlus"),
            ("hemoglobin",      12.0, 15.5,  "g/dL",  "adult_female", "MedlinePlus"),
            ("fasting glucose", 70.0, 99.0,  "mg/dL", "adult",        "CDC FBG"),
        ],
    )
    conn.executemany(
        "INSERT INTO reference_ranges_pediatric (test_name, age_low, age_high, sex, ref_low, ref_high, units, citation) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        [
            ("hemoglobin",     6,  12, "all", 11.5, 15.5, "g/dL", "Mayo-PEDS"),
            ("hemoglobin a1c", 10, 18, "all", None, 5.6,  "%",    "ADA pediatric"),
        ],
    )
    conn.executemany(
        "INSERT INTO reference_ranges_pregnancy (test_name, trimester, ref_low, ref_high, units, citation) VALUES (?, ?, ?, ?, ?, ?)",
        [
            ("creatinine", 1, 0.4, 0.7, "mg/dL", "PMC pregnancy"),
            ("creatinine", 2, 0.4, 0.8, "mg/dL", "PMC pregnancy"),
            ("creatinine", 3, 0.4, 0.9, "mg/dL", "PMC pregnancy"),
        ],
    )
    conn.commit()
    conn.close()


@pytest.fixture()
def test_db(tmp_path: Path) -> str:
    db_path = str(tmp_path / "test_kb.sqlite")
    _seed_db(db_path)
    return db_path


def test_adult_ldl_hit(test_db):
    r = lookup_lab_reference_range("LDL cholesterol", db_path=test_db)
    assert r.get("error") is None, r
    assert r["ref_high"] == 100.0
    assert r["units"] == "mg/dL"
    assert r["population"] == "adult"
    assert r["source"] == "kb"
    assert r["citation"] == "NHLBI ATP III"


def test_adult_a1c_only_upper(test_db):
    r = lookup_lab_reference_range("hemoglobin a1c", db_path=test_db)
    assert r["ref_low"] is None
    assert r["ref_high"] == 5.6


def test_sex_routing_hemoglobin_female(test_db):
    r = lookup_lab_reference_range("hemoglobin", age=42, sex="female", db_path=test_db)
    assert r["population"] == "adult_female"
    assert r["ref_low"] == 12.0 and r["ref_high"] == 15.5


def test_sex_routing_hemoglobin_male(test_db):
    r = lookup_lab_reference_range("hemoglobin", age=42, sex="m", db_path=test_db)
    assert r["population"] == "adult_male"
    assert r["ref_low"] == 13.5 and r["ref_high"] == 17.5


def test_pediatric_hit(test_db):
    r = lookup_lab_reference_range("hemoglobin", age=8, db_path=test_db)
    assert r["population"] == "pediatric"
    assert r["ref_low"] == 11.5 and r["ref_high"] == 15.5


def test_pediatric_miss_falls_back_to_adult_default(test_db):
    r = lookup_lab_reference_range("LDL cholesterol", age=8, db_path=test_db)
    assert r.get("error") is None, r
    assert r["population"] == "adult"
    assert r["ref_high"] == 100.0


def test_case_insensitive_test_name(test_db):
    r = lookup_lab_reference_range("Hemoglobin A1C", db_path=test_db)
    assert r["ref_high"] == 5.6


def test_unknown_test_returns_error(test_db):
    r = lookup_lab_reference_range("nonexistent_test", db_path=test_db)
    assert "error" in r
    assert "nonexistent_test" in r["error"]


def test_missing_db_returns_error():
    r = lookup_lab_reference_range("LDL cholesterol", db_path="/nonexistent/path.sqlite")
    assert "error" in r
    assert "/nonexistent/path.sqlite" in r["error"]


def test_empty_test_name_returns_error():
    r = lookup_lab_reference_range("")
    assert r == {"error": "Empty test_name provided"}
    r = lookup_lab_reference_range(None)  # type: ignore[arg-type]
    assert r == {"error": "Empty test_name provided"}


def test_pregnancy_trimester_hit(test_db):
    r = _lookup_pregnancy_range("creatinine", trimester=2, db_path=test_db)
    assert r.get("error") is None, r
    assert r["population"] == "pregnant"
    assert r["trimester"] == 2
    assert r["ref_high"] == 0.8
    assert r["source"] == "kb"


def test_pregnancy_invalid_trimester_returns_error(test_db):
    r = _lookup_pregnancy_range("creatinine", trimester=4, db_path=test_db)
    assert "error" in r


def test_pregnancy_unknown_test_returns_error(test_db):
    r = _lookup_pregnancy_range("nonexistent_test", trimester=1, db_path=test_db)
    assert "error" in r


def test_pregnancy_missing_db_returns_error():
    r = _lookup_pregnancy_range("creatinine", trimester=1, db_path="/nonexistent.sqlite")
    assert "error" in r
