"""ACIP Adult Immunization Schedule source.

Downloads the CDC CDSi Supporting Data ZIP (v4.64, Nov 2025) and inserts one
row per adult-eligible vaccine series into the `guidelines` table with
`grade='I'`. This extends our HealthPartner screening recommendations to
cover vaccinations alongside USPSTF preventive-care guidelines.

The CDSi (Clinical Decision Support for Immunization) release is the
canonical machine-readable form of the ACIP schedule. It ships as 31
per-antigen XML files plus a CVX-to-antigen XLSX map. Each XML contains
`<series>` blocks (Standard / Risk-based / Catch-up) whose first
`<seriesDose>` defines the recommendation's minimum and (when applicable)
maximum age and sex.

Scope: adult-only (minimum age ≥ 18 years). Pediatric-only series (Rotavirus,
Hib under 5 years, etc.) are filtered out. Risk-based series whose
indications are condition-driven are included; the observationTitle
values surface in the description so the get_guideline condition-match
path picks them up.

License: US CDC public domain (17 USC §105).
Attribution: "CDC ACIP CDSi Supporting Data v4.64 (Nov 2025)".
"""
from __future__ import annotations

import logging
import os
import re
import sqlite3
import tempfile
import urllib.request
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET

log = logging.getLogger(__name__)

CDSI_ZIP_URL = "https://www.cdc.gov/iis/downloads/supporting-data-4.64-508.zip"
CITATION     = "CDC ACIP CDSi Supporting Data v4.64 (Nov 2025)"
CLINICAL_URL = "https://www.cdc.gov/iis/cdsi/index.html"
CACHE_PATH   = Path(tempfile.gettempdir()) / "acip_v4.64.zip"
ADULT_MIN_AGE = 18  # skip series whose first-dose minimum age is under this

# Match age strings like "50 years", "18 years - 4 days", "6 weeks",
# "6 months". Returns the age in integer years (weeks/months → 0).
_AGE_YEARS_RE  = re.compile(r"(\d+)\s*years?", re.I)
_AGE_MONTHS_RE = re.compile(r"(\d+)\s*months?", re.I)
_AGE_WEEKS_RE  = re.compile(r"(\d+)\s*weeks?", re.I)


def _parse_age_years(text: str | None) -> int | None:
    """Convert a CDSi age string to integer years. Returns None if unparseable."""
    if not text:
        return None
    s = text.strip()
    m = _AGE_YEARS_RE.search(s)
    if m:
        return int(m.group(1))
    # Sub-year ages are meaningful for pediatric series only; treat as 0
    # so the adult-scope filter rejects them cleanly.
    if _AGE_MONTHS_RE.search(s) or _AGE_WEEKS_RE.search(s):
        return 0
    return None


def _normalize_sex(required_gender: str | None) -> str:
    """Map CDSi requiredGender (empty|'M'|'F') to our 'all'|'male'|'female'."""
    g = (required_gender or "").strip().upper()
    if g in ("M", "MALE"):
        return "male"
    if g in ("F", "FEMALE"):
        return "female"
    return "all"


def _collect_indications(series_el: ET.Element) -> list[str]:
    """Return a list of condition strings that cause this series to apply.

    CDSi risk-based schema (real):
      <indication>
        <observationCode><text>Patient seeks protection</text><code>001</code></observationCode>
        <description>Administer to persons seeking protection.</description>
        ...
      </indication>
    """
    out: list[str] = []
    for ind in series_el.iterfind("indication"):
        text = ind.findtext("observationCode/text")
        if text and text.strip():
            out.append(text.strip())
        desc = ind.findtext("description")
        if desc and desc.strip() and desc.strip() not in out:
            out.append(desc.strip())
    return out


def _download_zip() -> Path:
    """Download the CDSi ZIP to CACHE_PATH if not already present."""
    if CACHE_PATH.exists() and CACHE_PATH.stat().st_size > 100_000:
        log.info("ACIP: using cached CDSi ZIP at %s", CACHE_PATH)
        return CACHE_PATH
    log.info("ACIP: downloading CDSi ZIP from %s", CDSI_ZIP_URL)
    CACHE_PATH.parent.mkdir(parents=True, exist_ok=True)
    req = urllib.request.Request(
        CDSI_ZIP_URL,
        headers={"User-Agent": "aegis-health/0.1 (mailto:hello@researchcommons.ai)"},
    )
    with urllib.request.urlopen(req, timeout=120) as resp, open(CACHE_PATH, "wb") as f:
        f.write(resp.read())
    log.info("ACIP: downloaded %d bytes", CACHE_PATH.stat().st_size)
    return CACHE_PATH


def _parse_xml(xml_bytes: bytes) -> list[dict]:
    """Return a list of guideline dicts extracted from one antigen XML."""
    try:
        root = ET.fromstring(xml_bytes)
    except ET.ParseError as exc:
        log.warning("ACIP: XML parse error: %s", exc)
        return []

    rows: list[dict] = []
    for s in root.iterfind("series"):
        name    = (s.findtext("seriesName") or "").strip()
        disease = (s.findtext("targetDisease") or "").strip()
        stype   = (s.findtext("seriesType") or "").strip()
        gender  = _normalize_sex(s.findtext("requiredGender"))
        if not name:
            continue

        # Pull the first <seriesDose>'s age band
        first_dose = s.find("seriesDose")
        if first_dose is None:
            continue
        age_el = first_dose.find("age")
        min_age = _parse_age_years(age_el.findtext("minAge")) if age_el is not None else None
        max_age = _parse_age_years(age_el.findtext("maxAge")) if age_el is not None else None

        # Also try the preferableVaccine beginAge if minAge was empty
        if min_age is None:
            pref = first_dose.find("preferableVaccine")
            if pref is not None:
                min_age = _parse_age_years(pref.findtext("beginAge"))

        # Adult-schedule scope: minimum age must be ≥ 18.
        # Empty minAge is treated as an unknown scope and dropped.
        if min_age is None or min_age < ADULT_MIN_AGE:
            continue

        indications = _collect_indications(s)
        preferred_vaccine = ""
        pref_el = first_dose.find("preferableVaccine")
        if pref_el is not None:
            preferred_vaccine = (pref_el.findtext("vaccineType") or "").strip()

        parts: list[str] = [f"{disease or name} vaccination."]
        if stype:
            parts.append(f"Schedule type: {stype.lower()}.")
        parts.append(f"Recommended starting age: {min_age} years")
        if max_age is not None and max_age > 0:
            parts[-1] += f", through {max_age} years."
        else:
            parts[-1] += "."
        if preferred_vaccine:
            parts.append(f"Preferred vaccine: {preferred_vaccine}.")
        if indications:
            # Truncate very long indication lists for readability.
            ind_str = "; ".join(indications[:8])
            parts.append(f"Indicated for: {ind_str}.")

        description = " ".join(parts).strip()

        # recommendation_id must be unique across the table. Combine
        # (disease, full series name, min_age) — using seriesType alone
        # collides for diseases with multiple Standard series.
        rec_id = (
            f"ACIP-{_slug(disease or name)}"
            f"-{_slug(name)}-{min_age}"
        )

        # Risk-based / Catch-up / Evaluation-Only / series whose name or
        # indication text flags them as condition-indicated. Only pure
        # "Standard" series with no risk language surface in the
        # demographic-only match; the rest are condition-gated.
        stype_lower = stype.lower()
        name_lower  = name.lower()
        is_risk_only = (
            stype_lower != "standard"
            or bool(indications)
            or "risk" in name_lower
            or "catch-up" in name_lower
            or "catch up" in name_lower
        )

        rows.append({
            "recommendation_id":  rec_id,
            "title":              name,
            "grade":              "I",
            "population_age_min": min_age,
            "population_age_max": max_age if (max_age and max_age > 0) else None,
            "population_sex":     gender,
            "description":        description,
            "clinical_url":       CLINICAL_URL,
            "source":             CITATION,
            "risk_only":          1 if is_risk_only else 0,
        })
    return rows


def _slug(s: str) -> str:
    return re.sub(r"[^A-Za-z0-9]+", "_", s).strip("_").upper() or "X"


def _ensure_risk_only_column(conn: sqlite3.Connection) -> bool:
    """Add guidelines.risk_only if migrating an existing KB. Returns True
    if the column is present after this call.
    """
    cols = {r[1] for r in conn.execute("PRAGMA table_info(guidelines)")}
    if "risk_only" in cols:
        return True
    conn.execute(
        "ALTER TABLE guidelines ADD COLUMN risk_only INTEGER NOT NULL DEFAULT 0"
    )
    log.info("ACIP: added risk_only column to guidelines")
    return True


def build(db_path: str) -> int:
    """Download CDSi, parse every antigen XML, and insert adult-scope rows."""
    log.info("ACIP: starting build")
    zip_path = _download_zip()

    rows: list[dict] = []
    with zipfile.ZipFile(zip_path) as z:
        xml_names = [n for n in z.namelist() if n.endswith(".xml")]
        log.info("ACIP: parsing %d antigen XMLs", len(xml_names))
        for name in xml_names:
            parsed = _parse_xml(z.read(name))
            rows.extend(parsed)

    log.info("ACIP: %d adult-scope series rows extracted", len(rows))

    conn = sqlite3.connect(db_path)
    inserted = 0
    try:
        _ensure_risk_only_column(conn)
        for r in rows:
            # Idempotent: INSERT OR IGNORE on recommendation_id.
            cur = conn.execute(
                "INSERT OR IGNORE INTO guidelines "
                "(recommendation_id, title, grade, "
                " population_age_min, population_age_max, population_sex, "
                " description, clinical_url, source, risk_only) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (r["recommendation_id"], r["title"], r["grade"],
                 r["population_age_min"], r["population_age_max"], r["population_sex"],
                 r["description"], r["clinical_url"], r["source"], r["risk_only"]),
            )
            inserted += cur.rowcount
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()

    log.info("ACIP: inserted %d new rows into guidelines (grade='I')", inserted)
    return inserted
