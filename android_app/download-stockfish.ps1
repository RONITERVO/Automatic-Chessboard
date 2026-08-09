[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$url = 'https://github.com/official-stockfish/Stockfish/releases/download/sf_18/stockfish-android-armv8.tar'
$archiveSha256 = 'E2ECA54B0E3189EC7DE338133C2B34FA8F5CDEC3D2473519B414A5CB6815E768'
$binarySha256 = 'CC3B3B74466B6D85CDC0F8CFDAD4111C6A44BD0698F4EAB96FEA7B359A54D3D4'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
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
