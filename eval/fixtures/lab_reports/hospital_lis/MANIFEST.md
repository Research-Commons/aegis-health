# hospital_lis fixture — MANIFEST

**Fixture:** hospital_lipid.pdf
**Panel type:** lipid
**Slot:** hospital_lis

## Provenance

- **Source URL:** https://images.hod.care/web/sample-report/PL0005.pdf
- **Retrieval date:** 2026-05-13
- **License observation:** Publicly accessible sample report PDF from H.O.D / House of Diagnostics. H.O.D's Terms of Use state that website materials/content/logos are owned by the company or licensors and prohibit reproducing, distributing, publicly displaying, or exploiting website materials except as expressly allowed; no open license or redistribution permission was found.
- **Redistribution rationale:** Use only as a small evaluation fixture with attribution/source URL and provenance metadata. Because no permissive license is stated and the terms restrict reproduction/distribution, avoid broad redistribution; keep it limited to internal testing or replace with a synthetic/permissioned equivalent if the fixture will be published.
- **SHA256:** 90ea5fe91b9c16a31a2f472db70a2413248f51963dcac3ac2c5597b5e7fcac4d

## PHI redaction status

Vendor-supplied patient-education sample (per Source URL above). No real PHI present. Verified by reviewer eye before commit.

## Substitution note

Substituted H.O.D / House of Diagnostics sample lipid profile report for the hospital_lis slot because the plan allowed a major hospital/LIS-style public sample when Cerner/Epic patient-portal demo reports were not available; PDF is labeled as a sample report and uses demo patient data.

## Ground truth

Paired ground-truth JSON: `hospital_lipid-evaluated.json`
Validates against `.planning/specs/PreparsedReport.schema.json` (Draft 2020-12).

## Update protocol

If this fixture is replaced or the PDF is reprocessed:
1. Update the SHA256 above to match the new PDF bytes.
2. Re-derive the paired ground-truth JSON.
3. Re-run `pytest eval/tests/test_fixture_manifests.py`.

---

*Manifest created: 2026-05-13 as part of Phase 1 fixture corpus (Plan 07).*
