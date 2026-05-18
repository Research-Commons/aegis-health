param(
    [string]$ModelRepo = "rescommons/gemma4-e4b-toolcalling-litertlm-v2",
    [string]$DownloadDir = ".\downloads",
    [string]$PackageName = "com.aegis.health"
)

$ErrorActionPreference = "Stop"

$devicePath = "/sdcard/Android/data/$PackageName/files/aegis_model.litertlm"
$localModel = Join-Path $DownloadDir "model.litertlm"

Write-Host "Downloading model from $ModelRepo..."
huggingface-cli download $ModelRepo model.litertlm --local-dir $DownloadDir

Write-Host "Stopping $PackageName..."
adb shell am force-stop $PackageName | Out-Null

Write-Host "Pushing model to $devicePath..."
adb push $localModel $devicePath

Write-Host "Launching Aegis Health..."
adb shell am start -n "$PackageName/.MainActivity"

Write-Host "Done. Run scripts\verify_install.ps1 to inspect the install."

