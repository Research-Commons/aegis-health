"""Tests for KB schema creation and basic integrity."""
from __future__ import annotations

import os
import sqlite3
import tempfile

import pytest

SCHEMA_PATH = os.path.join(os.path.dirname(__file__), "..", "kb", "schema.sql")

EXPECTED_TABLES = {
    "rxnorm_lookup",
    "drugs",
    "drug_ingredients",
    "interactions",
    "warnings",
    "supplements",
    "terms",
    "guidelines",
}


@pytest.fixture()
def db_path():
    """Create a temp DB with schema applied."""
    fd, path = tempfile.mkstemp(suffix=".sqlite")
    os.close(fd)
    conn = sqlite3.connect(path)
    with open(SCHEMA_PATH) as f:
        conn.executescript(f.read())
    conn.close()
    yield path
    os.unlink(path)


@pytest.fixture()
def conn(db_path):
    c = sqlite3.connect(db_path)
    c.execute("PRAGMA foreign_keys = ON")
    yield c
    c.close()


class TestSchemaCreation:
    def test_all_tables_exist(self, conn: sqlite3.Connection):
        tables = {
            row[0]
            for row in conn.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            )
        }
        assert EXPECTED_TABLES.issubset(tables)

    def test_rxnorm_lookup_columns(self, conn: sqlite3.Connection):
        info = conn.execute("PRAGMA table_info(rxnorm_lookup)").fetchall()
        cols = {row[1] for row in info}
        assert {"rxcui", "brand_name", "generic_name", "tty", "source"}.issubset(cols)

    def test_drugs_columns(self, conn: sqlite3.Connection):
        info = conn.execute("PRAGMA table_info(drugs)").fetchall()
        cols = {row[1] for row in info}
        assert {"rxcui", "drug_name", "generic_name", "source"}.issubset(cols)

    def test_interactions_severity_constraint(self, conn: sqlite3.Connection):
        conn.execute(
            "INSERT INTO rxnorm_lookup (rxcui, brand_name, generic_name, source) "
            "VALUES ('111', 'DrugA', 'drug_a', 'test')"
        )
        conn.execute(
            "INSERT INTO rxnorm_lookup (rxcui, brand_name, generic_name, source) "
            "VALUES ('222', 'DrugB', 'drug_b', 'test')"
        )

        conn.execute(
            "INSERT INTO interactions "
            "(drug_rxcui_1, drug_name_1, drug_rxcui_2, drug_name_2, severity, source) "
            "VALUES ('111', 'DrugA', '222', 'DrugB', 3, 'test')"
        )

        with pytest.raises(sqlite3.IntegrityError):
            conn.execute(
                "INSERT INTO interactions "
                "(drug_rxcui_1, drug_name_1, drug_rxcui_2, drug_name_2, severity, source) "
                "VALUES ('111', 'DrugA', '222', 'DrugB', 0, 'test')"
            )

        with pytest.raises(sqlite3.IntegrityError):
            conn.execute(
                "INSERT INTO interactions "
                "(drug_rxcui_1, drug_name_1, drug_rxcui_2, drug_name_2, severity, source) "
                "VALUES ('111', 'DrugA', '222', 'DrugB', 6, 'test')"
            )

    def test_severity_valid_range(self, conn: sqlite3.Connection):
        conn.execute(
            "INSERT INTO rxnorm_lookup (rxcui, brand_name, generic_name, source) "
            "VALUES ('333', 'DrugC', 'drug_c', 'test')"
        )
        for sev in (1, 2, 3, 4, 5):
            conn.execute(
                "INSERT INTO interactions "
                "(drug_rxcui_1, drug_name_1, drug_rxcui_2, drug_name_2, severity, source) "
                "VALUES ('333', 'DrugC', '333', 'DrugC', ?, 'test')",
                (sev,),
            )
        assert conn.execute("SELECT COUNT(*) FROM interactions").fetchone()[0] >= 5

    def test_warnings_severity_constraint(self, conn: sqlite3.Connection):
        conn.execute(
            "INSERT INTO rxnorm_lookup (rxcui, brand_name, generic_name, source) "
            "VALUES ('444', 'DrugD', 'drug_d', 'test')"
        )
        conn.execute(
            "INSERT INTO warnings (drug_rxcui, drug_name, warning_type, description, severity, source) "
            "VALUES ('444', 'DrugD', 'boxed', 'Severe warning', 5, 'test')"
        )
        with pytest.raises(sqlite3.IntegrityError):
            conn.execute(
                "INSERT INTO warnings (drug_rxcui, drug_name, warning_type, description, severity, source) "
                "VALUES ('444', 'DrugD', 'boxed', 'Bad severity', 0, 'test')"
            )

    def test_supplements_columns(self, conn: sqlite3.Connection):
        info = conn.execute("PRAGMA table_info(supplements)").fetchall()
        cols = {row[1] for row in info}
        assert {"supplement_name", "interacting_drug", "severity", "source"}.issubset(cols)

    def test_terms_unique_constraint(self, conn: sqlite3.Connection):
        conn.execute(
            "INSERT INTO terms (term, definition, source) VALUES ('Hypertension', 'High BP', 'test')"
        )
        with pytest.raises(sqlite3.IntegrityError):
            conn.execute(
                "INSERT INTO terms (term, definition, source) VALUES ('Hypertension', 'Dup', 'test')"
            )

    def test_guidelines_columns(self, conn: sqlite3.Connection):
        info = conn.execute("PRAGMA table_info(guidelines)").fetchall()
        cols = {row[1] for row in info}
        assert {
            "recommendation_id", "title", "grade",
            "population_age_min", "population_age_max", "population_sex",
            "description", "source",
        }.issubset(cols)

    def test_drug_ingredients_columns(self, conn: sqlite3.Connection):
        info = conn.execute("PRAGMA table_info(drug_ingredients)").fetchall()
        cols = {row[1] for row in info}
        assert {"parent_rxcui", "parent_name", "ingredient_name", "source"}.issubset(cols)

    def test_indexes_exist(self, conn: sqlite3.Connection):
        indexes = {
            row[1]
            for row in conn.execute(
                "SELECT * FROM sqlite_master WHERE type='index' AND sql IS NOT NULL"
            )
        }
        assert "idx_drugs_rxcui" in indexes
        assert "idx_drugs_name" in indexes
        assert "idx_rxnorm_brand" in indexes
        assert "idx_rxnorm_generic" in indexes

    def test_schema_idempotent(self, db_path: str):
        """Applying schema twice should not raise."""
        conn = sqlite3.connect(db_path)
        with open(SCHEMA_PATH) as f:
            schema = f.read()
        conn.executescript(schema)
        conn.executescript(schema)
        conn.close()
