# Aegis Health — Kaggle hosted-inference notebook

This directory contains everything needed to host the fine-tuned **Gemma 4 E4B SFT v4** checkpoint as a public Gradio demo on a Kaggle notebook, for the Kaggle Gemma 4 Impact Hackathon submission.

The notebook serves [`V1rtucious/aegis-sft-e4b-merged-v4`](https://huggingface.co/V1rtucious/aegis-sft-e4b-merged-v4) (the FP16 merged SFT checkpoint) loaded in 8-bit via bitsandbytes on Kaggle's free T4 GPU. Same fine-tuned weights as the Android APK; cloud 8-bit on T4 vs phone INT8 W8/A32 via LiteRT-LM.

## Why Kaggle and not HF Inference Endpoints

- $0 cost on Kaggle's free T4 GPU tier (vs ~$3–5 for HF Inference Endpoints over the judging window)
- Lives on the same platform Kaggle judges use — they can fork the notebook and re-run it themselves, which is a strong reproducibility signal
- Trade-offs: Kaggle sessions are time-limited and the `gradio.live` URL only works while the notebook process is running. Plan for a manual restart if the session stops.

## One-time setup (~10 min)

1. **Create a Kaggle account** at [kaggle.com/account/login](https://www.kaggle.com/account/login) if you don't already have one. Verify your phone number (required for GPU + internet access in notebooks).

2. **Get a Hugging Face token** with read access to your private repos:
   - [huggingface.co/settings/tokens](https://huggingface.co/settings/tokens) → New token → **Read** scope is enough.
   - Copy the `hf_...` token.

3. **Create the Kaggle notebook**:
   - Go to [kaggle.com/code](https://www.kaggle.com/code) → click **+ Create** → **New Notebook**.
   - Title: `Aegis Health — Gemma 4 Impact Hackathon hosted demo`
   - In the right-hand **Settings** panel:
     - **Accelerator**: `GPU T4 x2` (or `GPU P100`)
     - **Internet**: turn **ON** (required to pull the model + KB data)
     - **Persistence**: `No persistence` (we re-clone the repo each run)

4. **Add the HF token as a Kaggle Secret**:
   - In the notebook editor's right-hand panel → **Add-ons** menu (top bar) → **Secrets**
   - Click **Add a new secret**:
     - Label: `HF_TOKEN`
     - Value: paste your `hf_...` token
   - Make sure it's **attached** to this notebook (toggle on).

   If the GitHub repository is still private while you are testing, add a
   second attached secret:
   - Label: `GITHUB_TOKEN`
   - Value: a GitHub fine-grained token with read access to
     `Research-Commons/aegis-health`

   The final Kaggle submission still needs a public code repository, so remove
   this dependency by making the repo public before final submission.

5. **Paste in the script**:
   - Open `aegis_health_kaggle.py` from this directory.
   - In the Kaggle notebook editor, either:
     - **Single cell**: paste the entire file into one cell, OR
     - **Multiple cells**: split at each `# %% CELL N` marker — one cell per section. Multi-cell is recommended so you can inspect intermediate output.

6. **Save** the notebook (top-right). Use a public Save (Save & Run All — or just Save Version → Quick Save for now).

## Running for hackathon judging (~10 min cold start)

1. **Click "Run All"** in the Kaggle notebook editor.
2. **Watch the cell outputs**:
   - Cell 1 (install deps): ~2 min; installs current Transformers 5.x for Gemma 4 support and lets Transformers choose its compatible `huggingface_hub`
   - Cell 2 (auth + git clone): ~30 sec
   - Cell 3 (build KB): ~3 min on first run; installs only the repo's KB/tool extras, not the full training stack
   - Cell 4 (tool dispatcher): should print `Dispatcher smoke test: normalize_drug(warfarin) → {...}`
   - Cell 5 (load model): ~5 min — downloading + 8-bit quantizing the model is the slow step
   - Cell 6 (define handlers): instant
   - Cell 7 (smoke test): ~30 sec; verifies the full agentic loop works end-to-end
   - Cell 8 (UI definition): instant
   - Cell 9 (launch): outputs the public share URL, looks like:
     ```
     Running on public URL:  https://<random-id>.gradio.live
     ```
3. **Copy that gradio.live URL** — that's your live demo link for the Kaggle Writeup.
4. **Keep the browser tab with the notebook open**. Closing it triggers Kaggle's session timeout countdown (~30 min idle = killed).

## Updating the Kaggle Writeup with the live demo URL

In the Writeup's `## Try It` section (the bottom of `submission/writeup_draft.md`), replace the placeholder live-demo line with:

```markdown
- **Live web demo:** https://<your-id>.gradio.live (hosted via a Kaggle notebook
  serving the same fine-tuned SFT v4 checkpoint; see [the notebook]
  for the full reproducible setup)
```

Where `[the notebook]` links to your Kaggle notebook's public URL (Kaggle gives one to every saved notebook).

## Operational notes

| Issue | Mitigation |
|---|---|
| **Kaggle session stops** | Kaggle notebook sessions are time-limited. If the kernel stops, click Run All again and update the Writeup if the public URL changes. |
| **gradio.live URL stops working** | Gradio share links currently last up to about one week, but they are only a proxy to the running notebook. If Kaggle stops the process, restart Cell 9 after the app is loaded to generate a fresh URL. |
| **30 hours / week free GPU quota** | A continuous 24-hour run burns most of the weekly quota. If you can, only run during active judging windows. |
| **Cold start = ~10 min total** | The first run after a kernel restart re-downloads the model (~5 min) and rebuilds the KB (~3 min). Consider uploading the KB as a Kaggle Dataset attached to the notebook to skip the rebuild on subsequent runs (cuts to ~5 min). |
| **First request after kernel-warm = ~30 sec** | bitsandbytes 8-bit inference compiles CUDA kernels on first call. Subsequent calls are ~3–8 sec for a typical DrugSafe agentic loop. |

## What the notebook proves to judges

- The **same fine-tuned model** that's INT8-quantized for the Android APK runs the hosted demo (just at 8-bit on T4 vs INT8 W8/A32 on Snapdragon)
- The agentic loop is **visible in the UI** — judges can expand the `🔍 Agentic loop trace` accordion to see real `<|tool_call|>` blocks firing against the SQLite KB
- Every response carries an **AegisResponse JSON envelope** with cited flags and a `defer_to_professional` flag
- The notebook is **forkable and reproducible** — any judge can click "Copy & Edit" and run it themselves, with their own HF token

## If Kaggle doesn't work out

Fallback: the GitHub release at [`v1.1.0-demo`](https://github.com/Research-Commons/aegis-health/releases/tag/v1.1.0-demo) already includes the Android APK + sideload instructions. Per Kaggle's submission rules, APK + instructions counts as a valid Live Demo deliverable. The Kaggle hosted version is a quality lift, not a P0 blocker.
