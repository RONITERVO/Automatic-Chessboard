# ACB serial protocol

Commands and events are printable ASCII terminated by CR, LF, or CRLF at 9600
baud. BLE packets may split a line at any byte; clients must buffer until a line
terminator. Firmware 4.0.0 advertises `ACB2` monitoring while retaining the legacy
`READY ACB1`, `PONG ACB1`, and `STATUS ACB1` responses.

## Compatibility handshake

Send `PING`, then `INFO`:

```text
> PING
< PONG ACB1
> INFO
< INFO ACB2 4.0.0 BOARD,TELEM,REMOTE,ESTOP,BTTEST,SWTEST,CALIBRATE,MANUAL,SENSORFRAME,DEVPATH,DEVJOG
```

Clients must use the capability list instead of assuming that every firmware
supports every command. A missing `INFO` response indicates legacy firmware.

## Read-only commands

- `PING` or `HELLO` → `PONG ACB1`
- `INFO` → protocol, firmware version, and comma-separated capabilities
- `STATUS` → legacy `STATUS ACB1 sequence homed remote`
- `TELEM` → versioned visual-monitoring telemetry
- `BOARD` → sixteen hexadecimal occupancy digits
- `BTTEST` → idle-only HC-08 AT test; normally run over USB
- `SWTEST` → idle-only guided press/release test for both shared limit inputs;
  it keeps the magnet off, performs no movement, rejects crossed activation,
  and reports the pressed A6 raw value

`BOARD` contains two hexadecimal digits per normalized row, rank 8 through rank 1.
Bit 0 is file a and bit 7 is file h. A set bit means a reed sensor sees a magnetic
piece. It contains no piece-type or colour information. Firmware applies the
fixed hardware row map before producing this snapshot.

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
3->6, 6->7, 5->8`; files A-H are unchanged. This is part of the hardware/firmware
contract and is not a runtime setting.

## Game and motion commands

- `START W` or `START B` selects the human colour and requests calibration.
- `ACCEPT` or `REJECT` resolves a reported human move.
- `PLAY e7e5` requests an ordinary automatic move.
- `PLAY e1g1 C` marks castling.
- `PLAY e5d6 E` marks en passant.
- `PLAY e7e8q` requests promotion; the physical piece is replaced by hand.
- `GAMEOVER 1-0`, `GAMEOVER 0-1`, or `GAMEOVER 1/2-1/2` closes the session.
- `STOP` stops a session when the main loop is available.

Important events include `SETUP PRESS A`, `SESSION W|B`, `TURN HUMAN|COMPUTER`,
`MOVE e2e4`, `MOVING e7e5`, `DONE e7e5`, `PROMOTE q`, `STOPPED`, and `ERR reason`.

`PLAY` is accepted only in the remote wait-host state. A host must never send a
second automatic move before receiving `DONE`.

## App calibration and direct square movement

Firmware 3.31+ provides guarded maintenance commands. They are accepted only from the
idle main menu, outside a remote game, and never while a motion fault is latched.

- `CALIBRATE` performs the complete reference routine. It emits `CALIBRATING`,
  then `CALIBRATED e6 W<steps> B<steps>` on firmware 4.0.0 only when the head is
  homed, parked at e6, and the magnet
  is off. The companion requests fresh `TELEM` and independently confirms
  `homed=1`, `fault=0`, `magnet=0`, `x=5`, and `y=6` before enabling movement.
- `HEAD e4` moves only the head and keeps the electromagnet off. It emits
  `MOVING HEAD e4`, then `MOVED HEAD e4`.
- `PIECE e2e4` scans the reed matrix before moving. The source must be occupied
  and the destination empty. The head first reaches e2 with the magnet off,
  energizes it only for the carried segment, and verifies that e2 became empty
  and e4 occupied before emitting `MOVED PIECE e2e4`.

Possible rejections include `ERR CALIBRATE`, `ERR SOURCE EMPTY`,
`ERR TARGET FULL`, `ERR SENSORS`, `ERR BUSY`, `ERR FAULT`, and `ERR MOTION`.
Manual motion uses telemetry sequence 21 while active. `!` remains the immediate
best-effort remote halt during calibration and direct movement.

Firmware 4.0.0 also advertises `DEVPATH`. `PATH e2e4` is a developer-only,
magnet-free motion command that exercises the production straight or knight
piece planner without changing sensor state. It has the same calibration,
fault, idle-state, and emergency-halt guards as direct movement and returns
`MOVED PATH e2e4`. It is intended for the repository endurance tool, not normal
game clients.

`DEVJOG` advertises the guarded `JOG W+`, `JOG W-`, `JOG B+`, and `JOG B-`
commands. Each pulses only the selected CoreXY driver for 20 full steps, keeps
the magnet off, and invalidates the carriage position. Use them only for
one-driver-at-a-time commissioning with the other driver and magnet power
disconnected. A successful jog returns `MOVED JOG W+`; calibration is required
before any coordinate-based movement.

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

Use at most one outstanding read-only request. Alternate `TELEM` and `BOARD`
every 1–10 seconds. Pause normal polling after `MOVING` and resume after `DONE`,
`ERR`, or `ESTOP`. Precise motor loops intentionally delay ordinary responses.
