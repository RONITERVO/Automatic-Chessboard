# Nano firmware development

Firmware 4.3 keeps the controller responsible for deterministic motion, sensor
normalization, safety interlocks, persistence, the two-button/LCD experience,
and a compact standalone chess opponent. Full chess rules, Stockfish, rich
monitoring, and configurable development workloads belong on a connected phone
or computer.

## Release 4.3.0 behavior

Firmware 4.3 adds an explicit `mks-gen-l-v1` build profile for the integrated
ATmega2560 board. It preserves the same deterministic runtime, standalone play,
geometry, protocol, and 64-square sensor map while using the MKS X/Y driver
sockets, HE0 MOSFET, labeled expansion headers, full-duplex Serial2 Bluetooth,
and software-I2C LCD wiring. See `hardware/MKS_GEN_L_V1.md`. The Nano remains
the default and retains its exact 28562-byte flash / 1118-byte SRAM budgets.

The Nano still works without a companion: calibration, starting-position
validation, human-vs-Micro-Max chess, physical captures/castling/en-passant,
geometry measurement, EEPROM position recovery, LCD guidance, and two-button
controls remain local. Full 64-square sensor inspection remains available from
either app or a serial terminal through `BOARD`; removing its duplicate local
screen pays for geometry setup without increasing Nano flash.

Connected USB/BLE clients retain remote games, occupancy/telemetry, safe
diagnostics, app calibration, head-only and sensor-verified piece movement, and
best-effort `!` halt. The fixed on-device 200-ply AI self-play test is the only
deliberately removed workflow; the configurable logged endurance tool below is
its more useful connected replacement. It is unavailable without a companion,
but its absence does not affect playing or servicing the board.

Firmware 4.1 advertises `PLANROUTE`. A trusted host opens a fixed-width `PLAN`,
sends one or more straight orthogonal `DRAG` commands, and finishes with
`COMMIT`. The Nano compares the full normalized reed frame before the plan,
before and after every drag, and before commit. It independently rejects stale
state, occupied destinations, blocked corridors, incomplete capture/castling
occupancy, and unexpected sensor transitions. Complex evacuation, restoration,
staging, and recursive clearing remain host responsibilities so Nano memory and
motion behaviour stay deterministic. See `windows_app/FIRMWARE_PROTOCOL.md`.

Firmware 4.2 adds an app-independent **Service > Geometry** measurement mode.
It displays the compiled file/rank pitches and corner-to-e6 park values, lets a
builder select any square, and nudges a visible magnetic marker in coordinated
physical axes one precise step at a time. Finishing reports, for example,
`a2 X+3 Y-1` on the LCD. It does not save or change geometry and uses no
additional EEPROM.

Record two widely separated reports whose files and ranks both differ, then use
the documented formulas in `global.h` or the optional offline calculator:

```powershell
python ./firmware/geometry_calculator.py `
  "GEOMETRY a2 X+3 Y-1" "GEOMETRY h7 X+10 Y-6"
```

Edit `FILE_PITCH_STEPS`, `RANK_PITCH_STEPS`, and both
`CALIBRATION_PARK_*_STEPS` values in `global.h`, upload, calibrate again, and
verify several separated squares before enabling play. The mode reuses normal
service state, so global SRAM remains at the 4.1 release level.

For deterministic limit-input commissioning, send `SWTEST` from USB or the
guarded developer console and follow its `PRESS A`, `RELEASE`, and `PRESS B`
prompts. Unlike ordinary telemetry, the test temporarily owns the shared
button/limit inputs so standalone menu actions cannot consume the press. It
never moves hardware and forces the magnet off before sampling.

For one-driver-at-a-time commissioning, firmware 4.0+ advertises `DEVJOG`.
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

This installs pinned `hd44780` and `SoftwareWire` dependencies when requested,
validates the
hardware/firmware pin contract, runs developer-tool tests, compiles for
`arduino:avr:nano:cpu=atmega328old` and `arduino:avr:mega:cpu=atmega2560`, and
enforces both profiles' flash/global-SRAM budgets.
Later runs can omit `-InstallDependencies`.

Upload is intentionally explicit:

```powershell
./firmware/build.ps1 -Upload -Port COM7
```

The MKS alternative must always be selected explicitly:

```powershell
./firmware/build.ps1 -HardwareProfile mks-gen-l-v1 -Upload -Port COM11
```

Disconnect 24 V motor/magnet power before ordinary firmware uploads. Uploading
or connecting must never begin movement.

## Motionless release validation

From the repository root, the complete hardware-free workflow is:

```powershell
./test-no-motion.ps1 -Port COM7
```

`route_transaction_model.py` is an independent occupancy-only digital twin of
the Nano executor. Its tests exhaustively cover every clear straight drag, every
non-straight rejection, every possible intermediate corridor blocker, Plan A
evacuation/restore sequencing, capture, en passant, promotion, both standard
castles, exact commit/cancel behavior, emergency halt, and injected stale or
failed sensor transitions. No model code is compiled into the Nano.

`non_motion_serial_test.py` samples a connected Nano using a hard allowlist of
only `PING`, `INFO`, `STATUS`, `TELEM`, and `BOARD`. It checks firmware identity,
capabilities, framing, magnet-off telemetry, and repeated serial stability. Reed
occupancy is recorded but deliberately not judged. The probe cannot send an
upload, calibration, movement, magnet, transaction, stop, or emergency command.
Some Nano USB adapters can reset when their serial port opens even with DTR held
inactive; normal firmware startup therefore remains part of the magnet/STEP-low
safety contract.

If the Nano does not answer with DTR held inactive, rerun the root workflow with
`-AllowSerialReset` after making startup electrically safe. This permits only
the ordinary USB DTR reset; the command allowlist remains unchanged.

Motionless validation does not replace commissioning of motor direction and
step retention, magnet pickup/release, reed mapping, limit switches, carriage
clearance, or real-piece collision behavior.

## Connected endurance test

The old fixed 200-ply AI-vs-AI service routine consumed scarce Nano flash and
SRAM and could not be configured or logged. Firmware 4.0+ replaces it with the
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

`build.ps1` rejects any global SRAM increase beyond 1118 bytes and keeps the
sketch at or below 28562 bytes, leaving at least 2158 bytes of physical Nano
flash for future maintenance. The packed three-snapshot reed representation
uses 24 bytes instead of 192 bytes.
