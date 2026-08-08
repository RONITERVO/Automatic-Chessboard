# Design lineage and attribution

## Base project

This repository is a modified implementation of
[**Automated Chessboard**](https://www.instructables.com/Automated-Chessboard/)
by Instructables user
[**Greg06**](https://www.instructables.com/member/Greg06), licensed under
[CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/).

The base project established the overall arrangement used here: a CoreXY-style
mechanism below a magnetic chessboard, an Arduino Nano, two STEP/DIR motor
drivers, an electromagnet switched through a transistor and flyback diode, 64
normally-open reed switches read through four 16-channel multiplexers, an I2C
display, two player controls, and a compact Micro-Max chess engine.

The current documentation was written for this repository from prototype
measurements, current firmware behavior, and component datasheets. It does not
reproduce the source project's prose, diagrams, or photographs.

## Material changes in this implementation

This is not a drop-in reproduction of the base build. Important changes
include:

- a 300 x 300 mm playing field with 37.5 mm square pitch;
- reused JGAurora A3S printer motors, V-slot rails, V-wheels, GT2 belt,
  pulleys, idlers, and frame hardware instead of a fixed purchased frame kit;
- a 24 V H2520 electromagnet and 24 V motor rail instead of the base project's
  12 V power arrangement;
- HR4988SQ StepStick-compatible carriers with measured `R100` sense resistors,
  full-step operation, and prototype-specific current calibration;
- protected power distribution with a main fuse, latching cutoff,
  reverse-polarity protection, local driver capacitors, and a regulated 5 V
  buck feeding the Nano `5V` pin;
- two controls that also serve as calibration endstops, including an external
  pull-up for the Nano's analog-only A6 pin;
- an HC-08 BLE interface and a versioned monitoring/control protocol;
- a different LCD library with automatic PCF8574-backpack detection;
- a measured sensor-channel permutation matching the assembled board;
- persistent position journaling, guarded calibration, service diagnostics,
  step-loss testing, capture-bin routing, and revised carried-piece paths;
- a separate GPL-licensed Windows monitoring and Stockfish companion; and
- project-specific enclosure, tile, and TPU spacer models plus separately
  attributed third-party printable components.

## Micro-Max

The compact chess engine was written by **H. G. Muller**. The local
`Micro_Max.cpp` remains recognizably derived from the version included with the
base project. Upstream information is available from the
[Micro-Max author page](https://home.hccnet.nl/h.g.muller/max-src2.html).
The licensing qualification in [`LICENSE.md`](LICENSE.md) applies.

## Third-party 3D models

The original archives, source links, authors, modification statements, and
licenses for imported models are kept beside each package under
[`hardware/printFiles/third_party`](hardware/printFiles/third_party/). The
index in [`hardware/printFiles/README.md`](hardware/printFiles/README.md)
summarizes them.

No attribution implies endorsement by Greg06, H. G. Muller, Thingiverse, or
any third-party model creator.
