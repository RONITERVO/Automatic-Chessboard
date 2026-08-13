[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$globalPath = Join-Path $repo "global.h"
$sketchPath = Join-Path $repo "Automatic_Chessboard_V3_27_i2c_value.ino"
$connectionsPath = Join-Path $PSScriptRoot "connections.csv"
$mksConnectionsPath = Join-Path $PSScriptRoot "mks-gen-l-v1-connections.csv"
$sensorMapPath = Join-Path $PSScriptRoot "sensor-map.csv"

$global = Get-Content -LiteralPath $globalPath -Raw
$sketch = Get-Content -LiteralPath $sketchPath -Raw
$connections = Import-Csv -LiteralPath $connectionsPath
$mksConnections = Import-Csv -LiteralPath $mksConnectionsPath
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
  # Profile-specific constants appear before the classic Nano block. This
  # validator owns connections.csv, which is the Nano build contract, so use
  # the final matching declaration in global.h.
  $matches = [regex]::Matches($global, "const\s+byte\s+$Name\s*=\s*(A?\d+)\s*;")
  if ($matches.Count -eq 0) {
    Fail "Could not find byte constant $Name in global.h"
    return $null
  }
  $match = $matches[$matches.Count - 1]
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

function Assert-MksConnection([string]$BoardConnector, [string]$BoardContact,
                              [string]$Destination, [string]$DestinationPin) {
  $match = @($mksConnections | Where-Object {
    $_.board_connector -eq $BoardConnector -and
    $_.board_contact -eq $BoardContact -and
    $_.destination -eq $Destination -and
    $_.destination_pin -eq $DestinationPin
  })
  if ($match.Count -ne 1) {
    Fail "Expected one MKS connection: $BoardConnector/$BoardContact -> $Destination/$DestinationPin"
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

$duplicateMksIds = @($mksConnections | Group-Object id | Where-Object Count -ne 1)
if ($duplicateMksIds.Count -gt 0) {
  Fail "Duplicate MKS connection IDs: $($duplicateMksIds.Name -join ', ')"
}
if (@($mksConnections | Where-Object required -ne "yes").Count -gt 0) {
  Fail "Every published MKS connection must be marked required"
}

# Connector contacts are verified against Makerbase MKS Gen L V1.0_008 PIN.
Assert-MksConnection "X motor" "2B+2A+1A+1B" "Motor 1" "coil B + coil A"
Assert-MksConnection "Y motor" "2B+2A+1A+1B" "Motor 2" "coil B + coil A"
Assert-MksConnection "HE0" "+" "H2520 electromagnet" "+"
Assert-MksConnection "HE0" "-" "H2520 electromagnet" "-"
Assert-MksConnection "X-" "S" "Switch A" "NO"
Assert-MksConnection "Y-" "S" "Switch B" "NO"
Assert-MksConnection "SERVOS2" "D4" "MUX0..MUX3" "SIG"
Assert-MksConnection "SERVOS1" "D11" "LCD backpack" "SDA"
Assert-MksConnection "SERVOS1" "D6" "LCD backpack" "SCL"
$mksBluetoothRx = @($mksConnections | Where-Object {
  $_.id -eq "BLE-05" -and $_.board_contact -eq "TXD" -and
  $_.destination -eq "EXP1" -and $_.destination_pin -eq "pin 3 / D17"
})
if ($mksBluetoothRx.Count -ne 1) {
  Fail "MKS Bluetooth TXD must connect to EXP1 pin 3 / D17 / Serial2 RX"
}

$expectedMksMux = @(
  @{ contact = "pin 4 / A9";  pin = "D63"; destination = "MUX0..MUX3"; destPin = "S0" },
  @{ contact = "pin 3 / A5";  pin = "D59"; destination = "MUX0..MUX3"; destPin = "S1" },
  @{ contact = "pin 6 / D40"; pin = "D40"; destination = "MUX0..MUX3"; destPin = "S2" },
  @{ contact = "pin 8 / D42"; pin = "D42"; destination = "MUX0..MUX3"; destPin = "S3" },
  @{ contact = "pin 10 / A11"; pin = "D65"; destination = "MUX0"; destPin = "EN" },
  @{ contact = "pin 5 / A10"; pin = "D64"; destination = "MUX1"; destPin = "EN" },
  @{ contact = "pin 7 / D44"; pin = "D44"; destination = "MUX2"; destPin = "EN" },
  @{ contact = "pin 9 / A12"; pin = "D66"; destination = "MUX3"; destPin = "EN" }
)
foreach ($expected in $expectedMksMux) {
  $matches = @($mksConnections | Where-Object {
    $_.board_connector -eq "AUX-2" -and
    $_.board_contact -eq $expected.contact -and
    $_.arduino_pin -eq $expected.pin -and
    $_.destination -eq $expected.destination -and
    $_.destination_pin -eq $expected.destPin
  })
  if ($matches.Count -ne 1) {
    Fail "MKS AUX-2 mapping is missing or ambiguous: $($expected.contact) -> $($expected.destination)/$($expected.destPin)"
  }
}

$mksPinContract = @(
  "const byte MAGNET = 10",
  "const byte MOTOR_WHITE_STEP = 54",
  "const byte MOTOR_WHITE_DIR = 55",
  "const byte MOTOR_WHITE_ENABLE = 38",
  "const byte MOTOR_BLACK_STEP = 60",
  "const byte MOTOR_BLACK_DIR = 61",
  "const byte MOTOR_BLACK_ENABLE = 56",
  "const byte MUX_ADDR[4] = {A9, A5, 40, 42}",
  "const byte MUX_SELECT[4] = {A11, A10, 44, A12}",
  "const byte MUX_OUTPUT = 4",
  "const byte BUTTON_A_LIMIT_WHITE = 3",
  "const byte BUTTON_B_LIMIT_BLACK = 14"
)
foreach ($declaration in $mksPinContract) {
  if (-not $global.Contains($declaration)) {
    Fail "MKS firmware pin contract is missing: $declaration"
  }
}
if (-not $sketch.Contains("#define bluetoothInput Serial2")) {
  Fail "MKS Bluetooth transport must use Serial2 / D17 RX"
}

$selectMatches = [regex]::Matches($global, 'const\s+byte\s+MUX_SELECT\[4\]\s*=\s*\{([^}]+)\}')
if ($selectMatches.Count -eq 0) {
  Fail "Could not parse MUX_SELECT from global.h"
  $muxSelect = @()
} else {
  $selectMatch = $selectMatches[$selectMatches.Count - 1]
  $muxSelect = @($selectMatch.Groups[1].Value.Split(',') | ForEach-Object {
    "D$([int]$_.Trim())"
  })
}

$addressMatches = [regex]::Matches($global, 'const\s+byte\s+MUX_ADDR\[4\]\s*=\s*\{([^}]+)\}')
if ($addressMatches.Count -eq 0) {
  Fail "Could not parse MUX_ADDR from global.h"
  $muxAddress = @()
} else {
  $addressMatch = $addressMatches[$addressMatches.Count - 1]
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

Write-Host "Hardware documentation is consistent with firmware: $($connections.Count) Nano connections, $($mksConnections.Count) MKS connections, $($sensors.Count) sensors."
