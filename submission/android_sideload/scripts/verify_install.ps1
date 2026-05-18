param(
    [string]$PackageName = "com.aegis.health"
)

$ErrorActionPreference = "Stop"
$devicePath = "/sdcard/Android/data/$PackageName/files/aegis_model.litertlm"

Write-Host "Checking package..."
adb shell pm path $PackageName

Write-Host "Checking model file..."
adb shell ls -lh $devicePath

Write-Host "Checking INTERNET permission. Expected: no granted INTERNET permission."
adb shell dumpsys package $PackageName | Select-String INTERNET

Write-Host "Follow logs with:"
Write-Host "adb logcat -s EngineRouter LiteRtLmEngine ToolDispatcher"

