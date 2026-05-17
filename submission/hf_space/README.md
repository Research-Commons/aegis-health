---
title: Aegis Health — Offline Medical Safety on Gemma 4
emoji: 🩺
colorFrom: amber
colorTo: green
sdk: gradio
sdk_version: 4.44.0
app_file: app.py
pinned: false
license: apache-2.0
short_description: Browser demo of the Aegis Health Android app. Routes inference to Gemma 4 via OpenRouter; APK runs fine-tuned Gemma 4 E4B fully offline.
---

# Aegis Health — hosted Gemma 4 demo

Browser-friendly companion to the [Aegis Health Android app](https://github.com/Research-Commons/aegis-health) — submission for the Kaggle Gemma 4 Impact Challenge (Health & Sciences track).

The on-device build is a fine-tuned **Gemma 4 E4B** (INT8 W8/A32 quantized, ~7.7 GB) running entirely offline via LiteRT-LM 0.10.2. This Space routes inference to **Gemma 4 via OpenRouter** so judges who cannot sideload the APK can still experience the agentic loop and citation grounding. The four modes — DrugSafe, ConsentReader, HealthPartner, ReportReader — share the same tool layer (six deterministic Python functions) and the same on-device SQLite knowledge base sourced from public-domain US federal data (FDA, NLM, RxNorm, MedlinePlus, USPSTF, NIH DSLD).

## Configuration

The Space needs one secret and (optionally) one variable, both set under **Space → Settings → Variables and secrets**:

| Name | Type | Required | Notes |
|---|---|---|---|
| `OPENROUTER_API_KEY` | **Secret** | yes | Your OpenRouter API key. Sign up at [openrouter.ai](https://openrouter.ai) — pay-per-token, hackathon traffic is well under $1. |
| `OPENROUTER_MODEL` | **Public variable** | no | Defaults to `google/gemma-3-27b-it`. Override to whichever Gemma 4 variant your OpenRouter account has access to — e.g. `google/gemma-4-9b-it` or `google/gemma-4-27b-it`. |

## What you can try

- **DrugSafe** — paste a drug list (try `warfarin, ibuprofen` for a 72-year-old) and watch the agentic loop fire `normalize_drug → check_warnings → lookup_term` against the on-device KB.
- **ConsentReader** — paste a medical consent paragraph; the model simplifies it without crossing into medical advice.
- **HealthPartner** — enter a patient profile; get a USPSTF Grade A/B preventive checklist with citation IDs.
- **ReportReader** — intentionally APK-only. Requires the full Kotlin pre-parsing pipeline; [download the APK](https://github.com/Research-Commons/aegis-health/releases/tag/v1.1.0-demo) to try it.

## Submission links

- [GitHub repository](https://github.com/Research-Commons/aegis-health)
- [Android APK + sideload instructions](https://github.com/Research-Commons/aegis-health/releases/tag/v1.1.0-demo)
- [Fine-tuned model on HF Hub](https://huggingface.co/V1rtucious/gemma4-e4b-toolcalling-litertlm-v2)
- Apache 2.0 licensed; all KB data is public-domain US federal data

⚠️ **Not medical advice.** This is a research demo. Always consult a clinician for medical decisions.
