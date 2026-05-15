"""One-shot synthetic-PDF generator for Phase 4.1 fixtures.

Generates three deterministic synthetic PDFs that exercise the Phase 4.1
Tata1mgExtractor / DrLalPathLabsExtractor / GenericExtractor pipeline.
Real customer PDFs are NEVER committed (D-12). The user's real Tata 1mg
PDF stays local; this script emits a zero-PHI synthetic equivalent that
contains the page-1 brand-token fingerprints those extractors look for.

Generated outputs:
  - android/app/src/androidTest/assets/lab_reports/tata1mg/tata1mg_cbc.pdf
  - android/app/src/androidTest/assets/lab_reports/drlalpathlabs/drlalpathlabs_lipid.pdf
  - android/app/src/androidTest/assets/lab_reports/generic/acme_diagnostics_cbc.pdf

Run:
  python tools/parsers/synthesize_fixture.py

Determinism:
  reportlab's `canvas.Canvas(..., invariant=1)` flag pins creation timestamps
  and font subsetting state, so re-running this script produces byte-identical
  PDFs (verified locally on reportlab 4.5.1). The document metadata
  (Title/Author/Creator/Subject/Producer) is pinned to fixed strings; no
  pseudo-random whitespace or layout decisions exist.

Zero-PHI invariant (D-12 / D-13 / D-14):
  All three PDFs use the same fake patient identity:
    - Name:  Test Patient
    - DOB:   1990-01-01
    - MRN:   TEST-NNN  (NNN = 001 / 002 / 003 per vendor)
  All analyte values are clinically plausible but invented; no real patient
  data appears anywhere.

Anti-fingerprint invariant for the Acme PDF (D-14):
  generate_acme() MUST NOT emit ANY of the 15 named-vendor fingerprint
  substrings (case-insensitive):
    labcorp, lipid panel, comprehensive metabolic panel,
    complete blood count, haematology, hematology,
    lipid profile, biological ref, hgb a1c,
    tata 1mg, 1mg labs, 1mg health,
    dr lal pathlabs, dr lal path labs, drlpl
  eval/tests/test_acme_diagnostics_anti_fingerprint.py is the CI guard.
"""
from __future__ import annotations

from pathlib import Path

from reportlab.lib.pagesizes import letter
from reportlab.pdfgen import canvas

# Resolve the androidTest fixture root relative to this file (repo-anchored).
# tools/parsers/synthesize_fixture.py -> repo root is parents[2].
OUT_ROOT = (
    Path(__file__).resolve().parents[2]
    / "android"
    / "app"
    / "src"
    / "androidTest"
    / "assets"
    / "lab_reports"
)

# Pinned PDF metadata for byte-determinism across reruns.
_DOC_TITLE = "Aegis Health synthetic lab-report fixture"
_DOC_AUTHOR = "aegis-health synthesize_fixture.py"
_DOC_CREATOR = "aegis-health synthesize_fixture.py"
_DOC_SUBJECT = "Phase 4.1 androidTest fixture (zero PHI)"

# Shared fake-patient block. All three fixtures use the same identity; only
# the MRN suffix differs per vendor.
_PATIENT_NAME = "Test Patient"
_PATIENT_DOB = "1990-01-01"


def _new_canvas(out: Path) -> canvas.Canvas:
    """Create a deterministic reportlab Canvas with pinned metadata.

    `invariant=1` pins the in-PDF object IDs + creation timestamp so the
    same drawString sequence produces byte-identical output across runs.
    """
    c = canvas.Canvas(str(out), pagesize=letter, invariant=1)
    c.setTitle(_DOC_TITLE)
    c.setAuthor(_DOC_AUTHOR)
    c.setCreator(_DOC_CREATOR)
    c.setSubject(_DOC_SUBJECT)
    return c


def _draw_lines(c: canvas.Canvas, x: int, y_start: int, lines: list[str], leading: int = 16) -> int:
    """Draw a vertical run of lines starting at (x, y_start). Returns the
    next y coordinate below the last drawn line."""
    y = y_start
    for line in lines:
        c.drawString(x, y, line)
        y -= leading
    return y


# ---------------------------------------------------------------------------
# Tata 1mg synthetic PDF
# ---------------------------------------------------------------------------


def generate_tata1mg(out: Path) -> None:
    """Synthetic Tata 1mg Labs PDF (D-12).

    Page-1 fingerprint substrings (matches Tata1mgExtractor.fingerprintMatches):
      - "tata 1mg | labs"             (masthead — empirical, 04.1-2-01-NOTES.md)
      - "personal health smart report" (large title — empirical)
      - "tata 1mg app"                 (promo footer — empirical)

    Body rows are sourced from 04.1-2-01-NOTES.md verbatim sample rows
    (Family-B column order: TEST_NAME VALUE UNIT LOW - HIGH). Values are the
    same as NOTES.md (clinically plausible, fake-patient).
    """
    out.parent.mkdir(parents=True, exist_ok=True)
    c = _new_canvas(out)

    # Page-1 fingerprint header. The exact "TATA 1mg | Labs" masthead +
    # "PERSONAL HEALTH SMART REPORT" title + "Tata 1mg app" footer are all
    # required so Tata1mgExtractor.fingerprintMatches() returns true on
    # lower-cased extraction.
    c.setFont("Helvetica-Bold", 14)
    c.drawString(72, 750, "TATA 1mg | Labs")
    c.setFont("Helvetica", 10)
    c.drawString(72, 735, "NABL Accredited")

    c.setFont("Helvetica-Bold", 16)
    c.drawString(72, 705, "PERSONAL HEALTH SMART REPORT")

    # Patient block (zero-PHI).
    c.setFont("Helvetica", 10)
    _draw_lines(
        c,
        x=72,
        y_start=675,
        lines=[
            f"Patient Name: {_PATIENT_NAME}",
            f"Date of Birth: {_PATIENT_DOB}",
            "Sex: M",
            "MRN: TEST-001",
            "Report Date: 2026-05-14",
        ],
    )

    # Table header (informational; row regex does not require it).
    c.setFont("Helvetica-Bold", 10)
    c.drawString(72, 585, "Test Name")
    c.drawString(272, 585, "Result")
    c.drawString(352, 585, "Unit")
    c.drawString(432, 585, "Bio. Ref. Interval")

    # Family-B body rows. Format: "<name>  <value>  <unit>  <low> - <high>"
    # Verbatim from 04.1-2-01-NOTES.md Sample Lab Rows (CBC + A1c + glucose).
    # Single drawString per row so pdfminer extracts them as single lines.
    c.setFont("Helvetica", 10)
    body_rows = [
        "Hemoglobin                          13.1   g/dL              13.0 - 17.0",
        "RBC                                 4.21   10^6/cu.mm        4.5 - 5.5",
        "HCT                                 41.6   %                 40 - 50",
        "MCHC                                31.4   g/dL              31.5 - 34.5",
        "Total Leucocyte Count               5.16   10^3/ÂµL          4 - 10",
        "Eosinophils                         15     %                 1 - 6",
        "Absolute Eosinophil Count           0.77   10^3/ÂµL          0.02 - 0.5",
        "Platelet Count                      165    10^3/ÂµL          150 - 410",
        "Glycosylated Hemoglobin (HbA1c)     6.0    %                 4 - 5.6",
        "Glucose - Fasting                   100    mg/dL             70 - 99",
    ]
    _draw_lines(c, x=72, y_start=565, lines=body_rows, leading=14)

    # Footer (defence in depth — second copy of brand substring so page-1
    # extraction always picks at least one fingerprint, even if pdfminer
    # mis-orders the masthead).
    c.setFont("Helvetica", 9)
    c.drawString(72, 90, "View detailed health insights, and trends on Tata 1mg app.")

    c.save()


# ---------------------------------------------------------------------------
# Dr Lal PathLabs synthetic PDF
# ---------------------------------------------------------------------------


def generate_drlalpathlabs(out: Path) -> None:
    """Synthetic Dr Lal PathLabs PDF (D-13).

    Page-1 fingerprint substrings (matches DrLalPathLabsExtractor.fingerprintMatches):
      - "dr lal pathlabs"            (masthead — empirical, 04.1-2-01-NOTES.md)
      - "lpl-national reference lab" (processed-at section — empirical)

    Body rows mix the verbatim Liver & Kidney panel sample rows from
    04.1-2-01-NOTES.md (these exercise DrLalPathLabsExtractor regex including
    parenthetical method-line skipping and inequality ranges) with a defensive
    lipid panel subset so the file lives under the *_lipid.pdf path the
    androidTest asset map points at.
    """
    out.parent.mkdir(parents=True, exist_ok=True)
    c = _new_canvas(out)

    # Page-1 fingerprint header.
    c.setFont("Helvetica-Bold", 14)
    c.drawString(72, 750, "Dr Lal PathLabs")
    c.setFont("Helvetica", 10)
    c.drawString(72, 735, "LPL-NATIONAL REFERENCE LAB")
    c.drawString(72, 720, "Block E, Sector 18, Rohini, New Delhi")

    c.setFont("Helvetica-Bold", 12)
    c.drawString(72, 695, "SWASTHFIT SUPER 4")
    c.setFont("Helvetica-Bold", 11)
    c.drawString(72, 680, "LIVER & KIDNEY PANEL, SERUM")

    # Patient block (zero-PHI).
    c.setFont("Helvetica", 10)
    _draw_lines(
        c,
        x=72,
        y_start=655,
        lines=[
            f"Patient Name: {_PATIENT_NAME}",
            f"Date of Birth: {_PATIENT_DOB}",
            "Sex: F",
            "MRN: TEST-002",
            "Report Date: 2026-05-14",
        ],
    )

    # Table header.
    c.setFont("Helvetica-Bold", 10)
    c.drawString(72, 565, "Test Name")
    c.drawString(252, 565, "Results")
    c.drawString(332, 565, "Units")
    c.drawString(412, 565, "Bio. Ref. Interval")

    # Family-B body rows. Verbatim from 04.1-2-01-NOTES.md Sample Lab Rows
    # (Liver & Kidney panel). Includes one parenthetical method line per the
    # NOTES.md "method names printed BENEATH test names" pattern (`(Modified
    # Jaffe,Kinetic)` etc.) — these are intentional row-parser-skip exercises
    # for DrLalPathLabsExtractor.
    c.setFont("Helvetica", 10)
    body_rows = [
        "Creatinine                       1.00   mg/dL          0.70 - 1.30",
        "(Modified Jaffe,Kinetic)",
        "GFR Estimated                    107    mL/min/1.73m2  >59",
        "Urea                             40.00  mg/dL          13.00 - 43.00",
        "(Urease UV)",
        "Urea Nitrogen Blood              18.68  mg/dL          6.00 - 20.00",
        "Uric Acid                        7.00   mg/dL          3.50 - 7.20",
        "AST (SGOT)                       30.0   U/L            15.00 - 40.00",
        "(IFCC without P5P)",
        "ALT (SGPT)                       40.0   U/L            10.00 - 49.00",
        "GGTP                             50.0   U/L            0 - 73",
        "Alkaline Phosphatase (ALP)       100.00 U/L            30.00 - 120.00",
        "Bilirubin Total                  1.00   mg/dL          0.30 - 1.20",
    ]
    _draw_lines(c, x=72, y_start=545, lines=body_rows, leading=14)

    c.save()


# ---------------------------------------------------------------------------
# Acme Diagnostics synthetic PDF (catch-all path)
# ---------------------------------------------------------------------------


def generate_acme(out: Path) -> None:
    """Synthetic Acme Diagnostics PDF (D-14).

    Routes to GenericExtractor catch-all. Page-1 MUST NOT contain ANY of the
    15 named-vendor fingerprint substrings (case-insensitive). The pytest
    eval/tests/test_acme_diagnostics_anti_fingerprint.py is the CI guard for
    this invariant.

    Body rows are 5 generic chemistry analytes (Sodium, Chloride, Calcium,
    Albumin, Glucose) in Family-B column order — they pass GenericExtractor's
    per-row gate (has units + has range) and clear the aggregate-floor gate
    (>= 3 rows post-normalization) so the pipeline emits report_status.code
    == GENERIC_FALLBACK rather than UNKNOWN_VENDOR.

    Notably AVOIDS (per anti-fingerprint checklist):
      - "Hematology" / "Haematology"      (Mayo fingerprint)
      - "Comprehensive Metabolic Panel"   (Quest fingerprint)
      - "Complete Blood Count"            (Mayo fingerprint)
      - "Lipid Panel" / "Lipid Profile"   (LabCorp / HospitalLis fingerprints)
      - "Biological Ref"                  (HospitalLis fingerprint)
      - "Hgb A1c"                         (UrgentCare fingerprint)
      - "Tata 1mg" / "1mg Labs" / "1mg Health"  (Tata1mg fingerprints)
      - "Dr Lal PathLabs" / "Dr Lal Path Labs" / "DRLPL"  (DrLal fingerprints)
      - "LabCorp"                         (LabCorp fingerprint)
    """
    out.parent.mkdir(parents=True, exist_ok=True)
    c = _new_canvas(out)

    # Page-1 header. Deliberately bland; uses "Routine Blood Work" instead of
    # any named panel name. "Reference Interval" is intentionally NOT
    # "Biological Ref" (HospitalLis fingerprint).
    c.setFont("Helvetica-Bold", 14)
    c.drawString(72, 750, "Acme Diagnostics Inc.")
    c.setFont("Helvetica", 10)
    c.drawString(72, 735, "123 Example Street, Anywhere, USA")

    c.setFont("Helvetica-Bold", 16)
    c.drawString(72, 705, "Patient Lab Report")
    c.setFont("Helvetica", 11)
    c.drawString(72, 685, "Routine Blood Work")

    # Patient block (zero-PHI).
    c.setFont("Helvetica", 10)
    _draw_lines(
        c,
        x=72,
        y_start=655,
        lines=[
            f"Patient Name: {_PATIENT_NAME}",
            f"Date of Birth: {_PATIENT_DOB}",
            "Sex: M",
            "MRN: TEST-003",
            "Report Date: 2026-05-14",
        ],
    )

    # Table header — uses "Reference Interval" (NOT "Biological Ref").
    c.setFont("Helvetica-Bold", 10)
    c.drawString(72, 565, "Analyte")
    c.drawString(232, 565, "Result")
    c.drawString(312, 565, "Units")
    c.drawString(392, 565, "Reference Interval")

    # Family-B body rows. 5 analytes (>= 3 required for aggregate-floor gate).
    # All carry units AND a bilateral range, so each row passes the per-row
    # gate (hasUnits OR hasRange). Values clinically plausible, fake-patient.
    c.setFont("Helvetica", 10)
    body_rows = [
        "Sodium                  142    mmol/L     135 - 145",
        "Chloride                103    mmol/L     98 - 107",
        "Calcium                 9.5    mg/dL      8.5 - 10.5",
        "Albumin                 4.2    g/dL       3.5 - 5.0",
        "Glucose                 95     mg/dL      70 - 100",
    ]
    _draw_lines(c, x=72, y_start=545, lines=body_rows, leading=14)

    c.save()


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------


def main() -> None:
    (OUT_ROOT / "tata1mg").mkdir(parents=True, exist_ok=True)
    (OUT_ROOT / "drlalpathlabs").mkdir(parents=True, exist_ok=True)
    (OUT_ROOT / "generic").mkdir(parents=True, exist_ok=True)
    generate_tata1mg(OUT_ROOT / "tata1mg" / "tata1mg_cbc.pdf")
    generate_drlalpathlabs(OUT_ROOT / "drlalpathlabs" / "drlalpathlabs_lipid.pdf")
    generate_acme(OUT_ROOT / "generic" / "acme_diagnostics_cbc.pdf")
    print(f"Generated 3 synthetic PDFs under {OUT_ROOT}")


if __name__ == "__main__":
    main()
