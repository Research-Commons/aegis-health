"""Hermetic unit tests for tools/parsers/lab_report_parser.py.

These tests synthesize 1-page PDFs in tmp_path (via reportlab) so the parser
can be exercised against deterministic inputs without depending on the 5
vendor fixtures under eval/fixtures/lab_reports/. The byte-identical
integration test against those fixtures lives in
test_lab_report_parser_vs_fixtures.py.

Synthetic PDFs are emitted in a Quest-CMP-style tabular layout so the parser's
vendor fingerprint reliably detects them as 'quest' and routes to the
permissive CMP extractor regexes. Each test asserts a different deferral
or normalization invariant from .planning/specs/EXTRACTION-SPEC.md +
INTERPRET-03 / INTERPRET-04 / INTERPRET-05.
"""
from __future__ import annotations

from pathlib import Path

import pytest

from tools.parsers.lab_report_parser import parse


def _write_quest_style_pdf(
    tmp_path: Path,
    body_lines: list[str],
    filename: str = "synthetic.pdf",
    *,
    demographics_line: str | None = None,
) -> Path:
    """Write a 1-page PDF that the parser will fingerprint as 'quest'.

    The page-1 fingerprint key for Quest is the literal 'COMPREHENSIVE
    METABOLIC PANEL'; the parser then dispatches body lines to the Quest
    row-regex routines.
    """
    reportlab = pytest.importorskip("reportlab")
    from reportlab.lib.pagesizes import letter  # type: ignore
    from reportlab.pdfgen import canvas  # type: ignore

    pdf_path = tmp_path / filename
    c = canvas.Canvas(str(pdf_path), pagesize=letter)
    y = 750
    # Quest fingerprint header.
    c.drawString(72, y, "COMPREHENSIVE METABOLIC PANEL"); y -= 18
    c.drawString(72, y, "Test Name Result Flag Reference Range Lab"); y -= 18
    if demographics_line is not None:
        c.drawString(72, y, demographics_line); y -= 18
    for line in body_lines:
        c.drawString(72, y, line); y -= 18
    c.save()
    return pdf_path


def _write_image_only_pdf(tmp_path: Path) -> Path:
    """Write a PDF with no text layer (image-only) so extract_text() returns ''."""
    reportlab = pytest.importorskip("reportlab")
    from reportlab.lib.pagesizes import letter  # type: ignore
    from reportlab.pdfgen import canvas  # type: ignore

    pdf_path = tmp_path / "image_only.pdf"
    c = canvas.Canvas(str(pdf_path), pagesize=letter)
    # Only filled rectangles — no drawString text whatsoever.
    c.rect(72, 700, 200, 100, fill=1)
    c.rect(72, 500, 200, 100, fill=1)
    c.save()
    return pdf_path


# ---------------------------------------------------------------------------
# 1. Single-row happy path
# ---------------------------------------------------------------------------


def test_parse_single_row(tmp_path):
    """A single Quest-style row -> 1 EvaluatedRow with correct status + ref_source."""
    pdf = _write_quest_style_pdf(tmp_path, [
        "SODIUM 142 135-146 mmol/L EN",
    ])
    result = parse(pdf)
    assert "error" not in result, result
    assert len(result["rows"]) == 1
    row = result["rows"][0]
    assert row["canonical_name"] == "sodium"
    assert row["raw_name"] == "SODIUM"
    assert row["value"] == 142
    assert row["units"] == "mmol/L"
    assert row["ref_low"] == 135
    assert row["ref_high"] == 146
    assert row["ref_source"] == "report"
    assert row["status"] == "IN_RANGE"
    assert result["has_outside_range"] is False
    assert result["has_unknown"] is False
    # Definition + citation must come from the bundled _DEFINITION_DB
    # (sodium is a MedlinePlus authority entry).
    assert row["definition"] is not None
    assert row["definition_citation"] == \
        "https://medlineplus.gov/lab-tests/sodium-blood-test/"
    # Phase 2 D-10 / D-12: successful parse carries report_status='OK' and
    # an IN_RANGE row carries defer_reason=None.
    assert result["report_status"]["code"] == "OK"
    assert row["defer_reason"] is None


# ---------------------------------------------------------------------------
# 2. Multi-row aggregation + has_outside_range computation
# ---------------------------------------------------------------------------


def test_parse_multi_row(tmp_path):
    """Multiple Quest-style rows aggregate into rows[] in PDF order; OUTSIDE_RANGE
    rows flip has_outside_range."""
    pdf = _write_quest_style_pdf(tmp_path, [
        "SODIUM 142 135-146 mmol/L EN",
        "POTASSIUM 4.2 3.5-5.3 mmol/L EN",
        "CALCIUM 14.0 8.6-10.3 mg/dL EN",  # value > ref_high -> OUTSIDE_RANGE
    ])
    result = parse(pdf)
    assert "error" not in result
    canonicals = [r["canonical_name"] for r in result["rows"]]
    assert "sodium" in canonicals
    assert "potassium" in canonicals
    assert "calcium" in canonicals
    # has_outside_range fires because calcium > ref_high.
    assert result["has_outside_range"] is True
    calcium = next(r for r in result["rows"] if r["canonical_name"] == "calcium")
    assert calcium["status"] == "OUTSIDE_RANGE"
    # Citations dedup by canonical_name; here all 3 are distinct.
    assert len(result["citations"]) == 3
    # Phase 2 D-10 / D-12: successful parse + every row with a printed range
    # carries defer_reason=None (status drives the verdict, not deferral).
    assert result["report_status"]["code"] == "OK"
    for r in result["rows"]:
        if r["status"] in ("IN_RANGE", "BORDERLINE", "OUTSIDE_RANGE"):
            assert r["defer_reason"] is None, r


# ---------------------------------------------------------------------------
# 3. Missing units -> row defers (INTERPRET-05)
# ---------------------------------------------------------------------------


def test_parse_missing_units_defers_row(tmp_path, monkeypatch):
    """A row whose units cell is absent must defer with status='unknown'.

    INTERPRET-05 + Phase 2 D-12: when value is present, units is null, and no
    printed range exists, the row defers with defer_reason='missing_units'.
    Stubs _parse_rows so we can inject a row in exactly that shape without
    fighting the per-vendor regex.
    """
    from tools.parsers import lab_report_parser as mod

    fake_raw_rows = [{
        "raw_name": "Cholesterol",
        "value": 200,
        "units": None,        # missing units
        "ref_low": None,
        "ref_high": None,     # no printed range either
    }]
    monkeypatch.setattr(mod, "_parse_rows", lambda pages, *, vendor: fake_raw_rows)
    monkeypatch.setattr(mod, "_detect_vendor", lambda pages: "quest")
    monkeypatch.setattr(mod, "_extract_demographics", lambda pages, *, vendor: {
        "age": None, "sex": None,
    })

    pdf = _write_quest_style_pdf(tmp_path, ["dummy"])
    # db_path=None so the KB-fallback branch is skipped; the no-range path
    # decides between missing_units and range_unavailable on units presence.
    result = parse(pdf, db_path=None)

    matching = [r for r in result["rows"] if r["canonical_name"] == "total cholesterol"]
    assert matching, result["rows"]
    row = matching[0]
    assert row["status"] == "unknown"
    assert row["ref_source"] == "none"
    assert row["defer_reason"] == "missing_units"
    assert result["report_status"]["code"] == "OK"


# ---------------------------------------------------------------------------
# 4. Mismatched units between row + KB-fallback range -> row defers
# ---------------------------------------------------------------------------


def test_parse_mismatched_units_defers_row(tmp_path, monkeypatch):
    """Value present, units='mg/dL' on the row, KB returns mmol/L -> defer.

    Stubs the lazily-imported lookup_lab_reference_range so the test stays
    hermetic (no KB build required).
    """
    # Quest-style row without a printed reference range so the KB-fallback
    # path is taken. "PROTEIN, TOTAL 6.6 mg/dL" omits the dash range.
    # We'll craft a synthetic line the parser CAN match without a range by
    # piggybacking on the NON-HDL CHOLESTEROL flow — but that's LabCorp only.
    # Easier: monkeypatch the parser's vendor dispatch and inject a row
    # directly.
    from tools.parsers import lab_report_parser as mod

    # Build a fake parsed row that the evaluator will route through KB.
    fake_raw_rows = [{
        "raw_name": "Cholesterol",
        "value": 200,
        "units": "mg/dL",  # row says mg/dL
        "ref_low": None,
        "ref_high": None,  # forces KB fallback
    }]
    # Stub _parse_rows + _detect_vendor so parse() runs end-to-end without
    # caring about PDF content.
    monkeypatch.setattr(mod, "_parse_rows", lambda pages, *, vendor: fake_raw_rows)
    monkeypatch.setattr(mod, "_detect_vendor", lambda pages: "quest")
    monkeypatch.setattr(mod, "_extract_demographics", lambda pages, *, vendor: {
        "age": None, "sex": None,
    })

    # Stub the KB lookup at import-site.
    def fake_lookup(test_name, age=None, sex=None, db_path=None):
        return {
            "test_name": "Cholesterol",
            "ref_low": 4.0,
            "ref_high": 5.2,
            "units": "mmol/L",  # mismatched against row's mg/dL
            "population": "adult",
            "source": "kb",
            "citation": "test",
        }
    import tools.tools.lookup_lab_reference_range as kb_mod
    monkeypatch.setattr(kb_mod, "lookup_lab_reference_range", fake_lookup)

    # Use any text-bearing pdf -- doesn't matter since _parse_rows is stubbed.
    pdf = _write_quest_style_pdf(tmp_path, ["dummy line that doesn't parse"])
    result = parse(pdf, db_path=Path("/tmp/fake.sqlite"))

    # canonical_name for "Cholesterol" lower-cased is 'total cholesterol'.
    matching = [r for r in result["rows"] if r["canonical_name"] == "total cholesterol"]
    assert matching, f"expected total cholesterol row, got {result['rows']!r}"
    row = matching[0]
    assert row["status"] == "unknown", row
    assert row["ref_source"] == "none", row
    # Phase 2 D-12: mismatched_units short-code on the row in addition to the
    # legacy side-channel extraction_warnings entry.
    assert row["defer_reason"] == "mismatched_units", row
    assert any("mismatched_units" in w for w in result["extraction_warnings"]), \
        result["extraction_warnings"]


# ---------------------------------------------------------------------------
# 5. Image-only PDF -> scanned_image_only error envelope (INTERPRET-03)
# ---------------------------------------------------------------------------


def test_parse_image_only_pdf_defers_report(tmp_path):
    """PDF with no extractable text -> report_status.code='IMAGE_ONLY' (D-10).

    The Phase 2 D-10 schema extension replaced the legacy
    {'error': 'scanned_image_only', ...} envelope with a full
    PreparsedReport-shaped dict whose report_status discriminates outcomes.
    """
    pdf = _write_image_only_pdf(tmp_path)
    result = parse(pdf)
    assert result["report_status"]["code"] == "IMAGE_ONLY"
    assert result["report_status"]["message"]
    assert result["rows"] == []
    assert result["citations"] == []
    assert result["has_outside_range"] is False
    assert result["has_unknown"] is False
    # No legacy error envelope.
    assert "error" not in result


# ---------------------------------------------------------------------------
# 6. Unknown test name -> row dropped + extraction_warnings entry
# ---------------------------------------------------------------------------


def test_parse_unknown_test_drops_row(tmp_path):
    """A row whose raw_name doesn't match LAB_TERM_ALIASES is dropped + flagged."""
    from tools.parsers import lab_report_parser as mod

    # Synthesize a raw row that survives Stage 2 but fails Stage 3 alias
    # lookup. The cleanest path is to stub _parse_rows so it returns a row
    # with an unknown raw_name.
    fake_raw = [{
        "raw_name": "Mystery Analyte XYZ",
        "value": 42,
        "units": "units/mL",
        "ref_low": None,
        "ref_high": 100,
    }]
    import pytest as _pt
    monkeypatch = _pt.MonkeyPatch()
    monkeypatch.setattr(mod, "_parse_rows", lambda pages, *, vendor: fake_raw)
    monkeypatch.setattr(mod, "_detect_vendor", lambda pages: "quest")
    monkeypatch.setattr(mod, "_extract_demographics", lambda pages, *, vendor: {
        "age": None, "sex": None,
    })
    try:
        pdf = _write_quest_style_pdf(tmp_path, ["dummy"])
        result = parse(pdf)
    finally:
        monkeypatch.undo()

    # The unknown raw_name was dropped from rows[].
    assert all(r["raw_name"] != "Mystery Analyte XYZ" for r in result["rows"])
    assert all(r["canonical_name"] != "Mystery Analyte XYZ" for r in result["rows"])
    # And it shows up as a warning.
    assert any(
        "unknown_test" in w and "Mystery Analyte XYZ" in w
        for w in result["extraction_warnings"]
    ), result["extraction_warnings"]
    # Phase 2 D-10: dropped rows do not change report_status; the report
    # itself parsed successfully so code='OK'.
    assert result["report_status"]["code"] == "OK"


# ---------------------------------------------------------------------------
# 7. Unknown vendor (no fingerprint match) -> empty rows + no error
# ---------------------------------------------------------------------------


def test_parse_unknown_vendor_returns_empty_report(tmp_path):
    """PDF without any vendor fingerprint -> report_status.code='UNKNOWN_VENDOR'.

    Phase 2 D-10: instead of returning an OK PreparsedReport with rows=[],
    the parser now short-circuits and emits a specific UNKNOWN_VENDOR code so
    UI can render a deferral instead of a "no analytes found" empty state.
    """
    reportlab = pytest.importorskip("reportlab")
    from reportlab.lib.pagesizes import letter  # type: ignore
    from reportlab.pdfgen import canvas  # type: ignore

    pdf = tmp_path / "unknown_vendor.pdf"
    c = canvas.Canvas(str(pdf), pagesize=letter)
    c.drawString(72, 750, "Generic Patient Report")
    c.drawString(72, 730, "Some random body text that doesn't fingerprint")
    c.drawString(72, 710, "Result: 42 units")
    c.save()

    result = parse(pdf)
    assert "error" not in result, result
    assert result["report_status"]["code"] == "UNKNOWN_VENDOR"
    assert result["report_status"]["message"]
    assert result["rows"] == []
    assert result["citations"] == []
    assert result["has_outside_range"] is False
    assert result["has_unknown"] is False


# ---------------------------------------------------------------------------
# 8. Phase 2 D-10: >25 normalized rows -> TOO_MANY_ANALYTES short-circuit
# ---------------------------------------------------------------------------


def test_parse_too_many_analytes_defers_report(tmp_path, monkeypatch):
    """26 normalized rows trip the INTERPRET-05 threshold; whole report defers.

    Stubs the normalization stage so the threshold check is exercised
    deterministically without crafting a 26-line PDF.
    """
    from tools.parsers import lab_report_parser as mod

    # 26 synthetic raw rows whose alias map resolves to distinct canonicals.
    # The _alias_map maps lowercase raw names -> canonical; using canonicals
    # that already exist in _DEFINITION_DB so citations dedup is honest.
    fake_raw_rows = [
        {"raw_name": f"SODIUM_{i}", "value": 142, "units": "mmol/L",
         "ref_low": 135, "ref_high": 146}
        for i in range(26)
    ]
    # Force each row to normalize to a unique canonical so they all survive
    # Stage 3 (otherwise duplicates collapse via the alias map).
    monkeypatch.setattr(mod, "_parse_rows", lambda pages, *, vendor: fake_raw_rows)
    monkeypatch.setattr(mod, "_detect_vendor", lambda pages: "quest")
    monkeypatch.setattr(mod, "_extract_demographics", lambda pages, *, vendor: {
        "age": None, "sex": None,
    })
    monkeypatch.setattr(mod, "_normalize_alias", lambda raw_name: f"synthetic_{raw_name}")

    pdf = _write_quest_style_pdf(tmp_path, ["dummy"])
    result = parse(pdf)

    assert result["report_status"]["code"] == "TOO_MANY_ANALYTES"
    assert result["report_status"]["message"]
    assert "26" in result["report_status"]["message"], result["report_status"]
    assert result["rows"] == []
    assert result["citations"] == []
    assert result["has_outside_range"] is False
    assert result["has_unknown"] is False


# ---------------------------------------------------------------------------
# 9. Phase 2 D-11 / D-12: tumor-marker canonicals auto-defer per row
# ---------------------------------------------------------------------------


def test_parse_tumor_marker_auto_defers_row(tmp_path, monkeypatch):
    """A canonical in the auto_defer set emits defer_reason='auto_defer:tumor_marker'.

    Stubs the row pipeline so we can feed a canonical the alias map would not
    otherwise yield (the in-PDF fixtures intentionally avoid these
    canonicals because they would defer).
    """
    from tools.parsers import lab_report_parser as mod

    fake_raw_rows = [{
        "raw_name": "PSA",
        "value": 2.1,
        "units": "ng/mL",
        "ref_low": 0,
        "ref_high": 4,  # printed range present but auto-defer beats it
    }]
    monkeypatch.setattr(mod, "_parse_rows", lambda pages, *, vendor: fake_raw_rows)
    monkeypatch.setattr(mod, "_detect_vendor", lambda pages: "quest")
    monkeypatch.setattr(mod, "_extract_demographics", lambda pages, *, vendor: {
        "age": None, "sex": None,
    })
    # Force the alias-map to yield the canonical "PSA" verbatim so the row
    # makes it through Stage 3 with that canonical_name.
    monkeypatch.setattr(mod, "_normalize_alias", lambda raw_name: "PSA")

    pdf = _write_quest_style_pdf(tmp_path, ["dummy"])
    result = parse(pdf, db_path=None)

    assert result["report_status"]["code"] == "OK"
    assert len(result["rows"]) == 1
    row = result["rows"][0]
    assert row["canonical_name"] == "PSA"
    assert row["status"] == "unknown"
    assert row["ref_source"] == "none"
    assert row["ref_low"] is None
    assert row["ref_high"] is None
    assert row["defer_reason"] == "auto_defer:tumor_marker"
