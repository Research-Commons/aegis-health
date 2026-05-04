# Synthetic Data Generation

Uses a teacher LLM (via OpenRouter) to generate ~1,500 grounded training examples from the KB.

## Prerequisites

```bash
pip install -e ".[datagen]"
playwright install chromium   # for the pill renderer

export OPENROUTER_API_KEY=sk-or-v1-xxx
```

Optionally override the teacher model:

```bash
export TEACHER_MODEL=openrouter/google/gemini-2.5-pro   # default
```

## Generate text data

```bash
make data                       # all three modes
# or a specific mode:
make data-drugsafe
make data-consent
make data-healthpartner
```

Outputs:

- `datagen/output/drugsafe_sft.jsonl`  (~700 examples)
- `datagen/output/consent_sft.jsonl`   (~500 examples)
- `datagen/output/healthpartner_sft.jsonl` (~300 examples)
- `datagen/output/combined_sft.jsonl`  — shuffled, this is what SFT consumes

Every example is a Gemma 4 chat-template conversation (system + user + model with `<|tool_call>` / `<|tool_result>` turns). The SFT notebook converts these to Gemma 4 native `call:name{args}` format via `apply_chat_template(tools=...)`. Rejected outputs (schema-invalid) are logged to `datagen/output/rejected.jsonl`.

## Generate pill-bottle images

```bash
make data-pills
```

Outputs `datagen/output/pill_images/*.png` + `metadata.csv`. Used only if you do the optional vision SFT pass.

## Templates

All prompt templates are in [`datagen/datagen/templates/`](datagen/datagen/templates/) — ten Jinja2 files covering every data category. Edit these to tune the teacher's outputs.
