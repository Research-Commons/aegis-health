---
title: Aegis Health — Offline Medical Safety on Gemma 4
emoji: 🩺
colorFrom: yellow
colorTo: green
sdk: gradio
sdk_version: 4.44.0
app_file: app.py
pinned: false
license: apache-2.0
short_description: Offline medical safety on Gemma 4 — hosted browser demo
---

# Aegis Health — hosted Gemma 4 demo

Browser-friendly companion to the [Aegis Health Android app](https://github.com/Research-Commons/aegis-health) — submission for the Kaggle Gemma 4 Impact Challenge (Health & Sciences track).

This Space serves the **fine-tuned Gemma 4 E4B SFT v4 checkpoint** ([`V1rtucious/aegis-sft-e4b-merged-v4`](https://huggingface.co/V1rtucious/aegis-sft-e4b-merged-v4)) through an HF Inference Endpoint, so judges who cannot sideload the APK can still experience the agentic loop and citation grounding. The Android APK runs the INT8 W8/A32 quantized version of the same model (~7.7 GB `.litertlm`) entirely offline via LiteRT-LM 0.10.2 — **same fine-tuned weights**, cloud FP16 vs phone INT8. The four modes — DrugSafe, ConsentReader, HealthPartner, ReportReader — share the same tool layer (six deterministic Python functions) and the same SQLite knowledge base across both deployments, sourced from public-domain US federal data (FDA, NLM, RxNorm, MedlinePlus, USPSTF, NIH DSLD).

## Configuration

The Space needs two values set under **Space → Settings → Variables and secrets**:

| Name | Type | Required | Notes |
|---|---|---|---|
| `INFERENCE_API_KEY` | **Secret** | yes | An HF user access token with read permission. Generate one at [huggingface.co/settings/tokens](https://huggingface.co/settings/tokens). |
| `INFERENCE_BASE_URL` | **Public variable** | yes | Your HF Inference Endpoint root URL (no trailing slash), e.g. `https://abc123.us-east-1.aws.endpoints.huggingface.cloud`. Create the Endpoint at [ui.endpoints.huggingface.co/new](https://ui.endpoints.huggingface.co/new), pointing at `V1rtucious/aegis-sft-e4b-merged-v4`, GPU T4 small, scale-to-zero. |
| `INFERENCE_MODEL` | **Public variable** | no | Defaults to `tgi` (the default model identifier for TGI-backed Endpoints). Only override if your Endpoint's model name differs. |

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
