# Development guide

## Start without hardware

Run `setup.ps1`, start the app, select **Simulator**, and connect. Simulator emits
the same core `INFO`, `TELEM`, `BOARD`, and `PLANROUTE` transaction events as
firmware. Use the Developer command `SIMMOVE e2e4` to imitate a human physical
move.

This is the safest path for UI, route orchestration, documentation, translation,
and support-bundle work. Hardware is required only for transport timing,
sensors, and motion tests. Hardware-only `DEVPATH` and `DEVJOG` are deliberately
not advertised by the simulator.

## Tests

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
.\.venv\Scripts\python.exe -m ruff check .
.\.venv\Scripts\python.exe -m compileall -q app.py camera_source.py model.py protocol.py routing.py support.py transports.py tests
```

Tests cover fragmented BLE lines, protocol versioning, telemetry, occupancy
encoding, command-risk classification, visual-state mismatch logic, transaction
simulation, route splitting, direct moves, Plan A/B/C rearrangements, captures,
en passant, promotion, castling, Chess960 rejection, and privacy redaction.

For a repository-wide firmware/Windows/Android release check that cannot request
motion, run `..\test-no-motion.ps1 -Port COM7`. Omit `-Port` when no Nano is
connected. The optional serial probe is source-allowlisted to read-only commands;
BOARD content is not treated as correct when real reed readings are unavailable.

## Planner invariants

- A `PlanningProblem` contains unique labeled starts and unique goals.
- A carried path contains only orthogonal adjacent squares and never crosses
  stationary occupancy.
- Every plan is replayed with piece identity before protocol generation.
- Turns become separate straight `DRAG` commands and count as separate pickups.
- The exact final labeled configuration is required; temporary pieces are not
  allowed to remain parked.
- Search limit failures raise `PlanningError`; no unsafe direct fallback is
  generated.

When extending the search, add a small deterministic regression position before
changing heuristics. Keep hardware commands out of planner code; `routing.py`
must remain independently testable.

## Hardware test order

1. Run `..\firmware\test.ps1` and record flash/SRAM results.
2. Compile and upload with motors unpowered when practical.
3. Verify `HELLO 5.0.1`, `INFO`, `TELEM`, and `BOARD` over USB.
4. Verify the same commands over BLE.
5. Run guided diagnostics and validate both limit inputs by hand.
6. Test remote halt at very low-risk service motion with someone beside power.
7. Test calibration, direct straight routes, routes with turns, one evacuation
   and restore, capture removal, and standard castling.
8. Only then test full automatic play and randomized endurance positions.

Never introduce a test that begins movement merely by connecting or opening the
application. Use an empty board and magnet-off tools first. Route tests involving
pieces require a person beside the physical cutoff.

For configurable motion repeatability testing, use
`../firmware/endurance_test.py`. It exercises production planners with the
magnet forced off and periodically compares measured homing steps. It requires
an explicit port and `--confirm-motion`; it is never part of safe diagnostics.

## Changing the protocol

Keep Nano strings in flash with `F()`, avoid dynamic `String`, and retain enough
SRAM for Micro-Max recursion. Change the coordinated software version, both
typed parsers, both simulators, diagnostics, protocol documentation, and tests
in one release. Version 5.0 has no backward-compatibility motion fallback.

## Release build

`build.ps1` runs unit tests, Ruff, Python bytecode compilation, and PyInstaller.
It creates both `dist\OpenAutomaticChessboard` and a versioned Windows ZIP.
Stockfish is not embedded; the release includes the separate official-download
installer instead. The default build excludes camera dependencies;
`build.ps1 -IncludeCamera` creates the larger camera edition. Review
`THIRD_PARTY_NOTICES.md`, the ZIP contents, and `CHANGELOG.md` before every public
release.
