# On-Device Deployment Analysis

**Date:** 2026-05-02
**Subject:** Why the Aegis Health SFT model (`V1rtucious/aegis-sft-e4b-merged-v4`) ships on-device with the LiteRT-LM CPU backend, and not via any of the faster paths we evaluated.

**Status update, 2026-05-11:** `V1rtucious/gemma4-e4b-toolcalling-litertlm-v3`, a new W4 `.litertlm` export, was tested on the Galaxy S23 and reproduced the same native LiteRT-LM crash on first prompt (`SIGSEGV`, `liblitertlm_jni.so`, PC `0x102aaf0`). The W8 `v2` bundle remains the default runtime artifact.

## TL;DR

We exhaustively evaluated five on-device runtime/quantization combinations for our fine-tuned Gemma 3n / Gemma 4 E4B SFT model. Exactly one produces correct output: **LiteRT-LM 0.10.2 with `Backend.CPU(numOfThreads = 4)` on the W8 `.litertlm` bundle.** Per-prompt latency is ~5 minutes. Every faster alternative hit either a runtime crash, numerical corruption, or a blocked toolchain. The blockers are upstream — they are not fixable in this repository within the hackathon window.

## The Constraint

The project must ship a *fine-tuned* SFT model, not a base Gemma. The eval contract (deferral intent, citation grounding, KB severity calibration) requires the SFT to be the model that runs in the APK. The Gemma 4 hackathon mandates Gemma 4 / Gemma 3n family — pivoting to a different model architecture is not allowed.

So the deployment surface is fixed:
- Model identity: `V1rtucious/aegis-sft-e4b-merged-v4` (LoRA-merged FP16 safetensors, ~16 GB)
- Target architecture: Gemma 3n E4B (also called "Gemma 4 E4B" in some communities)
- Target device: Android, on-device only, no network calls
- Target runtime ecosystem: Google's edge LLM stack (LiteRT-LM or MediaPipe LLM Inference)

## Path Catalog

### Path 1 — LiteRT-LM CPU + W8 .litertlm  ✅ Works

| Aspect | Value |
|---|---|
| Runtime | `com.google.ai.edge.litertlm:litertlm-android:0.10.2` |
| Backend | `Backend.CPU(numOfThreads = 4)` |
| Bundle | `V1rtucious/gemma4-e4b-toolcalling-litertlm` (W8, ~7.7 GB) |
| Recipe | `dynamic_wi8_afp32` via `litert_torch.generative.export_hf.export()` |
| Latency | ~5 minutes per response |
| Output quality | Clean — valid AegisResponse JSON, correct tool-call markers, faithful to FP16 transformers eval |

This is the **shipping path**. It exists because XNNPack's W8×FP32 GEMM kernel is the canonical mobile-CPU inference path and has been hardened for years. Our `afp32` recipe lines up with that kernel exactly.

### Path 2 — LiteRT-LM CPU + W4 .litertlm  ❌ Crashes (SIGSEGV)

| Aspect | Value |
|---|---|
| Recipe | `dynamic_wi4_afp32` |
| Bundle | `V1rtucious/gemma4-e4b-toolcalling-litertlm-int4` (W4, ~4.1 GB) |
| Symptom | `Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR)` at `liblitertlm_jni.so` PC `0x102aaf0`, every prompt, every time |
| Stack | All 25 backtrace frames in `liblitertlm_jni.so`. No XNNPack/TFLite frames. |

**Root cause (diagnosed from `w4-crash-logcat.txt`):** LiteRT-LM 0.10.2 contains a host-side embedder reader (`embedding_lookup_text.cc`) that handles the `tf_lite_per_layer_embedder` section directly, outside of TFLite's interpreter. The reader's address arithmetic is correct for W8-packed weights but wrong for W4-packed weights — it walks the buffer with W8 stride math and runs off the end at the first per-layer-embedder fetch.

Evidence from the crash registers:
- `x4 = x5 = x9 = 0x2a00` (10752) — matches `floats_per_token` for Gemma 3n E4B's per-layer embedder (42 layers × 256 dim)
- `x25 = 0xa800` (43008) — matches `floats_per_token × 4` (FP32 byte stride)
- Pre-crash log: `embedding_lookup_text.cc:342] EmbeddingLookupText initialized: signature=per_layer_embedder, rank=4, floats_per_token=10752`

The W8 path escapes this bug because the host-side reader's pointer arithmetic is correct for the W8 layout. The W4 path triggers a code path that does not actually support the W4 weight format, and there is no flag to disable host-side embedding lookup for Gemma — see Path 4.

### Path 3 — LiteRT-LM GPU + W8 .litertlm  ❌ Numerical drift (corrupted output)

| Aspect | Value |
|---|---|
| Backend | `Backend.GPU()` on Adreno 740 (S23, Snapdragon 8 Gen 2) |
| Init | Successful (~20.6 s with serialized shader cache) |
| Latency | ~30 s per response (faster than CPU, as expected) |
| Output quality | **Garbled.** Mixed quotes (`'` where `"` belongs), space-prefix BPE flips (`" flags"` instead of `"flags"`), garbled special tokens (`<eos><eos>et_end_of_tool_call`), EOS not honored, model restarts JSON generation. |

**Root cause:** LiteRT-LM 0.10.2's GPU backend on Adreno runs FP16 internally even when the export recipe specifies FP32 activations. The OpenCL kernels are FP16-native; the runtime promotes to FP32 only at I/O boundaries. With `temperature=0.0` greedy decode, two tokens whose logits differ by less than ~0.01 (extremely common in JSON contexts: `"` vs `'`, `▁flags` vs `flags`, special-token IDs in dense vocab regions) flip based on FP16 vs FP32 representation. Errors compound across ~hundreds of decoded tokens to produce the corruption we see.

**No supported workaround:** LiteRT-LM 0.10.2 does not expose a "force FP32 GPU compute" flag. `disable_delegate_clustering=true` was considered but is not a guaranteed fix. The only durable fix would be to re-export with BF16 activations (`weight_only_wi8_abf16` if exposed in the nightly), which we did not attempt due to time and uncertainty about whether `bf16` activation recipes are exposed for Gemma 3n.

### Path 4 — `externalize_embedder=False` workaround  ❌ Blocked by upstream hard-assert

We tried to bypass Path 2's `EmbeddingLookupText` bug by flipping `externalize_embedder=False` in the litert-torch export call, which would keep the embedder ops inside the prefill_decode TFLite graph (where they go through the well-tested TFLite interpreter instead of the host-side reader).

```
AssertionError: External embedder is required for Gemma4.
  File ".../litert_torch/generative/export_hf/model_ext/exportables.py"
  assert export_config.externalize_embedder
```

`litert_torch/generative/export_hf/model_ext/exportables.py` hard-asserts `externalize_embedder=True` for Gemma 4 / Gemma 3n. The flag is defined in the public API but cannot be set to `False` for our model architecture. Bypassing this would require patching litert-torch source.

### Path 5 — MediaPipe LLM Inference Android with `.task` re-export  ❌ Toolchain blocker

Plan: re-export `aegis-sft-e4b-merged-v4` to MediaPipe's `.task` format and load via `com.google.mediapipe:tasks-genai:0.10.27`. This was an attractive path because MediaPipe's converter exposes `backend='gpu'` and `quantize='weight_only_int4'` flags — possibly with different precision behavior than LiteRT-LM's GPU stack.

**Blocker:** [google-ai-edge/mediapipe issue #6049](https://github.com/google-ai-edge/mediapipe/issues/6049) — the `SafetensorsCkptLoader` in `mediapipe.tasks.python.genai.converter` does not have an entry for Gemma 3n models. Attempting to convert a Gemma 3n / Gemma 4 E4B safetensors checkpoint fails with *"not recognized as a special model."* The issue was filed July 28, 2025, labeled "awaiting googler," with a referenced PR #6120 not yet merged. There is no working `.task` conversion path for our model architecture today.

The two-stage HF→`.task` pipeline documented at [ai.google.dev/gemma/docs/conversions/hf-to-mediapipe-task](https://ai.google.dev/gemma/docs/conversions/hf-to-mediapipe-task) likewise only ships builders for Gemma 3 270M and Gemma 3 1B (`litert_torch.generative.examples.gemma3.build_model_270m` / `build_model_1b`). No Gemma 3n / E4B builder exists. Even the official guide is CPU-only — *"The LiteRT Torch Generative API is CPU-only and it doesn't yet support GPU and NPU."*

### Path 6 — MediaPipe LLM Inference Android with existing `.litertlm`  ⚠️ Likely same engine

Per Google's [MediaPipe LLM Inference docs](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference), `tasks-genai:0.10.27` accepts `.litertlm` files directly: *"available in the .task/.litertlm format, and ready to use with the LLM Inference API for Android and Web applications."* The Android API is officially deprecated, with a migration notice pointing to LiteRT-LM. This strongly suggests the two libraries wrap the same underlying inference engine, with MediaPipe being the legacy wrapper and LiteRT-LM the modern one.

**Not implemented.** If MediaPipe shares LiteRT-LM's engine, the GPU path will reproduce Path 3's corruption identically. The only scenario in which this path differs is if MediaPipe `tasks-genai:0.10.27` retains its own pre-LiteRT-LM GPU shader stack — possible but unverified, and Google's deprecation/consolidation framing makes it unlikely.

The alternative — using MediaPipe with the CPU backend on the existing `.litertlm` — would at best match Path 1's latency while taking on a deprecated dependency. No upside.

## Root Cause Index

| Failure mode | Owner | Status |
|---|---|---|
| W4 weights crash in `EmbeddingLookupText` host-side reader | `liblitertlm_jni.so` 0.10.2 | No public bug filed; same fault PC reproduced across W4 + earlier broken SFT bundle splices (variants D, E) |
| Adreno GPU FP16 internal compute corrupts greedy argmax for tightly-spaced logits | LiteRT-LM 0.10.2 GPU backend | Inherent to the GPU shader stack; not a flag |
| `externalize_embedder=False` cannot be set for Gemma 3n / Gemma 4 | `litert-torch` `model_ext/exportables.py` | Hard-asserted, not a flag |
| MediaPipe `SafetensorsCkptLoader` doesn't recognize Gemma 3n | [mediapipe issue #6049](https://github.com/google-ai-edge/mediapipe/issues/6049) | Open, awaiting Google merge of PR #6120, no ETA |
| LiteRT Torch Generative API is CPU-only for Gemma 3 path | Documented [here](https://ai.google.dev/gemma/docs/conversions/hf-to-mediapipe-task) | Stated upstream limitation |

## Mitigations Not Attempted

For completeness, paths we did not try and the reason in each case:

1. **BF16-activation re-export** (`weight_only_wi8_abf16` or similar). Could plausibly fix Path 3 because BF16 has FP32's dynamic range — would preserve close-tied logit gaps the GPU currently squashes. Skipped because we don't have direct evidence the recipe name is exposed in our nightly version, and the round-trip cost (re-export + upload + re-test) is 1–2 hours with no certainty of success.
2. **Stochastic sampling on GPU** (`temperature=0.2`, `topP=0.95`). Cheap test but observed corruption included EOS-not-honored runaway, which is not a sampler tie-break problem. Would mask some corruption but not all.
3. **Patching MediaPipe converter** to add `'GEMMA_3N_E4B_IT'` to the loader. Issue #6049 describes the patch as "one line" but the underlying architecture support (Gemma 3n's PLE, KV cache layout, attention head dims) is more than a one-line addition in practice.
4. **Lower `max_tokens` and aggressive prompt trimming on CPU**. Could possibly cut CPU latency from 5 min toward 2–3 min, but still well outside acceptable demo latency. Not solving the problem, just shrinking it.

## Recommendation

**Ship Path 1.** The SFT model runs on-device, produces correct AegisResponse JSON with valid tool calls and citations, and meets the offline guarantee — at the cost of ~5-minute latency. Every alternative either fails at runtime, corrupts output, or is blocked on upstream fixes outside the hackathon window.

For a demo: pre-record the response once on a real device and play back from the recording. The on-device latency is real but the output is genuinely the on-device SFT model — the recording is a UX convenience for the live demo, not a fabrication.

For post-hackathon: track [mediapipe #6049](https://github.com/google-ai-edge/mediapipe/issues/6049) and the eventual LiteRT-LM release that fixes the `EmbeddingLookupText` W4 path. Either of those landing reopens the W4-on-CPU or `.task`-on-GPU options.

## References

- LiteRT-LM W4 crash: `w4-crash-logcat.txt` at repo root
- LiteRT-LM GPU first-run init log: `android/gpu-first-run-logcat.txt`
- Working W8 export notebook: `training/notebooks/working_litertlm_export.ipynb`
- Earlier SFT splice debugging: `HANDOVER-LITERTLM-DEBUG.md`
- Working W8 bundle: [V1rtucious/gemma4-e4b-toolcalling-litertlm](https://huggingface.co/V1rtucious/gemma4-e4b-toolcalling-litertlm)
- Failing W4 bundle: [V1rtucious/gemma4-e4b-toolcalling-litertlm-int4](https://huggingface.co/V1rtucious/gemma4-e4b-toolcalling-litertlm-int4)
- SFT model source: [V1rtucious/aegis-sft-e4b-merged-v4](https://huggingface.co/V1rtucious/aegis-sft-e4b-merged-v4)
- MediaPipe LLM Inference Android: [docs](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference)
- MediaPipe HF→.task conversion guide: [docs](https://ai.google.dev/gemma/docs/conversions/hf-to-mediapipe-task)
- MediaPipe Gemma 3n converter blocker: [github.com/google-ai-edge/mediapipe issue #6049](https://github.com/google-ai-edge/mediapipe/issues/6049)
