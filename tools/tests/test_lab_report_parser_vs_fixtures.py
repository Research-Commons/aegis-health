"""Integration test: Python reference parser output vs hand-curated ground truth.

For each of the 5 vendor fixtures (labcorp / quest / mayo / hospital_lis /
urgent_care), parse the PDF with tools.parsers.lab_report_parser.parse() and
diff the canonicalized output against the corresponding *-evaluated.json
under eval/fixtures/lab_reports/{vendor}/.

Canonicalization rule (per .planning/specs/EXTRACTION-SPEC.md):
  - sort_keys=True, 2-space indent, ensure_ascii=False, trailing newline
  - strip the parser's side-channel 'extraction_warnings' key before diff
    (the schema's additionalProperties:false forbids it on the wire)
  - rows[] is left in parser-emitted order; ground truth uses PDF reading order

This integration test is what ROADMAP Phase 1 success criterion 6 buys us:
"one reference Python parser and one matching expected-output JSON per
fixture". The Kotlin production parser (Phase 2) is later diff-checked
against the same ground-truth JSONs; if the Python parser and Kotlin parser
disagree, this test pins where the truth lives.
"""
from __future__ import annotations

import json
from pathlib import Path

import pytest

# Repo root resolution. This file lives at:
#   c:/ResearchCommons/aegis-health/tools/tests/test_lab_report_parser_vs_fixtures.py
# so parent.parent.parent -> repo root.
REPO_ROOT = Path(__file__).resolve().parent.parent.parent
FIXTURE_ROOT = REPO_ROOT / "eval" / "fixtures" / "lab_reports"
KB_PATH = REPO_ROOT / "kb" / "output" / "aegis_kb.sqlite"


def _canonicalize(obj: dict) -> str:
    """Apply EXTRACTION-SPEC canonicalization rule. Strips parser side-channels."""
    obj = {k: v for k, v in obj.items() if k != "extraction_warnings"}
    return json.dumps(obj, indent=2, sort_keys=True, ensure_ascii=False) + "\n"


def _is_substitute_with_strict_skip(manifest_text: str) -> bool:
    """Return True if MANIFEST.md flags the fixture for non-strict parity.

    Per the plan's T-01-10-01 mitigation: a Substitution note that says 'N/A'
    means parity is required; any other Substitution note text grants
    xfail(strict=False). NOTE: at the time of writing all 5 fixtures pass
    strict parity so this safety hatch is unused — but we keep it as the
    documented escape for future vendor swaps.
    """
    marker = "## Substitution note"
    if marker not in manifest_text:
        return False
    after = manifest_text.split(marker, 1)[1]
    head = after[:400].strip()
    # An empty Substitution note OR one whose body is 'N/A' / 'None' /
    # 'Not applicable' means strict parity required.
    first_paragraph = head.split("\n\n", 1)[0].strip()
    return bool(first_paragraph) and first_paragraph.lower() not in {
        "n/a", "none", "not applicable",
    }


@pytest.mark.parametrize(
    "vendor",
    ["labcorp", "quest", "mayo", "hospital_lis", "urgent_care"],
)
def test_parser_matches_ground_truth(vendor):
    """Parser output must byte-match ground truth (after canonicalization).

    Each fixture is exercised end-to-end:
      1. Locate the single PDF + paired *-evaluated.json under the vendor dir.
      2. Run tools.parsers.lab_report_parser.parse() against the PDF.
      3. Canonicalize both sides per EXTRACTION-SPEC.md.
      4. Assert byte-equality. On failure, emit the unified diff.
    """
    from tools.parsers.lab_report_parser import parse

    fixture_dir = FIXTURE_ROOT / vendor
    if not fixture_dir.exists():
        pytest.skip(f"fixture dir missing: {fixture_dir}")

    pdfs = list(fixture_dir.glob("*.pdf"))
    assert len(pdfs) == 1, (
        f"{vendor}: expected exactly 1 PDF, found {len(pdfs)}: {pdfs}"
    )
    pdf = pdfs[0]

    gt_path = fixture_dir / f"{pdf.stem}-evaluated.json"
    if not gt_path.exists():
        pytest.skip(f"ground truth missing: {gt_path}")

    ground_truth = json.loads(gt_path.read_text(encoding="utf-8"))
    parsed = parse(pdf, db_path=KB_PATH if KB_PATH.exists() else None)

    parsed_canon = _canonicalize(parsed)
    gt_canon = _canonicalize(ground_truth)

    if parsed_canon == gt_canon:
        return  # strict parity OK

    # Strict parity failed. Per T-01-10-01 mitigation, vendors with a
    # non-'N/A' Substitution note are allowed xfail; vendors with 'N/A'
    # block. (Currently all 5 pass strict parity so this branch is unused.)
    manifest_path = fixture_dir / "MANIFEST.md"
    manifest_text = manifest_path.read_text(encoding="utf-8") if manifest_path.exists() else ""
    if _is_substitute_with_strict_skip(manifest_text):
        pytest.xfail(
            f"{vendor} is a substitution-substitute per MANIFEST.md; "
            f"strict parity not currently required. "
            f"See {manifest_path} 'Substitution note' for the rationale."
        )

    # No xfail escape -> assertion failure with unified diff.
    import difflib

    diff = "".join(
        difflib.unified_diff(
            gt_canon.splitlines(keepends=True),
            parsed_canon.splitlines(keepends=True),
            fromfile=f"{vendor}/ground_truth",
            tofile=f"{vendor}/parser_output",
            n=3,
        )
    )
    pytest.fail(
        f"{vendor}: parser output does not byte-match ground truth.\n"
        f"Unified diff (ground_truth -> parser_output):\n{diff}"
    )
