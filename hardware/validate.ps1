[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$globalPath = Join-Path $repo "global.h"
$sketchPath = Join-Path $repo "Automatic_Chessboard_V3_27_i2c_value.ino"
$connectionsPath = Join-Path $PSScriptRoot "connections.csv"
$sensorMapPath = Join-Path $PSScriptRoot "sensor-map.csv"

$global = Get-Content -LiteralPath $globalPath -Raw
$sketch = Get-Content -LiteralPath $sketchPath -Raw
$connections = Import-Csv -LiteralPath $connectionsPath
$sensors = Import-Csv -LiteralPath $sensorMapPath
$failures = [System.Collections.Generic.List[string]]::new()

function Fail([string]$Message) {
  $script:failures.Add($Message)
}

function Assert-Connection([string]$Source, [string]$SourcePin,
                           [string]$Destination, [string]$DestinationPin) {
  $match = @($connections | Where-Object {
    $_.source -eq $Source -and $_.source_pin -eq $SourcePin -and
    $_.destination -eq $Destination -and $_.destination_pin -eq $DestinationPin
  })
  if ($match.Count -ne 1) {
    Fail "Expected exactly one connection: $Source/$SourcePin -> $Destination/$DestinationPin"
  }
}

function Read-ByteConstant([string]$Name) {
  $match = [regex]::Match($global, "const\s+byte\s+$Name\s*=\s*(A?\d+)\s*;")
  if (-not $match.Success) {
    Fail "Could not find byte constant $Name in global.h"
    return $null
  }
  $value = $match.Groups[1].Value
  if ($value -match '^\d+$') { return "D$value" }
  return $value
}

function Assert-NanoConnection([string]$Constant, [string]$Destination,
                               [string]$DestinationPin) {
  $pin = Read-ByteConstant $Constant
  if ($null -eq $pin) { return }
  $match = @($connections | Where-Object {
    ($_.source -eq "Nano" -and $_.source_pin -eq $pin -and
      $_.destination -eq $Destination -and $_.destination_pin -eq $DestinationPin) -or
    ($_.destination -eq "Nano" -and $_.destination_pin -eq $pin -and
      $_.source -eq $Destination -and $_.source_pin -eq $DestinationPin)
  })
  if ($match.Count -ne 1) {
    Fail "$Constant=$pin must have exactly one Nano -> $Destination/$DestinationPin row"
  }
}

foreach ($geometryName in @(
  "FILE_PITCH_STEPS", "RANK_PITCH_STEPS",
  "CALIBRATION_PARK_BLACK_STEPS", "CALIBRATION_PARK_WHITE_STEPS"
)) {
  $matches = [regex]::Matches(
    $global,
    "const\s+unsigned\s+int\s+$geometryName\s*=\s*\d+U\s*\*\s*MOTOR_MICROSTEPS\s*;"
  )
  if ($matches.Count -ne 1) {
    Fail "$geometryName must be one compile-time global.h value scaled by MOTOR_MICROSTEPS"
  }
}

if ($global -match 'extern\s+unsigned\s+int\s+calibration_park_' -or
    $sketch -match 'loadCalibrationProfile') {
  Fail "Board geometry must not allocate runtime globals or load an EEPROM profile"
}

Assert-NanoConnection "MAGNET" "R1 1k" "1"
Assert-NanoConnection "MOTOR_WHITE_DIR" "Driver 1" "DIR"
Assert-NanoConnection "MOTOR_WHITE_STEP" "Driver 1" "STEP"
Assert-NanoConnection "MOTOR_BLACK_DIR" "Driver 2" "DIR"
Assert-NanoConnection "MOTOR_BLACK_STEP" "Driver 2" "STEP"
Assert-NanoConnection "MUX_OUTPUT" "MUX0..MUX3" "SIG"
Assert-NanoConnection "BUTTON_A_LIMIT_WHITE" "Switch A" "NO"
Assert-NanoConnection "BUTTON_B_LIMIT_BLACK" "Switch B" "NO"
Assert-NanoConnection "BLUETOOTH_RX" "R4 1k" "2"

$duplicateConnectionIds = @($connections | Group-Object id | Where-Object Count -ne 1)
if ($duplicateConnectionIds.Count -gt 0) {
  Fail "Duplicate connection IDs: $($duplicateConnectionIds.Name -join ', ')"
}

$optionalConnections = @($connections | Where-Object required -ne "yes")
if ($optionalConnections.Count -gt 0) {
  Fail "Every published connection must be marked required"
}

Assert-Connection "5V distribution" "+" "Nano" "5V"
if (@($connections | Where-Object {
  ($_.source -eq "Nano" -and $_.source_pin -eq "VIN") -or
  ($_.destination -eq "Nano" -and $_.destination_pin -eq "VIN")
}).Count -gt 0) {
  Fail "The public connection table must leave Nano VIN unused"
}

foreach ($driver in 1..2) {
  Assert-Connection "5V distribution" "+" "Driver $driver" "RESET+SLEEP"
  Assert-Connection "Ground distribution" "GND" "Driver $driver" "MS1+MS2+MS3"
  Assert-Connection "Ground distribution" "GND" "Driver $driver" "ENABLE"
  Assert-Connection "Ground distribution" "GND" "Driver $driver" "motor GND"
  Assert-Connection "Driver $driver" "VMOT" "C$driver 100uF 50V" "+"
  Assert-Connection "Driver $driver" "motor GND" "C$driver 100uF 50V" "-"
}

Assert-Connection "Driver 1" "DIR" "R7 10k" "1"
Assert-Connection "R7 10k" "2" "Driver 1" "logic GND"
Assert-Connection "Driver 1" "STEP" "R8 10k" "1"
Assert-Connection "R8 10k" "2" "Driver 1" "logic GND"
Assert-Connection "Driver 2" "DIR" "R9 10k" "1"
Assert-Connection "R9 10k" "2" "Driver 2" "logic GND"
Assert-Connection "Driver 2" "STEP" "R10 10k" "1"
Assert-Connection "R10 10k" "2" "Driver 2" "logic GND"

Assert-Connection "Flyback diode" "CATHODE" "H2520 electromagnet" "+"
Assert-Connection "Flyback diode" "ANODE" "H2520 electromagnet" "-"
Assert-Connection "5V distribution" "+" "R3 10k" "1"
Assert-Connection "Nano" "D1" "R5 1k" "1"
Assert-Connection "HC-08" "RXD" "R6 2k" "1"

$selectMatch = [regex]::Match($global, 'const\s+byte\s+MUX_SELECT\[4\]\s*=\s*\{([^}]+)\}')
if (-not $selectMatch.Success) {
  Fail "Could not parse MUX_SELECT from global.h"
  $muxSelect = @()
} else {
  $muxSelect = @($selectMatch.Groups[1].Value.Split(',') | ForEach-Object {
    "D$([int]$_.Trim())"
  })
}

$addressMatch = [regex]::Match($global, 'const\s+byte\s+MUX_ADDR\[4\]\s*=\s*\{([^}]+)\}')
if (-not $addressMatch.Success) {
  Fail "Could not parse MUX_ADDR from global.h"
  $muxAddress = @()
} else {
  $muxAddress = @($addressMatch.Groups[1].Value.Split(',') | ForEach-Object { $_.Trim() })
}

$expectedAddress = @("A3", "A2", "A1", "A0")
for ($bit = 0; $bit -lt 4; $bit++) {
  if ($muxAddress.Count -eq 4 -and $muxAddress[$bit] -ne $expectedAddress[$bit]) {
    Fail "MUX address bit $bit is $($muxAddress[$bit]); documentation expects $($expectedAddress[$bit])"
  }
  $addressRow = @($connections | Where-Object {
    $_.source -eq "Nano" -and $_.source_pin -eq $expectedAddress[$bit] -and
    $_.destination -eq "MUX0..MUX3" -and $_.destination_pin -eq "S$bit"
  })
  if ($addressRow.Count -ne 1) {
    Fail "Missing unique address connection $($expectedAddress[$bit]) -> S$bit"
  }
}

for ($mux = 0; $mux -lt 4; $mux++) {
  if ($muxSelect.Count -eq 4) {
    Assert-Connection "Nano" $muxSelect[$mux] "MUX$mux" "EN"
  }
}

$rowMapMatch = [regex]::Match($sketch, 'const\s+byte\s+SENSOR_ROW_MAP\[8\]\s+PROGMEM\s*=\s*\{([^}]+)\}')
if (-not $rowMapMatch.Success) {
  Fail "Could not parse SENSOR_ROW_MAP from the firmware"
  $rowMap = @()
} else {
  $rowMap = @($rowMapMatch.Groups[1].Value.Split(',') | ForEach-Object { [int]$_.Trim() })
}

if ($sensors.Count -ne 64) {
  Fail "sensor-map.csv has $($sensors.Count) rows; expected 64"
}

$duplicateSquares = @($sensors | Group-Object logical_square | Where-Object Count -ne 1)
if ($duplicateSquares.Count -gt 0) {
  Fail "Duplicate or missing logical squares: $($duplicateSquares.Name -join ', ')"
}

$duplicateChannels = @($sensors | Group-Object mux, channel | Where-Object Count -ne 1)
if ($duplicateChannels.Count -gt 0) {
  Fail "Duplicate multiplexer channels exist in sensor-map.csv"
}

$columnStart = @(6, 4, 2, 0)
if ($rowMap.Count -eq 8 -and $muxSelect.Count -eq 4) {
  foreach ($sensor in $sensors) {
    $mux = [int]$sensor.mux
    $channel = [int]$sensor.channel
    if ($mux -lt 0 -or $mux -gt 3 -or $channel -lt 0 -or $channel -gt 15) {
      Fail "Invalid MUX/channel: $mux/$channel"
      continue
    }
    $column = $columnStart[$mux] + [math]::Floor($channel / 8)
    $rawRow = 7 - $column
    $logicalRow = $rowMap[$rawRow]
    $rank = 8 - $logicalRow
    $file = [char]([int][char]'a' + ($channel % 8))
    $expectedSquare = "$file$rank"
    if ($sensor.logical_square -cne $expectedSquare) {
      Fail "MUX$mux C$channel maps to $expectedSquare in firmware, not $($sensor.logical_square)"
    }
    if ($sensor.mux_enable_pin -ne $muxSelect[$mux]) {
      Fail "MUX$mux enable is $($muxSelect[$mux]) in firmware, not $($sensor.mux_enable_pin)"
    }
    if ($sensor.reed_other_terminal -ne "GND" -or $sensor.occupied_level -ne "LOW") {
      Fail "Sensor $($sensor.logical_square) must close to GND and report LOW"
    }
  }
}

if ($failures.Count -gt 0) {
  $failures | ForEach-Object { Write-Error $_ }
  exit 1
}

Write-Host "Hardware documentation is consistent with firmware: $($connections.Count) connections, $($sensors.Count) sensors."
