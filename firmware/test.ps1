[CmdletBinding()]
param([switch]$InstallDependencies)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot

& (Join-Path $repo "hardware/validate.ps1")
if (-not $?) { throw "Hardware/firmware contract validation failed." }

python -m unittest discover -s (Join-Path $PSScriptRoot "tests") -v
if ($LASTEXITCODE -ne 0) { throw "Firmware developer-tool tests failed." }

python -m unittest discover -s $PSScriptRoot -p "test_geometry_calculator.py" -v
if ($LASTEXITCODE -ne 0) { throw "Firmware geometry-calculator tests failed." }

& (Join-Path $PSScriptRoot "build.ps1") `
  -InstallDependencies:$InstallDependencies
if (-not $?) { throw "Firmware build failed." }
