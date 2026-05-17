"""Aegis Health — Hugging Face Space hosted demo.

Browser-friendly companion to the Aegis Health Android app. Routes inference
to the same fine-tuned SFT v4 checkpoint hosted on a Hugging Face Inference
Endpoint (V1rtucious/aegis-sft-e4b-merged-v4), so judges who cannot sideload
the APK can still experience the agentic loop and citation grounding.

The on-device Android APK runs the INT8 W8/A32 quantized version of the same
model (~7.7 GB .litertlm) entirely offline via LiteRT-LM 0.10.2. This hosted
demo serves the FP16 merged checkpoint via TGI. Same fine-tuned weights,
same tool layer, same SQLite KB, same AegisResponse envelope — only the
quantization and runtime differ.

Environment variables (configured as Space secrets / variables):
- INFERENCE_BASE_URL  (public variable, required)  — your HF Inference
                       Endpoint root URL (no trailing slash). Will look like
                       https://<id>.us-east-1.aws.endpoints.huggingface.cloud
- INFERENCE_API_KEY   (secret, required)           — your HF user access token
                       with read permission on the Endpoint
- INFERENCE_MODEL     (public variable, optional)  — defaults to "tgi"
                       (the TGI server's default model name). Only override
                       if your Endpoint uses a different model identifier.

The Endpoint deploys the fine-tuned SFT v4 checkpoint at
V1rtucious/aegis-sft-e4b-merged-v4 — same model as the Android APK,
served in FP16 (cloud) instead of INT8 W8/A32 (on-device).
"""

from __future__ import annotations

import json
import logging
import os
import sys
from pathlib import Path
from typing import Any

import gradio as gr
from openai import OpenAI

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger("aegis.space")

# Make the bundled tools/ package importable.
ROOT = Path(__file__).parent.resolve()
sys.path.insert(0, str(ROOT))

from tools.tools.dispatcher import ToolDispatcher  # noqa: E402

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
TOOL_DEFS_PATH = ROOT / "tools" / "tools" / "tool_defs.json"
KB_PATH = ROOT / "kb" / "output" / "aegis_kb.sqlite"

INFERENCE_API_KEY = os.environ.get("INFERENCE_API_KEY", "")
INFERENCE_BASE_URL = os.environ.get("INFERENCE_BASE_URL", "").rstrip("/")
INFERENCE_MODEL = os.environ.get("INFERENCE_MODEL", "tgi")

if not INFERENCE_API_KEY:
    logger.warning(
        "INFERENCE_API_KEY is not set; the demo will fail on first request. "
        "Add it as a Secret under Space Settings → Variables and secrets."
    )
if not INFERENCE_BASE_URL:
    logger.warning(
        "INFERENCE_BASE_URL is not set; the demo will fail on first request. "
        "Add it as a public Variable pointing at your HF Inference Endpoint."
    )

with open(TOOL_DEFS_PATH) as fh:
    TOOL_DEFS: list[dict] = json.load(fh)

dispatcher = ToolDispatcher(db_path=str(KB_PATH))

# HF Inference Endpoints expose an OpenAI-compatible API at <endpoint>/v1.
client = OpenAI(
    base_url=f"{INFERENCE_BASE_URL}/v1" if INFERENCE_BASE_URL else "http://missing",
    api_key=INFERENCE_API_KEY or "missing",
)

# ---------------------------------------------------------------------------
# System prompt — mirrors the Android app's mode-agnostic preamble
# ---------------------------------------------------------------------------
SYSTEM_PROMPT = (
    "You are Aegis Health, an offline medical-safety assistant running on Gemma 4. "
    "You have tools to look up drugs, check interactions, simplify medical terms, "
    "and retrieve preventive-care guidelines, all backed by a local SQLite knowledge "
    "base sourced from FDA, NLM, RxNorm, MedlinePlus, USPSTF, and NIH DSLD.\n\n"
    "Critical rules:\n"
    "1. Every medical claim must be grounded in a tool result. Use tools liberally.\n"
    "2. If a fact requires data you cannot retrieve, defer to a clinician — do not "
    "fabricate.\n"
    "3. End every response with an AegisResponse-style summary block in this exact "
    "JSON shape:\n"
    "```json\n"
    '{"flags": [{"severity": "MINOR|MODERATE|MAJOR|CRITICAL", "description": "...", '
    '"citation": "..."}], "explanation": "...", "defer_to_professional": true|false}\n'
    "```\n"
    "4. Never recommend specific dosages or make diagnoses. Use the safety-boundary "
    "phrasing 'I can't make that decision for you — please consult your clinician.'\n"
)

# ---------------------------------------------------------------------------
# Agentic loop
# ---------------------------------------------------------------------------
MAX_TURNS = 6


def run_agentic_loop(user_message: str) -> tuple[str, str]:
    """Run the agentic loop. Returns (trace_markdown, final_response)."""
    messages: list[dict[str, Any]] = [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": user_message},
    ]
    trace_lines: list[str] = [
        "**Model:** `V1rtucious/aegis-sft-e4b-merged-v4` "
        "(fine-tuned Gemma 4 E4B SFT v4, FP16 via HF Inference Endpoint)\n"
    ]

    for turn in range(MAX_TURNS):
        try:
            response = client.chat.completions.create(
                model=INFERENCE_MODEL,
                messages=messages,
                tools=TOOL_DEFS,
                tool_choice="auto",
                temperature=0.3,
                max_tokens=1024,
            )
        except Exception as exc:  # noqa: BLE001 — surface to the UI
            logger.exception("OpenRouter call failed")
            trace_lines.append(f"\n❌ **Inference error:** `{exc}`")
            return "\n".join(trace_lines), (
                "An error occurred while calling the inference endpoint. "
                "Check that `INFERENCE_API_KEY` and `INFERENCE_BASE_URL` are set "
                "in Space Settings → Variables and secrets, and that the "
                "endpoint is running (HF Inference Endpoints scale to zero "
                "after idle; the first request after a cold start can take "
                "~30 seconds to warm the GPU)."
            )

        msg = response.choices[0].message

        # Append the assistant message (preserves tool_calls for the next round)
        messages.append(
            {
                "role": "assistant",
                "content": msg.content or "",
                "tool_calls": [
                    {
                        "id": tc.id,
                        "type": "function",
                        "function": {
                            "name": tc.function.name,
                            "arguments": tc.function.arguments,
                        },
                    }
                    for tc in (msg.tool_calls or [])
                ],
            }
        )

        if msg.tool_calls:
            trace_lines.append(f"\n### Turn {turn + 1} — tool calls")
            for tc in msg.tool_calls:
                try:
                    args = json.loads(tc.function.arguments) if tc.function.arguments else {}
                except json.JSONDecodeError:
                    args = {}
                logger.info("Dispatch %s(%s)", tc.function.name, args)
                result_json = dispatcher.dispatch(
                    {"name": tc.function.name, "arguments": args}
                )
                preview = result_json
                if len(preview) > 240:
                    preview = preview[:240] + "…"
                trace_lines.append(
                    f"- `{tc.function.name}({json.dumps(args, ensure_ascii=False)})`\n"
                    f"  ↳ {preview}"
                )
                messages.append(
                    {
                        "role": "tool",
                        "tool_call_id": tc.id,
                        "name": tc.function.name,
                        "content": result_json,
                    }
                )
            continue

        # No tool calls → the model is done
        trace_lines.append(f"\n### Turn {turn + 1} — final response")
        return "\n".join(trace_lines), (msg.content or "").strip() or "(empty response)"

    trace_lines.append("\n⚠️ Max turns exceeded; returning last assistant message.")
    return "\n".join(trace_lines), messages[-1].get("content", "") or "(no content)"


# ---------------------------------------------------------------------------
# Per-mode handlers
# ---------------------------------------------------------------------------
def run_drugsafe(drugs: str, age: str, conditions: str) -> tuple[str, str]:
    drug_list = [d.strip() for d in drugs.split(",") if d.strip()]
    if not drug_list:
        return "", "Please enter at least one drug name (comma-separated)."
    msg = f"Check these drugs for interactions and warnings: {', '.join(drug_list)}."
    if age.strip():
        msg += f" Patient age: {age.strip()}."
    if conditions.strip():
        msg += f" Conditions: {conditions.strip()}."
    return run_agentic_loop(msg)


def run_consent(text: str) -> tuple[str, str]:
    if not text.strip():
        return "", "Please paste the consent text you'd like simplified."
    msg = (
        "Simplify the following medical consent text. Highlight binding clauses, "
        "define medical terms in plain language, and flag anything the patient "
        "should pay particular attention to. Do not advise the patient whether "
        "to sign.\n\n"
        f"{text.strip()}"
    )
    return run_agentic_loop(msg)


def run_healthpartner(age: str, sex: str, conditions: str, meds: str) -> tuple[str, str]:
    if not age.strip():
        return "", "Please enter the patient age."
    msg = f"Patient profile — Age: {age.strip()}, Sex: {sex}."
    if conditions.strip():
        msg += f" Conditions: {conditions.strip()}."
    if meds.strip():
        msg += f" Current medications: {meds.strip()}."
    msg += (
        " Provide a personalized prevention checklist using USPSTF Grade A and B "
        "recommendations. Cite the recommendation ID for each item. Note what "
        "demographic information is missing."
    )
    return run_agentic_loop(msg)


# ---------------------------------------------------------------------------
# Gradio UI
# ---------------------------------------------------------------------------
INTRO_MD = """
# 🩺 Aegis Health — hosted demo

**Offline medical safety assistant powered by Gemma 4.** This Space is the
browser-friendly companion to the
[Android app](https://github.com/Research-Commons/aegis-health) and serves
the **same fine-tuned Gemma 4 E4B SFT v4 checkpoint** (V1rtucious/aegis-sft-e4b-merged-v4)
through an HF Inference Endpoint, so judges who cannot sideload the APK can
still experience the agentic loop and citation grounding.

The on-device build is the INT8 W8/A32 quantized version of the same model
(~7.7 GB `.litertlm`) running entirely offline via LiteRT-LM. Cloud serves
FP16; phone serves INT8. **Same weights, same tool layer, same SQLite KB**
across both deployments. Every medical claim is cited (FDA · NLM · RxNorm ·
MedlinePlus · USPSTF · NIH DSLD) or deferred to a clinician.

> ⚠️ **Not medical advice.** Aegis Health is a research demo for the Kaggle
> Gemma 4 Impact Challenge. Always consult a clinician for medical decisions.

**Submission links:** [GitHub](https://github.com/Research-Commons/aegis-health) ·
[Android APK release](https://github.com/Research-Commons/aegis-health/releases/tag/v1.1.0-demo) ·
[Fine-tuned model on HF Hub](https://huggingface.co/V1rtucious/gemma4-e4b-toolcalling-litertlm-v2) ·
Apache 2.0 licensed
"""

REPORTREADER_TAB_MD = """
### 📊 ReportReader

ReportReader (lab report PDF parsing) is intentionally **available in the
on-device Android APK only**. It requires the full Kotlin pre-parsing
pipeline (vendor-specific layouts, per-row range evaluation, pediatric /
pregnancy demographic routing, auto-defer for tumor markers / genetic /
pathology-grade tests) that doesn't translate cleanly to a hosted browser
demo without losing the strict safety contract.

**To try ReportReader:** [download the APK + sideload instructions →](https://github.com/Research-Commons/aegis-health/releases/tag/v1.1.0-demo)
"""


with gr.Blocks(
    title="Aegis Health — Gemma 4 Impact Hackathon demo",
    theme=gr.themes.Soft(primary_hue="amber", secondary_hue="emerald", neutral_hue="stone"),
) as demo:
    gr.Markdown(INTRO_MD)

    with gr.Tab("💊 DrugSafe"):
        gr.Markdown(
            "Check drug interactions, contraindications, and population-specific "
            "warnings. Try the demo example: `warfarin, ibuprofen` for a 72-year-old."
        )
        with gr.Row():
            with gr.Column(scale=2):
                d_drugs = gr.Textbox(
                    label="Drugs (comma-separated)",
                    value="warfarin, ibuprofen",
                    placeholder="e.g. warfarin, ibuprofen, atorvastatin",
                )
                d_age = gr.Textbox(label="Patient age (years)", value="72")
                d_cond = gr.Textbox(
                    label="Conditions (optional, comma-separated)",
                    placeholder="e.g. hypertension, kidney disease",
                )
                d_btn = gr.Button("Check safety", variant="primary")
            with gr.Column(scale=3):
                d_resp = gr.Markdown(label="Aegis response", value="")
        with gr.Accordion("🔍 Agentic loop trace (tool calls + KB lookups)", open=False):
            d_trace = gr.Markdown(value="")
        d_btn.click(run_drugsafe, [d_drugs, d_age, d_cond], [d_trace, d_resp])

    with gr.Tab("📄 ConsentReader"):
        gr.Markdown(
            "Simplify a medical consent paragraph to ~8th-grade reading level while "
            "preserving legally binding clauses. The model will not advise you "
            "whether to sign — that decision stays with you and your clinician."
        )
        with gr.Row():
            with gr.Column(scale=2):
                c_text = gr.Textbox(
                    label="Consent text",
                    lines=10,
                    placeholder="Paste a consent paragraph here…",
                )
                c_btn = gr.Button("Simplify", variant="primary")
            with gr.Column(scale=3):
                c_resp = gr.Markdown(label="Aegis response", value="")
        with gr.Accordion("🔍 Agentic loop trace", open=False):
            c_trace = gr.Markdown(value="")
        c_btn.click(run_consent, [c_text], [c_trace, c_resp])

    with gr.Tab("🩺 HealthPartner"):
        gr.Markdown(
            "Personalized preventive-care checklist grounded in USPSTF Grade A and B "
            "recommendations. Try `45 / male` for a sample run."
        )
        with gr.Row():
            with gr.Column(scale=2):
                h_age = gr.Textbox(label="Age (years)", value="45")
                h_sex = gr.Dropdown(
                    label="Sex",
                    choices=["male", "female"],
                    value="male",
                )
                h_cond = gr.Textbox(label="Conditions (optional)")
                h_meds = gr.Textbox(label="Current medications (optional)")
                h_btn = gr.Button("Get checklist", variant="primary")
            with gr.Column(scale=3):
                h_resp = gr.Markdown(label="Aegis response", value="")
        with gr.Accordion("🔍 Agentic loop trace", open=False):
            h_trace = gr.Markdown(value="")
        h_btn.click(run_healthpartner, [h_age, h_sex, h_cond, h_meds], [h_trace, h_resp])

    with gr.Tab("📊 ReportReader"):
        gr.Markdown(REPORTREADER_TAB_MD)

    gr.Markdown(
        "---\n"
        "*Inference: fine-tuned `aegis-sft-e4b-merged-v4` (FP16) via HF Inference Endpoint · "
        "Tool layer + KB: identical to the on-device APK · "
        "Apache 2.0 licensed · "
        "Built for the Kaggle Gemma 4 Impact Challenge (Health & Sciences track).*"
    )


if __name__ == "__main__":
    demo.launch()
