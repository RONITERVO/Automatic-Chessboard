# Remote operation safety model

This project is hobby hardware, not a safety-rated motion controller.

## Safety boundary

The Arduino Nano owns step timing, physical switch checks, magnet output, and
motion-fault state. Windows can request actions but cannot prove that motion,
switches, or the magnet behaved physically. The camera and Bluetooth connection
are observational aids, not interlocks.

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

## Fault recovery

Remote halt intentionally marks the carriage position unknown. Inspect the board
locally, remove the obstruction, test switches, and recalibrate. The public app
does not include a remote “clear fault and continue” control because doing so
would conceal uncertainty rather than resolve it.

## Camera limitations

Camera latency, buffering, reconnects, field of view, lighting, and frozen frames
can all hide hazards. Support bundles never collect camera frames. The app does
not perform computer-vision safety decisions.
