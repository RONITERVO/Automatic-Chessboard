[CmdletBinding()]
param(
  [string]$Port,
  [switch]$AllowSerialReset,
  [switch]$InstallDependencies
)

$ErrorActionPreference = "Stop"
$repo = $PSScriptRoot

Write-Host "Firmware contract, digital-twin, compile, and memory tests"
try {
  & (Join-Path $repo "firmware/test.ps1") -InstallDependencies:$InstallDependencies
  if ($LASTEXITCODE -ne 0) { throw "Native firmware validation command failed with exit code $LASTEXITCODE." }
}
catch {
  throw "Firmware validation failed. $($_.Exception.Message)"
}

Write-Host "Windows tests, lint, compile, and release package"
try {
  & (Join-Path $repo "windows_app/build.ps1")
  if ($LASTEXITCODE -ne 0) { throw "Native Windows validation command failed with exit code $LASTEXITCODE." }
}
catch {
  throw "Windows validation failed. $($_.Exception.Message)"
}

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
  Write-Host "Read-only controller protocol probe on $Port"
  $venvPython = Join-Path $repo "windows_app/.venv/Scripts/python.exe"
  $probePython = if (Test-Path -LiteralPath $venvPython) { $venvPython } else { "python" }
  $probeArguments = @((Join-Path $repo "firmware/non_motion_serial_test.py"), "--port", $Port, "--samples", "20")
  if ($AllowSerialReset) { $probeArguments += "--allow-reset" }
  & $probePython @probeArguments
  if ($LASTEXITCODE -ne 0) { throw "Read-only controller protocol probe failed." }
}
else {
  Write-Host "No serial port supplied; skipped the optional read-only controller probe."
}

Write-Host "PASS: hardware-free release validation completed."
Write-Host "No script in this workflow uploads firmware or sends a motion/magnet command."
