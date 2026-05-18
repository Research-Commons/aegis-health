# ============================================================================
# AEGIS HEALTH — KAGGLE HOSTED INFERENCE NOTEBOOK
# ============================================================================
# Hosts the fine-tuned Gemma 4 E4B SFT v4 checkpoint
# (V1rtucious/aegis-sft-e4b-merged-v4) on Kaggle's free GPU with a public
# Gradio share URL, for the Kaggle Gemma 4 Impact Hackathon submission.
#
# PREREQUISITES (set in the Kaggle notebook's right-side panel):
# ── Accelerator:  GPU T4 x2  (or P100)
# ── Internet:     ON
# ── Add a Secret named HF_TOKEN with read access to
#    V1rtucious/aegis-sft-e4b-merged-v4
#
# Paste each "# %% CELL N" block into a separate Kaggle notebook cell, OR
# paste the entire file into a single cell — both work.
# ============================================================================


# %% CELL 1 — Install dependencies (~2 min) ----------------------------------
# fmt: off
import sys, subprocess
def _pip_install(*pkgs):
    subprocess.check_call([sys.executable, "-m", "pip", "install", "-q", "-U", *pkgs])
_pip_install(
    # Gemma 4 support lives in current Transformers 5.x. Older 4.x builds may
    # fail with "model_type gemma4 not recognized".
    "transformers>=5.8,<6",
    "accelerate>=1.7,<2",
    "bitsandbytes>=0.46,<1",
    "gradio>=5.49,<6",
    "pydantic>=2,<3",
    "sentencepiece",
    # Pin protobuf to a range that's compatible with transformers + sentencepiece
    # AND that Kaggle's pre-installed Google Cloud / TensorFlow packages don't
    # immediately reject. Unpinned, pip pulls protobuf 7.x which the rest of the
    # Kaggle env hates.
    "protobuf>=5.29,<7",
)
# fmt: on


# %% CELL 2 — Auth + clone the Aegis repo (~30 sec) --------------------------
import os
import stat
import tempfile
from pathlib import Path
from kaggle_secrets import UserSecretsClient

secrets = UserSecretsClient()

# Kaggle Secret with read access to V1rtucious/aegis-sft-e4b-merged-v4
os.environ["HF_TOKEN"] = secrets.get_secret("HF_TOKEN")
os.environ["HUGGING_FACE_HUB_TOKEN"] = os.environ["HF_TOKEN"]

def _optional_secret(name: str) -> str | None:
    try:
        value = secrets.get_secret(name)
    except Exception:
        return None
    return value.strip() if value and value.strip() else None

REPO_DIR = Path("/kaggle/working/aegis-health")
REPO_URL = "https://github.com/Research-Commons/aegis-health.git"
if not REPO_DIR.exists():
    github_token = _optional_secret("GITHUB_TOKEN")
    clone_env = os.environ.copy()
    clone_env["GIT_TERMINAL_PROMPT"] = "0"
    if github_token:
        clone_env["GITHUB_TOKEN"] = github_token
        askpass = Path(tempfile.gettempdir()) / "aegis-git-askpass.sh"
        askpass.write_text(
            "#!/bin/sh\n"
            "case \"$1\" in\n"
            "*Username*) echo \"x-access-token\" ;;\n"
            "*Password*) printf '%s' \"$GITHUB_TOKEN\" ;;\n"
            "*) echo \"\" ;;\n"
            "esac\n"
        )
        askpass.chmod(stat.S_IRWXU)
        clone_env["GIT_ASKPASS"] = str(askpass)
    subprocess.check_call(["git", "clone", REPO_URL, str(REPO_DIR)], env=clone_env)

# Make the bundled tools/ package importable.
if str(REPO_DIR) not in sys.path:
    sys.path.insert(0, str(REPO_DIR))


# %% CELL 3 — Build (or download) the SQLite knowledge base (~3 min first time) -----
# The KB is normally built via `make kb` against FDA / NLM / RxNorm / etc.
# Skipping it would mean tools return empty results, so we run it once.
KB_PATH = REPO_DIR / "kb" / "output" / "aegis_kb.sqlite"
if not KB_PATH.exists():
    # Install only the KB + tool extras. The repo-level `make install` also
    # pulls training/RL/export stacks and can perturb the notebook's model
    # runtime, so keep this Kaggle path narrow.
    subprocess.check_call(
        [sys.executable, "-m", "pip", "install", "-q", "-e", ".[kb,tools]"],
        cwd=str(REPO_DIR),
    )
    subprocess.check_call([sys.executable, "-m", "kb.build"], cwd=str(REPO_DIR))
assert KB_PATH.exists(), f"KB build failed; expected file at {KB_PATH}"
print(f"KB ready at {KB_PATH} ({KB_PATH.stat().st_size / 1e6:.1f} MB)")


# %% CELL 4 — Load tool dispatcher + tool definitions ------------------------
import json
from tools.tools.dispatcher import ToolDispatcher
from demo.backend.tool_dispatcher import run_agentic_loop, extract_tool_calls, format_tool_response

dispatcher = ToolDispatcher(db_path=str(KB_PATH))

with open(REPO_DIR / "tools" / "tools" / "tool_defs.json") as fh:
    TOOL_DEFS: list[dict] = json.load(fh)

# Smoke-test the dispatcher (verifies the KB is loaded correctly).
_smoke = dispatcher.dispatch({"name": "normalize_drug", "arguments": {"name": "warfarin"}})
print(f"Dispatcher smoke test: normalize_drug(warfarin) → {_smoke[:120]}")


# %% CELL 5 — Load the fine-tuned Gemma 4 E4B SFT v4 model (~5 min first run) ----
# Loaded in 8-bit via bitsandbytes — fits comfortably on a single T4 (16 GB VRAM)
# and matches the on-device INT8 W8/A32 narrative ("same weights, cloud-int8 vs
# phone-int8"). FP16 would need T4 x2 with device_map; 4-bit is faster but
# diverges from the on-device quantization story.
import torch
from transformers import AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig

MODEL_ID = "V1rtucious/aegis-sft-e4b-merged-v4"

bnb_config = BitsAndBytesConfig(
    load_in_8bit=True,
    llm_int8_threshold=6.0,
)

tokenizer = AutoTokenizer.from_pretrained(
    MODEL_ID,
    token=os.environ["HF_TOKEN"],
    trust_remote_code=False,
)

model = AutoModelForCausalLM.from_pretrained(
    MODEL_ID,
    quantization_config=bnb_config,
    device_map="auto",
    token=os.environ["HF_TOKEN"],
    trust_remote_code=False,
    dtype=torch.float16,
)
model.eval()
_first_param = next(model.parameters())
_device_map = getattr(model, "hf_device_map", None)
print("Model loaded.")
if _device_map is not None:
    print(f"Device map: {_device_map}")
else:
    print(f"First parameter device: {_first_param.device}")
print(f"First parameter dtype: {_first_param.dtype}")


# %% CELL 6 — Define generate() + the per-mode handlers ----------------------
SYSTEM_PROMPT = (
    "You are Aegis Health, an offline medical-safety assistant running on a "
    "fine-tuned Gemma 4 E4B. You have tools to look up drugs, check interactions, "
    "simplify medical terms, and retrieve preventive-care guidelines, all backed "
    "by a local SQLite knowledge base sourced from FDA, NLM, RxNorm, MedlinePlus, "
    "USPSTF, and NIH DSLD.\n\n"
    "Critical rules:\n"
    "1. Every medical claim must be grounded in a tool result. Use tools liberally.\n"
    "2. If a fact requires data you cannot retrieve, defer to a clinician — do not "
    "fabricate.\n"
    "3. End every response with an AegisResponse-style summary block:\n"
    '   {"flags": [...], "explanation": "...", "defer_to_professional": true|false}\n'
    "4. Never recommend specific dosages or make diagnoses. Use the safety-boundary "
    "phrasing 'I can't make that decision for you — please consult your clinician.'\n"
)

MAX_NEW_TOKENS = 768


def _generate(prompt: str) -> str:
    """Run one forward pass and return only the new tokens (full text)."""
    inputs = tokenizer(prompt, return_tensors="pt").to(model.device)
    with torch.inference_mode():
        outputs = model.generate(
            **inputs,
            max_new_tokens=MAX_NEW_TOKENS,
            do_sample=True,
            temperature=0.3,
            top_p=0.95,
            pad_token_id=tokenizer.eos_token_id,
        )
    new_tokens = outputs[0][inputs["input_ids"].shape[-1]:]
    return tokenizer.decode(new_tokens, skip_special_tokens=False)


def _build_prompt(user_message: str) -> str:
    """Apply Gemma 4's chat template with the tool catalog declared in the system turn."""
    messages = [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": user_message},
    ]
    return tokenizer.apply_chat_template(
        messages,
        tools=TOOL_DEFS,
        tokenize=False,
        add_generation_prompt=True,
    )


def _run_with_trace(user_message: str) -> tuple[str, str]:
    """Custom agentic loop that captures a trace alongside the final response."""
    prompt = _build_prompt(user_message)
    trace_lines: list[str] = [
        "**Model:** `V1rtucious/aegis-sft-e4b-merged-v4` "
        "(fine-tuned Gemma 4 E4B SFT v4, 8-bit via bitsandbytes on Kaggle T4)\n"
    ]
    conversation = prompt

    for turn in range(6):
        response = _generate(conversation)
        tool_calls = extract_tool_calls(response)

        if not tool_calls:
            trace_lines.append(f"\n### Turn {turn + 1} — final response")
            return "\n".join(trace_lines), response.strip()

        trace_lines.append(f"\n### Turn {turn + 1} — tool calls")
        conversation = conversation + response
        for tc in tool_calls:
            result = dispatcher.dispatch(tc)
            preview = result if len(result) <= 240 else result[:240] + "…"
            trace_lines.append(
                f"- `{tc.get('name')}({json.dumps(tc.get('arguments', {}), ensure_ascii=False)})`\n"
                f"  ↳ {preview}"
            )
            conversation += format_tool_response(tc.get("name", ""), result)

    trace_lines.append("\n⚠️ Max turns exceeded.")
    return "\n".join(trace_lines), response.strip()


def run_drugsafe(drugs: str, age: str, conditions: str):
    print(f"[DrugSafe] request drugs={drugs!r} age={age!r} conditions={conditions!r}", flush=True)
    drug_list = [d.strip() for d in drugs.split(",") if d.strip()]
    if not drug_list:
        return "", "Please enter at least one drug name (comma-separated)."
    msg = f"Check these drugs for interactions and warnings: {', '.join(drug_list)}."
    if age.strip():
        msg += f" Patient age: {age.strip()}."
    if conditions.strip():
        msg += f" Conditions: {conditions.strip()}."
    result = _run_with_trace(msg)
    print("[DrugSafe] response ready", flush=True)
    return result


def run_consent(text: str):
    print(f"[ConsentReader] request chars={len(text or '')}", flush=True)
    if not text.strip():
        return "", "Please paste the consent text you'd like simplified."
    msg = (
        "Simplify the following medical consent text. Highlight binding clauses, "
        "define medical terms in plain language, and flag anything the patient should "
        "pay particular attention to. Do not advise the patient whether to sign.\n\n"
        f"{text.strip()}"
    )
    result = _run_with_trace(msg)
    print("[ConsentReader] response ready", flush=True)
    return result


def run_healthpartner(age: str, sex: str, conditions: str, meds: str):
    print(
        f"[HealthPartner] request age={age!r} sex={sex!r} conditions={conditions!r} meds={meds!r}",
        flush=True,
    )
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
    result = _run_with_trace(msg)
    print("[HealthPartner] response ready", flush=True)
    return result


# %% CELL 7 — Smoke test (runs ~30 sec on T4; verify before launching UI) ----
def _busy_status(mode: str) -> str:
    return (
        f"### Running {mode}\n"
        "Gemma 4 is generating and may call the local SQLite-backed tools. "
        "First requests on Kaggle can take 30-90 seconds."
    )


def _ready_status(mode: str) -> str:
    return f"### Done\n{mode} response is ready. Expand the trace to inspect tool calls."


def _error_status(mode: str, exc: Exception) -> str:
    return (
        f"### {mode} error\n"
        f"`{type(exc).__name__}: {exc}`\n\n"
        "Check the Kaggle cell logs under the launch cell for the full traceback."
    )


def ui_drugsafe(drugs: str, age: str, conditions: str):
    yield _busy_status("DrugSafe"), "", ""
    try:
        trace, response = run_drugsafe(drugs, age, conditions)
    except Exception as exc:  # noqa: BLE001 - surface failures in the public demo
        yield _error_status("DrugSafe", exc), "", ""
        raise
    yield _ready_status("DrugSafe"), trace, response


def ui_consent(text: str):
    yield _busy_status("ConsentReader"), "", ""
    try:
        trace, response = run_consent(text)
    except Exception as exc:  # noqa: BLE001
        yield _error_status("ConsentReader", exc), "", ""
        raise
    yield _ready_status("ConsentReader"), trace, response


def ui_healthpartner(age: str, sex: str, conditions: str, meds: str):
    yield _busy_status("HealthPartner"), "", ""
    try:
        trace, response = run_healthpartner(age, sex, conditions, meds)
    except Exception as exc:  # noqa: BLE001
        yield _error_status("HealthPartner", exc), "", ""
        raise
    yield _ready_status("HealthPartner"), trace, response


print("\n=== Smoke test — DrugSafe: warfarin + ibuprofen, 72yo ===")
_trace, _resp = run_drugsafe("warfarin, ibuprofen", "72", "")
print(_trace[-500:])
print("\n--- response ---")
print(_resp[:800])


# %% CELL 8 — Gradio UI + public share URL -----------------------------------
import gradio as gr

INTRO_MD = """
# 🩺 Aegis Health — hosted Gemma 4 demo (Kaggle)

**Offline medical safety assistant powered by Gemma 4.** This Kaggle notebook
serves the **fine-tuned Gemma 4 E4B SFT v4 checkpoint**
([`V1rtucious/aegis-sft-e4b-merged-v4`](https://huggingface.co/V1rtucious/aegis-sft-e4b-merged-v4))
through a public Gradio share URL, so judges who cannot sideload the APK can
still experience the agentic loop and citation grounding.

The on-device Android build runs the INT8 W8/A32 quantized version of the same
model (~7.7 GB `.litertlm`) entirely offline via LiteRT-LM. Cloud serves
8-bit on T4; phone serves INT8 on Snapdragon 8 Gen 2. **Same weights, same tool
layer, same SQLite KB** across both deployments. Every medical claim is cited
(FDA · NLM · RxNorm · MedlinePlus · USPSTF · NIH DSLD) or deferred to a clinician.

> ⚠️ **Not medical advice.** Research demo for the Kaggle Gemma 4 Impact
> Challenge. Always consult a clinician.

**Submission links:** [GitHub](https://github.com/Research-Commons/aegis-health) ·
[Android APK release](https://github.com/Research-Commons/aegis-health/releases/tag/v1.1.0-demo) ·
[Fine-tuned model on HF Hub](https://huggingface.co/V1rtucious/aegis-sft-e4b-merged-v4) ·
Apache 2.0 licensed
"""

REPORTREADER_TAB_MD = """
### 📊 ReportReader

ReportReader (lab report PDF parsing) is intentionally **available in the
on-device Android APK only**. It requires the full Kotlin pre-parsing pipeline
(vendor-specific layouts, per-row range evaluation, pediatric / pregnancy
demographic routing, auto-defer for tumor markers / genetic / pathology-grade
tests) that doesn't translate cleanly to a hosted browser demo.

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
                d_status = gr.Markdown(
                    "### Ready\nEnter medications and click **Check safety**.",
                    label="Status",
                )
                d_resp = gr.Markdown(label="Aegis response", value="")
        with gr.Accordion("🔍 Agentic loop trace (tool calls + KB lookups)", open=False):
            d_trace = gr.Markdown(value="")
        d_btn.click(
            ui_drugsafe,
            [d_drugs, d_age, d_cond],
            [d_status, d_trace, d_resp],
            show_progress="full",
        )

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
                c_status = gr.Markdown(
                    "### Ready\nPaste text and click **Simplify**.",
                    label="Status",
                )
                c_resp = gr.Markdown(label="Aegis response", value="")
        with gr.Accordion("🔍 Agentic loop trace", open=False):
            c_trace = gr.Markdown(value="")
        c_btn.click(
            ui_consent,
            [c_text],
            [c_status, c_trace, c_resp],
            show_progress="full",
        )

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
                h_status = gr.Markdown(
                    "### Ready\nEnter a profile and click **Get checklist**.",
                    label="Status",
                )
                h_resp = gr.Markdown(label="Aegis response", value="")
        with gr.Accordion("🔍 Agentic loop trace", open=False):
            h_trace = gr.Markdown(value="")
        h_btn.click(
            ui_healthpartner,
            [h_age, h_sex, h_cond, h_meds],
            [h_status, h_trace, h_resp],
            show_progress="full",
        )

    with gr.Tab("📊 ReportReader"):
        gr.Markdown(REPORTREADER_TAB_MD)

    gr.Markdown(
        "---\n"
        "*Inference: fine-tuned `aegis-sft-e4b-merged-v4` (8-bit) on Kaggle T4 GPU · "
        "Tool layer + KB: identical to the on-device APK · "
        "Apache 2.0 licensed · "
        "Built for the Kaggle Gemma 4 Impact Challenge (Health & Sciences track).*"
    )


# %% CELL 9 — Launch with public share URL ---------------------------------
# When this cell runs, look in the output for a line like:
#
#   Running on public URL:  https://<random>.gradio.live
#
# That URL is what you paste into the Kaggle Writeup as the live demo link.
# Keep the notebook running — when this cell stops, the share URL dies.
# Gradio share URLs currently last up to about one week, but they are only a
# proxy to this running Kaggle notebook. If the notebook stops, the link dies.
demo.queue(default_concurrency_limit=1)
demo.launch(share=True, server_name="0.0.0.0", server_port=7860, debug=True)
