$ErrorActionPreference = 'Stop'
$appRoot = $PSScriptRoot
$venvPython = Join-Path $appRoot '.venv\Scripts\python.exe'

if (-not (Test-Path -LiteralPath $venvPython)) {
    & (Join-Path $appRoot 'setup.ps1')
}

& $venvPython (Join-Path $appRoot 'app.py')
