"""Python reference parser for lab-report PDFs.

This module is the reference parser per ROADMAP Phase 1 success criterion 6:
"one reference Python parser and one matching expected-output JSON per fixture".

Its output for each of the 5 vendor fixture PDFs MUST byte-match the
corresponding hand-curated *-evaluated.json after canonicalization per
.planning/specs/EXTRACTION-SPEC.md. The Kotlin production parser (Phase 2) is
diff-checked against this parser's output stage-by-stage.

This parser is intentionally simple/slow — it is a validation tool, not a
production parser. The Kotlin pipeline ships on Android; this Python module
exists only to give Phase 2 a cross-language byte-comparable target.

Pipeline (5 stages, mirroring .planning/research/ARCHITECTURE.md):

  1. PdfTextExtractor  — pdfplumber extract_text() + per-page text join
  2. LabValueParser    — per-vendor regex over the joined page text;
                         each fixture's tabular layout has its own row regex
                         keyed by page-1 fingerprint (LabCorp, Quest, Mayo,
                         hospital-LIS, urgent-care)
  3. LabRowNormalizer  — apply LAB_TERM_ALIASES; rows whose canonical_name
                         cannot be resolved are dropped and a side-channel
                         extraction_warnings entry "unknown_test:<raw>" is
                         emitted
  4. RangeEvaluator    — three-state status from row-printed range first,
                         KB fallback second via lookup_lab_reference_range
  5. ReportAssembler   — emit dict matching .planning/specs/PreparsedReport
                         .schema.json (rows, has_outside_range, has_unknown,
                         profile_used, citations); attach per-row definition
                         + citation from the bundled _DEFINITION_DB (the
                         MedlinePlus authority for the canonical-name set)

Error envelopes:

  {"error": "scanned_image_only", "vendor": None, "rows": []}

Returned when pdfplumber.extract_text() yields no non-whitespace text for any
page — matches the INTERPRET-03 deferral path; no silent OCR.

NOTE on definition + citation source. The Phase 2 Kotlin pipeline obtains the
per-row plain-language definition + citation via tools.tools.explain_lab_test
-> the terms KB table. This Python reference parser bundles the same MedlinePlus
prose locally (the _DEFINITION_DB constant) so the reference output is
self-contained and does not depend on a built KB during cross-language diff.
The Kotlin port should produce identical strings via the same upstream source.
"""
from __future__ import annotations

import re
from pathlib import Path
from typing import Any

import pdfplumber  # type: ignore[import-untyped]

from tools.parsers._alias_map import LAB_TERM_ALIASES, normalize as _normalize_alias

# Tumor markers / genetic tests / pathology-grade tests auto-defer at the
# row level (INTERPRET-04 mirror; canonical names so post-normalization).
#
# Kept as a frozenset for legacy callers that use it as a pure membership
# test; the new _lookup_auto_defer_category() helper (Phase 2 D-11) returns
# a defer_reason short-code category and is preferred for new code paths.
_AUTO_DEFER_CANONICAL = frozenset({
    "CA-125", "CA 19-9", "PSA", "AFP", "CEA",
    "BRCA1", "BRCA2", "KRAS",
})

# Category assignment for the legacy _AUTO_DEFER_CANONICAL set. Used as the
# fallback when no auto_defer_tests KB table is available (Plan 02-03 lands
# the table; until then this in-memory map keeps Python and Kotlin agreed on
# the 8-entry seed set).
_CANONICAL_DEFAULT_CATEGORIES: dict[str, str] = {
    "CA-125": "tumor_marker",
    "CA 19-9": "tumor_marker",
    "PSA": "tumor_marker",
    "AFP": "tumor_marker",
    "CEA": "tumor_marker",
    "BRCA1": "genetic",
    "BRCA2": "genetic",
    "KRAS": "genetic",
}


def _lookup_auto_defer_category(canonical: str, db_path) -> str | None:
    """Return 'tumor_marker' | 'genetic' | 'pathology' on hit, else None.

    When db_path is provided, queries the auto_defer_tests KB table (D-11) to
    keep Python and Kotlin pointing at the same single source of truth. Falls
    back to the in-memory _CANONICAL_DEFAULT_CATEGORIES map when db_path is
    None or the table does not yet exist (Plan 02-03 may not have rebuilt the
    KB yet during test runs).
    """
    if db_path is not None:
        try:
            import sqlite3
            from pathlib import Path as _Path
            db = _Path(db_path)
            if db.exists():
                conn = sqlite3.connect(str(db))
                try:
                    cur = conn.execute(
                        "SELECT category FROM auto_defer_tests "
                        "WHERE LOWER(canonical_name) = LOWER(?) LIMIT 1",
                        (canonical,),
                    )
                    row = cur.fetchone()
                    if row is not None:
                        return row[0]
                except sqlite3.Error:
                    pass  # table may not exist yet; fall through
                finally:
                    conn.close()
        except Exception:  # pragma: no cover -- defensive
            pass  # any failure falls through to the in-memory fallback
    return _CANONICAL_DEFAULT_CATEGORIES.get(canonical)


# ---------------------------------------------------------------------------
# MedlinePlus definition + citation lookup table.
#
# Keyed by canonical_name. Values follow the AegisResponse Citation shape
# (definition_citation is the bare URL; the citations[] entries on
# PreparsedReport wrap the URL with a "MedlinePlus: <Title>" label).
#
# These strings match the per-row definition + definition_citation values in
# the 5 hand-curated ground-truth JSONs at eval/fixtures/lab_reports/. The
# Kotlin parser obtains the same strings via tools.tools.explain_lab_test ->
# terms KB table. The reference parser bundles them locally so the
# cross-language diff is self-contained.
# ---------------------------------------------------------------------------
_DEFINITION_DB: dict[str, tuple[str, str, str]] = {
    # canonical_name -> (definition, definition_citation_url, citation_label)
    "total cholesterol": (
        "Cholesterol is a waxy, fat-like substance in your blood. A total "
        "cholesterol test measures the overall amount of cholesterol in your "
        "blood, including both LDL and HDL cholesterol.",
        "https://medlineplus.gov/cholesterol.html",
        "MedlinePlus: Cholesterol",
    ),
    "HDL cholesterol": (
        'HDL stands for high-density lipoprotein. HDL is the "good" '
        "cholesterol because it helps remove other forms of cholesterol from "
        "your blood.",
        "https://medlineplus.gov/hdlthegoodcholesterol.html",
        "MedlinePlus: HDL Cholesterol",
    ),
    "LDL cholesterol": (
        "LDL stands for low-density lipoprotein. LDL is sometimes called the "
        '"bad" cholesterol because a high LDL level can lead to plaque '
        "buildup in your arteries.",
        "https://medlineplus.gov/ldlthebadcholesterol.html",
        "MedlinePlus: LDL Cholesterol",
    ),
    "VLDL cholesterol": (
        "VLDL stands for very-low-density lipoprotein. VLDL contains the "
        "highest amount of triglycerides among the lipoproteins and is "
        'considered a type of "bad" cholesterol.',
        "https://medlineplus.gov/lab-tests/vldl-cholesterol/",
        "MedlinePlus: VLDL Cholesterol Test",
    ),
    "triglycerides": (
        "Triglycerides are a type of fat found in your blood. Your body uses "
        "them for energy. High levels can raise your risk of heart disease.",
        "https://medlineplus.gov/lab-tests/triglycerides-test/",
        "MedlinePlus: Triglycerides Test",
    ),
    "non-HDL cholesterol": (
        "Non-HDL cholesterol is your total cholesterol minus your HDL "
        'cholesterol. It includes LDL and other types of "bad" cholesterol.',
        "https://medlineplus.gov/lab-tests/cholesterol-levels/",
        "MedlinePlus: Cholesterol Levels",
    ),
    "cholesterol ratio": (
        "The cholesterol ratio compares total cholesterol to HDL cholesterol. "
        "Doctors sometimes use it to estimate heart-disease risk, though most "
        "clinical guidelines now rely on the individual cholesterol values "
        "directly.",
        "https://medlineplus.gov/lab-tests/cholesterol-levels/",
        "MedlinePlus: Cholesterol Levels",
    ),
    "LDL/HDL ratio": (
        'The LDL-to-HDL ratio compares "bad" LDL cholesterol to "good" HDL '
        "cholesterol. It is one of several ways to look at heart-disease "
        "risk.",
        "https://medlineplus.gov/lab-tests/cholesterol-levels/",
        "MedlinePlus: Cholesterol Levels",
    ),

    "hemoglobin a1c": (
        "A hemoglobin A1c test measures your average blood sugar level over "
        "the past 3 months. It is used to diagnose and monitor type 2 "
        "diabetes and prediabetes.",
        "https://medlineplus.gov/a1c.html",
        "MedlinePlus: A1C",
    ),
    "estimated average glucose": (
        "Estimated average glucose (eAG) translates your hemoglobin A1c "
        "result into the same units used for everyday glucose meters. It is "
        "a derived value calculated from your A1c.",
        "https://medlineplus.gov/a1c.html",
        "MedlinePlus: A1C",
    ),
    "glucose": (
        "A blood glucose test measures the amount of glucose (sugar) in your "
        "blood. Glucose is your body's main source of energy.",
        "https://medlineplus.gov/bloodglucose.html",
        "MedlinePlus: Blood Glucose",
    ),

    "hemoglobin": (
        "Hemoglobin is a protein in your red blood cells that carries oxygen "
        "from your lungs to the rest of your body. Low hemoglobin can be a "
        "sign of anemia.",
        "https://medlineplus.gov/lab-tests/hemoglobin-test/",
        "MedlinePlus: Hemoglobin Test",
    ),
    "hematocrit": (
        "Hematocrit is the percentage of your blood that is made up of red "
        "blood cells. It is part of a complete blood count and helps screen "
        "for anemia and other conditions.",
        "https://medlineplus.gov/lab-tests/hematocrit-test/",
        "MedlinePlus: Hematocrit Test",
    ),
    "white blood cell count": (
        "A white blood cell (WBC) count measures the number of white blood "
        "cells in your blood. White blood cells are part of your immune "
        "system and help fight infections.",
        "https://medlineplus.gov/lab-tests/white-blood-count-wbc/",
        "MedlinePlus: White Blood Count (WBC)",
    ),
    "red blood cell count": (
        "A red blood cell (RBC) count measures the number of red blood cells "
        "in your blood. Red blood cells carry oxygen from your lungs to the "
        "rest of your body.",
        "https://medlineplus.gov/lab-tests/red-blood-cell-count/",
        "MedlinePlus: Red Blood Cell Count",
    ),
    "platelet count": (
        "Platelets are blood cells that help your body form clots to stop "
        "bleeding. A platelet count measures the number of platelets in your "
        "blood.",
        "https://medlineplus.gov/lab-tests/platelet-tests/",
        "MedlinePlus: Platelet Tests",
    ),
    "mean corpuscular volume": (
        "Mean corpuscular volume (MCV) measures the average size of your red "
        "blood cells. It is part of a complete blood count and helps diagnose "
        "different types of anemia.",
        "https://medlineplus.gov/lab-tests/mcv-blood-test-mean-corpuscular-volume/",
        "MedlinePlus: MCV Blood Test",
    ),
    "mean corpuscular hemoglobin": (
        "Mean corpuscular hemoglobin (MCH) measures the average amount of "
        "hemoglobin in each red blood cell. It is reported on a complete "
        "blood count alongside MCV and MCHC.",
        "https://medlineplus.gov/lab-tests/red-blood-cell-indices/",
        "MedlinePlus: Red Blood Cell Indices",
    ),
    "mean corpuscular hemoglobin concentration": (
        "Mean corpuscular hemoglobin concentration (MCHC) measures the "
        "average concentration of hemoglobin inside red blood cells. It is "
        "reported on a complete blood count alongside MCV and MCH.",
        "https://medlineplus.gov/lab-tests/red-blood-cell-indices/",
        "MedlinePlus: Red Blood Cell Indices",
    ),
    "neutrophils": (
        "Neutrophils are the most common type of white blood cell and a key "
        "part of your immune response to infections. They are typically "
        "reported as part of a complete blood count (CBC) with differential.",
        "https://medlineplus.gov/lab-tests/blood-differential-test/",
        "MedlinePlus: Blood Differential Test",
    ),
    "lymphocytes": (
        "Lymphocytes are a type of white blood cell that helps your immune "
        "system fight infections. They include T cells, B cells, and natural "
        "killer (NK) cells.",
        "https://medlineplus.gov/lab-tests/blood-differential-test/",
        "MedlinePlus: Blood Differential Test",
    ),
    "eosinophils": (
        "Eosinophils are a type of white blood cell. They help fight off "
        "infections and play a role in allergic reactions.",
        "https://medlineplus.gov/lab-tests/blood-differential-test/",
        "MedlinePlus: Blood Differential Test",
    ),
    "monocytes": (
        "Monocytes are a type of white blood cell that helps fight infection "
        "and remove damaged tissue. They are part of a complete blood count "
        "(CBC) with differential.",
        "https://medlineplus.gov/lab-tests/blood-differential-test/",
        "MedlinePlus: Blood Differential Test",
    ),
    "basophils": (
        "Basophils are the rarest type of white blood cell. They release "
        "chemicals such as histamine and play a role in allergic reactions "
        "and inflammation.",
        "https://medlineplus.gov/lab-tests/blood-differential-test/",
        "MedlinePlus: Blood Differential Test",
    ),

    "blood urea nitrogen": (
        "A BUN test measures the amount of urea nitrogen in your blood. Urea "
        "nitrogen is a waste product made when your body breaks down protein.",
        "https://medlineplus.gov/lab-tests/blood-urea-nitrogen-bun-test/",
        "MedlinePlus: BUN Test",
    ),
    "creatinine": (
        "Creatinine is a waste product made when your muscles use energy. A "
        "blood creatinine test helps show how well your kidneys are working.",
        "https://medlineplus.gov/lab-tests/creatinine-test/",
        "MedlinePlus: Creatinine Test",
    ),
    "BUN/creatinine ratio": (
        "The BUN/creatinine ratio compares the level of urea nitrogen to "
        "creatinine in your blood. It helps doctors understand whether kidney "
        "problems may be related to dehydration or another cause.",
        "https://medlineplus.gov/lab-tests/blood-urea-nitrogen-bun-test/",
        "MedlinePlus: BUN Test",
    ),
    "eGFR": (
        "eGFR (estimated glomerular filtration rate) estimates how well your "
        "kidneys filter waste from your blood. A lower eGFR can be a sign of "
        "kidney disease.",
        "https://medlineplus.gov/lab-tests/glomerular-filtration-rate-gfr-test/",
        "MedlinePlus: GFR Test",
    ),
    "sodium": (
        "Sodium is an electrolyte that helps balance fluid in your body. A "
        "blood sodium test can help diagnose conditions affecting the "
        "kidneys, adrenal glands, or hydration.",
        "https://medlineplus.gov/lab-tests/sodium-blood-test/",
        "MedlinePlus: Sodium Blood Test",
    ),
    "potassium": (
        "Potassium is an electrolyte that helps your nerves and muscles work "
        "properly. A blood potassium test can help diagnose problems with "
        "the kidneys, heart, or other conditions.",
        "https://medlineplus.gov/lab-tests/potassium-blood-test/",
        "MedlinePlus: Potassium Blood Test",
    ),
    "chloride": (
        "Chloride is an electrolyte that works with sodium and potassium to "
        "keep your body's fluids and acid-base balance in check.",
        "https://medlineplus.gov/lab-tests/chloride-blood-test/",
        "MedlinePlus: Chloride Blood Test",
    ),
    "carbon dioxide": (
        "A CO2 (carbon dioxide) blood test measures the amount of carbon "
        "dioxide in the liquid part of your blood. It helps doctors check "
        "the acid-base balance and kidney/lung function.",
        "https://medlineplus.gov/lab-tests/co2-blood-test/",
        "MedlinePlus: CO2 Blood Test",
    ),
    "calcium": (
        "Calcium is a mineral your body needs to build strong bones and "
        "teeth. A blood calcium test measures the calcium level in your "
        "blood and can help find problems with the bones, kidneys, or other "
        "conditions.",
        "https://medlineplus.gov/calcium.html",
        "MedlinePlus: Calcium",
    ),
    "total protein": (
        "A total protein test measures the total amount of two types of "
        "proteins — albumin and globulin — in the liquid part of "
        "your blood. It can help diagnose problems with the liver or kidneys.",
        "https://medlineplus.gov/lab-tests/total-protein-and-albumin-globulin-ag-ratio/",
        "MedlinePlus: Total Protein and A/G Ratio",
    ),
    "albumin": (
        "Albumin is a protein made by the liver. A blood albumin test can "
        "help diagnose problems with the liver, kidneys, or nutrition.",
        "https://medlineplus.gov/lab-tests/albumin-blood-test/",
        "MedlinePlus: Albumin Blood Test",
    ),
    "globulin": (
        "Globulins are a group of proteins in the blood made by the liver "
        "and immune system. A globulin test is often part of a total-protein "
        "panel and can help check the immune system or liver.",
        "https://medlineplus.gov/lab-tests/total-protein-and-albumin-globulin-ag-ratio/",
        "MedlinePlus: Total Protein and A/G Ratio",
    ),
    "albumin/globulin ratio": (
        "The albumin/globulin (A/G) ratio compares the amounts of albumin "
        "and globulin in your blood. Doctors use it together with the "
        "total-protein test to look for liver, kidney, or immune-system "
        "problems.",
        "https://medlineplus.gov/lab-tests/total-protein-and-albumin-globulin-ag-ratio/",
        "MedlinePlus: Total Protein and A/G Ratio",
    ),
    "bilirubin": (
        "Bilirubin is a yellowish substance made when the body breaks down "
        "old red blood cells. High levels can cause jaundice and may signal "
        "a problem with the liver, gallbladder, or red blood cells.",
        "https://medlineplus.gov/lab-tests/bilirubin-blood-test/",
        "MedlinePlus: Bilirubin Blood Test",
    ),
    "alkaline phosphatase": (
        "Alkaline phosphatase (ALP) is an enzyme found mostly in the liver "
        "and bones. A blood ALP test can help diagnose liver disease or bone "
        "problems.",
        "https://medlineplus.gov/lab-tests/alp-alkaline-phosphatase-blood-test/",
        "MedlinePlus: ALP Blood Test",
    ),
    "AST": (
        "AST (aspartate aminotransferase) is an enzyme found in the liver, "
        "heart, and muscles. A blood AST test helps check for liver damage "
        "or disease.",
        "https://medlineplus.gov/lab-tests/ast-test/",
        "MedlinePlus: AST Test",
    ),
    "ALT": (
        "ALT (alanine aminotransferase) is an enzyme found mostly in the "
        "liver. A blood ALT test helps check for liver injury or disease.",
        "https://medlineplus.gov/lab-tests/alt-blood-test/",
        "MedlinePlus: ALT Blood Test",
    ),
}


def parse(pdf_path: Path | str, *, db_path: Path | str | None = None) -> dict:
    """Parse a lab-report PDF into a PreparsedReport-shaped dict.

    Always returns a dict matching .planning/specs/PreparsedReport.schema.json.
    Phase 2 D-10: the top-level `report_status.code` discriminates outcomes:
      - "OK"                 -- successful parse; rows populated
      - "IMAGE_ONLY"         -- no extractable text layer (EXTRACT-03)
      - "UNKNOWN_VENDOR"     -- page-1 fingerprint did not match (EXTRACT-01)
      - "TOO_MANY_ANALYTES"  -- >25 normalized rows (INTERPRET-05)

    For the three non-OK codes, rows is [] and citations is [].

    The db_path argument is the path to the SQLite KB built by `make kb`. It
    is consulted only when a parsed row has no printed reference range; the
    KB fallback is via tools.tools.lookup_lab_reference_range. When db_path
    is None the parser simply uses ref_source="none" + status="unknown" for
    rows missing a printed range.
    """
    pdf_path = Path(pdf_path)

    # --- Stage 1: PdfTextExtractor ---
    pages_text, all_text = _extract_text(pdf_path)
    if not all_text.strip():
        # Phase 2 D-10: image-only PDFs return a full PreparsedReport-shaped
        # dict with report_status.code='IMAGE_ONLY' (EXTRACT-03). UI handles
        # the deferral via report_status; rows[] is empty.
        return _build_status_only_report(
            demographics={"age": None, "sex": None},
            status_code="IMAGE_ONLY",
            status_message=(
                "This appears to be a scanned image; "
                "please use a digital lab report."
            ),
        )

    # --- Stage 2: LabValueParser ---
    vendor = _detect_vendor(pages_text)
    if vendor is None:
        # Phase 2 D-10: no page-1 fingerprint match. Short-circuit before
        # _parse_rows (which would just return [] anyway) so UI gets a
        # specific UNKNOWN_VENDOR code instead of an empty OK report.
        demographics = _extract_demographics(pages_text, vendor=None)
        return _build_status_only_report(
            demographics=demographics,
            status_code="UNKNOWN_VENDOR",
            status_message="Lab vendor format not recognized.",
        )
    raw_rows = _parse_rows(pages_text, vendor=vendor)

    # --- Stage 3: LabRowNormalizer ---
    extraction_warnings: list[str] = []
    normalized_rows: list[dict[str, Any]] = []
    for raw in raw_rows:
        canonical = _normalize_alias(raw["raw_name"])
        if canonical is None:
            extraction_warnings.append(f"unknown_test:{raw['raw_name']}")
            continue
        normalized_rows.append({**raw, "canonical_name": canonical})

    # Phase 2 D-10 / INTERPRET-05: TOO_MANY_ANALYTES guard. >25 normalized
    # rows triggers a whole-report deferral so the model never sees a wall of
    # uncurated analytes. Threshold mirrors the spec.
    if len(normalized_rows) > 25:
        demographics = _extract_demographics(pages_text, vendor=vendor)
        return _build_status_only_report(
            demographics=demographics,
            status_code="TOO_MANY_ANALYTES",
            status_message=(
                f"Unusually many values ({len(normalized_rows)}); "
                f"discuss with clinician."
            ),
        )

    # --- Stage 4: RangeEvaluator ---
    demographics = _extract_demographics(pages_text, vendor=vendor)
    evaluated_rows: list[dict[str, Any]] = []
    for row in normalized_rows:
        evaluated = _evaluate_row(
            row,
            demographics=demographics,
            db_path=db_path,
            warnings=extraction_warnings,
        )
        evaluated_rows.append(evaluated)

    # --- Stage 5: ReportAssembler ---
    return _assemble_report(evaluated_rows, demographics, extraction_warnings, vendor)


# ---------------------------------------------------------------------------
# Stage 1: text extraction
# ---------------------------------------------------------------------------


def _extract_text(pdf_path: Path) -> tuple[list[str], str]:
    """Return (per-page text list, joined text). Empty list when PDF lacks text."""
    pages: list[str] = []
    with pdfplumber.open(str(pdf_path)) as pdf:
        for page in pdf.pages:
            text = page.extract_text() or ""
            pages.append(text)
    joined = "\n".join(pages)
    return pages, joined


# ---------------------------------------------------------------------------
# Stage 2a: vendor fingerprint (page-1 header)
# ---------------------------------------------------------------------------


def _detect_vendor(pages_text: list[str]) -> str | None:
    """Page-1 header fingerprint -> vendor key. Returns None for unknown."""
    if not pages_text:
        return None
    page1 = pages_text[0].lower()

    # LabCorp / Quest share the same patient-portal template; distinguish by
    # which panel header appears.
    if "lipid panel" in page1 and "cholesterol, total" in page1:
        return "labcorp"
    if "comprehensive metabolic panel" in page1:
        return "quest"
    # Mayo / Indian-style CBC fingerprint
    if "complete blood count" in page1 or "haematology" in page1 or "hematology" in page1:
        return "mayo"
    # Hospital LIS letterhead -- "Lipid Profile" header + "Biological Ref. Interval"
    if "lipid profile" in page1 and "biological ref" in page1:
        return "hospital_lis"
    # Urgent-care A1C minimal report
    if "hgb a1c" in page1 or ("hemoglobin a1c" in page1 and "eag" in page1):
        return "urgent_care"
    return None


# ---------------------------------------------------------------------------
# Stage 2b: per-vendor row extraction
# ---------------------------------------------------------------------------


# A row spec: (raw_name_in_pdf, output_raw_name, output_units_override_or_None)
#
# raw_name_in_pdf is a literal substring that anchors the row on a line; the
# parser then captures (value, ref_low, ref_high, units) from that line. The
# output_raw_name is what appears in the ground-truth JSON's raw_name field
# (may differ slightly from the PDF — hand-curation normalizes punctuation).
# output_units_override forces a units string when the PDF line is ambiguous.

# Numeric token: digits with optional decimal point.
_NUM = r"-?\d+(?:\.\d+)?"


def _parse_rows(pages_text: list[str], *, vendor: str | None) -> list[dict[str, Any]]:
    """Dispatch to vendor-specific extractor."""
    if vendor == "labcorp":
        return _parse_labcorp(pages_text)
    if vendor == "quest":
        return _parse_quest(pages_text)
    if vendor == "mayo":
        return _parse_mayo(pages_text)
    if vendor == "hospital_lis":
        return _parse_hospital_lis(pages_text)
    if vendor == "urgent_care":
        return _parse_urgent_care(pages_text)
    return []


def _parse_labcorp(pages_text: list[str]) -> list[dict[str, Any]]:
    """LabCorp lipid panel: 'TEST NAME  VALUE  REF_RANGE  UNITS  LAB' columns."""
    text = "\n".join(pages_text)
    rows: list[dict[str, Any]] = []

    # CHOLESTEROL, TOTAL 151 125-200 mg/dL EN
    m = re.search(
        rf"CHOLESTEROL,\s*TOTAL\s+({_NUM})\s+({_NUM})-({_NUM})\s+(mg/dL)",
        text,
    )
    if m:
        rows.append(_row("CHOLESTEROL, TOTAL", _num(m.group(1)), m.group(4),
                         _num(m.group(2)), _num(m.group(3))))

    # HDL CHOLESTEROL 58 > OR = 46 mg/dL EN  (single-sided: ref_high=None)
    m = re.search(
        rf"HDL\s*CHOLESTEROL\s+({_NUM})\s+>\s*OR\s*=\s*({_NUM})\s+(mg/dL)",
        text,
    )
    if m:
        rows.append(_row("HDL CHOLESTEROL", _num(m.group(1)), m.group(3),
                         _num(m.group(2)), None))

    # TRIGLYCERIDES 48 <150 mg/dL EN
    m = re.search(
        rf"TRIGLYCERIDES\s+({_NUM})\s+<\s*({_NUM})\s+(mg/dL)",
        text,
    )
    if m:
        rows.append(_row("TRIGLYCERIDES", _num(m.group(1)), m.group(3),
                         None, _num(m.group(2))))

    # LDL-CHOLESTEROL 83 <130 mg/dL (calc) EN  -- GT raw_name is "LDL CHOLESTEROL"
    m = re.search(
        rf"LDL-?CHOLESTEROL\s+({_NUM})\s+<\s*({_NUM})\s+(mg/dL)",
        text,
    )
    if m:
        rows.append(_row("LDL CHOLESTEROL", _num(m.group(1)), m.group(3),
                         None, _num(m.group(2))))

    # CHOL/HDLC RATIO 2.6 < OR = 5.0 (calc) EN  -- units None
    m = re.search(
        rf"CHOL/HDLC\s+RATIO\s+({_NUM})\s+<\s*OR\s*=\s*({_NUM})",
        text,
    )
    if m:
        rows.append(_row("CHOL/HDLC RATIO", _num(m.group(1)), None,
                         None, _num(m.group(2))))

    # NON HDL CHOLESTEROL 93 mg/dL (calc) EN  -- no ref range
    m = re.search(
        rf"NON\s*HDL\s*CHOLESTEROL\s+({_NUM})\s+(mg/dL)",
        text,
    )
    if m:
        rows.append(_row("NON HDL CHOLESTEROL", _num(m.group(1)), m.group(2),
                         None, None))

    return rows


def _parse_quest(pages_text: list[str]) -> list[dict[str, Any]]:
    """Quest CMP: TEST  VALUE [FLAG]  REF_RANGE  UNITS  LAB columns; some lines
    have stray watermark single letters that we strip pre-extraction."""
    text = "\n".join(pages_text)
    rows: list[dict[str, Any]] = []

    # GLUCOSE 99 65-99 mg/dL EN
    m = re.search(rf"GLUCOSE\s+({_NUM})\s+({_NUM})-({_NUM})\s+(mg/dL)", text)
    if m:
        rows.append(_row("GLUCOSE", _num(m.group(1)), m.group(4),
                         _num(m.group(2)), _num(m.group(3))))

    # UREA NITROGEN (BUN) 20 7-25 mg/dL EN
    m = re.search(
        rf"UREA\s+NITROGEN\s+\(BUN\)\s+({_NUM})\s+({_NUM})-({_NUM})\s+(mg/dL)",
        text,
    )
    if m:
        rows.append(_row("UREA NITROGEN (BUN)", _num(m.group(1)), m.group(4),
                         _num(m.group(2)), _num(m.group(3))))

    # CREATININE 1.44 HIGH 0.70-1.25 mg/dL EN
    m = re.search(
        rf"CREATININE\s+({_NUM})\s+(?:HIGH|LOW)?\s*({_NUM})-({_NUM})\s+(mg/dL)",
        text,
    )
    if m:
        rows.append(_row("CREATININE", _num(m.group(1)), m.group(4),
                         _num(m.group(2)), _num(m.group(3))))

    # eGFR NON-AFR. AMERICAN 51 LOW > OR = 60 mL/min/1.73m2 EN
    m = re.search(
        rf"eGFR\s+NON-AFR\.\s+AMERICAN\s+({_NUM})\s+(?:HIGH|LOW)?\s*>\s*OR\s*=\s*({_NUM})\s+(mL/min/1\.73m2)",
        text,
    )
    if m:
        rows.append(_row("eGFR NON-AFR. AMERICAN", _num(m.group(1)), m.group(3),
                         _num(m.group(2)), None))

    # eGFR AFRICAN AMERICAN 59 LBOW > OR = 60 mL/min/1.73m2 EN
    # ("LBOW" = stray watermark; tolerate any letters between value and flag)
    m = re.search(
        rf"eGFR\s+AFRICAN\s+AMERICAN\s+({_NUM})\s+[A-Z]*\s*>\s*OR\s*=\s*({_NUM})\s+(mL/min/1\.73m2)",
        text,
    )
    if m:
        rows.append(_row("eGFR AFRICAN AMERICAN", _num(m.group(1)), m.group(3),
                         _num(m.group(2)), None))

    # BUN/CSREATININE RATIO 14 6-22 (calc) EN  -- watermark inserts 'S' into name
    m = re.search(
        rf"BUN/C?S?REATININE\s+RATIO\s+({_NUM})\s+({_NUM})-({_NUM})\s+\(calc\)",
        text,
    )
    if m:
        rows.append(_row("BUN/CREATININE RATIO", _num(m.group(1)), None,
                         _num(m.group(2)), _num(m.group(3))))

    # SODIUM 142 135-146 mmol/L EN
    m = re.search(rf"SODIUM\s+({_NUM})\s+({_NUM})-({_NUM})\s+(mmol/L)", text)
    if m:
        rows.append(_row("SODIUM", _num(m.group(1)), m.group(4),
                         _num(m.group(2)), _num(m.group(3))))

    # POTASSIUM 4.2 3.5-5.3 mmol/L EN
    m = re.search(rf"POTASSIUM\s+({_NUM})\s+({_NUM})-({_NUM})\s+(mmol/L)", text)
    if m:
        rows.append(_row("POTASSIUM", _num(m.group(1)), m.group(4),
                         _num(m.group(2)), _num(m.group(3))))

    # CHLORIDE 104 98-110 mmol/L EN
    m = re.search(rf"CHLORIDE\s+({_NUM})\s+({_NUM})-({_NUM})\s+(mmol/L)", text)
    if m:
        rows.append(_row("CHLORIDE", _num(m.group(1)), m.group(4),
                         _num(m.group(2)), _num(m.group(3))))

    # CARBON DIOXIDE 29 19-30 mmol/L EN
    m = re.search(
        rf"CARBON\s+DIOXIDE\s+({_NUM})\s+({_NUM})-({_NUM})\s+(mmol/L)",
        text,
    )
    if m:
        rows.append(_row("CARBON DIOXIDE", _num(m.group(1)), m.group(4),
                         _num(m.group(2)), _num(m.group(3))))

    # CALCIUM 9.5 8.6-10.3 mg/dL EN
    m = re.search(rf"CALCIUM\s+({_NUM})\s+({_NUM})-({_NUM})\s+(mg/dL)", text)
    if m:
        rows.append(_row("CALCIUM", _num(m.group(1)), m.group(4),
                         _num(m.group(2)), _num(m.group(3))))

    # PROTEIN, TOTAL 6.6 6.1-8.1 g/dL EEN  (watermark E intrudes into 'EN')
    m = re.search(
        rf"PROTEIN,\s*TOTAL\s+({_NUM})\s+({_NUM})-({_NUM})\s+(g/dL)",
        text,
    )
    if m:
        rows.append(_row("PROTEIN, TOTAL", _num(m.group(1)), m.group(4),
                         _num(m.group(2)), _num(m.group(3))))

    # ALBUMIN 4.3A 3.6-5.1 g/dL EN  (watermark A clings to value)
    m = re.search(
        rf"\bALBUMIN\s+({_NUM})[A-Z]?\s+({_NUM})-({_NUM})\s+(g/dL)",
        text,
    )
    if m:
        rows.append(_row("ALBUMIN", _num(m.group(1)), m.group(4),
                         _num(m.group(2)), _num(m.group(3))))

    # GLOBULIN 2.3 1.9-3.7 g/dL (calc) EN
    m = re.search(
        rf"\bGLOBULIN\s+({_NUM})\s+({_NUM})-({_NUM})\s+(g/dL)",
        text,
    )
    if m:
        rows.append(_row("GLOBULIN", _num(m.group(1)), m.group(4),
                         _num(m.group(2)), _num(m.group(3))))

    # ALBUMIN/GLOBULIN RATIO 1.9 1.0-2.5 (calc)LEN  -- units None
    m = re.search(
        rf"ALBUMIN/GLOBULIN\s+RATIO\s+({_NUM})\s+({_NUM})-({_NUM})\s+\(calc\)",
        text,
    )
    if m:
        rows.append(_row("ALBUMIN/GLOBULIN RATIO", _num(m.group(1)), None,
                         _num(m.group(2)), _num(m.group(3))))

    # BILIRUBIN, TOTAL 0.5 0.2-1.2 mg/dL EN
    m = re.search(
        rf"BILIRUBIN,\s*TOTAL\s+({_NUM})\s+({_NUM})-({_NUM})\s+(mg/dL)",
        text,
    )
    if m:
        rows.append(_row("BILIRUBIN, TOTAL", _num(m.group(1)), m.group(4),
                         _num(m.group(2)), _num(m.group(3))))

    # ALKALINE PHOSPHATASE 66 40-115 U/L EN
    m = re.search(
        rf"ALKALINE\s+PHOSPHATASE\s+({_NUM})\s+({_NUM})-({_NUM})\s+(U/L)",
        text,
    )
    if m:
        rows.append(_row("ALKALINE PHOSPHATASE", _num(m.group(1)), m.group(4),
                         _num(m.group(2)), _num(m.group(3))))

    # AST 14 10-35 U/L EN
    m = re.search(
        rf"(?<![A-Z])AST\s+({_NUM})\s+({_NUM})-({_NUM})\s+(U/L)",
        text,
    )
    if m:
        rows.append(_row("AST", _num(m.group(1)), m.group(4),
                         _num(m.group(2)), _num(m.group(3))))

    # ALT 18 9-46 U/L EN
    m = re.search(
        rf"(?<![A-Z])ALT\s+({_NUM})\s+({_NUM})-({_NUM})\s+(U/L)",
        text,
    )
    if m:
        rows.append(_row("ALT", _num(m.group(1)), m.group(4),
                         _num(m.group(2)), _num(m.group(3))))

    return rows


def _parse_mayo(pages_text: list[str]) -> list[dict[str, Any]]:
    """Mayo/Indian-style CBC: TEST  [FLAG]  VALUE  UNIT  REF_LOW - REF_HIGH columns."""
    text = "\n".join(pages_text)
    rows: list[dict[str, Any]] = []

    # HEMOGLOBIN 15 g/dl 13 - 17
    m = re.search(
        rf"HEMOGLOBIN\s+({_NUM})\s+(g/dl)\s+({_NUM})\s*-\s*({_NUM})",
        text,
    )
    if m:
        rows.append(_row("HEMOGLOBIN", _num(m.group(1)), m.group(2),
                         _num(m.group(3)), _num(m.group(4))))

    # TOTAL LEUKOCYTE COUNT 5,100 cumm 4,800 - 10,800  (comma in numbers)
    m = re.search(
        r"TOTAL\s+LEUKOCYTE\s+COUNT\s+([0-9,]+)\s+(cumm)\s+([0-9,]+)\s*-\s*([0-9,]+)",
        text,
    )
    if m:
        v = _num(m.group(1).replace(",", ""))
        lo = _num(m.group(3).replace(",", ""))
        hi = _num(m.group(4).replace(",", ""))
        rows.append(_row("TOTAL LEUKOCYTE COUNT", v, m.group(2), lo, hi))

    # NEUTROPHILS 79 % 40 - 80
    m = re.search(
        rf"NEUTROPHILS\s+({_NUM})\s+(%)\s+({_NUM})\s*-\s*({_NUM})",
        text,
    )
    if m:
        rows.append(_row("NEUTROPHILS", _num(m.group(1)), m.group(2),
                         _num(m.group(3)), _num(m.group(4))))

    # LYMPHOCYTE L 18 % 20 - 40   (flag letter between name and value)
    m = re.search(
        rf"LYMPHOCYTE\s+[A-Z]?\s*({_NUM})\s+(%)\s+({_NUM})\s*-\s*({_NUM})",
        text,
    )
    if m:
        rows.append(_row("LYMPHOCYTE", _num(m.group(1)), m.group(2),
                         _num(m.group(3)), _num(m.group(4))))

    # EOSINOPHILS 1 % 1 - 6
    m = re.search(
        rf"EOSINOPHILS\s+({_NUM})\s+(%)\s+({_NUM})\s*-\s*({_NUM})",
        text,
    )
    if m:
        rows.append(_row("EOSINOPHILS", _num(m.group(1)), m.group(2),
                         _num(m.group(3)), _num(m.group(4))))

    # MONOCYTES L 1 % 2 - 10
    m = re.search(
        rf"MONOCYTES\s+[A-Z]?\s*({_NUM})\s+(%)\s+({_NUM})\s*-\s*({_NUM})",
        text,
    )
    if m:
        rows.append(_row("MONOCYTES", _num(m.group(1)), m.group(2),
                         _num(m.group(3)), _num(m.group(4))))

    # BASOPHILS 1 % < 2   (only upper bound)
    m = re.search(rf"BASOPHILS\s+({_NUM})\s+(%)\s+<\s*({_NUM})", text)
    if m:
        rows.append(_row("BASOPHILS", _num(m.group(1)), m.group(2),
                         None, _num(m.group(3))))

    # PLATELET COUNT 3.5 lakhs/cumm 1.5 - 4.1
    m = re.search(
        rf"PLATELET\s+COUNT\s+({_NUM})\s+(lakhs/cumm)\s+({_NUM})\s*-\s*({_NUM})",
        text,
    )
    if m:
        rows.append(_row("PLATELET COUNT", _num(m.group(1)), m.group(2),
                         _num(m.group(3)), _num(m.group(4))))

    # TOTAL RBC COUNT 5 million/cumm 4.5 - 5.5
    m = re.search(
        rf"TOTAL\s+RBC\s+COUNT\s+({_NUM})\s+(million/cumm)\s+({_NUM})\s*-\s*({_NUM})",
        text,
    )
    if m:
        rows.append(_row("TOTAL RBC COUNT", _num(m.group(1)), m.group(2),
                         _num(m.group(3)), _num(m.group(4))))

    # HEMATOCRIT VALUE, HCT 42 % 40 - 50
    m = re.search(
        rf"HEMATOCRIT\s+VALUE,?\s*HCT\s+({_NUM})\s+(%)\s+({_NUM})\s*-\s*({_NUM})",
        text,
    )
    if m:
        rows.append(_row("HEMATOCRIT VALUE, HCT", _num(m.group(1)), m.group(2),
                         _num(m.group(3)), _num(m.group(4))))

    # MEAN CORPUSCULAR VOLUME, MCV 84.0 fL 83 - 101
    m = re.search(
        rf"MEAN\s+CORPUSCULAR\s+VOLUME,?\s*MCV\s+({_NUM})\s+(fL)\s+({_NUM})\s*-\s*({_NUM})",
        text,
    )
    if m:
        rows.append(_row("MEAN CORPUSCULAR VOLUME, MCV", _num(m.group(1)), m.group(2),
                         _num(m.group(3)), _num(m.group(4))))

    # MEAN CELL HAEMOGLOBIN, MCH 30.0 Pg 27 - 32
    m = re.search(
        rf"MEAN\s+CELL\s+HAEMOGLOBIN,?\s*MCH\s+({_NUM})\s+(Pg)\s+({_NUM})\s*-\s*({_NUM})",
        text,
    )
    if m:
        rows.append(_row("MEAN CELL HAEMOGLOBIN, MCH", _num(m.group(1)), m.group(2),
                         _num(m.group(3)), _num(m.group(4))))

    # MEAN CELL HAEMOGLOBIN CON, MCHC H 35.7 % 31.5 - 34.5
    m = re.search(
        rf"MEAN\s+CELL\s+HAEMOGLOBIN\s+CON,?\s*MCHC\s+[A-Z]?\s*({_NUM})\s+(%)\s+({_NUM})\s*-\s*({_NUM})",
        text,
    )
    if m:
        rows.append(_row("MEAN CELL HAEMOGLOBIN CON, MCHC", _num(m.group(1)),
                         m.group(2), _num(m.group(3)), _num(m.group(4))))

    return rows


def _parse_hospital_lis(pages_text: list[str]) -> list[dict[str, Any]]:
    """Hospital LIS lipid: 'OBSERVATION  RESULT  UNIT  BIOLOGICAL REF. INTERVAL'."""
    text = "\n".join(pages_text)
    rows: list[dict[str, Any]] = []

    # Total Cholesterol 122 mg/dL <200 Enzymatic ...
    m = re.search(
        rf"Total\s+Cholesterol\s+({_NUM})\s+(mg/dL)\s+<\s*({_NUM})",
        text,
    )
    if m:
        rows.append(_row("Total Cholesterol", _num(m.group(1)), m.group(2),
                         None, _num(m.group(3))))

    # Triglyceride 184 mg/dL <150 Enzymatic, Endpoint
    m = re.search(
        rf"Triglyceride\s+({_NUM})\s+(mg/dL)\s+<\s*({_NUM})",
        text,
    )
    if m:
        rows.append(_row("Triglyceride", _num(m.group(1)), m.group(2),
                         None, _num(m.group(3))))

    # HDL Cholesterol 37 mg/dL >45 Direct Measure
    m = re.search(
        rf"HDL\s+Cholesterol\s+({_NUM})\s+(mg/dL)\s+>\s*({_NUM})",
        text,
    )
    if m:
        rows.append(_row("HDL Cholesterol", _num(m.group(1)), m.group(2),
                         _num(m.group(3)), None))

    # VLDL Cholesterol 37 mg/dL 5-40 Calculated
    m = re.search(
        rf"VLDL\s+Cholesterol\s+({_NUM})\s+(mg/dL)\s+({_NUM})-({_NUM})",
        text,
    )
    if m:
        rows.append(_row("VLDL Cholesterol", _num(m.group(1)), m.group(2),
                         _num(m.group(3)), _num(m.group(4))))

    # LDL Cholesterol 48 mg/dL <100 Friedewald Formula (Calculated)
    m = re.search(
        rf"LDL\s+Cholesterol\s+({_NUM})\s+(mg/dL)\s+<\s*({_NUM})",
        text,
    )
    if m:
        rows.append(_row("LDL Cholesterol", _num(m.group(1)), m.group(2),
                         None, _num(m.group(3))))

    # Non-HDL Cholesterol 85 mg/dL <130 tCalculated
    m = re.search(
        rf"Non-HDL\s+Cholesterol\s+({_NUM})\s+(mg/dL)\s+<\s*({_NUM})",
        text,
    )
    if m:
        rows.append(_row("Non-HDL Cholesterol", _num(m.group(1)), m.group(2),
                         None, _num(m.group(3))))

    # LDL / HDL Ratio 1.3 Ratio 1.5-3.5 Calculated
    m = re.search(
        rf"LDL\s*/\s*HDL\s+Ratio\s+({_NUM})\s+(Ratio)\s+({_NUM})-({_NUM})",
        text,
    )
    if m:
        rows.append(_row("LDL / HDL Ratio", _num(m.group(1)), m.group(2),
                         _num(m.group(3)), _num(m.group(4))))

    # TC / HDL Ratio 3.3 Ratio 3-5 Calculated
    m = re.search(
        rf"TC\s*/\s*HDL\s+Ratio\s+({_NUM})\s+(Ratio)\s+({_NUM})-({_NUM})",
        text,
    )
    if m:
        rows.append(_row("TC / HDL Ratio", _num(m.group(1)), m.group(2),
                         _num(m.group(3)), _num(m.group(4))))

    return rows


def _parse_urgent_care(pages_text: list[str]) -> list[dict[str, Any]]:
    """Urgent-care A1C: 2-row report (Hemoglobin A1c + Estim. Avg Glu).
    Note the 'k' watermark prefixed to '180' for the eAG value."""
    text = "\n".join(pages_text)
    rows: list[dict[str, Any]] = []

    # Hemoglobin A1c 7.9 High % 4.8 - 5.6
    m = re.search(
        rf"Hemoglobin\s+A1c\s+({_NUM})\s+(?:High|Low)?\s*(%)\s+({_NUM})\s*-\s*({_NUM})",
        text,
    )
    if m:
        rows.append(_row("Hemoglobin A1c", _num(m.group(1)), m.group(2),
                         _num(m.group(3)), _num(m.group(4))))

    # Estim. Avg Glu (eAG) k180 mg/dL    (watermark "k" prefixes value, no range)
    m = re.search(
        rf"Estim\.?\s*Avg\s*Glu\s*\(eAG\)\s+[a-z]?\s*({_NUM})\s+(mg/dL)",
        text,
    )
    if m:
        rows.append(_row("Estim. Avg Glu (eAG)", _num(m.group(1)), m.group(2),
                         None, None))

    return rows


def _row(raw_name: str, value: float | int | None, units: str | None,
         ref_low: float | int | None, ref_high: float | int | None) -> dict[str, Any]:
    """Build a raw parsed row dict for the LabValueParser stage.

    Numeric values pre-coerced via _num() preserve their int/float
    representation from the PDF text (e.g. '5' -> int 5, '5.0' -> float 5.0).
    Pass-through here without further coercion so the JSON serializer emits
    the same shape as the hand-curated ground truths.
    """
    return {
        "raw_name": raw_name,
        "value": value,
        "units": units,
        "ref_low": ref_low,
        "ref_high": ref_high,
    }


def _num(s: str | None) -> float | int | None:
    """Parse a numeric token preserving int/float distinction.

    Ground-truth JSONs (canonicalized via EXTRACTION-SPEC.md numeric-rule)
    serialize integers without a trailing '.0' and floats with their full
    precision. The reference parser must round-trip the original PDF token
    so '5' stays int 5 and '5.0' stays float 5.0.

    Handles thousands-separators (e.g. '5,100' from Mayo-vendor PDFs) by
    stripping commas before parsing.
    """
    if s is None:
        return None
    s = str(s).replace(",", "").strip()
    if not s:
        return None
    if "." in s:
        try:
            return float(s)
        except ValueError:  # pragma: no cover
            return None
    try:
        return int(s)
    except ValueError:  # pragma: no cover
        return None


# ---------------------------------------------------------------------------
# Stage 4: range evaluation
# ---------------------------------------------------------------------------


def _evaluate_row(row: dict, *, demographics: dict, db_path: Path | str | None,
                  warnings: list[str]) -> dict:
    """Stage 4: compute three-state status; row-printed range first, KB fallback second.

    Phase 2 D-12: every emit path also assigns a `defer_reason` short-code
    (null for non-deferring rows). The 9-entry vocabulary lives in
    .planning/specs/EXTRACTION-SPEC.md and is mirrored in test_fixture_manifests.py.
    """
    canonical = row["canonical_name"]
    value = row["value"]
    units = row["units"]
    ref_low = row["ref_low"]
    ref_high = row["ref_high"]

    # Auto-defer tumor markers / genetic tests / pathology (INTERPRET-04). KB
    # lookup is preferred (D-11); _CANONICAL_DEFAULT_CATEGORIES is the legacy
    # 8-entry fallback so both paths agree until the KB table lands.
    auto_defer_category = _lookup_auto_defer_category(canonical, db_path)
    if auto_defer_category is not None:
        return _emit_row(
            canonical, row, ref_low=None, ref_high=None,
            ref_source="none", status="unknown",
            defer_reason=f"auto_defer:{auto_defer_category}",
        )

    # Non-numeric result (e.g., "Negative" / "Positive" / "Detected"). The
    # row carries no comparable value; defer with the dedicated short-code.
    if value is None:
        return _emit_row(
            canonical, row, ref_low=ref_low, ref_high=ref_high,
            ref_source="none", status="unknown",
            defer_reason="non_numeric_result",
        )

    # Printed range path (INTERPRET-02 primary).
    if ref_low is not None or ref_high is not None:
        status = _classify_status(value, ref_low, ref_high)
        return _emit_row(
            canonical, row, ref_low=ref_low, ref_high=ref_high,
            ref_source="report", status=status,
            defer_reason=None,
        )

    # No printed range -- KB fallback (lazy import to avoid hard dep on KB
    # build during unit tests).
    if db_path is not None:
        try:
            from tools.tools.lookup_lab_reference_range import (
                lookup_lab_reference_range,
            )
            kb = lookup_lab_reference_range(
                canonical,
                age=demographics.get("age"),
                sex=demographics.get("sex"),
                db_path=str(db_path),
            )
        except Exception:  # pragma: no cover -- defensive
            kb = {"error": "lookup failed"}
        if kb and "error" not in kb:
            kb_units = kb.get("units")
            if kb_units and units and kb_units.lower() != units.lower():
                warnings.append(f"mismatched_units:{canonical}")
                return _emit_row(
                    canonical, row, ref_low=None, ref_high=None,
                    ref_source="none", status="unknown",
                    defer_reason="mismatched_units",
                )
            kb_low = kb.get("ref_low")
            kb_high = kb.get("ref_high")
            status = _classify_status(value, kb_low, kb_high)
            return _emit_row(
                canonical, row, ref_low=kb_low, ref_high=kb_high,
                ref_source="kb-fallback", status=status,
                defer_reason=None,
            )

    # No printed range AND no KB hit -> defer. Units presence determines
    # which short-code applies (D-12).
    if not units:
        warnings.append(f"missing_units:{canonical}")
        return _emit_row(
            canonical, row, ref_low=None, ref_high=None,
            ref_source="none", status="unknown",
            defer_reason="missing_units",
        )
    return _emit_row(
        canonical, row, ref_low=None, ref_high=None,
        ref_source="none", status="unknown",
        defer_reason="range_unavailable",
    )


def _classify_status(value: float, ref_low: float | None,
                     ref_high: float | None) -> str:
    """Three-state status (BORDERLINE requires clinical_thresholds; deferred to v2)."""
    if ref_low is not None and value < ref_low:
        return "OUTSIDE_RANGE"
    if ref_high is not None and value > ref_high:
        return "OUTSIDE_RANGE"
    return "IN_RANGE"


def _emit_row(canonical: str, raw: dict, *, ref_low, ref_high, ref_source,
              status, defer_reason: str | None = None) -> dict:
    """Stage 5 helper: emit one EvaluatedRow dict matching the schema.

    definition + definition_citation come from the bundled _DEFINITION_DB
    (MedlinePlus authority); when no entry exists they are None.

    Phase 2 D-12: defer_reason is one of the 9 short-codes in
    .planning/specs/EXTRACTION-SPEC.md (or None for non-deferring rows). The
    schema requires the field on every EvaluatedRow.
    """
    entry = _DEFINITION_DB.get(canonical)
    definition = entry[0] if entry else None
    definition_citation = entry[1] if entry else None
    return {
        "canonical_name": canonical,
        "raw_name": raw["raw_name"],
        "value": raw["value"],
        "units": raw["units"],
        "ref_low": ref_low,
        "ref_high": ref_high,
        "ref_source": ref_source,
        "status": status,
        "definition": definition,
        "definition_citation": definition_citation,
        "defer_reason": defer_reason,
    }


# ---------------------------------------------------------------------------
# Demographics extraction
# ---------------------------------------------------------------------------


def _extract_demographics(pages_text: list[str], *, vendor: str | None) -> dict:
    """Cover-sheet demographic extraction: age + sex; null when not extractable.

    Most fixture PDFs (labcorp, quest, urgent_care) ship with the demographics
    fields blank in the PDF text. Mayo and hospital_lis embed real values.
    """
    if not pages_text:
        return {"age": None, "sex": None}
    text = pages_text[0]

    age: int | None = None
    sex: str | None = None

    # Mayo format: "Age / Sex : 27 YRS / M"
    m = re.search(r"Age\s*/\s*Sex\s*:\s*(\d{1,3})\s*YRS?\s*/\s*([MF])", text,
                  re.IGNORECASE)
    if m:
        age = int(m.group(1))
        sex = "male" if m.group(2).upper() == "M" else "female"
        return {"age": age, "sex": sex}

    # Hospital LIS format: "Age / Sex : 40 Y / M"
    m = re.search(r"Age\s*/\s*Sex\s*:\s*(\d{1,3})\s*Y\s*/\s*([MF])", text,
                  re.IGNORECASE)
    if m:
        age = int(m.group(1))
        sex = "male" if m.group(2).upper() == "M" else "female"
        return {"age": age, "sex": sex}

    # Generic labcorp/quest fallback: "AGE:" / "GENDER:" headings.
    m = re.search(r"\bAGE[:\s]+(\d{1,3})\b", text, re.IGNORECASE)
    if m:
        age = int(m.group(1))
    m = re.search(r"\bGENDER[:\s]+(Male|Female|M|F)\b", text, re.IGNORECASE)
    if m:
        s = m.group(1).lower()
        sex = {"m": "male", "f": "female"}.get(s, s)

    return {"age": age, "sex": sex}


# ---------------------------------------------------------------------------
# Stage 5: report assembly
# ---------------------------------------------------------------------------


def _assemble_report(rows: list[dict], demographics: dict,
                     warnings: list[str], vendor: str | None,
                     *, status_code: str = "OK",
                     status_message: str | None = None) -> dict:
    """Stage 5: emit the full PreparsedReport dict per the schema.

    Phase 2 D-10: every report carries a top-level `report_status` envelope.
    Successful parses set `code='OK'`; the three short-circuit paths
    (IMAGE_ONLY, UNKNOWN_VENDOR, TOO_MANY_ANALYTES) call
    `_build_status_only_report` instead, which forwards a non-OK code here
    with rows=[] and citations=[].
    """
    has_outside_range = any(r["status"] == "OUTSIDE_RANGE" for r in rows)
    has_unknown = any(r["status"] == "unknown" for r in rows)

    # citations: one entry per unique canonical_name (NOT per row, NOT per
    # label-URL pair). Quest fixture has 2 eGFR rows that collapse to 1
    # citation; LabCorp fixture has 2 distinct canonicals ("cholesterol
    # ratio" + "non-HDL cholesterol") that happen to share the same label
    # "MedlinePlus: Cholesterol Levels" and remain as 2 citation entries.
    # Sorted alphabetically by label after dedup; duplicate labels are
    # therefore preserved when they come from different canonicals.
    seen_canonicals: set[str] = set()
    citations: list[dict] = []
    for r in rows:
        canonical = r["canonical_name"]
        if canonical in seen_canonicals:
            continue
        entry = _DEFINITION_DB.get(canonical)
        if entry is None:
            continue
        seen_canonicals.add(canonical)
        _def, url, label = entry
        citations.append({"label": label, "url": url})
    citations.sort(key=lambda c: c["label"])

    return {
        "rows": rows,
        "has_outside_range": has_outside_range,
        "has_unknown": has_unknown,
        "profile_used": {
            "age": demographics.get("age"),
            "sex": demographics.get("sex"),
        },
        "citations": citations,
        "report_status": {"code": status_code, "message": status_message},
        # extraction_warnings is NOT in PreparsedReport.schema.json — the test
        # canonicalizer strips it before the byte-comparable diff.
        "extraction_warnings": warnings,
    }


def _build_status_only_report(*, demographics: dict, status_code: str,
                              status_message: str | None) -> dict:
    """Emit a PreparsedReport-shaped dict for non-OK report_status paths.

    Phase 2 D-10: IMAGE_ONLY / UNKNOWN_VENDOR / TOO_MANY_ANALYTES all return
    rows=[] + citations=[] + has_outside_range=False + has_unknown=False
    alongside a populated report_status envelope. UI consumes the code +
    message directly; the model is never invoked on these paths.
    """
    return {
        "rows": [],
        "has_outside_range": False,
        "has_unknown": False,
        "profile_used": {
            "age": demographics.get("age"),
            "sex": demographics.get("sex"),
        },
        "citations": [],
        "report_status": {"code": status_code, "message": status_message},
        # No analytes to warn about; preserve the side-channel for parity
        # with the OK path (canonicalizer strips it before byte-diff).
        "extraction_warnings": [],
    }


# Re-export for tests / Phase 2 Kotlin port reference.
__all__ = ["parse", "LAB_TERM_ALIASES"]
