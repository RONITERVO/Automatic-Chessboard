# Development guide

## Start without hardware

Run `setup.ps1`, start the app, select **Simulator**, and connect. Simulator emits
the same `INFO`, `TELEM`, and `BOARD` events as firmware. Use the Developer command
`SIMMOVE e2e4` to imitate a human physical move.

This is the safest path for UI, documentation, translation, and support-bundle
work. Hardware is required only for transport timing, sensors, and motion tests.

## Tests

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
.\.venv\Scripts\python.exe -m compileall -q .
```

Test modules cover fragmented BLE lines, protocol versioning, telemetry, occupancy
encoding, command-risk classification, visual-state mismatch logic, simulator
responses, and privacy redaction.

## Hardware test order

1. Compile and upload with motors unpowered when practical.
2. Verify `PING`, `INFO`, `TELEM`, and `BOARD` over USB.
3. Verify the same commands over BLE.
4. Run guided diagnostics and validate both limit inputs by hand.
5. Test remote halt at very low-risk service motion with someone beside power.
6. Only then test calibration and full automatic movement.

Never introduce a test that begins movement merely by connecting or opening the
application.

## Adding telemetry

Keep Nano strings in flash with `F()`, avoid dynamic `String`, and retain enough
SRAM for Micro-Max recursion. Add a capability name, parser test, simulator value,
visual presentation, protocol documentation, and backward-compatible fallback.

## Release build

`build.ps1` runs tests and creates an onedir PyInstaller build. Stockfish is not
embedded; the release includes the separate official-download installer instead.
The default build excludes camera dependencies; `build.ps1 -IncludeCamera`
creates the larger camera edition. Review `THIRD_PARTY_NOTICES.md` before every
public release.
