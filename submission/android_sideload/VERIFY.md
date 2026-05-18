# Verification And Troubleshooting

## Install checks

Run:

```bash
adb shell pm path com.aegis.health
adb shell ls -lh /sdcard/Android/data/com.aegis.health/files/aegis_model.litertlm
adb logcat -s EngineRouter LiteRtLmEngine ToolDispatcher
```

Expected:

- Package path exists for `com.aegis.health`.
- `aegis_model.litertlm` exists in the app external files directory.
- Logs show `EngineRouter` selecting `LiteRtLmEngine`.

## Offline guarantee check

The APK removes network permissions. Verify with:

```bash
adb shell dumpsys package com.aegis.health | grep INTERNET
```

On Windows PowerShell:

```powershell
adb shell dumpsys package com.aegis.health | Select-String INTERNET
```

Expected: no granted `android.permission.INTERNET` entry.

## Smoke prompts

| Mode | Prompt | Expected |
|---|---|---|
| DrugSafe | `warfarin and ibuprofen, 72 year old` | High-severity bleeding-risk flag and clinician deferral. |
| ConsentReader | Paste a short consent paragraph | Plain-language rewrite and preserved binding clauses. |
| HealthPartner | `55 year old male preventive screenings` | USPSTF-grounded checklist. |
| ReportReader | Pick a sample lab-report PDF | Per-row summary and severity-coded flags. |

## Troubleshooting

### Model missing

If the app says the model is missing, the model either was not pushed or landed
at the wrong path. Install and launch the APK once, then run:

```bash
adb push ./downloads/model.litertlm \
  /sdcard/Android/data/com.aegis.health/files/aegis_model.litertlm
```

### First prompt crashes

Confirm the model came from:

```text
rescommons/gemma4-e4b-toolcalling-litertlm-v2
```

Do not use old W4/INT4 exports; those reproduced a LiteRT-LM native crash in
the Gemma 4 embedding lookup path.

### Very slow response

This is expected on some devices. Close background apps, keep the phone cool,
and wait for the current request to finish. The native APK is optimized for
privacy/offline operation, not cloud-style latency.

### App directory does not exist

Install and launch the APK once before pushing the model. Android creates:

```text
/sdcard/Android/data/com.aegis.health/files/
```

after app install/first launch.

