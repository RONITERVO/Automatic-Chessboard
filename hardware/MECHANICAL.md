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

The mechanism measures about 195 full motor steps across the nominal 37.5 mm
square pitch. Release firmware intentionally commands a 190-step logical pitch
and recenters the e6 calibration park, keeping every outer square about 3.3 to
3.5 mm inside the measured travel boundary so the carriage cannot contact the
frame. The prototype's 297 mm tile assembly is offset relative to its
calibration hardware, so its file axis is additionally translated about
6.5 mm toward the white switch/a-files. This places the 24 mm magnet under the
h-file tiles instead of against their outer edge. The safety inset and field
translation are motion calibration values, not universal CoreXY geometry. If
pulley tooth count, belt pitch, motor step angle, microstepping, carriage
geometry, board placement, or available clearance changes, measure and update
`FILE_PITCH_STEPS`, `RANK_PITCH_STEPS`, the two
`CALIBRATION_PARK_*_STEPS` values, and
`MOTOR_MICROSTEPS` before normal play.

## Confirmed donor-printer rail set

The tested build and the matching donor set use five pieces of 20 x 20 mm
V-slot extrusion. The lengths below were confirmed from the physical set; they
replace the earlier approximate ranges in this guide. They are independently
documented here as a cut list rather than reproducing the reference photograph.

| Qty | Rail function | Finished length |
| ---: | --- | ---: |
| 2 | Left and right side frame rails | **345 mm each** |
| 1 | Long frame cross rail | **350 mm** |
| 1 | Short frame cross rail | **315 mm** |
| 1 | Moving gantry rail | **395 mm** |

The compact procurement notation is `2 x 345 + 1 x 350 + 1 x 315 + 1 x 395
mm`. Label the pieces before assembly; the 395 mm gantry is intentionally the
longest rail and should not be confused with an outer cross rail.

The current public build recommendation follows this donor hardware and the
repository's 3D model rather than the exact dimensions of the base
Instructables frame.

| Interface | Recommended starting point | What must be verified |
| --- | --- | --- |
| Outer frame | Two 345 mm side rails, one 350 mm cross rail, and one 315 mm cross rail | Equal diagonals, 300 mm board clearance, bracket and endstop allowance |
| Moving gantry | One 395 mm rail | Full square-center reach and clearance from both outer rails |
| Linear guidance | Eight mini V-wheels, four eccentric spacers, four fixed spacers, and matching shims | No play, no bearing roughness, and free travel without preload binding |
| Drive | Two 20-tooth GT2 pulleys with 5 mm motor bores | Shaft fit, set-screw engagement, and agreement with motion calibration |
| Belt | Two CoreXY paths using 2 mm-pitch, 6 mm-wide GT2 belt; allow about 4 m total | Belt condition, parallel belt planes, independent tension, and no frame bowing |
| Idlers | Up to eight 6 mm-belt idlers on verified M5 stacks | Smooth surface against belt back; toothed surface against belt teeth; no flange rub |
| Frame joints | Approximately 8-10 right-angle brackets and about 30 matching M5 T-nuts | Squareness and resistance to racking under diagonal motion |

The detailed starter fastener assortment is in `BOM.md`. Exact axle screw
length depends on the printed bracket, pulley width, washers, shims, spacer,
and locknut. A screw must fully engage the locknut without bottoming in a motor
or protruding into a belt path.

Salvaged parts should be cleaned and inspected individually. Replace a V-wheel
with a flat spot, an idler with bearing play, a belt with damaged teeth, a motor
with an intermittent lead, or any fastener whose drive recess no longer accepts
full tightening torque.

## Printed alternatives

The `printFiles` directory includes separately attributed models for a 20 x
20 mm V-slot profile and compatible corner brackets. They are options for
experimentation, not evidence that printed plastic is structurally equivalent
to aluminium. A printed rail or bracket must pass the same squareness, racking,
repeatability, temperature, and endurance checks as donor aluminium hardware.
Layer orientation, creep, fastener compression, and bearing-surface wear must
be considered before using a printed component in the moving frame. Printed
rail replacements must preserve the 20 x 20 mm cross-section and reproduce the
five longitudinal lengths above. If a printer cannot make a rail in one piece,
the segment joints need positive alignment and reinforcement; glue-only butt
joints are not suitable for wheel-bearing or belt loads.

## Enclosure, sliding chassis, and board support

The working mechanism is built as a removable internal body that slides into
the outer case from the front. Four TPU spacers attach at the front and rear of
that body. They center it, restrain movement, support the enclosure/chassis
interface, and reduce vibration. Fit them with light, even compression: the
body must not rattle, but it must remain removable without bending the frame or
dragging cables through the case.

The tested case was printed with a 0.8 mm nozzle, three perimeters, and a slicer
extrusion-width limit of about 0.82 mm, giving a nominal 2.4 mm wall, top, and
floor. These settings describe the prototype rather than imposing one printer
profile. A substitute case must remain flat, keep the board and chassis
supported, and preserve access to the cutoff, fuses, USB connector, driver
adjusters, and removable harnesses.

The prototype's 2.2 mm tiles are glued to a repurposed 3D-printer glass door.
Because the glass is about as thick as the 2.4 mm case top, stacking the glass
board on an intact top would add a second structural layer to the magnetic
pickup path. The prototype instead supports the glass in an opening cut into
the printed case, lowering the playing surface while keeping it rigid.

A builder who attaches the tiles and supported reed switches directly to the
case top does not need that opening: the case top replaces the glass layer.
Whichever method is used, support the complete 300 x 300 mm field against
tilting and flex, protect glass edges, retain the board against sliding, and
test pickup plus sensing with every piece on every square. Never cut or drill a
glass plate unless its manufacturer explicitly permits that operation.

See the [working prototype photographs](PROTOTYPE.md) for the sliding body,
TPU spacer function, glass recess, and current enclosure print settings.

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
