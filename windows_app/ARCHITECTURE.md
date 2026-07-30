# Companion architecture

```text
HC-08 BLE or USB or Simulator
            │
            ▼
       transports.py ── reconnect, framing, 20-byte BLE writes
            │ callbacks
            ▼
      main-thread event queue
            │
      ┌─────┴───────────┐
      ▼                 ▼
 protocol.py        MonitorModel
 parse/version       health/mismatch
      │                 │
      └─────┬───────────┘
            ▼
         Tkinter UI ── visual board, mechanism, diagnostics, play
            │
      ┌─────┴──────────────┐
      ▼                    ▼
 EventRecorder         CameraWorker
 JSONL/support ZIP     optional local frames
```

## Threading

Tkinter is touched only by the main thread. USB, BLE, Stockfish thinking, camera
capture, discovery, and engine diagnostics run in worker threads and communicate
through a queue. Transport callbacks may run on any worker and must remain small.

## State authority

- `python-chess` is authoritative for legal rules and expected pieces.
- Reed sensors are authoritative only for physical occupancy.
- The Nano normalizes raw multiplexer rows through its persistent sensor-wiring
  profile before any move logic or `BOARD` telemetry consumes them.
- Nano telemetry reports commanded/calculated controller state.
- The visual model displays disagreement instead of silently choosing one source.

## Extensibility

Protocol evolution is capability-based. Add new optional lines rather than
changing old positional responses. Parsers reject malformed data without taking
control action. Simulator support should accompany every new public capability.

## Safety design

Read-only and motion commands are classified in `protocol.py`. The Developer UI
locks motion commands, unknown commands are blocked, ordinary polling pauses while
motion is expected, and support tooling is read-only. The `!` emergency path is
separate from newline command parsing and checked inside Nano motor loops.
