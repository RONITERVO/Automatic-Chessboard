# ACB serial protocol

Commands and events are printable ASCII terminated by CR, LF, or CRLF at 9600
baud. BLE packets may split a line at any byte; clients must buffer until a line
terminator. Firmware 4.4.0 advertises `ACB2` monitoring while retaining the
legacy `READY ACB1`, `PONG ACB1`, and `STATUS ACB1` responses.

## Compatibility handshake

Send `PING`, then `INFO`:

```text
> PING
< PONG ACB1
> INFO
< INFO ACB2 4.4.0 BOARD,TELEM,REMOTE,ESTOP,BTTEST,SWTEST,CALIBRATE,MANUAL,SENSORFRAME,PLANROUTE,DEVPATH,DEVJOG
```

Clients must use the capability list instead of assuming that every firmware
supports every command. A missing `INFO` response indicates legacy firmware.
The Windows app uses the verified `PLANROUTE` transaction when advertised and
retains `PLAY` as a compatibility fallback for firmware 4.0 and earlier.

## Read-only commands

- `PING` or `HELLO` -> `PONG ACB1`
- `INFO` -> protocol, firmware version, and comma-separated capabilities
- `STATUS` -> legacy `STATUS ACB1 sequence homed remote`
- `TELEM` -> versioned visual-monitoring telemetry
- `BOARD` -> sixteen hexadecimal occupancy digits
- `BTTEST` -> idle-only HC-08 AT test; normally run over USB
- `SWTEST` -> idle-only guided press/release test for both shared limit inputs;
  it keeps the magnet off, performs no movement, rejects crossed activation,
  and reports the pressed A6 raw value

`BOARD` contains two hexadecimal digits per normalized row, rank 8 through rank
1. Bit 0 is file a and bit 7 is file h. A set bit means a reed sensor sees a
magnetic piece. It contains no piece-type or colour information. Firmware
applies the fixed hardware row map before producing this snapshot.

`TELEM` fields are positional to minimize Nano memory:

```text
TELEM ACB2 sequence homed remote fault magnet x y a_released b_released b_raw free_ram uptime_s
```

- Booleans are `0` or `1`.
- `x` and `y` are the firmware's calculated carriage square.
- `b_raw` is the A6 ADC value, normally near 1023 when released and 0 when active.
- `free_ram` is an instantaneous stack-to-heap estimate.
- `uptime_s` wraps with the Nano's `millis()` counter.

The fixed reported-to-physical rank mapping is `8->1, 7->2, 2->3, 1->4, 4->5,
3->6, 6->7, 5->8`; files A-H are unchanged. This is part of the
hardware/firmware contract and is not a runtime setting.

## Collision-safe route transactions (firmware 4.1+)

The host plans the complete labeled rearrangement. The Nano executes only one
straight, orthogonal, sensor-verified drag at a time. A normal transaction is:

```text
> PLAN e7e5---
< PLAN READY
> BOARD
< BOARD <16 hex digits>
> DRAG e7e6
< MOVING PIECE e7e6
< MOVED PIECE e7e6
> BOARD
< BOARD <16 hex digits>
> DRAG e6e5
< MOVING PIECE e6e5
< MOVED PIECE e6e5
> BOARD
< BOARD <16 hex digits>
> COMMIT
< DONE e7e5
```

`PLAN` has a fixed seven-character payload:

```text
PLAN <from><to><mode><capture-square-or-->
```

- `mode` is `-` for an ordinary move, `q`, `r`, `b`, or `n` for promotion,
  and `k` or `c` for standard king-side or queen-side castling.
- Captures name the occupied square removed before `PLAN READY`. For en passant
  this is the captured pawn's square, not the move destination.
- `DRAG` endpoints must share a file or rank. Every intermediate square must be
  empty. A turning host route is split at square centres, where the magnet is
  released and reacquired for the next straight run.
- The Nano checks its complete 64-square frame before accepting `PLAN`, before
  each `DRAG`, after every physical move, and before `COMMIT`.
- Windows independently requests and checks `BOARD` after `PLAN` and every
  `DRAG`. It sends only one transaction command at a time and waits for the
  exact acknowledgement.
- `COMMIT` succeeds only when occupancy matches the final chess move, including
  capture and standard castling. If no physical change occurred and the start
  frame is intact, it returns `PLAN CANCELLED` instead.

Possible transaction errors include `ERR NOT READY`, `ERR BAD PLAN`,
`ERR PLAN STATE`, `ERR NO PLAN`, `ERR SOURCE EMPTY`, `ERR TARGET FULL`,
`ERR BAD ROUTE`, `ERR ROUTE BLOCKED`, `ERR FINAL SENSORS`, and
`ERR PLAN INCOMPLETE`. A sensor or motion failure after movement latches a
fault. Do not retry from an assumed position: inspect all squares, stop the
session, and recalibrate where required.

The safety proof is occupancy-based. Reed switches cannot establish piece
identity, polarity, precise centring, magnet current, or missed gantry steps.
The trusted host owns chess legality and labeled-piece planning; the Nano owns
deterministic motion and occupancy transition checks.

## Legacy game and motion commands

- `START W` or `START B` selects the human colour and requests calibration.
- `ACCEPT` or `REJECT` resolves a reported human move.
- `PLAY e7e5` requests an ordinary automatic move.
- `PLAY e1g1 C` marks castling.
- `PLAY e5d6 E` marks en passant.
- `PLAY e7e8q` requests promotion; the physical piece is replaced by hand.
- `GAMEOVER 1-0`, `GAMEOVER 0-1`, or `GAMEOVER 1/2-1/2` closes the session.
- `STOP` stops a session when the main loop is available.

Important events include `SETUP PRESS A`, `SESSION W|B`, `TURN HUMAN|COMPUTER`,
`MOVE e2e4`, `MOVING e7e5`, `DONE e7e5`, `PROMOTE q`, `STOPPED`, and
`ERR reason`.

`PLAY` is accepted only in the remote wait-host state. A host must never send a
second automatic move before receiving `DONE`.

## App calibration and direct square movement

Firmware 3.31+ provides guarded maintenance commands. They are accepted only
from the idle main menu, outside a remote game, and never while a motion fault
is latched.

- `CALIBRATE` performs the complete reference routine. It emits `CALIBRATING`,
  then `CALIBRATED e6 W<steps> B<steps>` only when the head is homed, parked at
  e6, and the magnet is off. The companion requests fresh `TELEM` and
  independently confirms `homed=1`, `fault=0`, `magnet=0`, `x=5`, and `y=6`
  before enabling movement.
- `HEAD e4` moves only the head and keeps the electromagnet off. It emits
  `MOVING HEAD e4`, then `MOVED HEAD e4`.
- `PIECE e2e4` scans the reed matrix before moving. The source must be occupied
  and the destination empty. The head first reaches e2 with the magnet off,
  energizes it only for the carried segment, and verifies that e2 became empty
  and e4 occupied before emitting `MOVED PIECE e2e4`.
- Firmware 4.4 accepts `PIECE` only when its square centres share a file, rank,
  or diagonal. Turning and knight moves use `PLANROUTE`; direct unsupported
  geometry returns `ERR BAD ROUTE` before the head or magnet moves.

Possible rejections include `ERR CALIBRATE`, `ERR SOURCE EMPTY`,
`ERR TARGET FULL`, `ERR SENSORS`, `ERR BUSY`, `ERR FAULT`, and `ERR MOTION`.
Manual motion uses telemetry sequence 21 while active. `!` remains the immediate
best-effort remote halt during calibration and direct movement.

## Developer motion tools

`DEVPATH` advertises `PATH e2e4`, a developer-only, magnet-free command that
exercises the production queen-aligned piece planner without changing
sensor state. It has the same calibration, fault, idle-state, and emergency-halt
guards as direct movement and returns `MOVED PATH e2e4`. It is intended for the
repository endurance tool, not normal game clients.

`DEVJOG` advertises guarded `JOG W+`, `JOG W-`, `JOG B+`, and `JOG B-` commands.
Each pulses only the selected CoreXY driver for 20 full steps, keeps the magnet
off, and invalidates the carriage position. Use them only for one-driver-at-a-
time commissioning with the other driver and magnet power disconnected. A
successful jog returns `MOVED JOG W+`; calibration is required before any
coordinate-based movement.

The firmware independently limits any continuous magnet command to 30 seconds.
On timeout it switches the magnet off, invalidates the carriage position,
latches a motion fault, and reports `ERR MAGNET TIMEOUT`.

## Best-effort emergency halt

The single printable character `!` is reserved for emergency halt. It does not
wait for a line terminator and is checked from both USB and Bluetooth inside the
motor step loops. The response is:

```text
ESTOP REMOTE
```

The firmware disables the magnet, marks carriage position unknown, enters the
motion-fault screen, and requires local inspection/recovery. This is not a
certified emergency stop: radio loss, host failure, electrical faults, or MCU
failure can prevent it. Physical power isolation remains authoritative.

## Polling guidance

Use at most one outstanding request. Alternate `TELEM` and `BOARD` every 1-10
seconds while idle. Pause normal polling during any route transaction or after
`MOVING`, and resume only after its terminal `MOVED HEAD`, `MOVED PIECE`,
`MOVED PATH`, `MOVED JOG`, `DONE`, `PLAN CANCELLED`, `ERR`, `STOPPED`, or
`ESTOP`. Precise motor loops intentionally delay ordinary responses.
