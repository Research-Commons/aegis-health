"""Unit tests for kb.sources.lactmed.

Covers the HTML parser, severity heuristic, and rxnorm fuzzy-matcher in
isolation (no network). A sample cached monograph at /tmp is used only
if present; otherwise the relevant test is skipped.
"""
from __future__ import annotations

import os
import sqlite3
from pathlib import Path
from unittest.mock import patch

import pytest

from kb.sources import lactmed


SAMPLE_HTML_ACET = Path("c:/tmp/acetaminophen.html")
SAMPLE_HTML_DEPE = Path("c:/tmp/lactmed_nbk.html")


# ---------------------------------------------------------------------------
# Summary extractor — uses cached real monographs
# ---------------------------------------------------------------------------

@pytest.mark.skipif(not SAMPLE_HTML_ACET.exists(), reason="no cached sample HTML")
def test_extract_summary_acetaminophen():
    html = SAMPLE_HTML_ACET.read_text(encoding="utf-8", errors="replace")
    summary = lactmed._extract_summary(html)
    assert summary is not None
    assert "Acetaminophen" in summary
    assert "nursing mothers" in summary.lower() or "breastfed" in summary.lower()
    # Heading itself must have been stripped
    assert not summary.lower().startswith("summary of use during lactation")
    # Citation refs like "[ 1 ]" must have been cleaned out
    assert "[ 1 ]" not in summary


@pytest.mark.skipif(not SAMPLE_HTML_DEPE.exists(), reason="no cached sample HTML")
def test_extract_summary_depemokimab():
    """The 'Lactat' (truncated) variant of the section id must also match."""
    html = SAMPLE_HTML_DEPE.read_text(encoding="utf-8", errors="replace")
    summary = lactmed._extract_summary(html)
    assert summary is not None
    assert len(summary) > 50


def test_extract_summary_returns_none_on_empty():
    assert lactmed._extract_summary("") is None
    assert lactmed._extract_summary("<html><body>no section here</body></html>") is None


# ---------------------------------------------------------------------------
# Severity heuristic
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("text,expected_sev", [
    ("This drug is contraindicated during breastfeeding.", 5),
    ("Breastfeeding is not recommended during therapy.", 5),
    ("Nursing should be discontinued during treatment.", 5),
    ("An alternate drug may be preferred for breastfeeding mothers.", 4),
    ("Avoid use in breastfeeding women.", 4),
    ("No special precautions are required for breastfeeding.", 1),
    ("The drug is not absorbed orally by the infant.", 1),
    ("Compatible with breastfeeding at standard doses.", 1),
    ("Sertraline is one of the preferred SSRIs during breastfeeding.", 2),
    ("Acceptable during breastfeeding.", 2),
    ("Probably compatible with breastfeeding.", 2),
    ("No information on milk transfer; use with caution.", 3),
    ("", 3),
])
def test_severity_heuristic(text, expected_sev):
    assert lactmed._infer_severity(text) == expected_sev


def test_severity_takes_max_when_multiple_match():
    """When both a safe and a dangerous pattern match, the dangerous one wins."""
    text = (
        "This drug is sometimes described as a preferred antibiotic during "
        "breastfeeding, but it is contraindicated in infants with G6PD deficiency."
    )
    assert lactmed._infer_severity(text) == 5


# ---------------------------------------------------------------------------
# Fuzzy name → rxcui matcher
# ---------------------------------------------------------------------------

def _index() -> dict:
    """Build a small rxnorm_lookup-style index for matcher tests."""
    return {
        "acetaminophen":            ("161",   "Acetaminophen"),
        "sertraline":               ("36437", "Sertraline"),
        "hydroxychloroquine":       ("5521",  "Hydroxychloroquine"),
        "trimethoprim / sulfamethoxazole": ("10831", "Trimethoprim / Sulfamethoxazole"),
    }


def test_match_exact():
    idx = _index()
    assert lactmed._match_rxnorm("Acetaminophen", idx) == ("161", "Acetaminophen")


def test_match_case_insensitive_with_extra_suffix():
    idx = _index()
    # LactMed sometimes titles monographs with suffix, e.g. "Hydroxychloroquine Sulfate"
    assert lactmed._match_rxnorm("Hydroxychloroquine Sulfate", idx) == ("5521", "Hydroxychloroquine")


def test_match_first_token_fallback():
    idx = _index()
    assert lactmed._match_rxnorm("Sertraline Hydrochloride", idx) == ("36437", "Sertraline")


def test_match_combo_with_slash():
    idx = _index()
    assert lactmed._match_rxnorm(
        "Trimethoprim / Sulfamethoxazole", idx,
    ) == ("10831", "Trimethoprim / Sulfamethoxazole")


def test_match_no_hit_returns_none():
    idx = _index()
    assert lactmed._match_rxnorm("Unobtainium", idx) is None
    assert lactmed._match_rxnorm("", idx) is None


# ---------------------------------------------------------------------------
# _insert_curated — idempotency
# ---------------------------------------------------------------------------

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
    # Seed two drugs so at least two curated entries match.
    conn.executemany(
        "INSERT INTO rxnorm_lookup VALUES (?, ?, ?, ?, ?, ?)",
        [
            ("161",   "Tylenol", "Acetaminophen", "IN", "OTC", "test"),
            ("36437", "Zoloft",  "Sertraline",    "IN", "Rx",  "test"),
        ],
    )
    conn.commit()
    conn.close()


def test_insert_curated_is_idempotent(tmp_path: Path):
    db = tmp_path / "test.sqlite"
    _seed_minimal_kb(str(db))
    conn = sqlite3.connect(str(db))
    try:
        n1 = lactmed._insert_curated(conn)
        conn.commit()
        n2 = lactmed._insert_curated(conn)
        conn.commit()
        total = conn.execute(
            "SELECT COUNT(*) FROM warnings WHERE warning_type = 'lactation'"
        ).fetchone()[0]
    finally:
        conn.close()
    assert n1 >= 2      # at least the two seeded drugs match
    assert n2 == 0      # second run inserts nothing
    assert total == n1  # no duplicates


# ---------------------------------------------------------------------------
# _insert_auto — end-to-end with mocked HTTP + cached sample monograph
# ---------------------------------------------------------------------------

@pytest.mark.skipif(not SAMPLE_HTML_ACET.exists(), reason="no cached sample HTML")
def test_insert_auto_uses_fetched_monograph_when_drug_not_curated(tmp_path: Path):
    db = tmp_path / "test.sqlite"
    conn = sqlite3.connect(str(db))
    conn.executescript(
        """
        CREATE TABLE rxnorm_lookup (
            rxcui TEXT PRIMARY KEY, generic_name TEXT
        );
        CREATE TABLE warnings (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            drug_rxcui TEXT, drug_name TEXT, warning_type TEXT,
            population TEXT, description TEXT, severity INTEGER, source TEXT
        );
        """
    )
    # Seed a drug whose name matches the cached monograph but is NOT in
    # CURATED_LACTMED so auto-fetch is the only possible source.
    conn.execute(
        "INSERT INTO rxnorm_lookup VALUES (?, ?)",
        ("99999_test", "Depemokimab"),
    )
    conn.commit()

    html = SAMPLE_HTML_DEPE.read_text(encoding="utf-8", errors="replace") \
        if SAMPLE_HTML_DEPE.exists() else ""

    class FakeResp:
        def __init__(self, text="", json_data=None):
            self.text = text
            self._json = json_data
            self.status_code = 200
        def raise_for_status(self): pass
        def json(self): return self._json

    def fake_http_get(url, params=None):
        if "esearch" in url:
            return FakeResp(json_data={"esearchresult": {
                "count": "1", "idlist": ["uid_dep"],
            }})
        if "esummary" in url:
            return FakeResp(json_data={"result": {
                "uid_dep": {"chapteraccessionid": "NBK_DEP",
                            "title": "Depemokimab"},
            }})
        # Any other URL = the bookshelf HTML fetch
        return FakeResp(text=html)

    with patch.object(lactmed, "_http_get", side_effect=fake_http_get), \
         patch.object(lactmed, "NCBI_RATE_SLEEP", 0):
        inserted = lactmed._insert_auto(conn)
        conn.commit()

    row = conn.execute(
        "SELECT severity, description, source FROM warnings "
        "WHERE drug_rxcui = ?", ("99999_test",),
    ).fetchone()
    conn.close()

    assert inserted == 1
    assert row is not None
    severity, description, source = row
    assert 1 <= severity <= 5
    assert "NBK_DEP" in description
    assert source == lactmed.CITATION
