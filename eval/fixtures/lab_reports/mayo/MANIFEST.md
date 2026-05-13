# mayo fixture — MANIFEST

**Fixture:** mayo_cbc.pdf
**Panel type:** CBC
**Slot:** mayo

## Provenance

- **Source URL:** https://www.labsmartlis.com/pdf/pathology/cbc-report-format.pdf
- **Retrieval date:** 2026-05-13
- **License observation:** Publicly accessible sample PDF from Labsmart LIS. The related Labsmart CBC report-format page states that the inbuilt interpretations are © copyright of Labsmart Healthcare Technologies; no open license or redistribution permission is stated on the page/PDF.
- **Redistribution rationale:** Use only as a small evaluation fixture with attribution/source URL and provenance metadata. Because no permissive license is stated, avoid broad redistribution; keep it limited to internal testing or replace with a synthetic/permissioned equivalent if the fixture will be published.
- **SHA256:** 5244fffaa421e0c845cdb0c34f42808f0fbd1d17c00ec971eedc12cbcf530145

## PHI redaction status

Vendor-supplied patient-education sample (per Source URL above). No real PHI present. Verified by reviewer eye before commit.

## Substitution note

Substituted Labsmart LIS sample CBC report for Mayo because no suitable public Mayo-branded sample CBC PDF fixture was used; PDF is a publicly accessible sample report and contains no apparent real patient PHI. PDF cover shows "Labsmart Software Sample Letterhead" rather than Mayo Clinic branding.

## Ground truth

Paired ground-truth JSON: `mayo_cbc-evaluated.json`
Validates against `.planning/specs/PreparsedReport.schema.json` (Draft 2020-12).

## Update protocol

If this fixture is replaced or the PDF is reprocessed:
1. Update the SHA256 above to match the new PDF bytes.
2. Re-derive the paired ground-truth JSON.
3. Re-run `pytest eval/tests/test_fixture_manifests.py`.

---

*Manifest created: 2026-05-13 as part of Phase 1 fixture corpus (Plan 07).*
