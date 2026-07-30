param(
    [switch]$IncludeCamera
)

$ErrorActionPreference = 'Stop'
$appRoot = $PSScriptRoot
$venvPython = Join-Path $appRoot '.venv\Scripts\python.exe'

if (-not (Test-Path -LiteralPath $venvPython)) {
    python -m venv (Join-Path $appRoot '.venv')
    & $venvPython -m pip install --upgrade pip
    & $venvPython -m pip install -r (Join-Path $appRoot 'requirements.txt')
}

& $venvPython -m pip install --upgrade -r (Join-Path $appRoot 'requirements-dev.txt')
if ($IncludeCamera) {
    & $venvPython -m pip install --upgrade -r (Join-Path $appRoot 'requirements-camera.txt')
}

$extraArgs = @()
if (-not $IncludeCamera) {
    $extraArgs += @('--exclude-module', 'cv2', '--exclude-module', 'PIL', '--exclude-module', 'numpy')
}

Push-Location $appRoot
try {
    & $venvPython -m unittest discover -s tests -v
    if ($LASTEXITCODE -ne 0) { throw 'Unit tests failed.' }
    & $venvPython -m PyInstaller --noconfirm --clean --onedir --windowed `
        --name OpenAutomaticChessboard @extraArgs app.py
    if ($LASTEXITCODE -ne 0) { throw 'PyInstaller build failed.' }
    Copy-Item -LiteralPath 'README.md', 'FIRMWARE_PROTOCOL.md', 'LICENSE', `
        'THIRD_PARTY_NOTICES.md', 'install-stockfish.ps1' `
        -Destination (Join-Path $appRoot 'dist\OpenAutomaticChessboard') -Force
}
finally {
    Pop-Location
}

Write-Host "Build ready at $appRoot\dist\OpenAutomaticChessboard"
