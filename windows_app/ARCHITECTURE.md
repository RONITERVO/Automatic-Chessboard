# Companion architecture

```text
HC-08 BLE / USB / Simulator
             |
      transports.py  (reconnect, framing, 20-byte BLE writes)
             |
      main-thread event queue
             |
   +---------+----------+----------------+
   |                    |                |
protocol.py        MonitorModel      routing.py
parse/version      health/mismatch   labeled rearrangement search
   |                    |                |
   +--------------------+----------------+
                        |
                    Tkinter UI
                        |
             +----------+-----------+
             |                      |
        EventRecorder          CameraWorker
        JSONL/support ZIP      optional local frames
```

## Responsibility boundary

The Windows host owns expensive or evolving policy: chess legality, Stockfish,
labeled-piece configuration search, evacuation/restore ordering, main-piece
staging, time/node budgets, and transaction orchestration. The Nano owns the
small deterministic safety kernel: sensor scanning, homing, limit checks, step
timing, magnet cutoff, straight-corridor validation, per-drag occupancy proof,
and final-frame proof.

This separation keeps the Nano maintainable and makes future planners replaceable
without weakening the hardware guardrails. Version 5.0 has one route-transaction
contract and no direct-move fallback. An exact `HELLO 5.0.0` handshake gates all
control and motion commands.

## Routing model

`routing.py` models a labeled configuration of physical pieces. Only one piece
moves at a time. Carried paths use the four orthogonal neighbours; this is a
conservative subset of the physical diagonal-clearance rule and therefore never
attempts to squeeze diagonally between pieces.

The bounded best-first search can:

- route the primary piece directly;
- evacuate blockers to empty parking squares and restore them;
- stage the primary piece while a blocker returns through a shared gate;
- recursively move secondary blockers to free a trapped piece;
- handle capture removal, en passant, promotion occupancy, and both standard
  castling sides.

Capture removal is itself a bounded search transition. The captured piece
becomes a tracked transaction
object: Windows routes it through empty square centres to any available `a1`-
`a8` exit using ordinary verified `DRAG`s, then sends `REMOVE`. A zero-disturbance
path therefore wins even when it winds around occupied squares. Only when every
edge exit is disconnected does the normal dependency/parking search move another
piece. There is no version-dependent routing branch in the 5.0 companion.

Candidate corridors and parking squares are ranked before full configuration
search. Disturbance count dominates the cost, followed by the physical number of
magnet pickups, carried distance, turns, and clearance risk. Time, node,
temporary-piece, corridor, parking, and dependency-depth limits bound worst-case
work. Failure is explicit; the app never silently substitutes an unverified
physical move.

A logical path may turn, but `protocol.py` splits it into maximal straight runs.
The magnet releases and reacquires only at square centres. This lets every
`DRAG` have a simple corridor proof and a complete post-move sensor proof.

## Transaction orchestration

Before planning, Windows requests a fresh 64-square `BOARD` frame and requires
exact agreement with the logical position. Planning runs on a worker thread
against an immutable board copy. Generation identifiers discard results made
stale by disconnect, stop, or a newer move.

Execution is one command at a time:

```text
PLAN -> BOARD -> (DRAG -> BOARD)* -> [capture DRAG(s) -> BOARD -> REMOVE -> BOARD] ->
        (DRAG -> BOARD)* -> COMMIT
```

Windows waits for the exact acknowledgement, maintains its own expected
occupancy frame, and applies separate control and motion timeouts. The Nano also
checks the authoritative occupancy frame locally. `PLAN` is motionless;
after any `REMOVE` or drag, a lost or mismatched acknowledgement makes physical
state uncertain and ends the session instead of retrying.

`APPBOARD` provides an explicit second authority mode rather than weakening that
proof opportunistically. In `APPBOARD` play, both human and AI moves originate
in the UI. The Nano seeds and independently updates a virtual standard-position
occupancy frame; `BOARD` exposes that command-derived frame so the existing
transaction comparison remains intact without sampling reed inputs. After
`DONE`, Windows previews the intended result and blocks all further moves until
the user visually confirms the whole physical board. A mismatch sends `STOP` on
a best-effort basis while transport remains available. After connection loss,
Windows invalidates the logical session without claiming that `STOP` was
delivered. Both cases require physical inspection and recalibration through the
established recovery procedure. Code must never auto-switch authority based on
sensor quality.

## Threading

Tkinter is touched only by the main thread. USB, BLE, Stockfish thinking, route
planning, camera capture, discovery, and engine diagnostics run in worker
threads and communicate through a queue. Transport callbacks may run on any
worker and must remain small.

All ordinary read-only requests pass through one main-thread queue. Exactly one
request may await its matching response at a time; a timeout releases the next
request. Route transactions temporarily own the command channel and normal
polling pauses until they finish or fail.

## State authority

- `python-chess` is authoritative for legal rules and expected piece identity.
- Reed sensors are authoritative only for physical occupancy in verified mode.
- In explicitly selected `APPBOARD` play, command-derived Nano/app occupancy is
  authoritative for routing while human visual confirmation is the only
  physical feedback.
- The Nano normalizes raw multiplexer rows through the fixed hardware row map
  before move logic or `BOARD` telemetry consumes them.
- Nano telemetry reports commanded/calculated controller state.
- The visual model displays disagreement instead of silently choosing one source.
- `ManualSelection` owns direct-control mode and square selection. The UI enables
  movement only after e6 calibration agrees with fresh telemetry, while both
  app and firmware enforce source/destination occupancy.

## Extensibility

Protocol evolution uses coordinated release versions. Change the shared version,
firmware, both companions, typed parsers, simulators, diagnostics, documentation,
and tests together. Parsers reject malformed data without taking control action;
there is no legacy motion fallback.

## Safety design

Read-only and motion commands are classified in `protocol.py`. The Developer UI
locks motion commands, unknown commands are blocked, ordinary polling pauses
while motion is expected, and support tooling is read-only. The `!` emergency
path is separate from newline parsing and checked inside Nano motor loops.
Settings are replaced atomically, and only the 20 most recent structured log
sessions are retained.
