[CmdletBinding()]
param(
  [string]$Fqbn = "arduino:avr:nano:cpu=atmega328old",
  [switch]$InstallDependencies,
  [switch]$Upload,
  [string]$Port,
  [int]$MaxFlashBytes = 28672,
  [int]$MaxRamBytes = 1200
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$output = Join-Path $repo "build/nano"

function Find-ArduinoCli {
  $command = Get-Command arduino-cli -ErrorAction SilentlyContinue
  if ($command) { return $command.Source }

  $bundled = Join-Path $env:LOCALAPPDATA `
    "Programs/Arduino IDE/resources/app/lib/backend/resources/arduino-cli.exe"
  if (Test-Path -LiteralPath $bundled) { return $bundled }

  throw "arduino-cli was not found. Install Arduino CLI or Arduino IDE 2.x."
}

$cli = Find-ArduinoCli
if ($InstallDependencies) {
  $cores = & $cli core list 2>&1 | Out-String
  if ($cores -notmatch "arduino:avr\s+1\.8\.6") {
    & $cli core update-index
    if ($LASTEXITCODE -ne 0) { throw "Could not update the Arduino core index." }
    & $cli core install "arduino:avr@1.8.6"
    if ($LASTEXITCODE -ne 0) { throw "Could not install arduino:avr@1.8.6." }
  }
  & $cli lib install "hd44780@1.3.2"
  if ($LASTEXITCODE -ne 0) { throw "Could not install hd44780@1.3.2." }
}

New-Item -ItemType Directory -Force -Path $output | Out-Null
$compileArguments = @(
  "compile"
  "--fqbn", $Fqbn
  "--warnings", "default"
  "--clean"
  "--output-dir", $output
  $repo
)
$result = & $cli @compileArguments 2>&1 | Out-String
Write-Host $result.TrimEnd()
if ($LASTEXITCODE -ne 0) { throw "Nano compilation failed." }

$flashMatch = [regex]::Match($result, "Sketch uses (\d+) bytes")
$ramMatch = [regex]::Match($result, "Global variables use (\d+) bytes")
if (-not $flashMatch.Success -or -not $ramMatch.Success) {
  throw "Could not read flash/RAM usage from arduino-cli output."
}

$flash = [int]$flashMatch.Groups[1].Value
$ram = [int]$ramMatch.Groups[1].Value
if ($flash -gt $MaxFlashBytes) {
  throw "Flash budget exceeded: $flash > $MaxFlashBytes bytes."
}
if ($ram -gt $MaxRamBytes) {
  throw "Global SRAM budget exceeded: $ram > $MaxRamBytes bytes."
}
Write-Host "Firmware budgets pass: flash $flash/$MaxFlashBytes, global SRAM $ram/$MaxRamBytes bytes."

if ($Upload) {
  if (-not $Port) {
    throw "-Upload requires an explicit -Port (for example COM7)."
  }
  & $cli upload --fqbn $Fqbn --port $Port --input-dir $output $repo
  if ($LASTEXITCODE -ne 0) { throw "Nano upload failed." }
  Write-Host "Uploaded firmware to $Port."
}
