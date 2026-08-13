param(
    [switch]$IncludeCamera
)

$ErrorActionPreference = 'Stop'
$appRoot = $PSScriptRoot
$venvPython = Join-Path $appRoot '.venv\Scripts\python.exe'
$appSource = Get-Content -LiteralPath (Join-Path $appRoot 'app.py') -Raw
$versionMatch = [regex]::Match($appSource, 'APP_VERSION\s*=\s*"([^"]+)"')
if (-not $versionMatch.Success) {
    throw 'Could not read APP_VERSION from app.py.'
}
$appVersion = $versionMatch.Groups[1].Value
$edition = if ($IncludeCamera) { '-camera' } else { '' }
$distRoot = Join-Path $appRoot 'dist'
$releaseDirectory = Join-Path $distRoot 'OpenAutomaticChessboard'
$archivePath = Join-Path $distRoot "OpenAutomaticChessboard-$appVersion-windows$edition.zip"

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
    & $venvPython -m ruff check .
    if ($LASTEXITCODE -ne 0) { throw 'Ruff checks failed.' }
    & $venvPython -m compileall -q app.py camera_source.py model.py protocol.py `
        routing.py support.py transports.py tests
    if ($LASTEXITCODE -ne 0) { throw 'Python compilation checks failed.' }
    & $venvPython -m PyInstaller --noconfirm --clean --onedir --windowed `
        --name OpenAutomaticChessboard @extraArgs app.py
    if ($LASTEXITCODE -ne 0) { throw 'PyInstaller build failed.' }
    Copy-Item -LiteralPath 'README.md', 'FIRMWARE_PROTOCOL.md', 'LICENSE', `
        'THIRD_PARTY_NOTICES.md', 'CHANGELOG.md', 'REMOTE_SAFETY.md', `
        'install-stockfish.ps1' -Destination $releaseDirectory -Force
    if (Test-Path -LiteralPath $archivePath) {
        Remove-Item -LiteralPath $archivePath -Force
    }
    Compress-Archive -Path (Join-Path $releaseDirectory '*') `
        -DestinationPath $archivePath -CompressionLevel Optimal
}
finally {
    Pop-Location
}

Write-Host "Build folder ready at $releaseDirectory"
Write-Host "Release archive ready at $archivePath"
