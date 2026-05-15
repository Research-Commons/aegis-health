"""CI invariant: the Acme Diagnostics synthetic PDF must NOT page-1-match
any of the 7 named lab-report vendor fingerprints (Phase 4.1 D-14).

Spawned by Plan 04.1-5-01 / Wave 5. The Acme PDF at
`android/app/src/androidTest/assets/lab_reports/generic/acme_diagnostics_cbc.pdf`
is the empirical proof that the slot-7 GenericExtractor catch-all activates
when no named extractor fingerprints. If a future edit to
`tools/parsers/synthesize_fixture.py::generate_acme` inadvertently introduces
a named-vendor substring on page 1, this test fails with a clear assertion
message identifying the offending substring.

Two test methods:

  - test_acme_diagnostics_fingerprint_misses_all_named_vendors:
    Extract page-1 text via pdfminer.six, lowercase, assert NONE of the 15
    named-vendor substrings appear.

  - test_acme_diagnostics_page1_is_text_layered:
    Asserts pdfminer extraction returned non-empty text (PDF is text-layered,
    not image-only — image-only PDFs would silently route to the deferral
    path INTERPRET-03 instead of generic-fallback, defeating the test).

The substring list mirrors:
  - 04.1-RESEARCH.md "Acme Diagnostics anti-fingerprint checklist"
  - 04.1-2-01-NOTES.md page-1 fingerprint substrings for Tata1mg + DrLal
  - VendorRegistry.kt fingerprintMatches() bodies for the 7 named extractors

If a new named-vendor extractor is added in a future phase, append its
page-1 fingerprint substrings to NAMED_VENDOR_SUBSTRINGS so this guard stays
exhaustive.
"""
from __future__ import annotations

from pathlib import Path

import pytest
from pdfminer.high_level import extract_text

# Path resolved relative to this file. eval/tests/<this>.py -> repo root is
# parents[2].
PDF_PATH = (
    Path(__file__).resolve().parents[2]
    / "android"
    / "app"
    / "src"
    / "androidTest"
    / "assets"
    / "lab_reports"
    / "generic"
    / "acme_diagnostics_cbc.pdf"
)

# 15 named-vendor fingerprint substrings (lower-cased), one per page-1
# anchor used by the 7 named VendorExtractor objects in
# android/.../reportreader/VendorRegistry.kt. Source: 04.1-RESEARCH.md
# anti-fingerprint checklist + 04.1-2-01-NOTES.md page-1 inspection records.
#
# Order matches the RESEARCH.md checklist (kept stable so future audits can
# cross-reference at a glance):
#   - LabCorp         : "labcorp", "lipid panel"
#   - Quest           : "comprehensive metabolic panel"
#   - Mayo            : "complete blood count", "hematology" / "haematology"
#   - HospitalLis     : "lipid profile", "biological ref"
#   - UrgentCare      : "hgb a1c"
#   - Tata1mg         : "tata 1mg", "1mg labs", "1mg health"
#   - DrLalPathLabs   : "dr lal pathlabs", "dr lal path labs", "drlpl"
NAMED_VENDOR_SUBSTRINGS: list[str] = [
    "labcorp",
    "lipid panel",
    "comprehensive metabolic panel",
    "complete blood count",
    "haematology",
    "hematology",
    "lipid profile",
    "biological ref",
    "hgb a1c",
    "tata 1mg",
    "1mg labs",
    "1mg health",
    "dr lal pathlabs",
    "dr lal path labs",
    "drlpl",
]


@pytest.fixture(scope="module")
def acme_page1_lower() -> str:
    """Extract page-1 text from the Acme PDF, lower-cased.

    Fixture-scoped at module so pdfminer is invoked once across both test
    methods. If the PDF does not exist (e.g. partial checkout), the fixture
    short-circuits with a clear failure.
    """
    if not PDF_PATH.exists():
        pytest.fail(
            f"Acme fixture missing at {PDF_PATH}.\n"
            "Run `python tools/parsers/synthesize_fixture.py` to (re)generate."
        )
    return extract_text(str(PDF_PATH)).lower()


def test_acme_diagnostics_fingerprint_misses_all_named_vendors(acme_page1_lower: str) -> None:
    """D-14 anti-fingerprint invariant.

    Page-1 lower-cased text from the Acme PDF MUST NOT contain any of the
    15 named-vendor fingerprint substrings. If it does, the named extractor
    would claim the PDF before GenericExtractor's slot-7 catch-all gets a
    chance, defeating the entire generic-fallback proof.
    """
    violations = [
        token for token in NAMED_VENDOR_SUBSTRINGS if token in acme_page1_lower
    ]
    assert not violations, (
        "D-14 anti-fingerprint violation: Acme PDF page-1 contains "
        f"{len(violations)} named-vendor substring(s) that would route it to "
        f"a named extractor instead of GenericExtractor: {violations!r}.\n"
        "Edit tools/parsers/synthesize_fixture.py::generate_acme to remove "
        "the offending phrasing(s), then re-run synthesize_fixture.py to "
        "regenerate the PDF."
    )


def test_acme_diagnostics_page1_is_text_layered(acme_page1_lower: str) -> None:
    """Defence in depth: image-only PDFs silently route to the deferral
    path INTERPRET-03 instead of generic-fallback, defeating the test.
    Assert pdfminer returned at least some text content.
    """
    stripped = acme_page1_lower.strip()
    assert stripped, (
        f"Acme PDF page-1 extraction returned empty text — is the PDF "
        f"image-only? Path: {PDF_PATH}"
    )
    # Defensive: at minimum, the bland page-1 header line should survive.
    assert "acme diagnostics" in stripped, (
        "Acme PDF page-1 text-layer present but does not contain the "
        "expected 'Acme Diagnostics' header line — generator output drift?"
    )
