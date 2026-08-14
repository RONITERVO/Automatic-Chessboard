# Changelog

## Unreleased

- Added firmware-4.8 `EDGEEXIT` capture routing. The captured piece can now be
  carried through arbitrary empty square-centre paths to any `a1`-`a8` bin exit,
  so reachable detours beat moving an unrelated piece. The Nano tracks every
  verified capture drag while 4.7 compatibility keeps its original lane model.
- Added firmware-4.7 deferred capture removal. The route search can now park
  blockers before `REMOVE`, prove the capture frame, complete the main move,
  and restore every temporary piece in one transaction.
- Added firmware-4.6 app-controlled play for absent or unreliable reed switches.
  Human and Stockfish moves share the collision-safe route planner, and every
  completed move requires whole-board visual confirmation before play continues.
- Added an interactive Play board, explicit sensor/app authority selection,
  virtual-board simulator coverage, and stop-on-mismatch/disconnect recovery.
- Added a recoverable firmware-4.5 board-alignment workflow with user-selected
  squares, one-step X/Y controls, optional magnetic-marker mode, reconnect
  recovery, two-point geometry calculation, and copyable `global.h` values.
- Corrected displayed and simulated telemetry-state numbers to the firmware's
  stable 0-20 state contract.
- Aligned direct manual movement and the simulator with firmware 4.4's
  file/rank/diagonal-only carry contract. Turning moves remain available through
  the collision-safe square-centre route planner.

## 1.2.0

- Added host-side labeled rearrangement planning for collision-safe automatic
  moves on firmware 4.3.
- Added bounded blocker evacuation/restoration, main-piece staging, and recursive
  clearing for trapped blockers.
- Added transactional `PLAN` / straight `DRAG` / `COMMIT` execution with fresh
  64-square proofs after every physical action.
- Added capture, en passant, promotion, and standard-castling route adaptation;
  Chess960 is rejected explicitly until its physical rook mapping is supported.
- Added immutable worker-thread planning, stale-generation rejection, separate
  control/motion timeouts, and conservative uncertain-state recovery.
- Made route cost account for every real pickup created when a turning path is
  split into straight firmware runs.
- Added planner, protocol, simulator, and chess-adapter regression coverage.
- Added an exhaustive motionless Nano transaction model, read-only serial probe,
  and one-command firmware/Windows/Android validation workflow for contributors
  without functional motion or reed hardware.
- Aligned both app simulators with firmware 4.3 homing, remote-state, promotion,
  and four-coordinate `DONE` behavior.
- Added routing architecture and safety documentation plus a versioned release
  ZIP produced by the build script.
- Retained `PLAY` compatibility for firmware 4.0 and earlier.

## 1.1.0

- Added guarded in-app calibration, head-only moves, and click-source/click-target
  piece moves with fresh telemetry and sensor verification.
- Added firmware capabilities for manual movement and complete 64-square frames.
- Added stronger connection loss, stale-state, and command-risk handling.

## 1.0.0

- Added visual logical/physical board comparison and carriage overlay.
- Added ACB2 firmware identity and live telemetry.
- Added mechanism, limit input, magnet, memory, uptime, and stale-state displays.
- Added resilient BLE/USB reconnect and bounded safe polling.
- Added best-effort emergency halt checked inside Nano motion loops.
- Added guided non-motion diagnostics and privacy-sanitized support bundles.
- Added optional USB/RTSP/HTTP camera view with explicit snapshots only.
- Added simulator mode and command-risk gating for contributors.
- Added public documentation, tests, Windows build script, issue template, and CI.
- Added fixed reed-sensor rank normalization for the published glued-tile wiring.
- Serialized every read-only request path so setup, diagnostics, developer reads,
  and background monitoring cannot overlap on a slow BLE link.
- Added atomic settings saves, bounded session-log retention, and board-aware BLE
  discovery ranking.
- Required fresh PONG, INFO, TELEM, and BOARD evidence from the current
  diagnostic batch so timed-out requests cannot pass using cached data.
