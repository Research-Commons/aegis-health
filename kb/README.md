# Knowledge Base

Builds a single SQLite file (`kb/output/aegis_kb.sqlite`) containing all ground-truth medical data used by every downstream module.

All sources are US federal / public domain: openFDA, DailyMed SPL, RxNorm, NIH DSLD, MedlinePlus, USPSTF.

## Build

```bash
make kb            # from repo root
# or
python -m kb.build
```

Outputs `kb/output/aegis_kb.sqlite` (~5-10 MB).

## Validate

```bash
make kb-validate
# or
python -m kb.validate
```

Runs 9 integrity checks: foreign-key consistency, severity ranges, null rxcui, orphan references.

## Schema

See [`kb/kb/schema.sql`](kb/kb/schema.sql). Eight tables:

| Table | Rows | Source |
|-------|------|--------|
| `drugs` | ~260 | openFDA |
| `drug_ingredients` | ~400 | DailyMed SPL |
| `interactions` | ~800 | openFDA `drug_interactions` |
| `warnings` | ~500 | openFDA `warnings` + `contraindications` |
| `rxnorm_lookup` | ~2000 | RxNorm REST API |
| `supplements` | ~100 | NIH DSLD |
| `terms` | ~1000 | MedlinePlus |
| `guidelines` | ~50 | USPSTF |

## Downstream consumers

- `tools/` queries this DB for every tool call
- `datagen/` pulls real drug data to ground teacher-model prompts
- `android/` ships the DB as an asset (copied to encrypted storage via SQLCipher)
- `demo/backend/` opens this DB directly
