[CmdletBinding()]
param(
  [ValidateSet("nano", "mks-gen-l-v1")]
  [string]$HardwareProfile = "nano",
  [string]$Fqbn,
  [switch]$InstallDependencies,
  [switch]$Upload,
  [string]$Port,
  [int]$MaxFlashBytes,
  [int]$MaxRamBytes
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$mainSketch = Join-Path $repo "Automatic_Chessboard_V3_27_i2c_value.ino"

if ($HardwareProfile -eq "mks-gen-l-v1") {
  if (-not $Fqbn) { $Fqbn = "arduino:avr:mega:cpu=atmega2560" }
  if ($Fqbn -notmatch '^arduino:avr:mega:cpu=atmega2560$') {
    throw "mks-gen-l-v1 requires an ATmega2560 Mega FQBN."
  }
  # Preserve room for future features and runtime stack/heap before device limits.
  if (-not $PSBoundParameters.ContainsKey("MaxFlashBytes")) { $MaxFlashBytes = 60000 }
  if (-not $PSBoundParameters.ContainsKey("MaxRamBytes")) { $MaxRamBytes = 4096 }
  $profileBuildFlags = "-DACB_PROFILE_MKS_GEN_L_V1"
}
else {
  if (-not $Fqbn) { $Fqbn = "arduino:avr:nano:cpu=atmega328old" }
  if (-not $PSBoundParameters.ContainsKey("MaxFlashBytes")) { $MaxFlashBytes = 29620 }
  if (-not $PSBoundParameters.ContainsKey("MaxRamBytes")) { $MaxRamBytes = 1118 }
  $profileBuildFlags = ""
}
$output = Join-Path $repo ("build/" + $HardwareProfile)

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
  $previousErrorActionPreference = $ErrorActionPreference
  try {
    $ErrorActionPreference = "Continue"
    $cores = & $cli core list 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { throw "Could not list installed Arduino cores." }
  }
  finally {
    $ErrorActionPreference = $previousErrorActionPreference
  }
  if ($cores -notmatch "arduino:avr\s+1\.8\.6") {
    & $cli core update-index
    if ($LASTEXITCODE -ne 0) { throw "Could not update the Arduino core index." }
    & $cli core install "arduino:avr@1.8.6"
    if ($LASTEXITCODE -ne 0) { throw "Could not install arduino:avr@1.8.6." }
  }
  & $cli lib install "hd44780@1.3.2"
  if ($LASTEXITCODE -ne 0) { throw "Could not install hd44780@1.3.2." }
  & $cli lib install "SoftwareWire@1.6.0"
  if ($LASTEXITCODE -ne 0) { throw "Could not install SoftwareWire@1.6.0." }
}

New-Item -ItemType Directory -Force -Path $output | Out-Null
if (-not (Test-Path -LiteralPath $mainSketch)) {
  throw "Main Nano sketch was not found: $mainSketch"
}

# Arduino requires the primary .ino file and its containing directory to have
# the same name. A GitHub checkout is named after the repository, while local
# source folders may happen to match the sketch. Stage only Arduino source
# files in a correctly named temporary directory so builds are independent of
# the checkout directory name and cannot accidentally include repository files.
$sketchName = [System.IO.Path]::GetFileNameWithoutExtension($mainSketch)
$stagingRoot = Join-Path ([System.IO.Path]::GetTempPath()) `
  ("automatic-chessboard-" + [System.Guid]::NewGuid().ToString("N"))
$stagedSketch = Join-Path $stagingRoot $sketchName

try {
  New-Item -ItemType Directory -Force -Path $stagedSketch | Out-Null
  $sourceExtensions = @(".ino", ".h", ".hpp", ".c", ".cpp", ".S")
  Get-ChildItem -LiteralPath $repo -File |
    Where-Object { $sourceExtensions -ccontains $_.Extension } |
    Copy-Item -Destination $stagedSketch

  $compileArguments = @(
    "compile"
    "--fqbn", $Fqbn
    "--warnings", "default"
    "--clean"
    "--output-dir", $output
  )
  if ($profileBuildFlags) {
    $compileArguments += @("--build-property", "build.extra_flags=$profileBuildFlags")
  }
  $compileArguments += $stagedSketch
  $previousErrorActionPreference = $ErrorActionPreference
  try {
    $ErrorActionPreference = "Continue"
    $result = & $cli @compileArguments 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { throw "$HardwareProfile compilation failed." }
  }
  finally {
    $ErrorActionPreference = $previousErrorActionPreference
  }
  Write-Host $result.TrimEnd()

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
    $uploadArguments = @(
      "upload", "--fqbn", $Fqbn, "--port", $Port,
      "--input-dir", $output
    )
    $uploadArguments += $stagedSketch
    & $cli @uploadArguments
    if ($LASTEXITCODE -ne 0) { throw "$HardwareProfile upload failed." }
    Write-Host "Uploaded $HardwareProfile firmware to $Port."
  }
}
finally {
  if (Test-Path -LiteralPath $stagingRoot) {
    Remove-Item -LiteralPath $stagingRoot -Recurse -Force
  }
}
