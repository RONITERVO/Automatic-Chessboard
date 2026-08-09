# Automatic Chessboard for Android

This is the phone-first companion for firmware 3.29+. It covers the Windows
companion's current Bluetooth workflow while keeping Android, chess rules,
Stockfish, protocol handling, and screen rendering separate enough to extend.

## Current capability

| Area | Android behavior |
| --- | --- |
| Connection | Native BLE scan, saved-device reconnect, HC-08 FFE0/FFE1 GATT, exponential retry, 20-byte writes |
| Monitor | Logical pieces, all 64 occupancy sensors, missing/extra squares, carriage estimate, magnet command, controls, memory, uptime, stale-state health |
| Play | Full legal rules through chesslib, official Stockfish 18, human White/Black, Elo and think time, castling/en-passant/promotion flags, paged move history, PGN export |
| Diagnostics | Connection, INFO, TELEM, BOARD, controls, Stockfish, and camera checks; no motion commands |
| Camera | Local phone cameras, encrypted HTTPS streams, or unencrypted RTSP streams supported by Android; explicit JPEG snapshots only |
| Developer | Structured and raw-equivalent protocol timeline, pagination, documented-command allowlist, motion lock plus confirmation, simulator `SIMMOVE` |
| Support | JSONL session logs, copied diagnostic summary, sanitized ZIP with no frames, PGNs, camera credentials, or Bluetooth address |
| Safety | Persistent HALT button, separate single-byte `!` path, motion polling pause, stale-state warnings, no remote fault clearing |

No page contains a `ScrollView`, horizontally scrolling container, or vertically
scrolling list. Dense histories and logs use explicit pages. Portrait and
landscape layouts recompute on rotation, and system-bar insets are respected.

Cleartext HTTP camera URLs are rejected with an explicit warning. Use HTTPS for
encrypted transport. Plain `rtsp://` is not encrypted and should be used only on
a trusted local network or inside a separately secured tunnel; the current
Android media client does not advertise RTSPS support.
Devices without Bluetooth Low Energy can still install the app and use its safe
simulator; only real-board scan and connection are unavailable.

## Build

Requirements: Java 17 and Android SDK 35 or newer.

The official Stockfish 18 armv8 binary is large and is intentionally not stored
in normal Git history. On Windows, download and verify the official release:

```powershell
cd android_app
.\download-stockfish.ps1
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

The script verifies both the release tar SHA-256 and extracted binary SHA-256.
The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The current
package contains arm64-v8a Stockfish, matching modern 64-bit-only Android phones.
The rest of the app supports API 26+. Add another verified binary under the
matching `jniLibs/<abi>/libstockfish.so` directory to support another CPU ABI.

Install a development build:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## First safe use

1. Keep motor/magnet power physically removable and the mechanism clear.
2. Tap **Connect**, scan, and choose the HC-08. The strongest likely HC-08 is
   ranked first, but its address is still shown for confirmation.
3. Open **Checks** and tap **Run checks**. This sends only PING, INFO, TELEM, and
   BOARD requests.
4. Confirm firmware, sensor, and input results. Released A6 should normally be
   at least 700 and near 1023.
5. Use **Board** to reconcile every red missing and orange extra square.
6. Test physical limit behavior locally before **Start + calibrate**.

The red **HALT** control is best-effort radio delivery, not an emergency-stop
system. It intentionally leaves the carriage position unknown. Cut physical
power if motion continues or the link is unavailable.

## Contributor start

Read [`ARCHITECTURE.md`](ARCHITECTURE.md), then run the unit tests. Use
**Simulator** for UI and game-flow changes; `SIMMOVE e2e4` in **Dev** represents
a physical human move. New firmware capabilities should be optional additions,
parsed into typed state, simulated, and covered by tests before UI work.

Never add a generic "send anything" bypass. Read-only, session-control, motion,
emergency, and unknown commands are deliberately separate. Never make a stale
snapshot look live, silently merge sensor occupancy with logical identity, or
clear a physical fault from the phone.

## Privacy and files

Logs stay in app-private storage and are capped to the newest 20 sessions.
Support bundles redact BLE addresses, network camera URLs, and URL credentials.
Camera frames and games are included only when the user explicitly saves them.
Uninstalling the app removes private logs and settings.

This app is GPL-3.0-or-later. See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md);
complete Apache licensing and the Commons Lang NOTICE ship in `assets/third_party/`
inside every APK.
