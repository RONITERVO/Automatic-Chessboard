# Firmware protocol 5.0.1

The Nano uses newline-terminated printable ASCII at 9600 baud over USB and the
HC-08 transparent BLE link. Version 5.0.1 is one coordinated firmware/companion
contract; it does not negotiate old capability sets or fall back to old motion
commands.

## Exact release handshake

Each transport must establish its own match before sending a control or motion
command:

```text
> HELLO 5.0.1
< HELLO 5.0.1
> INFO
< INFO ACB3 5.0.1 NANO
```

The MKS build reports `MKS_GEN_L_V1`; simulators report `SIM`. A different `HELLO`
version returns `ERR VERSION` and clears that transport's agreement. `INFO`,
`TELEM`, and `BOARD` remain readable before agreement. `STOP` and the byte-level
`!` emergency halt also remain available so a mismatched tool can make a
best-effort stop and inspect state, but it cannot control or move the board.

`PING`, `STATUS`, `PLAY`, capability strings, and pre-5.0 compatibility behavior
are not part of this protocol.

## Read-only state

- `INFO` → `INFO ACB3 firmware hardware`, where hardware is `NANO`,
  `MKS_GEN_L_V1`, or `SIM`
- `TELEM` → the live controller frame below.
- `BOARD` → 16 hexadecimal digits, two per physical rank from rank 8 to rank 1;
  each byte uses bit 0 for file a through bit 7 for file h.
- `GEOMETRY` → `GEOMETRY ACB3 file_pitch rank_pitch black_park white_park microsteps`
- `ALIGN STATUS` → `ALIGN IDLE` or the active measurement report.
- `BTTEST` and `SWTEST` are explicit diagnostics. `SWTEST` requires local user
  interaction and must never be presented as a passive poll.

Telemetry is:

```text
TELEM ACB3 sequence homed remote fault magnet x y a_released b_released b_raw free_ram uptime_s
```

`remote` is `0` for standalone/idle, `1` for reed-authoritative companion play,
and `2` for app-authoritative play. A reported trolley square is a calculated
coordinate, not encoder feedback. `free_ram` is the minimum free SRAM observed
since boot, so temporary route-search stack peaks remain visible afterward.

All ordinary read-only requests must be serialized. BLE is slow enough that
multiple outstanding polls otherwise become stale and ambiguous.

## Session and direct controls

- `START W` / `START B` begins reed-authoritative companion play.
- `START W APP` / `START B APP` begins app-authoritative play from the standard
  starting position. Reed input is ignored for that entire session.
- `GAMEOVER ...` terminates the remote session and returns firmware to idle
  while leaving the result visible. The next `START` can therefore begin a new
  calibrated game directly; active states still reject `START` with `ERR BUSY`.
- `ACCEPT`, `REJECT`, and `GAMEOVER ...` advance or terminate the remote game
  state.
- `STOP` ends the remote session on a best-effort basis.
- `CALIBRATE` runs the production homing/reference routine and reports
  `CALIBRATED e6` only after success.
- `HEAD e4` moves the carriage without magnet pickup after calibration.
- `PIECE e2e4` performs one guarded direct carry. Its endpoints must be
  queen-aligned and its physical corridor must be clear.
- `ALIGN <square> H|M`, `NUDGE X+|X-|Y+|Y-`, and `ALIGN END` implement the
  recoverable geometry-measurement workflow.
- `PATH` and `JOG` are guarded developer commissioning commands, never normal
  game commands.

Every motion command can fail with an `ERR ...` response. A failure is not
permission to retry from an assumed position.

## Verified route transaction

The companion is the expensive planner; the Nano is the deterministic executor.
The host first requests a fresh `BOARD` snapshot matching its logical occupancy,
then opens a fixed-width plan:

```text
PLAN e2e4---
PLAN e4d5-d5
PLAN a7a8q--
PLAN e1g1k--
PLAN e1c1c--
```

The seven payload characters are source, target, mode (`-`, promotion piece,
`k`, or `c`), and captured square or `--`. A valid header returns `PLAN READY`
without moving hardware.

While the plan owns the board:

- `DRAG e2e4` performs exactly one straight orthogonal square-centre run and
  returns `MOVED PIECE e2e4` only after the authoritative occupancy transition
  is proven.
- `REMOVE` removes the tracked captured piece to the full-height left bin and
  returns `REMOVED` only after proof. The capture may already have been routed
  by verified drags to an a-file exit; the Nano can also find a shortest empty
  orthogonal route itself.
- `BOARD` proves the frame between every physical action.
- `COMMIT` accepts only the exact derived final occupancy and returns `DONE
  <from><to>`. If no physical state changed, it returns `PLAN CANCELLED`.

The normal companion sequence is:

```text
PLAN -> BOARD -> (DRAG -> BOARD)* ->
  [capture DRAG(s) -> BOARD -> REMOVE -> BOARD] ->
  (DRAG -> BOARD)* -> COMMIT
```

Turning paths are split into straight runs and release/reacquire only at square
centres. Companion routing first searches all empty paths to any `a1`-`a8` bin
exit; it moves unrelated pieces only if every exit is disconnected. Temporary
pieces must be restored before commit.

## App-authoritative play

In `APP` mode, both human and engine moves originate in the companion. The Nano
maintains an independent command-derived occupancy frame and returns it from
`BOARD`; reed readings never silently enter the game. After `DONE`, the app must
require a whole-board visual confirmation before applying the logical move or
starting another one. Manual physical movement is prohibited except when the app
explicitly prompts for promotion-piece replacement.

A mismatch, route error, halt, or connection loss invalidates the session.
Send `STOP` only while transport remains available; after link loss, do not claim
delivery. Inspect the physical board and recalibrate before a new session.

## Emergency behavior

The single `!` byte is checked inside motion loops, turns off the magnet, stops
step output, latches a motion fault, and makes the carriage position unknown.
It is best-effort communication, not a safety-rated emergency stop. Hardware
must provide a physical way to remove motor and magnet power.
