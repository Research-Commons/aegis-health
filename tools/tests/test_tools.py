"""Unit tests for the Aegis Health tools module.

Uses an in-memory SQLite database seeded with test fixtures so tests
run without the full knowledge base.
"""

from __future__ import annotations

import json
import sqlite3
import tempfile
from pathlib import Path

import pytest

from tools.tools.check_warnings import check_warnings
from tools.tools.decompose_product import decompose_product
from tools.tools.dispatcher import ToolDispatcher
from tools.tools.get_drug_info import get_drug_info
from tools.tools.get_guideline import get_guideline
from tools.tools.lookup_term import lookup_term
from tools.tools.normalize_drug import normalize_drug
from tools.tools.schemas import AegisResponse, Flag


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

def _seed_db(db_path: str) -> None:
    """Create test tables and populate with representative rows."""
    conn = sqlite3.connect(db_path)
    c = conn.cursor()

    c.execute("""
        CREATE TABLE rxnorm_lookup (
            brand_name TEXT,
            generic_name TEXT,
            rxcui TEXT,
            category TEXT
        )
    """)
    c.executemany(
        "INSERT INTO rxnorm_lookup VALUES (?, ?, ?, ?)",
        [
            ("Tylenol", "acetaminophen", "161", "OTC"),
            ("Advil", "ibuprofen", "5640", "OTC"),
            ("Lipitor", "atorvastatin", "83367", "Rx"),
            ("OxyContin", "oxycodone", "7804", "Controlled"),
            ("Vitamin D", "cholecalciferol", "11253", "Supplement"),
        ],
    )

    c.execute("""
        CREATE TABLE drugs (
            name TEXT,
            rxcui TEXT,
            drug_class TEXT,
            category TEXT,
            warnings_summary TEXT,
            citation TEXT
        )
    """)
    c.executemany(
        "INSERT INTO drugs VALUES (?, ?, ?, ?, ?, ?)",
        [
            ("acetaminophen", "161", "Analgesic", "OTC",
             "Hepatotoxicity risk at high doses", "FDA Label"),
            ("ibuprofen", "5640", "NSAID", "OTC",
             "GI bleeding risk; renal impairment", "FDA Label"),
            ("atorvastatin", "83367", "Statin", "Rx",
             "Monitor liver enzymes", "FDA Label"),
            ("oxycodone", "7804", "Opioid Analgesic", "Controlled",
             "High abuse potential; respiratory depression", "DEA / FDA Label"),
            ("warfarin", "11289", "Anticoagulant", "Rx",
             "Bleeding risk; requires INR monitoring", "FDA Label"),
        ],
    )

    c.execute("""
        CREATE TABLE drug_ingredients (
            product_name TEXT,
            ingredient_name TEXT,
            rxcui TEXT
        )
    """)
    c.executemany(
        "INSERT INTO drug_ingredients VALUES (?, ?, ?)",
        [
            ("nyquil", "acetaminophen", "161"),
            ("nyquil", "dextromethorphan", "3289"),
            ("nyquil", "doxylamine", "3443"),
        ],
    )

    c.execute("""
        CREATE TABLE interactions (
            drug_a TEXT,
            drug_b TEXT,
            rxcui_a TEXT,
            rxcui_b TEXT,
            severity INTEGER,
            description TEXT,
            citation TEXT
        )
    """)
    c.executemany(
        "INSERT INTO interactions VALUES (?, ?, ?, ?, ?, ?, ?)",
        [
            ("ibuprofen", "warfarin", "5640", "11289", 4,
             "NSAIDs increase bleeding risk with anticoagulants",
             "FDA Drug Safety Communication"),
            ("acetaminophen", "warfarin", "161", "11289", 3,
             "Acetaminophen may enhance anticoagulant effect of warfarin",
             "Lexicomp"),
        ],
    )

    c.execute("""
        CREATE TABLE contraindications (
            drug_name TEXT,
            rxcui TEXT,
            condition TEXT,
            severity INTEGER,
            description TEXT,
            citation TEXT
        )
    """)
    c.executemany(
        "INSERT INTO contraindications VALUES (?, ?, ?, ?, ?, ?)",
        [
            ("ibuprofen", "5640", "kidney disease", 4,
             "NSAIDs contraindicated in renal impairment",
             "KDIGO Guidelines"),
        ],
    )

    c.execute("""
        CREATE TABLE terms (
            term TEXT,
            plain_language_definition TEXT,
            citation TEXT
        )
    """)
    c.executemany(
        "INSERT INTO terms VALUES (?, ?, ?)",
        [
            ("hypertension",
             "High blood pressure – when the force of blood against artery walls is consistently too high.",
             "AHA"),
            ("NSAID",
             "Non-Steroidal Anti-Inflammatory Drug – a class of pain relievers including ibuprofen and naproxen.",
             "MedlinePlus"),
        ],
    )

    c.execute("""
        CREATE TABLE guidelines (
            title TEXT,
            grade TEXT,
            description TEXT,
            population TEXT,
            citation TEXT,
            min_age INTEGER,
            max_age INTEGER,
            sex TEXT,
            condition TEXT
        )
    """)
    c.executemany(
        "INSERT INTO guidelines VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        [
            ("Blood Pressure Screening", "A",
             "Screen for hypertension in adults aged 18+",
             "Adults 18+", "USPSTF 2021", 18, 120, "all", ""),
            ("Breast Cancer Screening", "B",
             "Biennial mammography for women aged 50-74",
             "Women 50-74", "USPSTF 2024", 50, 74, "female", ""),
            ("Statin Use for CVD Prevention", "B",
             "Prescribe statin for adults 40-75 with CVD risk factors",
             "Adults 40-75 with risk factors", "USPSTF 2022",
             40, 75, "all", "cardiovascular disease"),
        ],
    )

    conn.commit()
    conn.close()


@pytest.fixture()
def test_db(tmp_path: Path) -> str:
    db_path = str(tmp_path / "test_kb.sqlite")
    _seed_db(db_path)
    return db_path


# ---------------------------------------------------------------------------
# normalize_drug
# ---------------------------------------------------------------------------

class TestNormalizeDrug:
    def test_brand_to_generic(self, test_db: str) -> None:
        result = normalize_drug("Tylenol", db_path=test_db)
        assert result["generic_name"] == "acetaminophen"
        assert result["rxcui"] == "161"
        assert result["category"] == "OTC"

    def test_case_insensitive(self, test_db: str) -> None:
        result = normalize_drug("tylenol", db_path=test_db)
        assert result["generic_name"] == "acetaminophen"

    def test_generic_passthrough(self, test_db: str) -> None:
        result = normalize_drug("ibuprofen", db_path=test_db)
        assert result["generic_name"] == "ibuprofen"

    def test_unknown_drug(self, test_db: str) -> None:
        result = normalize_drug("nonexistentdrug123", db_path=test_db)
        assert "error" in result

    def test_empty_input(self, test_db: str) -> None:
        result = normalize_drug("", db_path=test_db)
        assert "error" in result

    def test_missing_db(self) -> None:
        result = normalize_drug("aspirin", db_path="/nonexistent/path.sqlite")
        assert "error" in result

    def test_unicode_input(self, test_db: str) -> None:
        result = normalize_drug("タイレノール", db_path=test_db)
        assert "error" in result


# ---------------------------------------------------------------------------
# decompose_product
# ---------------------------------------------------------------------------

class TestDecomposeProduct:
    def test_nyquil(self, test_db: str) -> None:
        result = decompose_product("NyQuil", db_path=test_db)
        assert "ingredients" in result
        names = {i["name"] for i in result["ingredients"]}
        assert names == {"acetaminophen", "dextromethorphan", "doxylamine"}

    def test_unknown_product(self, test_db: str) -> None:
        result = decompose_product("MadeUpProduct", db_path=test_db)
        assert "error" in result

    def test_empty_input(self, test_db: str) -> None:
        result = decompose_product("", db_path=test_db)
        assert "error" in result

    def test_missing_db(self) -> None:
        result = decompose_product("NyQuil", db_path="/nonexistent/path.sqlite")
        assert "error" in result


# ---------------------------------------------------------------------------
# get_drug_info
# ---------------------------------------------------------------------------

class TestGetDrugInfo:
    def test_valid_rxcui(self, test_db: str) -> None:
        result = get_drug_info("161", db_path=test_db)
        assert result["name"] == "acetaminophen"
        assert result["drug_class"] == "Analgesic"
        assert result["category"] == "OTC"

    def test_unknown_rxcui(self, test_db: str) -> None:
        result = get_drug_info("999999", db_path=test_db)
        assert "error" in result

    def test_empty_rxcui(self, test_db: str) -> None:
        result = get_drug_info("", db_path=test_db)
        assert "error" in result

    def test_missing_db(self) -> None:
        result = get_drug_info("161", db_path="/nonexistent/path.sqlite")
        assert "error" in result


# ---------------------------------------------------------------------------
# check_warnings
# ---------------------------------------------------------------------------

class TestCheckWarnings:
    def test_no_interaction(self, test_db: str) -> None:
        result = check_warnings(["acetaminophen"], db_path=test_db)
        assert result["defer_to_professional"] is False
        assert len(result["flags"]) == 0

    def test_drug_drug_interaction(self, test_db: str) -> None:
        result = check_warnings(["ibuprofen", "warfarin"], db_path=test_db)
        assert len(result["flags"]) > 0
        severities = [f["severity"] for f in result["flags"]]
        assert max(severities) >= 4
        assert result["defer_to_professional"] is True

    def test_drug_condition_contraindication(self, test_db: str) -> None:
        result = check_warnings(
            ["ibuprofen"], conditions=["kidney disease"], db_path=test_db,
        )
        assert len(result["flags"]) > 0
        descs = [f["description"] for f in result["flags"]]
        assert any("renal" in d.lower() or "kidney" in d.lower() for d in descs)

    def test_elderly_warning(self, test_db: str) -> None:
        result = check_warnings(["acetaminophen"], age=70, db_path=test_db)
        descs = [f["description"] for f in result["flags"]]
        assert any("65" in d or "elderly" in d.lower() for d in descs)

    def test_pregnancy_defer(self, test_db: str) -> None:
        result = check_warnings(
            ["acetaminophen"], conditions=["pregnancy"], db_path=test_db,
        )
        assert result["defer_to_professional"] is True

    def test_pediatric_rx_defer(self, test_db: str) -> None:
        result = check_warnings(["atorvastatin"], age=8, db_path=test_db)
        assert result["defer_to_professional"] is True

    def test_controlled_substance_defer(self, test_db: str) -> None:
        result = check_warnings(["oxycodone"], db_path=test_db)
        assert result["defer_to_professional"] is True

    def test_polypharmacy_defer(self, test_db: str) -> None:
        drugs = ["acetaminophen", "ibuprofen", "warfarin", "atorvastatin", "oxycodone"]
        result = check_warnings(drugs, db_path=test_db)
        assert result["defer_to_professional"] is True
        descs = [f["description"] for f in result["flags"]]
        assert any("polypharmacy" in d.lower() for d in descs)

    def test_unknown_drug_defer(self, test_db: str) -> None:
        result = check_warnings(["unknowndrug_xyz"], db_path=test_db)
        assert result["defer_to_professional"] is True

    def test_empty_drug_list(self, test_db: str) -> None:
        result = check_warnings([], db_path=test_db)
        assert "explanation" in result

    def test_missing_db(self) -> None:
        result = check_warnings(["aspirin"], db_path="/nonexistent/path.sqlite")
        assert result["defer_to_professional"] is True

    def test_response_validates_as_aegis_response(self, test_db: str) -> None:
        raw = check_warnings(["ibuprofen", "warfarin"], db_path=test_db)
        resp = AegisResponse(**raw)
        assert resp.confidence > 0


# ---------------------------------------------------------------------------
# lookup_term
# ---------------------------------------------------------------------------

class TestLookupTerm:
    def test_exact_match(self, test_db: str) -> None:
        result = lookup_term("hypertension", db_path=test_db)
        assert "blood pressure" in result["plain_language_definition"].lower()

    def test_case_insensitive(self, test_db: str) -> None:
        result = lookup_term("HYPERTENSION", db_path=test_db)
        assert result["term"] == "hypertension"

    def test_fuzzy_match(self, test_db: str) -> None:
        result = lookup_term("NSAID", db_path=test_db)
        assert "pain" in result["plain_language_definition"].lower()

    def test_unknown_term(self, test_db: str) -> None:
        result = lookup_term("xyzabc_unknown", db_path=test_db)
        assert "error" in result

    def test_empty_input(self, test_db: str) -> None:
        result = lookup_term("", db_path=test_db)
        assert "error" in result

    def test_missing_db(self) -> None:
        result = lookup_term("hypertension", db_path="/nonexistent/path.sqlite")
        assert "error" in result


# ---------------------------------------------------------------------------
# get_guideline
# ---------------------------------------------------------------------------

class TestGetGuideline:
    def test_general_screening(self, test_db: str) -> None:
        result = get_guideline(age=30, sex="male", db_path=test_db)
        titles = [r["title"] for r in result["recommendations"]]
        assert "Blood Pressure Screening" in titles

    def test_female_screening(self, test_db: str) -> None:
        result = get_guideline(age=55, sex="female", db_path=test_db)
        titles = [r["title"] for r in result["recommendations"]]
        assert "Breast Cancer Screening" in titles

    def test_male_excludes_female_only(self, test_db: str) -> None:
        result = get_guideline(age=55, sex="male", db_path=test_db)
        titles = [r["title"] for r in result["recommendations"]]
        assert "Breast Cancer Screening" not in titles

    def test_sex_shorthand(self, test_db: str) -> None:
        result = get_guideline(age=30, sex="f", db_path=test_db)
        assert "recommendations" in result

    def test_invalid_sex(self, test_db: str) -> None:
        result = get_guideline(age=30, sex="other", db_path=test_db)
        assert "error" in result

    def test_missing_db(self) -> None:
        result = get_guideline(age=30, sex="male", db_path="/nonexistent/path.sqlite")
        assert "error" in result


# ---------------------------------------------------------------------------
# ToolDispatcher
# ---------------------------------------------------------------------------

class TestToolDispatcher:
    def test_dispatch_normalize(self, test_db: str) -> None:
        dispatcher = ToolDispatcher(db_path=test_db)
        raw = dispatcher.dispatch({"name": "normalize_drug", "arguments": {"name": "Tylenol"}})
        result = json.loads(raw)
        assert result["generic_name"] == "acetaminophen"

    def test_dispatch_unknown_tool(self, test_db: str) -> None:
        dispatcher = ToolDispatcher(db_path=test_db)
        raw = dispatcher.dispatch({"name": "nonexistent_tool", "arguments": {}})
        result = json.loads(raw)
        assert "error" in result
        assert "Unknown tool" in result["error"]

    def test_dispatch_bad_args(self, test_db: str) -> None:
        dispatcher = ToolDispatcher(db_path=test_db)
        raw = dispatcher.dispatch({"name": "normalize_drug", "arguments": {"wrong_param": 42}})
        result = json.loads(raw)
        assert "error" in result

    def test_dispatch_check_warnings(self, test_db: str) -> None:
        dispatcher = ToolDispatcher(db_path=test_db)
        raw = dispatcher.dispatch({
            "name": "check_warnings",
            "arguments": {"drug_list": ["ibuprofen", "warfarin"]},
        })
        result = json.loads(raw)
        assert "flags" in result

    def test_dispatch_all_tools(self, test_db: str) -> None:
        """Verify every registered tool can be dispatched without crashing."""
        dispatcher = ToolDispatcher(db_path=test_db)
        calls = [
            {"name": "normalize_drug", "arguments": {"name": "Advil"}},
            {"name": "decompose_product", "arguments": {"product_name": "NyQuil"}},
            {"name": "get_drug_info", "arguments": {"rxcui": "161"}},
            {"name": "check_warnings", "arguments": {"drug_list": ["acetaminophen"]}},
            {"name": "lookup_term", "arguments": {"term": "NSAID"}},
            {"name": "get_guideline", "arguments": {"age": 40, "sex": "male"}},
        ]
        for call in calls:
            raw = dispatcher.dispatch(call)
            result = json.loads(raw)
            assert isinstance(result, dict), f"Failed for {call['name']}"
