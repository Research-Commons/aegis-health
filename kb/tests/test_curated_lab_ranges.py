"""Per-row sanity + integration build test for curated_lab_ranges KB source."""
from __future__ import annotations

import sqlite3
from pathlib import Path

import pytest

from kb.sources import curated_lab_ranges as clr


# ----- per-row sanity ------------------------------------------------------


def test_lab_reference_ranges_rows_have_required_fields():
    for r in clr.CURATED_LAB_REFERENCE_RANGES:
        assert r["test_name"] and r["test_name"].strip(), f"empty test_name: {r}"
        assert r["units"] and r["units"].strip(), f"empty units: {r}"
        assert r["citation"] and r["citation"].strip(), f"empty citation: {r}"
        assert r["source"] and r["source"].strip(), f"empty source: {r}"
        assert r["population"] in {"adult", "adult_male", "adult_female", "pediatric", "all"}, r
        if r["ref_low"] is not None and r["ref_high"] is not None:
            assert r["ref_low"] <= r["ref_high"], f"ref_low > ref_high: {r}"


def test_clinical_thresholds_rows_have_required_fields():
    for r in clr.CURATED_CLINICAL_THRESHOLDS:
        assert r["test_name"] and r["threshold_tier"] and r["units"] and r["citation"] and r["source"], r


def test_critical_values_rows_have_required_fields():
    for r in clr.CURATED_CRITICAL_VALUES:
        assert r["test_name"] and r["units"] and r["citation"] and r["source"], r
        assert r["direction"] in {"low", "high"}, r
        assert r["cutoff"] is not None, r


def test_pediatric_rows_have_required_fields():
    for r in clr.CURATED_PEDIATRIC_RANGES:
        assert r["test_name"] and r["units"] and r["citation"] and r["source"], r
        if r["age_low"] is not None and r["age_high"] is not None:
            assert r["age_low"] <= r["age_high"], r


def test_pregnancy_rows_have_required_fields():
    for r in clr.CURATED_PREGNANCY_RANGES:
        assert r["test_name"] and r["units"] and r["citation"] and r["source"], r
        assert r["trimester"] is None or r["trimester"] in {1, 2, 3}, r


def test_at_least_30_lab_reference_ranges():
    # D-05 says ~30-50 analytes; floor is 30.
    assert len(clr.CURATED_LAB_REFERENCE_RANGES) >= 30, len(clr.CURATED_LAB_REFERENCE_RANGES)


def test_source_short_codes_match_docstring_keys():
    docstring = clr.__doc__ or ""
    seen_sources = (
        {r["source"] for r in clr.CURATED_LAB_REFERENCE_RANGES}
        | {r["source"] for r in clr.CURATED_CLINICAL_THRESHOLDS}
        | {r["source"] for r in clr.CURATED_CRITICAL_VALUES}
        | {r["source"] for r in clr.CURATED_PEDIATRIC_RANGES}
        | {r["source"] for r in clr.CURATED_PREGNANCY_RANGES}
    )
    # Every source short-code used must appear in the module docstring sources block.
    for s in seen_sources:
        assert s in docstring, f"source short-code '{s}' missing from module docstring"


# ----- integration: build against fresh seeded DB --------------------------


def _seed_minimal_kb(db_path: str) -> None:
    """Apply schema.sql + create empty terms table for cross-check."""
    schema_path = Path(__file__).parent.parent / "kb" / "schema.sql"
    sql = schema_path.read_text(encoding="utf-8")
    conn = sqlite3.connect(db_path)
    conn.executescript(sql)
    # `terms` is in schema.sql; this is a no-op safety in case of a divergent
    # seed harness elsewhere.
    conn.execute(
        "CREATE TABLE IF NOT EXISTS terms ("
        "  id INTEGER PRIMARY KEY AUTOINCREMENT, term TEXT UNIQUE, "
        "  definition TEXT, citation TEXT)"
    )
    conn.commit()
    conn.close()


def test_build_inserts_rows(tmp_path: Path):
    db = tmp_path / "test_kb.sqlite"
    _seed_minimal_kb(str(db))
    n = clr.build(str(db))
    assert n >= 30, f"only inserted {n} rows"

    conn = sqlite3.connect(str(db))
    try:
        c = conn.execute("SELECT COUNT(*) FROM lab_reference_ranges").fetchone()[0]
        assert c >= 30, c
        for table in (
            "lab_reference_ranges",
            "clinical_thresholds",
            "critical_values",
            "reference_ranges_pediatric",
            "reference_ranges_pregnancy",
        ):
            null_rows = conn.execute(
                f"SELECT COUNT(*) FROM {table} WHERE source IS NULL OR citation IS NULL"
            ).fetchone()[0]
            assert null_rows == 0, f"{table} has {null_rows} rows with NULL source/citation"
    finally:
        conn.close()


def test_build_is_idempotent(tmp_path: Path):
    db = tmp_path / "test_kb.sqlite"
    _seed_minimal_kb(str(db))
    n1 = clr.build(str(db))
    n2 = clr.build(str(db))  # INSERT OR IGNORE + natural-key precheck — no double-up
    assert n1 > 0
    # Idempotency: a second build run must not duplicate any of the seeded rows.
    # The natural-key precheck implementation guarantees n2 == 0.
    assert n2 == 0, f"second build inserted {n2} duplicate rows"
    conn = sqlite3.connect(str(db))
    try:
        for table, expected in (
            ("lab_reference_ranges", len(clr.CURATED_LAB_REFERENCE_RANGES)),
            ("clinical_thresholds", len(clr.CURATED_CLINICAL_THRESHOLDS)),
            ("critical_values", len(clr.CURATED_CRITICAL_VALUES)),
            ("reference_ranges_pediatric", len(clr.CURATED_PEDIATRIC_RANGES)),
            ("reference_ranges_pregnancy", len(clr.CURATED_PREGNANCY_RANGES)),
        ):
            c = conn.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
            assert c == expected, f"{table}: expected {expected}, got {c}"
    finally:
        conn.close()


def test_check_constraint_rejects_bad_direction(tmp_path: Path):
    """critical_values.direction CHECK constraint must reject anything other than low/high."""
    db = tmp_path / "test_kb.sqlite"
    _seed_minimal_kb(str(db))
    conn = sqlite3.connect(str(db))
    try:
        with pytest.raises(sqlite3.IntegrityError):
            conn.execute(
                "INSERT INTO critical_values (test_name, direction, cutoff, units, citation, source) "
                "VALUES ('foo', 'bogus', 1.0, 'mg/dL', 'cite', 'src')"
            )
    finally:
        conn.close()


def test_check_constraint_rejects_bad_trimester(tmp_path: Path):
    """reference_ranges_pregnancy.trimester CHECK constraint must reject values outside 1-3."""
    db = tmp_path / "test_kb.sqlite"
    _seed_minimal_kb(str(db))
    conn = sqlite3.connect(str(db))
    try:
        with pytest.raises(sqlite3.IntegrityError):
            conn.execute(
                "INSERT INTO reference_ranges_pregnancy (test_name, trimester, ref_low, ref_high, units, citation, source) "
                "VALUES ('foo', 7, 0.1, 1.0, 'mg/dL', 'cite', 'src')"
            )
    finally:
        conn.close()
