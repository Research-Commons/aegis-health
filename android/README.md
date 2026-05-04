# Aegis Health Android

Offline, on-device medical safety assistant powered by the Gemma 4 E4B tool-calling LiteRT-LM artifact.

## Current Model Artifact

- Hugging Face repo: https://huggingface.co/V1rtucious/gemma4-e4b-toolcalling-litertlm-v2
- Repo filename: `model.litertlm`
- Device filename: `aegis_model.litertlm` (renamed on push)
- Device path: `/sdcard/Android/data/com.aegis.health/files/aegis_model.litertlm`

```powershell
huggingface-cli download V1rtucious/gemma4-e4b-toolcalling-litertlm-v2 `
  model.litertlm `
  --local-dir .\downloads

adb push .\downloads\model.litertlm `
  /sdcard/Android/data/com.aegis.health/files/aegis_model.litertlm
```

LiteRT-LM reads the bundle directly from `getExternalFilesDir()` via mmap. The app does not copy the model into internal storage.

## Build And Run

Prerequisites:

- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android SDK 34
- API 26+ device with an OpenCL-capable Adreno, Mali, or Xclipse GPU

Place the built SQLite knowledge base in assets:

```text
android/app/src/main/assets/aegis_kb.sqlite
```

Build it from the repo root:

```powershell
make kb
Copy-Item kb\output\aegis_kb.sqlite android\app\src\main\assets\
```

Build the APK:

```powershell
cd android
.\gradlew.bat assembleDebug
```

## Runtime Contract

The Android runtime is aligned to the eval/training contract:

- Tool calls use Gemma 4 native syntax: `<|tool_call>call:name{args}<tool_call|>`.
- The app dispatches real Kotlin tool results and returns `<|tool_response>response:name{...}<tool_response|>`.
- The model must then produce final JSON only, with no markdown or prose outside the JSON object.
- Final JSON key order starts with `confidence`, then `defer_to_professional`, `flags`, `citations`, and `explanation`.
- DrugSafe final answers must include non-empty citations.
- DrugSafe should prefer one `check_warnings` call with the full drug list, and should not repeatedly call `get_drug_info`.
- The Android parser extracts the first balanced JSON object, ignores trailing `<turn|>` or junk, and rejects leftover `<|tool_call>` fragments in final answers.
- The agent loop is capped at 6 turns.

Sampling is configured close to the Python eval path with `temperature = 0.0` and `topP = 1.0`. `topK` stays at `40` because LiteRT-LM 0.10.0 crashes natively on the S23/Adreno runtime when `max_top_k` is `1`.

## Core Runtime Files

```text
android/app/src/main/java/com/aegis/health/
  inference/EngineRouter.kt       # verifies the sideloaded artifact
  inference/LiteRtLmEngine.kt     # LiteRT-LM wrapper and sampler config
  inference/SystemPrompts.kt      # mode prompts and final JSON contract
  inference/ToolDispatcher.kt     # native tool loop, dispatcher, JSON parser
  inference/ProseParser.kt        # fallback parser for non-JSON model output
  render/ResponseRenderer.kt      # Compose response UI
  tools/                          # local KB-backed tool implementations
```

## On-device Smoke Prompts

After installing the APK and sideloading `aegis_model.litertlm`, run these prompts on a real device:

- DrugSafe: `warfarin + aspirin`
- DrugSafe: `pediatric aspirin fever`
- DrugSafe: `buprenorphine + pseudoephedrine`
- ConsentReader: `perpetual irrevocable royalty-free license`
- HealthPartner: `celiac symptom question`
- HealthPartner: `50-year-old male preventive screening`

Do not retrain or re-export unless these smoke tests show the `.litertlm` artifact itself is unusable. The main APK risks are prompt parity, tool-loop parity, and robust final JSON parsing.

## Offline Guarantee

The app has no network dependency:

- No `INTERNET` permission in `AndroidManifest.xml`
- Model runs on-device through LiteRT-LM
- Knowledge base is bundled as local SQLite in assets
- OCR uses ML Kit's bundled offline text recognition model
- No analytics, telemetry, or cloud calls
