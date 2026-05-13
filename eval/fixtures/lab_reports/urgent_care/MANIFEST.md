# urgent_care fixture — MANIFEST

**Fixture:** urgent_care_a1c.pdf
**Panel type:** A1C
**Slot:** urgent_care

## Provenance

- **Source URL:** https://www.walkinlab.com/products/download_sample_report/823
- **Retrieval date:** 2026-05-13
- **License observation:** Publicly accessible Walk-In Lab LC sample report for Hemoglobin A1c with eAG. The product page labels it as a sample report and states that reporting format and ranges are subject to change; the PDF footer contains Labcorp copyright text and no open license or redistribution permission was found.
- **Redistribution rationale:** Use only as a small evaluation fixture with attribution/source URL and provenance metadata. Because the PDF is public and presented as a sample report but does not carry a permissive redistribution license, avoid broad redistribution; keep it limited to internal testing or replace with a synthetic/permissioned equivalent if the fixture will be published.
- **SHA256:** 6ee707ec27831dce0e018993d2ce146842965854b0828cd82ee460e226dd4b25

## PHI redaction status

Vendor-supplied patient-education sample (per Source URL above). No real PHI present. Verified by reviewer eye before commit.

## Substitution note

Substituted a Walk-In Lab consumer-direct Hemoglobin A1c with eAG sample report for the urgent_care slot because no suitable public urgent-care-chain lab-result PDF fixture was available; this adds a distinct A1C layout/panel and avoids reusing the byte-identical Labsmart CBC fixture.

## Ground truth

Paired ground-truth JSON: `urgent_care_a1c-evaluated.json`
Validates against `.planning/specs/PreparsedReport.schema.json` (Draft 2020-12).

## Update protocol

If this fixture is replaced or the PDF is reprocessed:
1. Update the SHA256 above to match the new PDF bytes.
2. Re-derive the paired ground-truth JSON.
3. Re-run `pytest eval/tests/test_fixture_manifests.py`.

---

*Manifest created: 2026-05-13 as part of Phase 1 fixture corpus (Plan 07).*
