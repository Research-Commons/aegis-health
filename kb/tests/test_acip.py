"""Unit tests for kb.sources.acip.

Uses hand-constructed antigen XML so tests don't depend on the live ZIP.
"""
from __future__ import annotations

import sqlite3
from pathlib import Path

import pytest

from kb.sources import acip


# ---------------------------------------------------------------------------
# Age parser + sex normalizer
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("text,expected", [
    ("50 years", 50),
    ("18 years - 4 days", 18),
    ("65 years", 65),
    ("6 weeks", 0),     # pediatric → 0 so adult-scope filter drops it
    ("6 months", 0),
    ("", None),
    (None, None),
    ("garbage", None),
])
def test_parse_age_years(text, expected):
    assert acip._parse_age_years(text) == expected


@pytest.mark.parametrize("raw,expected", [
    ("M", "male"),
    ("F", "female"),
    ("male", "male"),
    ("female", "female"),
    ("", "all"),
    (None, "all"),
])
def test_normalize_sex(raw, expected):
    assert acip._normalize_sex(raw) == expected


# ---------------------------------------------------------------------------
# XML parsing — fabricated samples covering adult, pediatric, sex-specific
# ---------------------------------------------------------------------------

ZOSTER_XML = b"""
<antigenSupportingData>
  <series>
    <seriesName>Zoster 2-dose series</seriesName>
    <targetDisease>Zoster</targetDisease>
    <seriesType>Standard</seriesType>
    <requiredGender/>
    <indication/>
    <seriesDose>
      <age>
        <minAge>50 years</minAge>
        <maxAge/>
      </age>
      <preferableVaccine>
        <vaccineType>Zoster recombinant</vaccineType>
        <beginAge>18 years</beginAge>
      </preferableVaccine>
    </seriesDose>
  </series>
</antigenSupportingData>
""".strip()

HPV_RISK_XML = b"""
<antigenSupportingData>
  <series>
    <seriesName>HPV catch-up, risk-based</seriesName>
    <targetDisease>HPV</targetDisease>
    <seriesType>Risk</seriesType>
    <requiredGender></requiredGender>
    <indication>
      <observationCode>
        <text>Immunocompromising condition</text>
        <code>610</code>
      </observationCode>
      <description>Administer to persons with immunocompromising conditions such as HIV infection.</description>
    </indication>
    <seriesDose>
      <age>
        <minAge>27 years</minAge>
        <maxAge>45 years</maxAge>
      </age>
      <preferableVaccine>
        <vaccineType>HPV9</vaccineType>
      </preferableVaccine>
    </seriesDose>
  </series>
</antigenSupportingData>
""".strip()

ROTAVIRUS_PEDIATRIC_XML = b"""
<antigenSupportingData>
  <series>
    <seriesName>Rotavirus 3-dose series</seriesName>
    <targetDisease>Rotavirus</targetDisease>
    <seriesType>Standard</seriesType>
    <seriesDose>
      <age><minAge>6 weeks</minAge><maxAge>8 months</maxAge></age>
      <preferableVaccine><vaccineType>RV5</vaccineType></preferableVaccine>
    </seriesDose>
  </series>
</antigenSupportingData>
""".strip()


def test_parse_adult_zoster_series():
    rows = acip._parse_xml(ZOSTER_XML)
    assert len(rows) == 1
    r = rows[0]
    assert r["title"] == "Zoster 2-dose series"
    assert r["grade"] == "I"
    assert r["population_age_min"] == 50
    assert r["population_age_max"] is None
    assert r["population_sex"] == "all"
    assert r["source"] == acip.CITATION
    assert r["clinical_url"] == acip.CLINICAL_URL
    assert "Zoster" in r["description"]
    assert "Zoster recombinant" in r["description"]
    assert "recommendation_id" in r and r["recommendation_id"].startswith("ACIP-ZOSTER-")
    assert r["recommendation_id"].endswith("-50")


def test_parse_risk_based_series_surfaces_indications():
    rows = acip._parse_xml(HPV_RISK_XML)
    assert len(rows) == 1
    r = rows[0]
    assert r["population_age_min"] == 27
    assert r["population_age_max"] == 45
    assert "Immunocompromising" in r["description"]
    assert "HIV infection" in r["description"]


def test_parse_rejects_pediatric_only_series():
    rows = acip._parse_xml(ROTAVIRUS_PEDIATRIC_XML)
    assert rows == []


def test_parse_handles_malformed_xml():
    assert acip._parse_xml(b"<not valid xml") == []


# ---------------------------------------------------------------------------
# build() idempotency against a minimal in-memory DB, mocking download
# ---------------------------------------------------------------------------

def _mini_guidelines_db(db_path: str) -> None:
    conn = sqlite3.connect(db_path)
    conn.executescript(
        """
        CREATE TABLE guidelines (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            recommendation_id TEXT UNIQUE,
            title TEXT NOT NULL,
            grade TEXT NOT NULL,
            population_age_min INTEGER,
            population_age_max INTEGER,
            population_sex TEXT,
            description TEXT NOT NULL,
            clinical_url TEXT,
            source TEXT NOT NULL DEFAULT 'uspstf'
        );
        """
    )
    conn.commit()
    conn.close()


def test_build_is_idempotent(tmp_path: Path, monkeypatch):
    db = tmp_path / "test.sqlite"
    _mini_guidelines_db(str(db))

    # Build a tiny fake ZIP with two antigens.
    import io, zipfile
    fake_zip = tmp_path / "fake.zip"
    with zipfile.ZipFile(fake_zip, "w") as z:
        z.writestr("Version X/XML/AntigenSupportingData- Zoster-508.xml", ZOSTER_XML)
        z.writestr("Version X/XML/AntigenSupportingData- HPV-508.xml", HPV_RISK_XML)

    monkeypatch.setattr(acip, "_download_zip", lambda: fake_zip)

    n1 = acip.build(str(db))
    n2 = acip.build(str(db))

    conn = sqlite3.connect(str(db))
    try:
        total = conn.execute(
            "SELECT COUNT(*) FROM guidelines WHERE grade = 'I'"
        ).fetchone()[0]
    finally:
        conn.close()

    assert n1 == 2
    assert n2 == 0       # second build inserts nothing (rec_id UNIQUE)
    assert total == 2    # no duplicates


# ---------------------------------------------------------------------------
# get_guideline integration: grade='I' rows must now be returned
# ---------------------------------------------------------------------------

def test_get_guideline_returns_grade_I(tmp_path: Path):
    from tools.tools.get_guideline import get_guideline

    db = tmp_path / "test.sqlite"
    _mini_guidelines_db(str(db))

    conn = sqlite3.connect(str(db))
    conn.execute(
        "INSERT INTO guidelines "
        "(recommendation_id, title, grade, population_age_min, population_age_max, "
        " population_sex, description, source) "
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        ("ACIP-TEST", "Shingrix 2-dose series", "I", 50, None, "all",
         "Zoster vaccination from age 50.", acip.CITATION),
    )
    conn.commit()
    conn.close()

    result = get_guideline(age=55, sex="male", db_path=str(db))
    titles = [r["title"] for r in result["recommendations"]]
    assert "Shingrix 2-dose series" in titles
    for r in result["recommendations"]:
        if r["title"] == "Shingrix 2-dose series":
            assert r["grade"] == "I"
            assert "50+" in r["population"]
