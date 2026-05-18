# Aegis Health Android APK + Model Sideload

This folder is the judge-facing install guide for the native offline Android
demo. It keeps the APK instructions and the Gemma 4 LiteRT-LM model sideload
steps in one stable GitHub location.

## What judges install

| Artifact | Location | Notes |
|---|---|---|
| Android APK | GitHub Releases for this repo | Attach `app-debug.apk` or a renamed release APK as a release asset. |
| LiteRT-LM model | `rescommons/gemma4-e4b-toolcalling-litertlm-v2` on Hugging Face | Download file `model.litertlm` and push it to the phone as `aegis_model.litertlm`. |
| Source checkpoint | `rescommons/aegis-sft-e4b-merged-v4` on Hugging Face | The FP16 fine-tuned SFT checkpoint used to produce the LiteRT-LM bundle. |

The model is sideloaded because the `.litertlm` bundle is about 7.7 GB and is
not suitable for bundling inside the APK or committing to GitHub.

## Device requirements

| Resource | Minimum | Recommended |
|---|---|---|
| Android | API 26 / Android 8.0 | API 33+ |
| RAM | 8 GB | 12+ GB |
| Free storage | 9 GB | 16+ GB |
| SoC | Snapdragon 7+ Gen 2 / Dimensity 8000 / Tensor G2 | Snapdragon 8 Gen 2+ / Dimensity 9000+ / Tensor G3+ |

Validated on Samsung Galaxy S23. The current runtime uses LiteRT-LM CPU
inference and may take several minutes per full answer on-device.

## Fast path

1. Download the APK from the repo's GitHub Release.
2. Install the APK on a physical Android device.
3. Launch once so Android creates the app external files directory.
4. Download `model.litertlm` from Hugging Face:

   ```bash
   huggingface-cli download rescommons/gemma4-e4b-toolcalling-litertlm-v2 \
     model.litertlm \
     --local-dir ./downloads
   ```

5. Sideload the model to the app directory:

   ```bash
   adb shell am force-stop com.aegis.health
   adb push downloads/model.litertlm \
     /sdcard/Android/data/com.aegis.health/files/aegis_model.litertlm
   adb shell am start -n com.aegis.health/.MainActivity
   ```

6. Run a smoke prompt in DrugSafe:

   ```text
   warfarin and ibuprofen, 72 year old
   ```

Expected result: high-severity bleeding-risk warning with cited flags and
`defer_to_professional=true`.

## Detailed guides

- [Windows PowerShell guide](WINDOWS.md)
- [macOS / Linux guide](MAC_LINUX.md)
- [Verification and troubleshooting](VERIFY.md)
- [Release asset checklist](RELEASE_ASSETS.md)
- [Artifact manifest](artifact_manifest.json)

## Helper scripts

The `scripts/` folder contains optional helpers:

- `scripts/sideload_model.ps1`
- `scripts/sideload_model.sh`
- `scripts/verify_install.ps1`
- `scripts/verify_install.sh`

They assume `adb` and `huggingface-cli` are available on `PATH`.

