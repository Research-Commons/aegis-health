# GitHub Release Asset Checklist

Use this checklist when preparing the public GitHub release linked from the
Kaggle Writeup.

## Attach to GitHub Release

- `aegis-health-demo.apk` or `app-debug.apk`
- Link to this folder:
  `submission/android_sideload/`
- Link to model repo:
  `https://huggingface.co/rescommons/gemma4-e4b-toolcalling-litertlm-v2`
- Link to source SFT checkpoint:
  `https://huggingface.co/rescommons/aegis-sft-e4b-merged-v4`

Do not commit the APK or the `.litertlm` model directly to Git. The APK is
large and should live as a GitHub Release asset; the model belongs on Hugging
Face.

## Suggested release notes

```markdown
# Aegis Health Android Demo

This release contains the Android APK for the Aegis Health offline demo.

Install guide:
https://github.com/Research-Commons/aegis-health/tree/main/submission/android_sideload

Model to sideload:
https://huggingface.co/rescommons/gemma4-e4b-toolcalling-litertlm-v2

Fine-tuned SFT checkpoint:
https://huggingface.co/rescommons/aegis-sft-e4b-merged-v4

Device path:
/sdcard/Android/data/com.aegis.health/files/aegis_model.litertlm

Not medical advice. Research prototype only.
```

## Optional checksums

Generate an APK checksum before uploading:

PowerShell:

```powershell
Get-FileHash .\aegis-health-demo.apk -Algorithm SHA256
```

macOS / Linux:

```bash
shasum -a 256 ./aegis-health-demo.apk
```

