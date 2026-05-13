# quest fixture — MANIFEST

**Fixture:** quest_cmp.pdf
**Panel type:** CMP
**Slot:** quest

## Provenance

- **Source URL:** https://www.accesalabs.com/downloads/qst/Comprehensive-Metabolic-Panel-Test-Results.pdf?utm_source=chatgpt.com
- **Retrieval date:** 2026-05-13
- **License observation:** PDF text states "Sample results. Actual results may vary."
- **Redistribution rationale:** The PDF is a publicly accessible sample result document and is explicitly presented as non-real sample output. It is safe to commit as an evaluation fixture after PHI review confirms no real patient identifiers.
- **SHA256:** 4f22cbbc247238461f4ee25531762d05843d47e216e314c8a3e6fb3c8ad1ebce

## PHI redaction status

Vendor-supplied patient-education sample (per Source URL above). No real PHI present. Verified by reviewer eye before commit.

## Substitution note

Original Quest source was unavailable; substituted public AccesaLabs CMP sample report. Use only as a small evaluation fixture; revisit license clearance before any public redistribution beyond this repo.

## Ground truth

Paired ground-truth JSON: `quest_cmp-evaluated.json`
Validates against `.planning/specs/PreparsedReport.schema.json` (Draft 2020-12).

## Update protocol

If this fixture is replaced or the PDF is reprocessed:
1. Update the SHA256 above to match the new PDF bytes.
2. Re-derive the paired ground-truth JSON.
3. Re-run `pytest eval/tests/test_fixture_manifests.py`.

---

*Manifest created: 2026-05-13 as part of Phase 1 fixture corpus (Plan 07).*
