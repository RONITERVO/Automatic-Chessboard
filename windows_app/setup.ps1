$ErrorActionPreference = 'Stop'
$appRoot = $PSScriptRoot
$venvPython = Join-Path $appRoot '.venv\Scripts\python.exe'
$stockfishExe = Join-Path $appRoot 'stockfish\stockfish.exe'

if (-not (Test-Path -LiteralPath $venvPython)) {
    python -m venv (Join-Path $appRoot '.venv')
}

& $venvPython -m pip install --upgrade pip
& $venvPython -m pip install -r (Join-Path $appRoot 'requirements.txt')

if (-not (Test-Path -LiteralPath $stockfishExe)) {
    & (Join-Path $appRoot 'install-stockfish.ps1')
}

Write-Host "Setup complete: $stockfishExe"
