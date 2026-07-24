# Automatic Chessboard

Arduino firmware for a CoreXY automatic chessboard with:

- two STEP/DIR stepper-motor drivers;
- an electromagnet for moving pieces;
- a multiplexed 8x8 reed-sensor matrix;
- a 16x2 I2C LCD and two-button interface; and
- the Micro-Max chess engine.

## Hardware assumptions

The sketch uses the pin assignments and motion calibration in `global.h`. The
motor interface expects STEP/DIR drivers such as the A4988; motor current and
microstep selection are configured on the driver hardware.

## Building

Open `Automatic_Chessboard_V3_27_i2c_value.ino` in the Arduino IDE, install a
compatible `LiquidCrystal_I2C` library, select the board used by the project,
and compile or upload the sketch.

Review the pin assignments, travel calibration, limit-switch behavior, driver
current limit, and microstep settings before powering the motion hardware.

## Motor-driver configuration

`MOTOR_MICROSTEPS` in `global.h` must match the hardware configuration of both
motor drivers. For an A4988, the common modes are:

| Microsteps | MS1 | MS2 | MS3 |
| ---: | :---: | :---: | :---: |
| 1 | LOW | LOW | LOW |
| 2 | HIGH | LOW | LOW |
| 4 | LOW | HIGH | LOW |
| 8 | HIGH | HIGH | LOW |
| 16 | HIGH | HIGH | HIGH |

The current high-friction-drive profile remains in full-step mode
(`MOTOR_MICROSTEPS = 1`). Its measured working values ramp slow movement from
approximately 250 to 278 full steps per second and decelerate again before
stopping. If the driver jumpers or wiring are changed later, change this
constant to the same value. The firmware then scales the steps per square,
homing limit, ramp length, and step intervals to preserve the existing travel
distance and approximate physical speeds.

Set the driver current limit from the motor's rated phase current and the sense
resistors fitted to the particular driver board. For an A4988, consult that
board's documentation before using the common `Imax = Vref / (8 * Rsense)`
calculation; clone boards use different sense-resistor values. Never move a
driver or motor connection while motor power is applied.

## Step-loss test

The service menu includes **STEP LOSS**, a long-running motion repeatability
test. Remove all pieces from the board before starting it. The test:

1. homes both axes and restores the normal e7 service position;
2. repeats the homing pass to establish a switch-to-position baseline;
3. lets the Micro-Max chess engine play both sides of a sequence of legal games;
4. mirrors those games on a separate in-memory board, so captures, en passant,
   promotions, and castling are exercised without reading or modifying the
   real reed-sensor board state;
5. runs the same production travel planner used for real moves, with the
   electromagnet kept off because the physical board must be empty; and
6. returns to both home switches every eight half-moves and compares the
   measured step counts with the baseline, for a total of 200 half-moves.

A difference greater than four full steps, scaled to the configured microstep
mode, is reported as step loss. Either shared limit/button input stops regular
test motion. During a homing approach the target switch is expected to trigger,
so the other shared input is the abort control. Any abort or homing failure
invalidates the trolley position and requires calibration before further use.

This detects accumulated position drift using the existing switches. It cannot
prove that no individual step was missed and later cancelled by a missed step
in the opposite direction. Detecting every stall in real time still requires
motor encoders or a driver with suitable diagnostic feedback.

## Piece-retention travel planner

For a weak magnet, the production planner uses the shortest straight path for
normal legal moves. A legal rook, bishop, queen, pawn, or king move already has
a clear corridor, and a straight path has no corner-induced lateral jerk.
Knight moves use a 12-segment cubic S-curve through the middle of the normal
L-shaped clearance corridor instead of two sharp 90-degree corners. Capture
removal and the rook part of castling use rounded cubic detours. Unloaded head
travel is also coordinated directly in X/Y instead of moving one axis and then
the other.

Each curve ends on an exact whole-step destination; interpolation rounding is
not allowed to accumulate between moves. All paths keep the current tested
full-step timing values (`2000`, `1800`, and `1000` microseconds) unchanged.

## Captured-piece parking

Black's captured pieces are parked along the calibration side of the board.
The calibrated e7 offset places the left limit at approximately `x = 0.35` in
board-square coordinates; the playing-field edge is `x = 0.50`. Parking uses a
conservative `x = 0.48` center line, just outside the playing field and about
25 full steps away from the limit switch.

Eight tracked parking slots sit on the half-rank lanes from 0.5 through 7.5.
For each capture the firmware uses a free lane immediately beside the captured
square, moves vertically by only half a square, then follows a rounded turn to
the outside rail. This avoids pulling a held piece through occupied squares.
The setup screen asks the player to prepare both the board and this side area,
and the parking map is reset only after the starting position passes its sensor
check.

There are no reed sensors outside the 8x8 playing field, so the firmware does
not claim to sense untracked objects on the rail. If both safe exit lanes for a
capture are already occupied in its tracked parking map, the head moves under
the captured piece with the magnet off and the LCD asks the player to move that
piece off-board. Pressing A continues only after the board sensor confirms that
the indicated square is empty; B safely ends the game. The firmware never
drives a held piece toward a parking location it already considers blocked.
