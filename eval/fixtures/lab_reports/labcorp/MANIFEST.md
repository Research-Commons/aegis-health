# labcorp fixture — MANIFEST

**Fixture:** labcorp_lipid_panel.pdf
**Panel type:** lipid
**Slot:** labcorp

## Provenance

- **Source URL:** https://www.accesalabs.com/downloads/qst/Lipid-Panel-Test-Results.pdf?utm_source=chatgpt.com
- **Retrieval date:** 2026-05-13
- **License observation:** AccesaLabs product page provides a public "Download a sample results report" link for the Lipid Panel Test.
- **Redistribution rationale:** Publicly available sample report (not a portal-only patient record); contains no real patient PHI; used here as a small evaluation fixture.
- **SHA256:** 2b865ccb0a24586af261304ccf2423fcdea307710d1ff49751cdf572c3e10801

## PHI redaction status

Vendor-supplied patient-education sample (per Source URL above). No real PHI present. Verified by reviewer eye before commit.

## Substitution note

Original LabCorp source was unavailable; substituted public AccesaLabs lipid panel sample report. Use only as a small evaluation fixture; revisit license clearance before any public redistribution beyond this repo.

## Ground truth

Paired ground-truth JSON: `labcorp_lipid_panel-evaluated.json`
Validates against `.planning/specs/PreparsedReport.schema.json` (Draft 2020-12).

## Update protocol

If this fixture is replaced or the PDF is reprocessed:
1. Update the SHA256 above to match the new PDF bytes.
2. Re-derive the paired ground-truth JSON.
3. Re-run `pytest eval/tests/test_fixture_manifests.py`.

---

*Manifest created: 2026-05-13 as part of Phase 1 fixture corpus (Plan 07).*
