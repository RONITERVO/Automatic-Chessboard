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
(`MOTOR_MICROSTEPS = 1`). Slow movement accelerates from approximately 167 to
200 full steps per second and decelerates again before stopping. If the driver
jumpers or wiring are changed later, change this constant to the same value.
The firmware then scales the steps per square, homing limit, ramp length, and
step intervals to preserve the existing travel distance and approximate
physical speeds.

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
3. runs a large rectangular travel pattern 50 times; and
4. returns to both home switches after every cycle and compares the measured
   step counts with the baseline.

A difference greater than four full steps, scaled to the configured microstep
mode, is reported as step loss. Either shared limit/button input stops regular
test motion. During a homing approach the target switch is expected to trigger,
so the other shared input is the abort control. Any abort or homing failure
invalidates the trolley position and requires calibration before further use.

This detects accumulated position drift using the existing switches. It cannot
prove that no individual step was missed and later cancelled by a missed step
in the opposite direction. Detecting every stall in real time requires motor
encoders or a driver with suitable diagnostic feedback.
