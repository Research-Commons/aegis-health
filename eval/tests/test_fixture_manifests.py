"""CI invariant for the Phase 1 lab-report fixture corpus.

Asserts:
  - Every vendor directory under eval/fixtures/lab_reports/ has:
      * exactly one .pdf file
      * exactly one *-evaluated.json file
      * a MANIFEST.md containing all 5 required field labels
      * a SHA256 in MANIFEST.md that matches the actual PDF bytes
  - Every *-evaluated.json validates against .planning/specs/PreparsedReport.schema.json
  - Every row with status="unknown" has ref_source="none" (or null range)

Skips gracefully if the fixture corpus is not present (the corpus is committed,
but in case of partial checkouts).
"""
from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).parent.parent.parent
FIXTURE_ROOT = REPO_ROOT / "eval" / "fixtures" / "lab_reports"
SCHEMA_PATH = REPO_ROOT / ".planning" / "specs" / "PreparsedReport.schema.json"

EXPECTED_VENDORS = ["labcorp", "quest", "mayo", "hospital_lis", "urgent_care"]
REQUIRED_MANIFEST_FIELDS = [
    "Source URL:",
    "Retrieval date:",
    "License observation:",
    "Redistribution rationale:",
    "SHA256:",
]
# Matches `SHA256: <hex>` and also `**SHA256:** <hex>` (markdown-bolded label
# is the convention used in the MANIFEST.md template).
SHA256_RE = re.compile(r"SHA256:\**\s*([0-9a-fA-F]{64})")


def _require_corpus() -> None:
    if not FIXTURE_ROOT.exists():
        pytest.skip(f"Fixture corpus not present at {FIXTURE_ROOT}")
    if not SCHEMA_PATH.exists():
        pytest.skip(f"PreparsedReport schema not present at {SCHEMA_PATH}")


@pytest.mark.parametrize("vendor", EXPECTED_VENDORS)
def test_vendor_directory_exists(vendor):
    _require_corpus()
    d = FIXTURE_ROOT / vendor
    assert d.is_dir(), f"missing vendor directory: {d}"


@pytest.mark.parametrize("vendor", EXPECTED_VENDORS)
def test_vendor_has_one_pdf(vendor):
    _require_corpus()
    pdfs = list((FIXTURE_ROOT / vendor).glob("*.pdf"))
    assert len(pdfs) == 1, f"{vendor}: expected exactly 1 PDF, found {len(pdfs)}: {pdfs}"


@pytest.mark.parametrize("vendor", EXPECTED_VENDORS)
def test_vendor_has_one_evaluated_json(vendor):
    _require_corpus()
    jsons = list((FIXTURE_ROOT / vendor).glob("*-evaluated.json"))
    assert len(jsons) == 1, f"{vendor}: expected exactly 1 *-evaluated.json, found {len(jsons)}"


@pytest.mark.parametrize("vendor", EXPECTED_VENDORS)
def test_manifest_has_all_required_fields(vendor):
    _require_corpus()
    m = FIXTURE_ROOT / vendor / "MANIFEST.md"
    assert m.exists(), f"{vendor}: missing MANIFEST.md"
    txt = m.read_text(encoding="utf-8")
    missing = [f for f in REQUIRED_MANIFEST_FIELDS if f not in txt]
    assert not missing, f"{vendor} MANIFEST missing fields: {missing}"


@pytest.mark.parametrize("vendor", EXPECTED_VENDORS)
def test_manifest_sha256_matches_pdf_bytes(vendor):
    _require_corpus()
    m = FIXTURE_ROOT / vendor / "MANIFEST.md"
    txt = m.read_text(encoding="utf-8")
    match = SHA256_RE.search(txt)
    assert match, f"{vendor} MANIFEST missing 64-hex SHA256"
    declared = match.group(1).lower()

    pdfs = list((FIXTURE_ROOT / vendor).glob("*.pdf"))
    assert len(pdfs) == 1, f"{vendor}: expected exactly 1 PDF"
    actual = hashlib.sha256(pdfs[0].read_bytes()).hexdigest().lower()

    assert declared == actual, (
        f"{vendor}: SHA256 mismatch.\n"
        f"  declared (MANIFEST): {declared}\n"
        f"  actual   ({pdfs[0].name}): {actual}"
    )


@pytest.mark.parametrize("vendor", EXPECTED_VENDORS)
def test_evaluated_json_validates_against_schema(vendor):
    _require_corpus()
    import jsonschema

    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    validator = jsonschema.Draft202012Validator(schema)

    jsons = list((FIXTURE_ROOT / vendor).glob("*-evaluated.json"))
    assert len(jsons) == 1, f"{vendor}: expected 1 *-evaluated.json"
    data = json.loads(jsons[0].read_text(encoding="utf-8"))

    errors = list(validator.iter_errors(data))
    assert not errors, (
        f"{vendor}: ground-truth JSON {jsons[0].name} fails schema validation:\n"
        + "\n".join(f"  - {e.message}" for e in errors)
    )


@pytest.mark.parametrize("vendor", EXPECTED_VENDORS)
def test_unknown_status_implies_no_range(vendor):
    """Cross-check: rows with status='unknown' must have ref_source='none' or null range."""
    _require_corpus()
    jsons = list((FIXTURE_ROOT / vendor).glob("*-evaluated.json"))
    data = json.loads(jsons[0].read_text(encoding="utf-8"))
    for i, row in enumerate(data["rows"]):
        if row["status"] == "unknown":
            assert row["ref_source"] == "none" or (row["ref_low"] is None and row["ref_high"] is None), (
                f"{vendor} row[{i}] ({row['canonical_name']}): status='unknown' but has a printed range"
            )
