# Automatic Chessboard

Arduino firmware for a CoreXY automatic chessboard with:

- two STEP/DIR stepper-motor drivers;
- an electromagnet for moving pieces;
- a multiplexed 8x8 reed-sensor matrix;
- a 16x2 I2C LCD and two-button interface; and
- the Micro-Max chess engine.

This is a substantially modified implementation of Greg06's
[Automated Chessboard](https://www.instructables.com/Automated-Chessboard/),
adapted around salvaged 3D-printer mechanics and the tested electronics
documented in this repository. See [design lineage and
attribution](ATTRIBUTION.md) and the repository's
[multi-license map](LICENSE.md) before redistributing it.

The [interactive 3D build explorer](https://ronitervo.github.io/Automatic-Chessboard/) turns the complete assembly into a rotatable, zoomable, parts-level build checklist. It is published automatically from `site/` after changes reach `main`.

## Hardware assumptions

The sketch uses the pin assignments and motion calibration in `global.h`. The
motor interface expects STEP/DIR drivers such as the A4988; motor current and
microstep selection are configured on the driver hardware.

For a complete beginner-oriented build, start with the
[`hardware` build guide](hardware/README.md). It includes protected power
wiring, a BOM, exact Nano and 64-square sensor maps, staged assembly,
commissioning checks, diagrams, photographs of the working prototype, and
rework notes for the operational long PCB whose design predates the current
Bluetooth and A6 additions. Its archived KiCad source is reference-only, not a
current fabrication release. The 5 V buck output belongs on the Nano `5V` pin,
not `VIN`.

## Building

The release build is reproducible from PowerShell:

```powershell
./firmware/test.ps1 -InstallDependencies
```

It pins the Nano core and `hd44780` library, validates the hardware contract,
runs tool tests, compiles for the classic Nano old bootloader, and enforces
flash/SRAM budgets. See [`firmware/README.md`](firmware/README.md) for the
modular source layout, explicit upload command, and contributor policy. The
main `.ino` can still be opened normally in Arduino IDE; its sibling `.ino`
tabs are compiled together automatically.

Review the pin assignments, travel calibration, limit-switch behavior, driver
current limit, and microstep settings before powering the motion hardware.

## Full validation without motion

When motors, piece magnets, or trustworthy reed readings are unavailable, run:

```powershell
./test-no-motion.ps1 -Port COM7
```

The workflow compiles the Nano firmware, enforces its memory budgets, exhaustively
tests the motionless `PLAN` / `DRAG` / `COMMIT` occupancy model, runs Windows and
Android planner/protocol/simulator tests, builds both apps, and optionally samples
the real Nano over USB. The COM probe is allowlisted to `PING`, `INFO`, `STATUS`,
`TELEM`, and `BOARD`; it validates BOARD framing but does not require any particular
occupancy. It never uploads firmware or requests calibration, motor steps, magnet
power, or a chess move.

The probe first tries to keep DTR/RTS inactive. If a classic Nano does not
answer without its normal USB reset, use `-AllowSerialReset` only after checking
that firmware startup cannot energize the mechanism; this still does not send
an upload or any motion-capable protocol command.

This is the strongest release test possible without actuation, but it cannot prove
motor direction or step accuracy, magnet current/pickup/release, reed wiring and
orientation, mechanical clearance, homing repeatability, or physical collision
behavior. Those remain staged commissioning checks.

## Windows and Bluetooth companion

`windows_app` is the public-ready, open-source Windows monitor and companion for
this firmware. Windows runs Stockfish 18 and full chess rules while the Nano
retains motor timing, sensor scanning, calibration, limit checks, and Micro-Max
as a standalone fallback. It supports the Nano USB port, HC-08 BLE using the
default `FFE0`/`FFE1` service, and a no-hardware simulator for contributors.

Run `windows_app/setup.ps1` once, then `windows_app/run.ps1`. The visual Monitor
shows physical occupancy mismatches, carriage state, magnet command, limit
inputs, firmware memory, uptime, and connection freshness. Guided diagnostics,
structured logs, privacy-sanitized support bundles, optional local camera view,
automatic reconnect, and guarded developer controls are included. Start with
**Diagnostics -> Run safe diagnostics**; it never moves hardware. **Start game
and calibrate** intentionally begins physical movement.

The HC-08 receives commands on D10 through a receive-only software UART and
gets replies from hardware TX/D1. D0 remains dedicated to the onboard USB
bridge, so Bluetooth and USB transmitters never fight electrically. To free
D10, Button B/black limit uses analog-only A6 with a required external 10 kOhm
pull-up to 5 V. The HC-08 RX input must receive 3.3 V logic through a divider.
See `windows_app/README.md` for the complete wiring and first-start procedure.

Firmware 4.1.0 keeps the standalone two-button/LCD and Micro-Max play
experience while adding a small transactional executor for host-planned
collision-safe rearrangements. Windows can evacuate and restore blockers, stage
the main piece, and recursively clear trapped pieces; the Nano accepts only
straight orthogonal drags and proves the complete sensor frame before and after
each one. Capture, en passant, promotion occupancy, and standard castling are
included. Firmware 4.0 clients retain their original `PLAY` path.

Reproducible resource-budgeted builds, measured calibration references, the
configurable connected endurance tool, the 30-second continuous-magnet timeout,
normalized sensor coordinates, guarded calibration/direct movement, and the
best-effort `!` halt remain available. Bluetooth and cameras are not safety
systems, so a local physical power cutoff remains required for remote operation.

## Android Bluetooth companion

`android_app` provides the same working range from a phone: native HC-08 BLE
with reconnect, live logical/physical board comparison, Stockfish 18 play,
the same collision-safe blocker rearrangement and verified route transaction as
Windows, guided safe diagnostics, phone and network-camera views, PGN export,
structured logs, privacy-sanitized support ZIPs, a hardware-free simulator,
guarded raw commands, and a persistent best-effort halt control. Every page uses
a fixed, adaptive layout with pagination instead of horizontal or vertical
scrolling.

Run `android_app/download-stockfish.ps1`, then open `android_app` in Android
Studio or run `android_app/gradlew.bat assembleDebug`. See
[`android_app/README.md`](android_app/README.md) for installation, safety, and
contributor guidance.

## Motor-driver configuration

`MOTOR_MICROSTEPS` in `global.h` must match the hardware configuration of both
motor drivers. For an A4988, the common modes are:

| Microsteps | MS1 | MS2 | MS3 |
| ---: | :---: | :---: | :---: |
| 1 | LOW | LOW | LOW |
| 2 | HIGH | LOW | LOW |
| 4 | LOW | HIGH | LOW |
| 8 | HIGH | HIGH | LOW |
| 16 | HIGH | HIGH | HIGH |

The current high-friction-drive profile remains in full-step mode
(`MOTOR_MICROSTEPS = 1`). Its measured working values ramp slow movement from
approximately 250 to 278 full steps per second and decelerate again before
stopping. If the driver jumpers or wiring are changed later, change this
constant to the same value. The firmware then scales the steps per square,
homing limit, ramp length, and step intervals to preserve the existing travel
distance and approximate physical speeds.

Set the driver current limit from the motor's rated phase current and the sense
resistors fitted to the particular driver board. For an A4988, consult that
board's documentation before using the common `Imax = Vref / (8 * Rsense)`
calculation; clone boards use different sense-resistor values. Never move a
driver or motor connection while motor power is applied.

## Calibration approach safety

Calibration always seeks the white switch before the black corner switch. If a
known trolley position is above rank 6, the unloaded head first moves down to
rank 6. If the black switch is already active, the head moves one full square
away to clear that lane. The staging move never travels toward the black switch.

During normal calibration both switches are approached one step at a time. The
white switch stays pressed while the black switch is found. Capture recovery and
developer reference calibrations use the same sequence. After both switches
establish the corner, the head moves directly to the exact e6 park offset with
no separate release/backoff stage or switch-specific release-distance setting.

The shared button inputs act only as endstops during calibration, so they cannot
abort calibration. Use the board's power switch for an emergency stop during
this sequence. Button emergency stops remain enabled for normal board and test
movements outside calibration.

Every successful calibration and endurance-test reference pass parks the head at
e6. This keeps the normal power-cycle and new-game starting position out of the
black-switch lane before the next white-switch approach.

## Power-cycle position memory

The firmware journals head positions across 32 EEPROM slots. Before the first
step of any motor movement it commits an `UNKNOWN` record. A new board-square
coordinate is committed only after the head reaches that stable endpoint. The
journal spreads writes across slots and commits each record last, so an
interrupted EEPROM write leaves the preceding complete record available.

Immediately after power-up the LCD displays `LAST HEAD` and either the saved
square or `??`. A saved coordinate is used only to stage calibration safely; it
does not bypass calibration or enable normal movement. For example, a head
saved at e8 first moves away from the corner to rank 6 before seeking the white
calibration switch.

Switching power off while the head is stopped preserves its displayed square.
Switching power off during movement produces `POS UNKNOWN` on the next boot,
because no coordinate can safely represent a partially completed move. The
saved square also assumes the unpowered head has not been moved by hand or
shifted mechanically after the motors lose holding torque.

Calibration is blocked when the position is unknown and the black switch is
not already active. The LCD asks for the unpowered head to be placed at rank 6
or below; after the next startup, select calibration and confirm `A=READY`.
This recovery confirmation is also required on the first boot after installing
firmware that has no valid position journal yet.

## Connected endurance test

Firmware 4.0 and later move the former fixed on-device AI self-play workload to the
configurable [`firmware/endurance_test.py`](firmware/endurance_test.py) tool.
This recovers scarce Nano memory while retaining real production straight and
knight planning through the guarded, developer-only `PATH` protocol command.
The magnet is forced off, results are logged on the computer, cycle count and
reference frequency are configurable, and both homing step counts are compared
against a measured baseline. See [`firmware/README.md`](firmware/README.md) for
the exact command and physical safety requirements.

This test detects accumulated position drift using the existing switches. It
cannot prove that no individual missed step was later cancelled in the opposite
direction; detecting every stall in real time requires motor encoders or a
driver with suitable diagnostic feedback.

## Piece-retention travel planner

The standalone and legacy `PLAY` planner uses the shortest straight path for
normal legal moves. Knight moves use three exact straight segments through the
open square-boundary lane. Capture removal and the rook part of castling use the
same explicit clearance lanes.

For firmware 4.1 connected play, `windows_app/routing.py` searches labeled board
configurations. It uses only orthogonal square-to-square carried paths, so the
physical diagonal-clearance constraint is satisfied conservatively. Turning
paths are split at square centres into straight sensor-verified `DRAG` commands.
The search minimizes disturbed pieces first, then actual magnet pickups,
distance, turns, and clearance risk. Time, node, parking, corridor, temporary-
piece, and recursion limits prevent unbounded work; failure stops the move rather
than falling back to an unverified path.

Every corridor ends on an exact whole-step destination, with no interpolation
rounding to accumulate between moves. Hardware validation uses a `1000`
microsecond half-period for start, carrying, and unloaded motion. Keeping these
equal intentionally avoids the mechanism's strong low-speed resonance.

## Captured-piece bin

Black's captured pieces are released into the bin along the calibration side.
The calibrated e6 park position places the left limit at approximately `x = 0.35` in
board-square coordinates; the playing-field edge is `x = 0.50`. Release uses a
conservative `x = 0.48` center line, just outside the playing field and about
25 full steps away from the limit switch.

For every capture, the head first moves half a rank toward the lower clearance
line and then follows that straight lane to the left-side release point. The
corridor uses the normal carrying speed and finishes before magnet release.
The head remains stationary through the magnet's release delay and for another
400 ms while the piece falls into the bin. It then immediately
homes both axes from the nearby calibration side and restores the known e6
position before moving the AI piece. Nothing is stored on the travel rail, so
later captures cannot collide with earlier ones.

Capture behavior remains covered by normal standalone and connected game paths.
The magnet-free endurance test intentionally focuses on repeatable production
board travel; capture-bin testing requires a guarded physical-piece test.
