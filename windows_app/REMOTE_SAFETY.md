# Remote operation safety model

This project is hobby hardware, not a safety-rated motion controller.

## Safety boundary

The Arduino Nano owns step timing, physical switch checks, magnet output,
straight-corridor checks, sensor transitions, and motion-fault state. Windows can
request actions and verify occupancy reports, but it cannot prove that motion,
switches, piece centring, or the magnet behaved physically. The camera and
Bluetooth connection are observational aids, not interlocks.

Always provide a physical method to remove motor and magnet power. Someone near
the board must be able to use it whenever remote movement is attempted.

## Before remote movement

- Confirm live video, not a frozen frame.
- Check the complete travel area, not only the chess squares.
- Confirm both limit inputs are released and have been tested locally.
- Resolve every red/orange occupancy mismatch.
- Do not start from an unknown or stale carriage position.
- Keep children, pets, loose clothing, cables, and ferromagnetic objects away.
- Do not leave the board moving unattended.

## Route transaction guarantees

Firmware 4.1 verifies the complete occupancy frame before a route begins, before
and after every straight drag, and before commit. Windows independently checks a
fresh `BOARD` frame after capture removal and every drag. These checks detect
many stale-plan, dropped-piece, wrong-square, blocked-corridor, and link-ordering
failures.

They do not identify pieces. Two pieces exchanged between occupied squares look
identical to the reed matrix. The checks also cannot measure gantry coordinates,
coil current, piece alignment within a square, magnetic attraction between
pieces, or an obstruction that does not change a reed switch. Orthogonal paths
are intentionally conservative, but physical supervision remains required.

## App-controlled play without reeds

`APPBOARD` mode is an explicit loss of sensor proof, not a degraded automatic
fallback. The app and Nano cross-check only the occupancy implied by commands.
They cannot detect a dropped piece, missed step, accidental manual change, or a
piece that never followed the magnet. Every human move must be selected in the
app and every completed move must be compared visually with the preview before
continuing. Never confirm through an obstructed, stale, or uncertain view.

Choose mismatch for any doubt. The app stops and invalidates the game; inspect
the entire board and recalibrate before starting over. A disconnect invalidates
the mode even when no movement was in progress. Do not move pieces manually
except for a prompted promotion replacement, and never switch to or from reed
authority inside a game.

## Uncertain transaction recovery

Before the first physical action, an unchanged route transaction can be
cancelled. Once capture removal or any `DRAG` is sent, a timeout, disconnect, or
unexpected acknowledgement makes the arrangement uncertain. The app does not
retry or infer success. It closes the session and requires inspection of every
square against the displayed logical position.

After inspection, correct the physical position manually, verify fresh `BOARD`
and `TELEM` data, clear any hardware fault locally, and recalibrate if carriage
position is unknown. Start a new session; do not continue the old transaction.

## General fault recovery

Remote halt intentionally marks the carriage position unknown. Inspect the board
locally, remove the obstruction, test switches, and recalibrate. The public app
does not include a remote "clear fault and continue" control because doing so
would conceal uncertainty rather than resolve it.

## Camera limitations

Camera latency, buffering, reconnects, field of view, lighting, and frozen frames
can all hide hazards. Support bundles never collect camera frames. The app does
not perform computer-vision safety decisions.
