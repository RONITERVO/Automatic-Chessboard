# Nano firmware development

Firmware 5.0 keeps the controller responsible for deterministic motion, sensor
normalization, safety interlocks, persistence, the two-button/LCD experience,
and a compact standalone chess opponent. Full chess rules, Stockfish, rich
monitoring, and configurable development workloads belong on a connected phone
or computer.

## Release 5.0.0 behavior

Firmware and both companions now use one exact release contract. A companion
must send `HELLO 5.0.0` on its USB or Bluetooth transport before any control or
motion command; the Nano answers only an exact match. `INFO`, `TELEM`, `BOARD`,
`STOP`, and the byte-level `!` emergency halt remain available for diagnosis and
recovery. The old `PLAY`, `PING`, and `STATUS` commands and capability-string
negotiation were removed. Install matching app and firmware releases together.

Standalone capture removal now searches the complete 8x8 empty-square graph.
It chooses a shortest orthogonal route to any of the eight a-file exits and
compresses it into straight, fast, full-step square-centre carries. This uses
stack workspace only—no EEPROM and no global SRAM. If every exit is genuinely
disconnected, the LCD requests the existing sensor-verified manual move.

All supported builds assume the capture bin and carriage clearance extend along
the complete left edge beside `a1` through `a8`.

## Release 4.8.0 history

Firmware 4.8 advertises `EDGEEXIT`. During a deferred capture, a capable phone
or computer may use ordinary verified `DRAG` segments to carry the captured
piece through any empty square-centre path to any available `a1`-`a8` exit.
The Nano follows that capture square through every proven transition and allows
`REMOVE` only while it is still present. The bounded host search therefore uses
zero temporary pieces whenever any edge exit is reachable and invokes the same
parking/dependency search only when every exit is genuinely disconnected.

Version 5.0 supersedes the compact L-only standalone routing described by this
historical release.

## Release 4.7.0 behavior

Firmware 4.7 makes capture removal an explicit `REMOVE` step inside a verified
route transaction. `PLAN` no longer moves a captured piece on new companions.
The phone or computer can first use the existing bounded rearrangement search
to park pieces blocking all left-bin lanes, request `REMOVE`, prove the complete
board frame, finish the main move, and restore every parked piece. This closes
positions where a legal capture previously failed with `ERR BAD ROUTE` before
the planner could clear an exit. Firmware 4.1-4.6 remains supported through
capability detection; that compatibility negotiation was removed in version 5.0.

## Release 4.6.0 behavior

Firmware 4.6 adds explicit app-authoritative play for boards with absent or
unreliable reed switches. `START W APP` / `START B APP` calibrates, seeds the
Nano's virtual occupancy to the standard position, and then accepts the same
bounded `PLANROUTE` transactions for both human and computer moves. During that
session `BOARD` reports the independently tracked virtual frame and no reed
input is used for game decisions. The apps require every human move to be
selected on screen and require visual confirmation after every completed chess
move. A mismatch, route error, halt, or connection loss ends the session; it
never silently changes between virtual and reed authority.

This mode does not pretend to detect a dropped piece or missed step. The app and
Nano cross-check command-derived occupancy, while the person watching the board
is the only physical feedback. Reed-verified play remains the default.

## Release 4.5.0 behavior

Firmware 4.5 replaces the local maintenance carousel with a simpler startup:
Button A starts standalone play and Button B calibrates. Complete sensor,
telemetry, switch, motor-jog, and board-registration tools remain available to
connected builders, where they can explain results and be tested without using
scarce Nano UI memory.

Board registration is a recoverable host-guided session. `GEOMETRY` reports the
compiled pitches, park offsets, and microstep factor. `ALIGN <square> H|M`
visits a chosen square, and four `NUDGE` commands move one logical X/Y step at a
time. Head-only mode keeps the magnet off; optional marker mode pulses it only
during each step. The firmware journals the carriage as unknown before nudging,
bounds each axis to 60 steps, reverses all accumulated nudges on `ALIGN END`,
and still requires calibration afterward. It never writes geometry to EEPROM.
The Android and Windows apps turn two widely separated measurements into exact
`global.h` source values.

Firmware 4.4 makes queen-aligned stepping a firmware-wide invariant. Every raw
CoreXY displacement is executed only as horizontal, vertical, or exact
45-degree segments; an unequal X/Y request is decomposed instead of using an
interpolated step ratio. Direct carried commands accept only square-centre
moves sharing a file, rank, or diagonal. Knights and other turning carries must
use the connected `PLANROUTE` executor, which already stops and verifies at
square centres between straight `DRAG` commands. Unsupported legacy/direct
routes fail before capture removal, magnet pickup, or head movement.
When the standalone Micro-Max opponent chooses a knight, the LCD instead asks
the player to make the displayed AI move manually. Pressing A verifies the
result from the reed switches and continues the current game; B exits to menu.
The same fallback applies when a straight corridor is occupied or either shared
corner of a diagonal step is occupied.

Standalone capture removal no longer assumes one fixed boundary lane. A
captured piece can travel vertically through empty square centres and then left
along an empty rank to any position beside `a1`-`a8`; current/lower ranks are
preferred. If no compact L route is clear, local play requests the complete move
manually and verifies the resulting occupancy. `EDGEEXIT` companions additionally
route around obstacles and can clear true disconnections before removal.
Manual captures use two sensor-verified phases: `REMOVE` the captured square
and press A, then automatically carry the AI piece only when the remaining
route is queen-aligned and every square and diagonal corner is clear. Otherwise
the player makes the displayed `MANUAL` move and presses A again. The empty
intermediate target closes the ordinary-capture identity ambiguity that cannot
be detected when a destination is occupied both before and after.

Firmware 4.3 added an explicit `mks-gen-l-v1` build profile for the integrated
ATmega2560 board. It preserves the same deterministic runtime, standalone play,
geometry, protocol, and 64-square sensor map while using the MKS X/Y driver
sockets, HE0 MOSFET, labeled expansion headers, full-duplex Serial2 Bluetooth,
and software-I2C LCD wiring. See `hardware/MKS_GEN_L_V1.md`. The Nano remains
the default and retains tight 29900-byte flash / 1115-byte global-SRAM budgets.

The Nano still works without a companion: calibration, starting-position
validation, human-vs-Micro-Max chess, physical captures/castling/en-passant,
EEPROM position recovery, LCD guidance, and two-button controls remain local.
Full 64-square sensor inspection and board registration remain available from
either app or a serial terminal. Removing the nested local maintenance UI makes
the ordinary controls clearer and returns memory to long-term maintenance.

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

Firmware 4.5 provides the same board-registration work through either companion
or a guarded serial terminal. Calibrate first, read `GEOMETRY`, then collect two
widely separated `ALIGN ACTIVE` reports whose files and ranks both differ. The
apps calculate the answer directly. A terminal-only builder can use the formulas
in `global.h` or the optional offline calculator:

```powershell
python ./firmware/geometry_calculator.py `
  "GEOMETRY a2 X+3 Y-1" "GEOMETRY h7 X+10 Y-6"
```

Edit `FILE_PITCH_STEPS`, `RANK_PITCH_STEPS`, and both
`CALIBRATION_PARK_*_STEPS` values in `global.h`, upload, calibrate again, and
verify several separated squares before enabling play. The mode reuses normal
state storage, never changes EEPROM geometry, and makes calibration mandatory
after every session.

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
only `HELLO 5.0.0`, `INFO`, `TELEM`, and `BOARD`. It checks exact release identity,
framing, magnet-off telemetry, and repeated serial stability. Reed
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

The test exercises horizontal, vertical, and both diagonal queen-aligned paths,
returns to e6, re-homes periodically, and compares both measured step counts
with an e6 baseline. It requires motor power and causes real movement. Remove all pieces,
keep the physical cutoff within reach, and never treat the `!` radio/USB halt as
a certified safety function.

## Resource policy

The 5.0 Nano build uses 29534 bytes of flash and 1091 bytes of global SRAM.
`build.ps1` rejects growth beyond 29900/1115 bytes, leaving 1186 bytes of
physical flash and 957 bytes for stack/local runtime state. Despite full-board
standalone bin routing, 5.0 saves 268 flash bytes and 24 global SRAM bytes versus
4.8. The packed
three-snapshot board representation uses 24 bytes instead of 192 bytes and is
reused as either reed-derived or command-derived occupancy.
