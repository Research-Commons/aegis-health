"""Per-row sanity + integration build test for curated_auto_defer KB source (D-11).

Mirrors test_curated_lab_ranges.py's pattern: hermetic schema-seed -> build()
exercise -> idempotency check -> CHECK-constraint enforcement.
"""
from __future__ import annotations

import sqlite3
from pathlib import Path

import pytest

from kb.sources import curated_auto_defer as cad


# ----- per-row sanity ------------------------------------------------------


def test_rows_have_required_fields():
    for r in cad.CURATED_AUTO_DEFER:
        assert r["canonical_name"] and r["canonical_name"].strip(), f"empty canonical_name: {r}"
        assert r["category"] in {"tumor_marker", "genetic", "pathology"}, r
        assert r["citation"] and r["citation"].strip(), f"empty citation: {r}"


def test_at_least_8_rows():
    """D-11 floor: tumor-markers + genetic + pathology, >=8 seeded."""
    assert len(cad.CURATED_AUTO_DEFER) >= 8, len(cad.CURATED_AUTO_DEFER)


def test_all_three_categories_represented():
    """Pathology rows MUST exist only in KB (proves the read path is real)."""
    cats = {r["category"] for r in cad.CURATED_AUTO_DEFER}
    assert cats == {"tumor_marker", "genetic", "pathology"}, cats


def test_unique_canonical_names():
    """canonical_name is the PRIMARY KEY -- duplicates would crash build()."""
    names = [r["canonical_name"] for r in cad.CURATED_AUTO_DEFER]
    assert len(names) == len(set(names)), names


# ----- integration: build against fresh seeded DB --------------------------


def _seed_minimal_kb(db_path: str) -> None:
    """Apply schema.sql so the auto_defer_tests CREATE TABLE block runs."""
    schema_path = Path(__file__).parent.parent / "kb" / "schema.sql"
    sql = schema_path.read_text(encoding="utf-8")
    conn = sqlite3.connect(db_path)
    conn.executescript(sql)
    conn.commit()
    conn.close()


def test_build_inserts_rows(tmp_path: Path):
    db = tmp_path / "test_kb.sqlite"
    _seed_minimal_kb(str(db))
    n = cad.build(str(db))
    assert n == len(cad.CURATED_AUTO_DEFER), f"inserted {n}, expected {len(cad.CURATED_AUTO_DEFER)}"

    conn = sqlite3.connect(str(db))
    try:
        c = conn.execute("SELECT COUNT(*) FROM auto_defer_tests").fetchone()[0]
        assert c == len(cad.CURATED_AUTO_DEFER), c
        # All rows must have non-null citation (schema NOT NULL + per-row discipline)
        null_rows = conn.execute(
            "SELECT COUNT(*) FROM auto_defer_tests WHERE citation IS NULL OR citation = ''"
        ).fetchone()[0]
        assert null_rows == 0, f"{null_rows} rows with NULL/empty citation"
    finally:
        conn.close()


def test_build_is_idempotent(tmp_path: Path):
    db = tmp_path / "test_kb.sqlite"
    _seed_minimal_kb(str(db))
    n1 = cad.build(str(db))
    n2 = cad.build(str(db))
    assert n1 > 0
    assert n2 == 0, f"second build inserted {n2} duplicate rows"

    conn = sqlite3.connect(str(db))
    try:
        c = conn.execute("SELECT COUNT(*) FROM auto_defer_tests").fetchone()[0]
        assert c == len(cad.CURATED_AUTO_DEFER), c
    finally:
        conn.close()


def test_check_constraint_rejects_bad_category(tmp_path: Path):
    """auto_defer_tests.category CHECK must reject anything outside the 3-enum."""
    db = tmp_path / "test_kb.sqlite"
    _seed_minimal_kb(str(db))
    conn = sqlite3.connect(str(db))
    try:
        with pytest.raises(sqlite3.IntegrityError):
            conn.execute(
                "INSERT INTO auto_defer_tests (canonical_name, category, citation) "
                "VALUES ('foo', 'not_a_real_category', 'cite')"
            )
    finally:
        conn.close()


def test_psa_row_present(tmp_path: Path):
    """PSA must be queryable as tumor_marker -- mirrors _AUTO_DEFER_CANONICAL fallback."""
    db = tmp_path / "test_kb.sqlite"
    _seed_minimal_kb(str(db))
    cad.build(str(db))
    conn = sqlite3.connect(str(db))
    try:
        row = conn.execute(
            "SELECT category FROM auto_defer_tests WHERE canonical_name = ?",
            ("PSA",),
        ).fetchone()
        assert row is not None and row[0] == "tumor_marker", row
    finally:
        conn.close()


def test_pathology_only_in_kb(tmp_path: Path):
    """Pathology rows (Gleason, Bethesda) must be readable via KB --
    proving the KB lookup path is the structural source-of-truth, not the
    in-memory legacy _CANONICAL_DEFAULT_CATEGORIES map (which only carries
    tumor_marker + genetic).
    """
    db = tmp_path / "test_kb.sqlite"
    _seed_minimal_kb(str(db))
    cad.build(str(db))
    conn = sqlite3.connect(str(db))
    try:
        row = conn.execute(
            "SELECT category FROM auto_defer_tests WHERE canonical_name = ?",
            ("Gleason score",),
        ).fetchone()
        assert row is not None and row[0] == "pathology", row
    finally:
        conn.close()
