# Android architecture

```text
HC-08 BLE / Simulator
          |
          v
 BoardTransport ---- LineBuffer, reconnect, 20-byte serialized writes
          |
          v
 BoardRepository --- one pending poll / exclusive route owner / typed state
       |        |
       |        +------ DiagnosticsRunner / SupportBundle
       v
 GameController ---- chesslib ---- StockfishEngine
       |
       +------ routing/RoutePlanner ---- bounded labeled configuration search
       |
       v
 MainActivity ------ fixed adaptive pages / ChessboardView / CameraController
```

## Boundaries

- `protocol` is dependency-free and owns wire parsing, board-bit ordering, line
  framing, route splitting, command generation, and command risk.
- `transport` owns Android Bluetooth details. It never interprets chess or board
  state. `SimulatorTransport` implements core firmware behaviour, including the
  complete `PLANROUTE` transaction.
- `BoardRepository` is the only connection/protocol authority. Ordinary
  read-only requests enter one deduplicating queue. It permits one outstanding
  read, alternates TELEM and BOARD, times out missing responses, resets across
  reconnects, and emits immutable `MonitorState` snapshots.
- During a route, `BoardRepository` grants exclusive ownership to
  `GameController`, clears queued polling, rejects unrelated commands, and sends
  only the coordinator's next verified command. The emergency `!` path remains
  available.
- `GameController` owns logical chess state, engine sequencing, immutable route
  planning jobs, stale-generation rejection, transaction acknowledgements,
  timeouts, expected occupancy, and uncertain-state recovery.
- `domain/routing` is pure Kotlin with no Android dependency. It owns the labeled
  rearrangement model and is JVM unit-tested independently.
- `StockfishEngine` is a narrow UCI adapter. It serializes engine access, clamps
  Elo/time, bounds waits, validates returned UCI, and closes its process.
- `ui` renders state and collects deliberate intent. It does not parse protocol
  lines or access Bluetooth GATT.
- `camera` owns only live preview and explicit snapshots. Camera state is
  observational, never an interlock or automatic recorder.

## Routing model

One macro action carries one labeled piece through empty four-connected squares.
Orthogonal-only planning conservatively satisfies the board's diagonal clearance
rule. Exact labeled goals turn evacuation/restoration, main-piece staging, and
recursive clearing into one configuration search.

The planner uses iterative temporary-piece budgets and focused, broad, then
bounded exhaustive successor generation. Candidate corridors minimize blockers;
parking ranks reserved goals, articulation points, distance, mobility, turns,
and deterministic square order. The physical cost prioritizes:

1. number of distinct secondary pieces disturbed;
2. actual magnet pickups after turns are split;
3. carried distance;
4. turns and side-adjacent magnetic pieces.

Search duration, node count, temporary pieces, corridor/parking branch width,
and dependency depth are bounded. A limit failure is explicit and never falls
back to an unverified move. Captures, en passant, promotion occupancy, and both
standard castling sides are adapted before search. Capture removal is a search
transition containing a tracked carried
path to any available `a1`-`a8` exit. A winding empty route requires no temporary
piece movement; only a genuinely disconnected edge invokes ordinary recursive
parking. Version 5.0.1 has no legacy routing branch.

## Route transaction

The phone requires a fresh sensor frame equal to chesslib's occupancy. Planning
runs on the existing named worker against an immutable problem. A generation
counter discards results after stop, disconnect, or position change.

Execution is strictly:

```text
PLAN -> BOARD -> (straight DRAG -> BOARD)* -> [capture DRAG(s) -> BOARD -> REMOVE -> BOARD] ->
        (straight DRAG -> BOARD)* -> COMMIT
```

Each exact acknowledgement advances one state. The phone maintains the expected
occupancy independently from the Nano and uses separate control and physical-
motion timeouts. `PLAN` never moves hardware; `REMOVE` is sent only after
the planner has routed the capture to a valid exit. After `REMOVE` or any `DRAG`, loss of
proof makes state uncertain. The app sends one `STOP`, releases the connection
owner, ends the game, and requires inspection instead of retry.

Reed switches prove occupancy, not identity, gantry position, magnet current, or
piece centring. The host owns chess legality and labels; the Nano owns
deterministic motion, limits, magnet cutoff, corridor checks, and sensor-frame
proof.

The protocol also supports explicit `APPBOARD` authority. The user selects every
human move in `ChessboardView`; `GameController` sends both sides through the
same route transaction. The Nano maintains a separate command-derived virtual
frame and `BOARD` returns that frame during the session, so phone/Nano state is
still cross-checked after each command without consulting unreliable reeds.
After `DONE`, the snapshot previews the proposed logical result and a
non-cancelable whole-board confirmation blocks progression. Mismatch or
disconnect stops and invalidates the game. There is deliberately no automatic
fallback between reed and virtual authority.

## Threading and lifecycle

BLE callbacks are converted into repository events. UI observers always run on
the main looper. Polling and Stockfish/route planning use named single-thread
executors. Camera2 uses one handler thread. Route timeout callbacks carry a token
so stale callbacks cannot affect a newer move. Transport, camera, game engine,
poll executor, pending scan, and callbacks are closed from the activity
lifecycle; an active route is stopped before the repository is closed.

The app reconnects only to a user-selected saved address. A scan does not
auto-connect to a similarly named nearby device. The HC-08's 9600-baud link is
protected by a single pending request, exclusive transaction owner, and 20-byte
write queue. Diagnostics waits for its complete serialized response batch.
Structured log writes use one daemon writer and bounded rollover files.

## State authority and safety invariants

1. chesslib owns legal position and piece identity.
2. In verified mode, BOARD owns physical occupancy after firmware rank
   normalization. In explicitly selected APPBOARD mode it owns the Nano's
   command-derived virtual occupancy and must never be presented as sensor proof.
3. TELEM reports commanded/calculated mechanism state, not physical proof.
4. A timestamp older than 12 seconds is stale, never ready, except while expected
   motion deliberately suppresses polling; that state is reported as a warning.
5. Movement-capable developer commands require an unlock and confirmation.
6. Unknown commands and raw route-transaction commands are blocked.
7. `!` is unframed and available independently of normal newline commands.
8. No UI path remotely clears a motion fault.
9. Ordinary polling pauses for calibration, automatic movement, and routes.
10. Diagnostics contains read-only protocol commands only.
11. `ManualControl` owns square selection and verification. Movement remains
    disabled until `CALIBRATED e6` agrees with fresh homed, fault-free,
    magnet-off telemetry; piece moves additionally require fresh occupancy.

## Extending the protocol

Add a typed value and parser in `protocol`, simulator coverage, repository event
reduction, parser/model tests, then display it. Bump the coordinated software
version and update Nano, Android, and Windows in the same release. Host-planned
motion must retain exclusive ownership and exact acknowledgement checks.

## Fixed-layout rule

All six pages divide the available window with weights after applying status and
navigation bar insets. Do not introduce `ScrollView`, `RecyclerView`, or
horizontal scrolling. Add pagination or a focused fixed-size dialog when data
can grow. Test at 320dp-wide portrait and short landscape sizes with large
system font before merging UI changes.
