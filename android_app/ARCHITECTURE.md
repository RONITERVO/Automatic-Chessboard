# Android architecture

```text
HC-08 BLE / Simulator
          |
          v
 BoardTransport ---- LineBuffer, reconnect, 20-byte serialized writes
          |
          v
 BoardRepository --- one pending poll, typed parsing, freshness, event log
       |        |
       |        +------ DiagnosticsRunner / SupportBundle
       v
 GameController ---- chesslib legal state ---- StockfishEngine (UCI process)
       |
       v
 MainActivity ------ fixed adaptive pages / ChessboardView / CameraController
```

## Boundaries

- `protocol` is dependency-free and owns wire parsing, board-bit ordering, line
  framing, command generation, and command risk.
- `transport` owns Android Bluetooth details. It never interprets chess or board
  state. `SimulatorTransport` implements the same interface.
- `BoardRepository` is the only connection/protocol authority. Every read-only
  request, including developer requests, enters one deduplicating queue. It
  permits one outstanding read, alternates TELEM and BOARD every two seconds,
  times out a missing response, resets safely across reconnects, pauses periodic
  work while movement is expected, and emits immutable `MonitorState` snapshots.
- `GameController` owns logical chess state and firmware game-event sequencing.
  Reed switches remain occupancy-only. The controller never invents piece
  identity from a sensor.
- `StockfishEngine` is a narrow UCI adapter. It serializes engine access, clamps
  Elo/time, bounds waits, validates returned UCI, and closes its process.
- `ui` renders state and collects deliberate intent. It does not parse protocol
  lines or access Bluetooth GATT.
- `camera` owns only live preview and explicit snapshots. Generation tokens and
  one state lock prevent late Camera2/MediaPlayer callbacks from reviving a
  stopped session. Cleartext HTTP is rejected; camera state is observational,
  never an interlock or automatic recorder.

## Threading and lifecycle

BLE callbacks are converted into repository events. UI observers always run on
the main looper. Polling and Stockfish use named single-thread executors. Camera2
uses one handler thread. Transport, camera, game engine, poll executor, pending
scan, and callbacks are closed from the activity lifecycle.

The app intentionally reconnects only to a user-selected saved address. A scan
does not auto-connect to a similarly named nearby device. The HC-08's 9600-baud
link is protected by a single pending request and 20-byte write queue.
Diagnostics waits for its complete serialized response batch instead of a fixed
delay. Structured log writes use one daemon writer and bounded rollover files.

## State authority and safety invariants

1. chesslib owns legal position and piece identity.
2. BOARD owns physical occupancy after firmware rank normalization.
3. TELEM reports commanded/calculated mechanism state, not physical proof.
4. A timestamp older than 12 seconds is stale, never ready, except while expected
   motion deliberately suppresses polling; that state is reported as a warning.
5. Movement-capable developer commands require an unlock and confirmation.
6. Unknown real-hardware commands are blocked.
7. `!` is unframed and available independently of normal newline commands.
8. No UI path remotely clears a motion fault.
9. Ordinary polling pauses for calibration and automatic movement.
10. Diagnostics contains read-only protocol commands only.
11. `ManualControl` owns square selection and verification. Movement remains
    disabled until `CALIBRATED e6` agrees with fresh homed, fault-free,
    magnet-off telemetry; piece moves additionally require fresh occupancy.

## Extending the protocol

Add a typed value and parser in `protocol`, add simulator coverage, update the
repository event reduction, add parser/model tests, then display it. Gate new
behavior on the INFO capability set. Keep old positional responses compatible;
prefer a new optional response line when a change cannot be backward compatible.

## Fixed-layout rule

All six pages divide the available window with weights after applying status
and navigation bar insets. Do not introduce `ScrollView`, `RecyclerView`, or
horizontal scrolling. Add pagination or a focused secondary page when data can
grow. Test at 320dp-wide portrait and short landscape sizes with large system
font before merging UI changes.
