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

The default remains full-step mode (`MOTOR_MICROSTEPS = 1`). After changing the
driver jumpers or wiring, change this constant to the same value. The firmware
then scales the steps per square, homing limit, ramp length, and step intervals
to preserve the existing travel distance and approximate physical speeds.

Set the driver current limit from the motor's rated phase current and the sense
resistors fitted to the particular driver board. For an A4988, consult that
board's documentation before using the common `Imax = Vref / (8 * Rsense)`
calculation; clone boards use different sense-resistor values. Never move a
driver or motor connection while motor power is applied.
