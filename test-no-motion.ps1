[CmdletBinding()]
param(
  [string]$Port,
  [switch]$AllowSerialReset,
  [switch]$InstallDependencies
)

$ErrorActionPreference = "Stop"
$repo = $PSScriptRoot

Write-Host "Firmware contract, digital-twin, compile, and memory tests"
& (Join-Path $repo "firmware/test.ps1") -InstallDependencies:$InstallDependencies
if (-not $?) { throw "Firmware validation failed." }

Write-Host "Windows tests, lint, compile, and release package"
& (Join-Path $repo "windows_app/build.ps1")
if (-not $?) { throw "Windows validation failed." }

Write-Host "Android unit tests, lint, and debug APK"
Push-Location (Join-Path $repo "android_app")
try {
  & .\gradlew.bat testDebugUnitTest lintDebug assembleDebug
  if ($LASTEXITCODE -ne 0) { throw "Android validation failed." }
}
finally {
  Pop-Location
}

if ($Port) {
  Write-Host "Read-only Nano protocol probe on $Port"
  $venvPython = Join-Path $repo "windows_app/.venv/Scripts/python.exe"
  $probePython = if (Test-Path -LiteralPath $venvPython) { $venvPython } else { "python" }
  $probeArguments = @((Join-Path $repo "firmware/non_motion_serial_test.py"), "--port", $Port, "--samples", "20")
  if ($AllowSerialReset) { $probeArguments += "--allow-reset" }
  & $probePython @probeArguments
  if ($LASTEXITCODE -ne 0) { throw "Read-only Nano protocol probe failed." }
}
else {
  Write-Host "No serial port supplied; skipped the optional read-only Nano probe."
}

Write-Host "PASS: hardware-free release validation completed."
Write-Host "No script in this workflow uploads firmware or sends a motion/magnet command."
