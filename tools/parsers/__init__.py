"""tools.parsers: Python reference parser for lab-report PDFs (Phase 1 Plan 10).

This package contains the reference Python parser that mirrors the Phase 2
Kotlin pre-parse pipeline. Output for each of the 5 vendor fixture PDFs is
byte-identical (after canonicalization per .planning/specs/EXTRACTION-SPEC.md)
to the corresponding hand-curated *-evaluated.json under
eval/fixtures/lab_reports/.
"""
