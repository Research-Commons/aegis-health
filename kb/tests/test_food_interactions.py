"""Unit tests for kb.sources.food_interactions."""
from __future__ import annotations

import sqlite3
from pathlib import Path

import pytest

from kb.sources import food_interactions


def _seed_minimal_kb(db_path: str) -> None:
    conn = sqlite3.connect(db_path)
    conn.executescript(
        """
        CREATE TABLE rxnorm_lookup (
            rxcui TEXT PRIMARY KEY, brand_name TEXT, generic_name TEXT,
            tty TEXT, category TEXT, source TEXT
        );
        CREATE TABLE warnings (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            drug_rxcui TEXT, drug_name TEXT, warning_type TEXT,
            population TEXT, description TEXT, severity INTEGER,
            source TEXT
        );
        """
    )
    conn.executemany(
        "INSERT INTO rxnorm_lookup VALUES (?, ?, ?, ?, ?, ?)",
        [
            ("36567",  "Zocor",    "Simvastatin", "IN", "Rx", "test"),
            ("11289",  "Coumadin", "Warfarin",    "IN", "Rx", "test"),
            ("8123",   "Nardil",   "Phenelzine",  "IN", "Rx", "test"),
        ],
    )
    conn.commit()
    conn.close()


def test_curated_entries_have_valid_severity():
    for food, drug, sev, desc, src in food_interactions.FOOD_INTERACTIONS:
        assert food and drug and desc and src
        assert 1 <= sev <= 5, f"{food} + {drug}: severity {sev} out of range"


def test_build_inserts_rows_for_known_drugs(tmp_path: Path):
    db = tmp_path / "test.sqlite"
    _seed_minimal_kb(str(db))
    n = food_interactions.build(str(db))
    assert n >= 3          # simvastatin, warfarin, phenelzine have entries in the list
    conn = sqlite3.connect(str(db))
    try:
        rows = conn.execute(
            "SELECT drug_name, population, severity "
            "FROM warnings WHERE warning_type = ?",
            (food_interactions.WARNING_TYPE,),
        ).fetchall()
    finally:
        conn.close()
    assert rows
    drug_foods = {(drug, pop) for drug, pop, _sev in rows}
    assert ("Simvastatin", "grapefruit juice") in drug_foods
    assert ("Warfarin", "vitamin K-rich foods") in drug_foods
    assert ("Phenelzine", "tyramine-rich foods") in drug_foods
    # MAOI + tyramine must be severity 5 (life-threatening hypertensive crisis)
    phen_sev = [sev for drug, pop, sev in rows
                if drug == "Phenelzine" and pop == "tyramine-rich foods"][0]
    assert phen_sev == 5


def test_build_is_idempotent(tmp_path: Path):
    db = tmp_path / "test.sqlite"
    _seed_minimal_kb(str(db))
    n1 = food_interactions.build(str(db))
    n2 = food_interactions.build(str(db))
    assert n1 > 0
    assert n2 == 0      # second run adds nothing
    conn = sqlite3.connect(str(db))
    try:
        total = conn.execute(
            "SELECT COUNT(*) FROM warnings WHERE warning_type = ?",
            (food_interactions.WARNING_TYPE,),
        ).fetchone()[0]
    finally:
        conn.close()
    assert total == n1  # no duplicates


def test_build_skips_drugs_not_in_rxnorm(tmp_path: Path):
    """When a curated entry points to a drug that isn't in rxnorm_lookup
    (e.g. minimal seed KB for a test), the build must skip it cleanly."""
    db = tmp_path / "test.sqlite"
    conn = sqlite3.connect(str(db))
    conn.executescript(
        """
        CREATE TABLE rxnorm_lookup (
            rxcui TEXT PRIMARY KEY, brand_name TEXT, generic_name TEXT,
            tty TEXT, category TEXT, source TEXT
        );
        CREATE TABLE warnings (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            drug_rxcui TEXT, drug_name TEXT, warning_type TEXT,
            population TEXT, description TEXT, severity INTEGER,
            source TEXT
        );
        """
    )
    conn.commit()
    conn.close()
    # No rxnorm_lookup rows → every curated entry is skipped, no crash.
    n = food_interactions.build(str(db))
    assert n == 0


def test_coverage_spans_major_interaction_classes():
    """Sanity check that the curated set hits the key mechanistic classes."""
    all_foods = {f.lower() for f, *_ in food_interactions.FOOD_INTERACTIONS}
    assert any("grapefruit" in f for f in all_foods)
    assert any("tyramine"   in f for f in all_foods)
    assert any("vitamin k"  in f for f in all_foods)
    assert any("dairy"      in f for f in all_foods)
    assert any("alcohol"    in f for f in all_foods)
    assert any("potassium"  in f for f in all_foods)
