$ErrorActionPreference = 'Stop'
$appRoot = $PSScriptRoot
$venvPython = Join-Path $appRoot '.venv\Scripts\python.exe'

if (-not (Test-Path -LiteralPath $venvPython)) {
    & (Join-Path $appRoot 'setup.ps1')
}

& $venvPython -m pip install --upgrade -r (Join-Path $appRoot 'requirements-camera.txt')
Write-Host 'Camera support is installed. Restart the monitor and open the Camera tab.'
