# Changelog

## 1.0.0-dev

- Added visual logical/physical board comparison and carriage overlay.
- Added ACB2 firmware identity and live telemetry.
- Added mechanism, limit input, magnet, memory, uptime, and stale-state displays.
- Added resilient BLE/USB reconnect and bounded safe polling.
- Added best-effort emergency halt checked inside Nano motion loops.
- Added guided non-motion diagnostics and privacy-sanitized support bundles.
- Added optional USB/RTSP/HTTP camera view with explicit snapshots only.
- Added simulator mode and command-risk gating for contributors.
- Added public documentation, tests, Windows build script, issue template, and CI.
- Added fixed reed-sensor rank normalization for the published glued-tile wiring
  in firmware 3.29.
- Serialized every read-only request path so setup, diagnostics, developer reads,
  and background monitoring cannot overlap on a slow BLE link.
- Added atomic settings saves, bounded session-log retention, and board-aware BLE
  discovery ranking.
