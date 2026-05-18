#!/usr/bin/env bash
set -euo pipefail

MODEL_REPO="${MODEL_REPO:-rescommons/gemma4-e4b-toolcalling-litertlm-v2}"
DOWNLOAD_DIR="${DOWNLOAD_DIR:-./downloads}"
PACKAGE_NAME="${PACKAGE_NAME:-com.aegis.health}"
DEVICE_PATH="/sdcard/Android/data/${PACKAGE_NAME}/files/aegis_model.litertlm"
LOCAL_MODEL="${DOWNLOAD_DIR}/model.litertlm"

echo "Downloading model from ${MODEL_REPO}..."
huggingface-cli download "${MODEL_REPO}" model.litertlm --local-dir "${DOWNLOAD_DIR}"

echo "Stopping ${PACKAGE_NAME}..."
adb shell am force-stop "${PACKAGE_NAME}" >/dev/null

echo "Pushing model to ${DEVICE_PATH}..."
adb push "${LOCAL_MODEL}" "${DEVICE_PATH}"

echo "Launching Aegis Health..."
adb shell am start -n "${PACKAGE_NAME}/.MainActivity"

echo "Done. Run scripts/verify_install.sh to inspect the install."

