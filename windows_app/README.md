# Open Automatic Chessboard Monitor for Windows

This is the public, open-source Windows companion for the Arduino Nano automatic
chessboard. It is designed both for ordinary players and for developers who may
need to observe and diagnose a board from across a room using Bluetooth and an
optional camera.

The interface leads with visual state rather than serial text. It shows the
logical chess position, all 64 physical occupancy sensors, missing or unexpected
pieces, the estimated carriage square, magnet command state, both button/limit
inputs, controller state, memory, uptime, connection freshness, and plain-language
next steps. Raw protocol data remains available in the Developer tab.

## What it can realistically do

| Capability | What the app knows | Important limitation |
| --- | --- | --- |
| Physical board view | Which squares currently contain a magnetic piece | Reed switches detect occupancy, not piece identity |
| Logical chess view | Expected piece type and legal position from `python-chess` | It can differ from reality after a missed or manual correction |
| Direct movement | Click one square for head-only motion, or an occupied source and empty destination for a magnet-carried piece | Requires firmware 3.31, in-app calibration, fresh sensors, and e6 telemetry verification |
| Board offset setup | Center one marker at e6 with board-direction arrows; saves and copies a portable two-number answer | Requires firmware 4.2; sheet, ruler, and camera are optional aids |
| Collision-safe automatic routing | Windows can evacuate and restore blockers, stage the main piece, and recursively free trapped pieces | Requires firmware 4.1; bounded search can report that no verified plan was found |
| Carriage view | The Nano's calculated and persisted square | There is no encoder; missed motor steps cannot be measured directly |
| Magnet indicator | Whether firmware commanded the magnet on | There is no current sensor proving that the coil energized |
| Limits/buttons | Electrical input state and A6 analog value | A snapshot does not prove the switch is mechanically positioned correctly |
| Bluetooth monitoring | Local BLE telemetry, sensor snapshots, and reconnects | HC-08 BLE is short-range and does not provide internet access |
| Camera | Local USB webcam or RTSP/HTTP view | A camera can miss obstructions and is not a safety device |
| Remote halt | A dedicated `!` byte checked inside motion loops | Radio, Windows, or firmware failure can prevent delivery; use physical power |
| Strong chess | Stockfish 18 with adjustable Elo and thinking time | The Nano's standalone Micro-Max remains much weaker |

The app never automatically clears motion faults or assumes that a stale camera
view is safe. Calibration and automatic moves require deliberate controls. Safe
diagnostics never move the mechanism.

## Hardware wiring used by firmware 3.28+

```text
HC-08 TXD -- 1 kOhm -- service jumper -- Nano D10 (Bluetooth receive)

Nano D1/TX -- 1 kOhm --+-- HC-08 RXD
                       +-- 2 kOhm -- GND

HC-08 GND ----------------------------- Nano GND

Button B / black-limit signal --------- Nano A6
Nano A6 ------------------- 10 kOhm ---- Nano 5V
Button B / black-limit other terminal -- Nano GND
```

D0 remains exclusively connected to the Nano's onboard USB bridge. D11 remains
Button A / white limit. A6 requires the external pull-up because it is
analog-input-only. The HC-08 service jumper can remain closed during USB uploads.

Use Nano 5 V for HC-08 VCC only if the carrier board explicitly includes a 3.3 V
regulator and accepts 5 V input. A bare HC-08 requires regulated 3.3 V power.

## Reed-sensor coordinate wiring (firmware 3.31+)

The firmware is built for the glued-tile wiring used by this project. It
normalizes the raw multiplexer rank permutation and the sensor panel's 180-degree
orientation before standalone move recognition, capture handling, setup checks,
manual piece movement, and `BOARD` telemetry. Firmware 3.30 could therefore show
the opposite square—for example physical e6 as d3 and physical b2 as g7. Firmware
3.31 reports both as their physical chess coordinates. Do not compensate by
flipping only an app drawing, because the Nano also uses these coordinates for
chess and motion verification. A custom sensor panel with different wiring must
change this normalization at the firmware scan boundary.

## Install and run

Install Python 3.11 or newer, then open PowerShell in this directory:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\setup.ps1
.\run.ps1
```

`setup.ps1` creates an isolated environment, installs the three required Python
packages, and invokes `install-stockfish.ps1` to download the official Stockfish
18 release separately. It does not install camera packages unless requested.

For a USB webcam, RTSP camera, or HTTP video stream:

```powershell
.\setup-camera.ps1
```

That command adds camera support to a source installation. Packaged users need
the camera-enabled release produced by `build.ps1 -IncludeCamera`; installing
packages beside the normal packaged executable cannot extend its embedded Python
runtime.

## First safe connection

1. Leave the mechanism clear and keep physical power accessible.
2. Select **BLE**, click **Scan**, choose the HC-08, and click **Connect**.
3. Open **Diagnostics** and run **Safe diagnostics**. These checks do not move.
4. Confirm telemetry reports both limit/button inputs released. A6 should normally
   read well above 700 and near 1023.
5. Use the Monitor tab to compare green physical sensor dots with logical pieces.
6. Test each physical limit input locally before requesting calibration.
7. Only then use **Play -> Start game and calibrate**.

The companion may also use USB or **Simulator**. Simulator mode is the recommended
starting point for contributors and UI demonstrations because it cannot energize
hardware. In the Developer tab, `SIMMOVE e2e4` simulates a human move.

## Interface guide

### Monitor

- Green dot: sensor occupancy agrees with the logical piece.
- Orange dot: a sensor sees a piece where the logical board expects none.
- Red outline: a logical piece is expected but its sensor is empty.
- Cyan target: the Nano's estimated carriage square.
- The right side translates firmware state into plain-language guidance.
- Live monitoring alternates `TELEM` and `BOARD` requests and permits only one
  outstanding poll, preventing a slow BLE link from building a command backlog.

### Play

Stockfish supplies legal moves and strength. With firmware 4.1, Windows also
plans the complete physical rearrangement before an automatic move. It can park
blocking pieces temporarily, restore them, stage the main piece when it blocks a
return path, and recursively free a trapped blocker. Carried paths are
orthogonal, avoiding physically unsafe diagonal squeezing.

Before planning, the app requires a fresh 64-square frame that exactly matches
the logical game. It then executes a transaction containing one straight drag at
a time and checks a fresh frame after each drag. A capture or disconnected move
that cannot be proved is treated as uncertain and stops the session; it is never
retried automatically. See [ARCHITECTURE.md](ARCHITECTURE.md) and
[REMOTE_SAFETY.md](REMOTE_SAFETY.md).

The Nano still owns sensor scanning, motor timing, calibration, magnet control,
physical limit checks, and per-drag sensor proof. Thinking and route search use
Windows resources and do not consume more Nano flash or RAM. Firmware 4.0 and
earlier retain the legacy direct/knight `PLAY` path as a compatibility fallback.

### Diagnostics

The guided routine checks the connection, firmware identity, telemetry, all 64
sensors, control inputs, Stockfish, and optional camera dependencies. A support
bundle can then be saved for a GitHub issue.

### Move head / piece

**Visually align board offset** is a fixed-size, no-scroll wizard. Put one
magnetic marker on e6, clear other pieces, and follow the direction-labeled
arrows while looking down at the square. Fine taps move one step; coarse taps
move five steps (about 1 mm on the reference build). Cancel returns the head to
its starting reference without saving. Save writes the Nano profile, copies the
plain-language offset answer, and requires one more normal calibration before
movement. A sheet, ruler, or Camera tab is optional.

### Camera

Enter `0` for the first local webcam, another integer for a different camera, or
an RTSP/HTTP URL. Video is displayed locally and is never recorded automatically.
Snapshots are created only when the user explicitly chooses **Save snapshot**.
URLs containing credentials are not persisted, and support bundles redact every
network-camera URL.

### Developer

The structured timeline and raw protocol view help developers at different
experience levels. Commands are classified before sending:

- Read-only diagnostics are immediately available.
- Session-control commands are identified clearly.
- Commands that can move hardware remain locked and require a second confirmation.
- Unknown commands are blocked.
- Emergency halt is always visible at the top of the application.

## Reconnection and stale state

BLE reconnect uses exponential backoff up to 15 seconds. USB retries every two
seconds. The monitor shows the age of the last board response and changes health
colour when data becomes delayed or stale. It never represents an old response as
live state.

The Nano blocks while producing precise motor steps, so normal telemetry may pause
during movement. The application stops polling while movement is expected. The
single-character emergency halt is handled separately inside the motion loops.

## Logs, privacy, and support bundles

Structured JSONL logs are stored under:

```text
%LOCALAPPDATA%\OpenAutomaticChessboard\logs
```

Support bundles contain the current firmware/telemetry snapshot, sanitized app
settings, the active session log, operating-system information, and public
protocol documentation. They exclude camera frames, PGNs, Stockfish binaries,
and stored camera credentials. Always inspect a ZIP before attaching it to a
public issue.

## Development and public releases

See [DEVELOPMENT.md](DEVELOPMENT.md), [ARCHITECTURE.md](ARCHITECTURE.md), and
[CONTRIBUTING.md](CONTRIBUTING.md). Run tests with:

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
```

Create a distributable Windows folder with:

```powershell
.\build.ps1
```

The build produces `dist\OpenAutomaticChessboard` and a versioned Windows ZIP.
Stockfish is deliberately not embedded; run the included
`install-stockfish.ps1` beside the packaged app or select an existing engine.
GitHub Actions runs the same unit-test and compile checks on Windows for every
companion change.

The default release excludes camera libraries. To create a larger camera-enabled
build with OpenCV and Pillow bundled, use `build.ps1 -IncludeCamera` and label
that release accordingly.

The companion is licensed under GNU GPL version 3 or later, matching its
`python-chess` rules dependency. Stockfish is GPLv3 and is downloaded separately
into the ignored `stockfish` directory. See `THIRD_PARTY_NOTICES.md`.
