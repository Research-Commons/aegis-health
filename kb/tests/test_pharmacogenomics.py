"""Unit tests for kb.sources.pharmacogenomics."""
from __future__ import annotations

import sqlite3
from pathlib import Path

import pytest

from kb.sources import pharmacogenomics as pgx


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
            ("32968", "Plavix",   "Clopidogrel",  "IN", "Rx", "test"),
            ("2670",  "Codeine",  "Codeine",      "IN", "Controlled", "test"),
            ("36567", "Zocor",    "Simvastatin",  "IN", "Rx", "test"),
            ("11289", "Coumadin", "Warfarin",     "IN", "Rx", "test"),
        ],
    )
    conn.commit()
    conn.close()


def test_curated_entries_have_valid_severity_and_fields():
    for phen, drug, sev, desc, src in pgx.CPIC_WARNINGS:
        assert phen and drug and desc and src
        assert 1 <= sev <= 5, f"{phen} + {drug}: severity {sev} out of range"
        # Phenotype should look structured (gene name + variant/phenotype)
        assert any(tok in phen for tok in (
            "CYP", "HLA", "TPMT", "NUDT15", "DPYD", "G6PD", "SLCO", "VKORC"
        )), f"phenotype {phen!r} lacks a recognized gene token"


def test_build_inserts_rows_for_known_drugs(tmp_path: Path):
    db = tmp_path / "test.sqlite"
    _seed_minimal_kb(str(db))
    n = pgx.build(str(db))
    assert n >= 4  # clopidogrel, codeine (×2 phenotypes), simvastatin, warfarin
    conn = sqlite3.connect(str(db))
    try:
        rows = conn.execute(
            "SELECT drug_name, population, severity FROM warnings "
            "WHERE warning_type = ?",
            (pgx.WARNING_TYPE,),
        ).fetchall()
    finally:
        conn.close()
    drug_phens = {(drug, pop) for drug, pop, _ in rows}
    assert ("Clopidogrel", "CYP2C19 poor metabolizer") in drug_phens
    assert ("Codeine", "CYP2D6 ultra-rapid metabolizer") in drug_phens
    assert ("Codeine", "CYP2D6 poor metabolizer") in drug_phens
    assert ("Simvastatin", "SLCO1B1 poor function") in drug_phens
    # Ultra-rapid codeine must be severity 5 (documented fatal pediatric cases)
    codeine_ur = [sev for drug, pop, sev in rows
                  if drug == "Codeine" and "ultra-rapid" in pop][0]
    assert codeine_ur == 5


def test_build_is_idempotent(tmp_path: Path):
    db = tmp_path / "test.sqlite"
    _seed_minimal_kb(str(db))
    n1 = pgx.build(str(db))
    n2 = pgx.build(str(db))
    assert n1 > 0
    assert n2 == 0
    conn = sqlite3.connect(str(db))
    try:
        total = conn.execute(
            "SELECT COUNT(*) FROM warnings WHERE warning_type = ?",
            (pgx.WARNING_TYPE,),
        ).fetchone()[0]
    finally:
        conn.close()
    assert total == n1


def test_coverage_includes_fda_boxed_warning_genes():
    """Sanity check: the FDA-boxed-warning PGx pairs must be present."""
    pairs = {(p, d.lower()) for p, d, *_ in pgx.CPIC_WARNINGS}
    # Every FDA boxed-warning PGx pair in our audience's drug list:
    must_include = [
        ("HLA-B*5701 positive", "abacavir"),
        ("HLA-B*1502 positive", "carbamazepine"),
        ("TPMT poor metabolizer", "azathioprine"),
    ]
    for phen, drug in must_include:
        assert (phen, drug) in pairs, f"missing required pair: {phen} + {drug}"
