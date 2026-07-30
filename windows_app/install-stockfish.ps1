$ErrorActionPreference = 'Stop'
$appRoot = $PSScriptRoot
$stockfishDir = Join-Path $appRoot 'stockfish'
$stockfishExe = Join-Path $stockfishDir 'stockfish.exe'
$archive = Join-Path $stockfishDir 'stockfish-release.zip'
$expanded = Join-Path $stockfishDir 'expanded'
$url = 'https://github.com/official-stockfish/Stockfish/releases/download/sf_18/stockfish-windows-x86-64.zip'

New-Item -ItemType Directory -Path $stockfishDir -Force | Out-Null
Write-Host 'Downloading the official Stockfish 18 release (GPLv3)...'
Invoke-WebRequest -Uri $url -OutFile $archive
Expand-Archive -LiteralPath $archive -DestinationPath $expanded -Force

$downloadedExe = Get-ChildItem -LiteralPath $expanded -Recurse -Filter 'stockfish*.exe' |
    Select-Object -First 1
if (-not $downloadedExe) { throw 'Stockfish executable was not found in the official archive.' }
Copy-Item -LiteralPath $downloadedExe.FullName -Destination $stockfishExe -Force

$copying = Get-ChildItem -LiteralPath $expanded -Recurse -Filter 'Copying.txt' |
    Select-Object -First 1
if ($copying) {
    Copy-Item -LiteralPath $copying.FullName -Destination (Join-Path $stockfishDir 'COPYING.txt') -Force
}

Remove-Item -LiteralPath $archive -Force
Remove-Item -LiteralPath $expanded -Recurse -Force
Write-Host "Stockfish installed at $stockfishExe"
Write-Host 'Corresponding source: https://github.com/official-stockfish/Stockfish/tree/sf_18'
