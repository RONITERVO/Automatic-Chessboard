# Mechanical interface

The frame is intentionally adaptable. The tested build reuses 3D-printer
V-slot extrusion, V-wheels, belts, pulleys, and motors, but the firmware needs
only the following measurable interfaces.

## Required geometry

- Playing field: 300 x 300 mm.
- Square pitch: 37.5 mm in both axes.
- Eight square centers must form a repeatable 37.5 mm grid.
- The carriage must reach every square center, the calibration switches, the
  e6 park location, and the left-side capture release path without collision.
- The mechanism must resist racking and belt slip over repeated diagonal
  moves. Both axes must remain square to the playing field.

The current firmware uses 195 full motor steps per square. That is a measured
value for this mechanism, not a universal CoreXY value. If pulley tooth count,
belt pitch, motor step angle, microstepping, or carriage geometry changes,
measure and update `FULL_STEPS_PER_SQUARE` and `MOTOR_MICROSTEPS` before normal
play.

## Reed tile requirements

- Place one normally-open reed switch at each logical square.
- The prototype leaves about 0.2 mm between the switch and the magnet-equipped
  piece. A different tile may use a larger gap only after every piece is tested
  on every square.
- Provide a slot or pocket that supports the switch body. Never bend a lead
  directly where it exits the glass capsule.
- Add strain relief before gluing. Hot glue may hold the cable and switch, but
  it must not preload or twist the glass.
- Route sensor wires away from motor, magnet, and belt movement. Label every
  cable with square and multiplexer/channel before the top is closed.
- Confirm that the H2520 carriage magnet does not falsely close neighboring
  reed switches through the board. The Windows visual monitor should be used
  to observe occupancy while moving the unpowered carriage by hand.

## Carriage and magnet

- Mount the H2520 magnet face parallel to the board underside.
- Use mechanical fasteners or a retained bracket; adhesive alone must not be
  the only thing preventing the magnet from falling into the belts.
- Keep a consistent air gap across the full travel. A tilted board produces
  weak pickup in one area and collisions in another.
- Provide cable flex relief so the magnet wire cannot catch a pulley or pull
  on the TIP120 connection.
- Verify that a piece releases reliably after the firmware's magnet-off dwell.
  Residual magnetism, excessive friction, or a sticky tile surface can make an
  electrically correct machine fail mechanically.

## Calibration switches

The two normally-open switches are also the two front-panel buttons outside
calibration. Mount them so the carriage actuates them before any hard stop, and
so a failed switch does not let the carriage eject a belt or crush wiring.

The current calibration sequence seeks the firmware's white switch first and
then the black switch. Because the exact donor-motor sides are not yet labelled,
do not infer switch identity from wire color or physical left/right position.
Use `COMMISSIONING.md` to identify and label `D11 / SWITCH_A` and
`A6 / SWITCH_B` before enabling homing.

## Acceptance tests for a custom frame

Before carrying a chess piece, the frame should pass all of these:

- Full travel by hand with power removed, with no binding or wire snagging.
- Both switches change state before a mechanical hard stop.
- Ten unloaded moves to each corner with no belt skip or pulley loosening.
- Repeated return to one marked square within 1 mm.
- A 15-minute unloaded motion test with no connector, driver, motor, or wire
  becoming unusually hot.
- Sensor view remains stable while the carriage moves with the electromagnet
  off.
