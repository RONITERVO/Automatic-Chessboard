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

## Captured-piece bin

Black's captured pieces are released into the bin along the calibration side.
The calibrated e7 offset places the left limit at approximately `x = 0.35` in
board-square coordinates; the playing-field edge is `x = 0.50`. Release uses a
conservative `x = 0.48` center line, just outside the playing field and about
25 full steps away from the limit switch.

For every capture, the head first moves half a rank toward the lower clearance
line and then follows a rounded cubic turn to that left-side release point.
The curve uses the normal carrying speed and decelerates completely at its
endpoint. The head remains stationary through the magnet's release delay and
for another 400 ms while the piece falls into the bin, then retraces the same
rounded route without the piece. Nothing is stored on the travel rail, so later
captures cannot collide with earlier ones.

The AI-vs-AI step-loss test recognizes captures on its virtual chessboard and
runs this exact same exit curve and empty return route. Its electromagnet and
release dwell remain disabled because no physical pieces are present, but the
motor workload and off-board travel are included in the endurance measurement.
