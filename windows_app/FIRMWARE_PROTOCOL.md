# ACB serial protocol

Commands and events are printable ASCII terminated by CR, LF, or CRLF at 9600
baud. BLE packets may split a line at any byte; clients must buffer until a line
terminator. Firmware 3.28 advertises `ACB2` monitoring while retaining the legacy
`READY ACB1`, `PONG ACB1`, and `STATUS ACB1` responses.

## Compatibility handshake

Send `PING`, then `INFO`:

```text
> PING
< PONG ACB1
> INFO
< INFO ACB2 3.28 BOARD,TELEM,REMOTE,ESTOP,BTTEST
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

`BOARD` contains two hexadecimal digits per internal row, rank 8 through rank 1.
Bit 0 is file a and bit 7 is file h. A set bit means a reed sensor sees a magnetic
piece. It contains no piece-type or colour information.

`TELEM` fields are positional to minimize Nano memory:

```text
TELEM ACB2 sequence homed remote fault magnet x y a_released b_released b_raw free_ram uptime_s
```

- Booleans are `0` or `1`.
- `x` and `y` are the firmware's calculated carriage square.
- `b_raw` is the A6 ADC value, normally near 1023 when released and 0 when active.
- `free_ram` is an instantaneous stack-to-heap estimate.
- `uptime_s` wraps with the Nano's `millis()` counter.

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
