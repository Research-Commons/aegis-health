# Aegis Health

**Offline, on-device medical safety assistant powered by Gemma 4.**

Aegis Health runs entirely on your Android phone with zero internet connection. It uses a fine-tuned Gemma 4 model with deterministic tool calling against a local knowledge base to provide cited, grounded medical safety information.

## Three Modes

- **DrugSafe** — Scan a pill bottle or type drug names. Get interaction warnings, contraindication flags, and severity-coded safety cards. Every output cites FDA label data.
- **ConsentReader** — Photograph a medical consent form. Get a plain-language summary with tappable medical terms and preserved binding clauses.
- **HealthPartner** — Enter your health profile. Get a personalized prevention checklist grounded in USPSTF Grade A/B recommendations.

## Architecture

```
Camera/Text Input → Gemma 4 (on-device via LiteRT-LM)
                      ↕ native function calling
                  Tool Dispatcher → SQLite Knowledge Base
                      ↓
              JSON Envelope Response → UI Cards
```

## Project Structure

| Directory | Purpose |
|-----------|---------|
| `kb/` | Knowledge base pipeline — scrapes public FDA/NLM/USPSTF data into SQLite |
| `tools/` | Six deterministic tool functions with Pydantic schemas |
| `datagen/` | Synthetic training data generation via teacher models |
| `training/` | SFT pipeline using Unsloth (separable workstream) |
| `rl/` | GRPO reinforcement learning pipeline (separable workstream) |
| `export/` | INT4 quantization via LiteRT-LM |
| `eval/` | Shared 50-case evaluation suite |
| `android/` | Kotlin/Jetpack Compose Android application |
| `demo/` | Web demo fallback (FastAPI + React) |

## Quick Start

```bash
# Install dependencies
pip install -e ".[kb,tools,datagen,eval,dev]"

# Build knowledge base
make kb

# Run tool tests
make tools-test

# Generate training data
make data

# Train (requires GPU)
make train

# Evaluate
make eval
```

## License

Apache 2.0
