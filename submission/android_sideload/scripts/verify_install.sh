#!/usr/bin/env bash
set -euo pipefail

PACKAGE_NAME="${PACKAGE_NAME:-com.aegis.health}"
DEVICE_PATH="/sdcard/Android/data/${PACKAGE_NAME}/files/aegis_model.litertlm"

echo "Checking package..."
adb shell pm path "${PACKAGE_NAME}"

echo "Checking model file..."
adb shell ls -lh "${DEVICE_PATH}"

echo "Checking INTERNET permission. Expected: no granted INTERNET permission."
adb shell dumpsys package "${PACKAGE_NAME}" | grep INTERNET || true

echo "Follow logs with:"
echo "adb logcat -s EngineRouter LiteRtLmEngine ToolDispatcher"

