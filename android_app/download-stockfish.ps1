[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$url = 'https://github.com/official-stockfish/Stockfish/releases/download/sf_18/stockfish-android-armv8.tar'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$checksums = ConvertFrom-StringData (Get-Content -Raw -LiteralPath (Join-Path $root 'stockfish-checksums.properties'))
$archiveSha256 = $checksums.archiveSha256.ToUpperInvariant()
$binarySha256 = $checksums.binarySha256.ToUpperInvariant()
$destination = Join-Path $root 'app\src\main\jniLibs\arm64-v8a\libstockfish.so'
$temporary = Join-Path ([System.IO.Path]::GetTempPath()) ('stockfish18-' + [guid]::NewGuid().ToString('N'))
$archive = Join-Path $temporary 'stockfish-android-armv8.tar'

New-Item -ItemType Directory -Force $temporary | Out-Null
New-Item -ItemType Directory -Force (Split-Path -Parent $destination) | Out-Null
try {
    Write-Host 'Downloading the official Stockfish 18 Android armv8 release...'
    Invoke-WebRequest -UseBasicParsing $url -OutFile $archive
    $actualArchiveHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash
    if ($actualArchiveHash -ne $archiveSha256) {
        throw "Stockfish archive checksum mismatch: $actualArchiveHash"
    }
    tar -xf $archive -C $temporary stockfish/stockfish-android-armv8
    $extracted = Join-Path $temporary 'stockfish\stockfish-android-armv8'
    $actualBinaryHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $extracted).Hash
    if ($actualBinaryHash -ne $binarySha256) {
        throw "Stockfish binary checksum mismatch: $actualBinaryHash"
    }
    Copy-Item -LiteralPath $extracted -Destination $destination -Force
    Write-Host "Installed verified Stockfish 18 binary at $destination"
}
finally {
    if (Test-Path -LiteralPath $temporary) {
        Remove-Item -LiteralPath $temporary -Recurse -Force
    }
}
