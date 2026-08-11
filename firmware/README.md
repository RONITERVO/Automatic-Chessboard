# Nano firmware development

Firmware 4.0 keeps the Nano responsible for deterministic motion, sensor
normalization, safety interlocks, persistence, the two-button/LCD experience,
and a compact standalone chess opponent. Full chess rules, Stockfish, rich
monitoring, and configurable development workloads belong on a connected phone
or computer.

## Release 4.0.0 behavior

The Nano still works without a companion: calibration, starting-position
validation, human-vs-Micro-Max chess, physical captures/castling/en-passant,
reed inspection, manual head positioning, magnet service, EEPROM position
recovery, LCD guidance, and two-button controls remain local.

Connected USB/BLE clients retain remote games, occupancy/telemetry, safe
diagnostics, app calibration, head-only and sensor-verified piece movement, and
best-effort `!` halt. The fixed on-device 200-ply AI self-play test is the only
deliberately removed workflow; the configurable logged endurance tool below is
its more useful connected replacement. It is unavailable without a companion,
but its absence does not affect playing or servicing the board.

For deterministic limit-input commissioning, send `SWTEST` from USB or the
guarded developer console and follow its `PRESS A`, `RELEASE`, and `PRESS B`
prompts. Unlike ordinary telemetry, the test temporarily owns the shared
button/limit inputs so standalone menu actions cannot consume the press. It
never moves hardware and forces the magnet off before sampling.

For one-driver-at-a-time commissioning, firmware 4.0.0 advertises `DEVJOG`.
`JOG W+`, `JOG W-`, `JOG B+`, and `JOG B-` pulse only that driver for 20 full
steps. They are idle-only motion commands, keep the magnet off, and deliberately
invalidate the carriage coordinate so calibration is mandatory afterward.
Disconnect the untested driver's VMOT and the magnet branch before using them.

## Source layout

- `Automatic_Chessboard_V3_27_i2c_value.ino` owns setup, the top-level state
  machine, and all mutable state.
- `global.h` contains the pin/timing contract, enums, types, and declarations.
- `FirmwareStandalone.ino` implements the no-companion play experience.
- `FirmwareHost.ino` implements the bounded USB/BLE protocol and remote play.
- `FirmwareSensors.ino` scans the reed matrix and tracks human moves.
- `FirmwareMotion.ino` owns CoreXY stepping, calibration, and path planning.
- `FirmwarePieces.ino` implements captures, castling, and carried moves.
- `FirmwareService.ino` contains local maintenance controls.
- `PositionJournal.ino` owns power-loss-safe EEPROM position records.
- `Micro_Max.cpp` is the separately attributed standalone engine adaptation.

Arduino concatenates the `.ino` tabs into one translation unit, which keeps the
classic Nano build simple while separating responsibilities for contributors.
No dynamic `String`, heap allocation, or unbounded input buffer is used.

## Reproducible build

From the repository root:

```powershell
./firmware/test.ps1 -InstallDependencies
```

This installs the pinned `hd44780` dependency when requested, validates the
hardware/firmware pin contract, runs developer-tool tests, compiles for
`arduino:avr:nano:cpu=atmega328old`, and enforces flash/global-SRAM budgets.
Later runs can omit `-InstallDependencies`.

Upload is intentionally explicit:

```powershell
./firmware/build.ps1 -Upload -Port COM7
```

Disconnect 24 V motor/magnet power before ordinary firmware uploads. Uploading
or connecting must never begin movement.

## Connected endurance test

The old fixed 200-ply AI-vs-AI service routine consumed scarce Nano flash and
SRAM and could not be configured or logged. Firmware 4.0 replaces it with the
magnet-free `PATH` developer command and a USB tool:

```powershell
python ./firmware/endurance_test.py --port COM7 --cycles 40 --confirm-motion
```

The test exercises straight and knight-corridor production paths, returns to e6,
re-homes periodically, and compares both measured step counts with an e6
baseline. It requires motor power and causes real movement. Remove all pieces,
keep the physical cutoff within reach, and never treat the `!` radio/USB halt as
a certified safety function.

## Resource policy

`build.ps1` currently reserves at least 2048 bytes of flash and 848 bytes beyond
global data for stack/local use. A change exceeding either budget must first
reduce another cost or deliberately revise the documented budget with measured
hardware evidence. The packed three-snapshot reed representation uses 24 bytes
instead of 192 bytes.
