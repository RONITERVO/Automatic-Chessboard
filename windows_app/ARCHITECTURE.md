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

All read-only host requests pass through one main-thread queue. Exactly one request
may await its matching response at a time; a timeout releases the next request.
This invariant applies equally to connection setup, manual refresh, diagnostics,
the developer console, and periodic monitoring.
Diagnostics evaluates only after its queued response batch completes or reaches
terminal timeouts; it never relies on a fixed BLE timing delay.

## State authority

- `python-chess` is authoritative for legal rules and expected pieces.
- Reed sensors are authoritative only for physical occupancy.
- The Nano normalizes raw multiplexer rows through the fixed published-hardware
  row map before any move logic or `BOARD` telemetry consumes them.
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
Settings are replaced atomically, and only the 20 most recent structured log
sessions are retained.
