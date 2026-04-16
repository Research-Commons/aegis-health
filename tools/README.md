# Tools

Six deterministic Python functions that query the KB and return structured JSON. These are the ground-truth implementations; Kotlin ports live in [`android/app/src/main/java/com/aegis/health/tools/`](../android/app/src/main/java/com/aegis/health/tools/).

## Functions

| Tool | Purpose |
|------|---------|
| `normalize_drug(name)` | Brand/generic resolution via RxNorm |
| `decompose_product(product_name)` | Multi-ingredient decomposition (e.g. NyQuil) |
| `get_drug_info(rxcui)` | Full record with class, category, warnings summary |
| `check_warnings(drug_list, age, conditions)` | Core safety engine — drug×drug, drug×condition, population checks |
| `lookup_term(term)` | Plain-language definition from MedlinePlus |
| `get_guideline(age, sex, conditions)` | USPSTF recommendation matching |

Every tool returns an `AegisResponse` envelope (see [`tools/tools/schemas.py`](tools/tools/schemas.py)):

```python
class AegisResponse(BaseModel):
    flags: list[Flag]
    citations: list[Citation]
    confidence: float
    defer_to_professional: bool
    explanation: str
```

## Gemma 4 function-calling

Tool definitions for the model are in [`tools/tools/tool_defs.json`](tools/tools/tool_defs.json) in OpenAI function-calling format. The datagen harness, SFT chat templates, and the Android app all read from this single file.

## Test

```bash
make tools-test
# or
pytest tools/tests/ -v
```

Anchor-case integration tests are auto-skipped if `kb/output/aegis_kb.sqlite` doesn't exist yet — build the KB first (`make kb`) to run them.
