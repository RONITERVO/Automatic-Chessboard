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
