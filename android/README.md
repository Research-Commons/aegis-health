# Aegis Health — Android App

Offline, on-device medical safety assistant powered by Gemma 4 via LiteRT-LM.

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                   Jetpack Compose UI                    │
│   HomeScreen · DrugSafe · ConsentReader · HealthPartner │
├─────────────────────────────────────────────────────────┤
│                  ResponseRenderer                       │
│   WarningCard · CitationBadge · DeferralCard · etc.     │
├─────────────────────────────────────────────────────────┤
│                   ToolDispatcher                        │
│        Agentic loop: model → tool → model → …          │
├───────────────┬─────────────────────────────────────────┤
│  GemmaEngine  │             Tool Functions              │
│  (LiteRT-LM)  │  NormalizeDrug · DecomposeProduct      │
│               │  CheckWarnings · LookupTerm             │
│               │  GetGuideline                           │
├───────────────┴──────────┬──────────────────────────────┤
│      KBDatabase          │      CameraPipeline          │
│  SQLCipher-encrypted     │  CameraX + ML Kit OCR       │
│  local knowledge base    │  On-device text recognition  │
└──────────────────────────┴──────────────────────────────┘
```

## Prerequisites

- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android SDK 34
- A device or emulator with API 26+

## Setup

### 1. Add the model file

Place the quantized Gemma 4 `.task` file in the assets directory:

```
android/app/src/main/assets/gemma4-aegis.task
```

Generate this file using the export pipeline:

```bash
cd /path/to/aegis-health
make export   # produces export/output/gemma4-aegis.task
cp export/output/gemma4-aegis.task android/app/src/main/assets/
```

### 2. Add the knowledge base

Place the built SQLite knowledge base in assets:

```
android/app/src/main/assets/aegis_kb.sqlite
```

Build it from the KB pipeline:

```bash
make kb   # produces kb/output/aegis_kb.sqlite
cp kb/output/aegis_kb.sqlite android/app/src/main/assets/
```

### 3. Build and run

```bash
cd android
./gradlew assembleDebug
# or open in Android Studio and run
```

## Project Structure

```
android/app/src/main/java/com/aegis/health/
├── AegisApp.kt              # Application class, initializes engine + DB
├── MainActivity.kt           # Single activity with Compose nav
├── models/
│   └── Models.kt             # @Serializable data classes
├── inference/
│   ├── GemmaEngine.kt        # LiteRT-LM wrapper (load, infer, prompt)
│   └── ToolDispatcher.kt     # Parses <tool_call>, routes to tools, agentic loop
├── tools/
│   ├── NormalizeDrug.kt      # Drug name → generic name + RxCUI
│   ├── DecomposeProduct.kt   # Combo product → individual ingredients
│   ├── CheckWarnings.kt      # Core safety engine (interactions, contras, populations)
│   ├── LookupTerm.kt         # Medical term → plain-language definition
│   └── GetGuideline.kt       # USPSTF preventive-care recommendations
├── db/
│   └── KBDatabase.kt         # SQLCipher wrapper for bundled KB
├── camera/
│   └── CameraPipeline.kt     # CameraX + ML Kit OCR
├── ui/
│   ├── theme/
│   │   └── Theme.kt          # Material 3 theme, colors, typography
│   ├── home/
│   │   └── HomeScreen.kt     # Landing page with feature cards
│   ├── drugsafe/
│   │   └── DrugSafeScreen.kt # Drug interaction checker
│   ├── consentreader/
│   │   └── ConsentReaderScreen.kt  # Consent form simplifier
│   └── healthpartner/
│       └── HealthPartnerScreen.kt  # Prevention checklist
└── render/
    └── ResponseRenderer.kt   # Reusable Compose components for responses
```

## Key Design Decisions

- **No internet permission** — the manifest deliberately omits `INTERNET` to prove the fully-offline claim
- **SQLCipher** — the KB is encrypted at rest on the device
- **LiteRT-LM** — Google's on-device inference SDK for Gemma models
- **Agentic tool loop** — the model can call tools iteratively until it produces a final response (max 6 turns)
- **Severity-coded UI** — red (4-5), amber (3), green (1-2) for immediate visual triage
- **Deterministic tools** — all medical data comes from the local KB, never from the model's weights

## Offline Guarantee

The app has **zero network dependencies**:
- No `INTERNET` permission in AndroidManifest.xml
- Model runs on-device via LiteRT-LM
- Knowledge base is bundled as a SQLite file in assets
- OCR uses ML Kit's bundled (offline) text recognition model
- No analytics, no telemetry, no cloud calls

## License

Apache 2.0
