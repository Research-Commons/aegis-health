# Windows PowerShell Install Guide

Use this when installing Aegis Health from a Windows machine.

## Prerequisites

Install:

- Android Platform Tools (`adb`)
- Python 3.10+
- Hugging Face CLI:

```powershell
py -m pip install -U huggingface_hub
```

If the model repo is gated for your account, log in:

```powershell
huggingface-cli login
```

Enable USB debugging on the Android device and confirm the device is visible:

```powershell
adb devices
```

## Install APK

Download the APK from the GitHub Release page for this repo.

Then install it:

```powershell
adb install -r .\aegis-health-demo.apk
```

Launch once. It may show a missing-model screen, which is expected before
sideloading:

```powershell
adb shell am start -n com.aegis.health/.MainActivity
```

## Download and sideload model

From any working directory:

```powershell
huggingface-cli download rescommons/gemma4-e4b-toolcalling-litertlm-v2 `
  model.litertlm `
  --local-dir .\downloads

adb shell am force-stop com.aegis.health

adb push .\downloads\model.litertlm `
  /sdcard/Android/data/com.aegis.health/files/aegis_model.litertlm

adb shell am start -n com.aegis.health/.MainActivity
```

Or run the helper:

```powershell
.\scripts\sideload_model.ps1
```

## Verify

```powershell
adb shell ls -lh /sdcard/Android/data/com.aegis.health/files/aegis_model.litertlm
adb logcat -s EngineRouter LiteRtLmEngine ToolDispatcher
```

Expected log line:

```text
EngineRouter: Selecting LiteRtLmEngine (... bytes at aegis_model.litertlm)
```

