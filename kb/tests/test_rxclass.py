"""Unit tests for kb.sources.rxclass.

Exercises both the API-response parser (mocked) and the curated
class-pair seed insert. Does not hit the network.
"""
from __future__ import annotations

import sqlite3
from pathlib import Path
from unittest.mock import patch

from kb.sources import rxclass


SAMPLE_RXCLASS_RESPONSE = {
    "rxclassDrugInfoList": {
        "rxclassDrugInfo": [
            {
                "minConcept": {"rxcui": "36437", "name": "Sertraline", "tty": "IN"},
                "rxclassMinConceptItem": {
                    "classId":   "N06AB",
                    "className": "Selective serotonin reuptake inhibitors",
                    "classType": "ATC1-4",
                },
                "rela":       "has_atc",
                "relaSource": "ATC",
            },
            {
                # Deeper ATC level — should be dropped (not in allowed_types)
                "minConcept": {"rxcui": "36437", "name": "Sertraline", "tty": "IN"},
                "rxclassMinConceptItem": {
                    "classId":   "N06AB06",
                    "className": "Sertraline",
                    "classType": "ATC5",
                },
                "rela":       "has_atc",
                "relaSource": "ATC",
            },
        ],
    },
}


def test_fetch_filters_to_allowed_class_types():
    """Only ATC1-4 rows survive; ATC5 is dropped; classType normalised to 'ATC'."""
    with patch.object(rxclass, "_get_json", return_value=SAMPLE_RXCLASS_RESPONSE):
        rows = rxclass._fetch_classes_for_rxcui("36437", "ATC", ["ATC1-4"])
    assert len(rows) == 1
    assert rows[0] == {
        "class_id":   "N06AB",
        "class_name": "Selective serotonin reuptake inhibitors",
        "class_type": "ATC",
    }


def test_fetch_handles_empty_response():
    with patch.object(rxclass, "_get_json", return_value=None):
        assert rxclass._fetch_classes_for_rxcui("999", "ATC", ["ATC1-4"]) == []


def test_fetch_handles_missing_fields():
    """Rows with blank classId/className are skipped rather than inserted."""
    bad = {
        "rxclassDrugInfoList": {
            "rxclassDrugInfo": [
                {"rxclassMinConceptItem": {"classId": "N06AB"}},  # missing name+type
            ],
        },
    }
    with patch.object(rxclass, "_get_json", return_value=bad):
        assert rxclass._fetch_classes_for_rxcui("36437", "ATC", ["ATC1-4"]) == []


def _mini_schema(conn: sqlite3.Connection) -> None:
    """Apply just the tables rxclass touches — keeps the test self-contained."""
    conn.executescript(
        """
        CREATE TABLE rxnorm_lookup (
            rxcui TEXT PRIMARY KEY, brand_name TEXT, generic_name TEXT,
            tty TEXT, category TEXT, source TEXT
        );
        CREATE TABLE drug_classes (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            rxcui TEXT NOT NULL, drug_name TEXT NOT NULL,
            class_id TEXT NOT NULL, class_name TEXT NOT NULL,
            class_type TEXT NOT NULL, rela_source TEXT,
            source TEXT NOT NULL DEFAULT 'rxclass',
            UNIQUE (rxcui, class_id, class_type)
        );
        CREATE TABLE class_interactions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            class_id_1 TEXT NOT NULL, class_name_1 TEXT NOT NULL, class_type_1 TEXT NOT NULL,
            class_id_2 TEXT NOT NULL, class_name_2 TEXT NOT NULL, class_type_2 TEXT NOT NULL,
            severity INTEGER NOT NULL CHECK (severity BETWEEN 1 AND 5),
            description TEXT NOT NULL, mechanism TEXT,
            source TEXT NOT NULL DEFAULT 'curated_class'
        );
        INSERT INTO rxnorm_lookup (rxcui, brand_name, generic_name, tty, category, source)
        VALUES ('36437', 'Zoloft', 'Sertraline', 'IN', 'Rx', 'test');
        """
    )


def test_build_populates_tables(tmp_path: Path):
    """Full build() path inserts drug_classes from the mocked API and seeds curated pairs."""
    db = tmp_path / "test.sqlite"
    conn = sqlite3.connect(db)
    try:
        _mini_schema(conn)
        conn.commit()
    finally:
        conn.close()

    with patch.object(rxclass, "_get_json", return_value=SAMPLE_RXCLASS_RESPONSE), \
         patch.object(rxclass, "RATE_LIMIT_SLEEP", 0):
        total = rxclass.build(str(db))

    conn = sqlite3.connect(db)
    try:
        # drug_classes: 1 filtered row per relaSource (ATC + DAILYMED) = 2 rows
        # (DAILYMED will also be mocked with SSRI response; it's tolerable — the
        # UNIQUE (rxcui, class_id, class_type) constraint collapses duplicates)
        dc_rows = conn.execute("SELECT COUNT(*) FROM drug_classes").fetchone()[0]
        assert dc_rows >= 1

        ci_rows = conn.execute("SELECT COUNT(*) FROM class_interactions").fetchone()[0]
        assert ci_rows == len(rxclass.CURATED_CLASS_PAIRS)
    finally:
        conn.close()
    assert total > 0


def test_curated_class_pairs_have_valid_severity():
    """Curated seed rows must satisfy the CHECK (severity BETWEEN 1 AND 5) constraint."""
    for pair in rxclass.CURATED_CLASS_PAIRS:
        assert 1 <= pair["severity"] <= 5
        assert pair["description"]
        assert pair["source"]
        for key in ("class_id_1", "class_name_1", "class_type_1",
                    "class_id_2", "class_name_2", "class_type_2"):
            assert pair.get(key), f"missing {key} in {pair}"
