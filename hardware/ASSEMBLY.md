# Staged assembly

Build and test one subsystem at a time. Do not populate everything and apply
24 V for the first test.

## 1. Label and inspect

1. Print `connections.csv` and `sensor-map.csv`, or keep them open beside the
   work area.
2. Label both ends of every motor, switch, magnet, and sensor cable.
3. Photograph every carrier's pin labels and sense-resistor markings before
   mounting a heatsink.
4. Inspect the Nano and module undersides for solder bridges.
5. Verify all polarized parts: supply connector, buck, both 100 uF capacitors,
   flyback diode, and reverse-polarity stage.

## 2. Build the protected 24 V input

With no electronics connected, wire the inlet, 3 A time-delay fuse, latching cutoff,
reverse-polarity protection, and covered distribution block. Use red for
protected +24 V and black for common GND where practical.

Power this stage alone. Verify 23-25 V at the distribution block and verify the
cutoff removes it. Disconnect power.

## 3. Configure the buck alone

Connect only the buck input through its logic fuse. Leave its output floating.
Power on, set the output to 5.00 V, switch off, wait for discharge, and mark the
adjuster so accidental movement is visible.

## 4. Build and test the 5 V logic rail

Connect the buck output to a 5 V distribution point. Connect the Nano `5V`
pin, all four multiplexer `VCC` pins, both driver `VDD` pins, the LCD, and the
HC-08 carrier. Connect every logic ground to the common GND point.

Do not connect `VMOT`, motors, or the magnet yet. Power the logic rail and
complete the logic-only commissioning stage.

## 5. Wire the reed system

Share `S0-S3` and `SIG` exactly as shown in `WIRING.md`. Give each multiplexer
its own enable wire. Connect and validate one multiplexer (16 squares) at a
time before adding the next. This makes a reversed ribbon cable or off-by-one
channel visible immediately.

## 6. Wire switches, LCD, and Bluetooth

Add the two normally-open switch circuits, including the external 10 kohm A6
pull-up. Add the LCD and confirm that the `hd44780_I2Cexp` diagnostic detects
its backpack; the firmware does not require one fixed PCF8574 address. Add the
HC-08 resistors and cover each resistor lead and solder joint with heat-shrink.

If reusing the fabricated long PCB, do not use its legacy D10 switch connector.
Wire HC-08 `TXD` through 1 kohm to D10, move the second switch to A6 with its
10 kohm pull-up to 5 V, and add the D1-to-HC-08 divider exactly as shown in
`WIRING.md`. Complete every missing PCB net with secured external wiring and
continuity-test it against `connections.csv`. The board is proven functional
with this rework; the archived layout is not correct without it.

## 7. Build the magnet switch

Wire the TIP120, 1 kohm base resistor, 10 kohm base pull-down, magnet, and
flyback diode. Confirm the diode stripe is on the +24 V side. Keep the magnet's
24 V branch disconnected until the service-output test.

## 8. Prepare the two motor drivers

1. Identify every carrier pin from its silkscreen.
2. Tie `RESET` to `SLEEP` and then to 5 V.
3. Tie `MS1`, `MS2`, `MS3`, and `ENABLE` to GND for the documented full-step
   mode.
4. Add one independent 10 kohm resistor from each `STEP` and `DIR` input to
   logic GND: four pull-downs total. Place them near the carrier inputs; do not
   put them in series or share one resistor between two signals.
5. Add a 100 uF/50 V capacitor directly across each `VMOT`/GND pair.
6. Identify each motor's two coil pairs with a meter.
7. Twist the two conductors of coil A together and twist the two conductors of
   coil B together. With power removed, connect both coils.
8. Route each `STEP` signal with a logic-GND return and each `DIR` signal with
   a logic-GND return. Keep these logic routes short and separate from motor,
   fan, electromagnet, and 24 V wiring as specified in `WIRING.md`.
9. Set a conservative VREF, then follow the current-limit procedure in
   `WIRING.md`. The prototype's final value is 0.720 V only for its R100 boards
   and donor motors.

## 9. Bench harness inspection

- Tug-test every crimp and terminal.
- Cover all exposed power terminals.
- Ensure no belt can touch a cable.
- Ensure the cutoff remains accessible with the board closed.
- Check continuity from every module ground to the GND bus.
- Check that +24 V is not continuous to 5 V, Nano pins, or the frame.
- Confirm motor, fan, magnet, and 24 V bundles do not run parallel against
  `STEP`, `DIR`, switch, Bluetooth, or sensor wiring. Cross unlike bundles at
  approximately 90 degrees where separation is impossible.
- Confirm cable clips and strain relief do not put tension on driver headers,
  crimps, solder joints, or the VREF adjusters.

Do not install the harness into the moving frame until these checks pass.

## 10. Dry-assemble the donor-printer frame

1. Inventory the V-slot rails, V-wheels, eccentric and fixed spacers, shims,
   brackets, T-nuts, screws, pulleys, idlers, belts, and both motors against
   `BOM.md`.
2. Identify and label the five 20 x 20 mm rails before adding brackets:
   two `345 mm` side rails, one `350 mm` cross rail, one `315 mm` cross rail,
   and the `395 mm` moving-gantry rail.
3. Reject bent rails, cracked wheels, rough bearings, damaged belt teeth,
   rounded set screws, and connectors with heat damage.
4. Assemble the outer rails loosely on a flat surface. Measure both diagonals
   and adjust until they match before tightening the corner brackets.
5. Install the 395 mm moving gantry and trolley. Turn eccentric spacers only far
   enough to remove play; every carriage must still roll freely by hand.
6. Confirm the carriage can reach all 64 square centers and both switch
   actuators without a belt installed.

The lengths in `BOM.md` are the confirmed finished lengths for this build. A
different bracket, wheel plate, or endstop design can change the required
geometry, so complete the reach and collision checks before treating them as a
drop-in cut list for a modified frame.

## 11. Install pulleys and CoreXY belts

1. Seat each 20-tooth drive pulley on the motor shaft with one set screw on the
   shaft flat where available. Tighten the second set screw after alignment.
2. Use smooth idlers where the belt back contacts the pulley and toothed idlers
   where the tooth side must bend. Fit washers or shims so belt flanges do not
   rub the bracket.
3. Route the two belt paths symmetrically. They must remain in their own planes
   and must not cross by touching or rubbing.
4. Anchor and tension each path independently. Remove slack without bowing the
   gantry or making the carriages hard to move.
5. Move the trolley through the full envelope by hand. The belt must stay
   centered and the gantry must remain square.

Do not assume a salvaged belt is serviceable merely because it is unbroken.
Replace it if teeth are polished, cracked, stretched, or separating from the
reinforcement.

## 12. Fit the board tiles and sensor harnesses

1. Dry-fit all 64 tiles on a 37.5 mm pitch and check the 300 x 300 mm playing
   field for flatness before adding sensors or adhesive.
2. Build eight labelled sensor banks. Each bank needs eight square signals and
   a common ground return to its assigned multiplexer channels.
3. Support each reed-switch lead before bending it. Fit the switch into its
   pocket without twisting or loading the glass capsule.
4. Validate each bank electrically before gluing it into the board.
5. Route and retain the harnesses outside the carriage and belt envelope, then
   repeat the complete 64-square sensor test.

## 13. Prepare the chess pieces

1. Use one magnet size and grade for the full set; 5 x 2 mm neodymium discs are
   the documented starting option, not a guarantee for every tile and piece.
2. Mark one pole of every magnet before installation and keep the same pole
   facing the board in all 32 pieces.
3. Retain each magnet mechanically where practical and use an adhesive suitable
   for both the magnet coating and piece material.
4. Add felt or another low-friction base only after confirming that the added
   gap does not prevent reed activation or reliable pickup.
5. Test every finished piece on every square before closing the enclosure.

## 14. Fit the sliding chassis and playing surface

1. Attach the two front and two rear TPU spacers to the completed internal
   body. Confirm they cannot move into a belt, wheel, or pulley path.
2. With power removed and service cables disconnected or safely supported,
   slide the body into the outer case from the front. The spacers should center
   and steady it without requiring enough force to distort the V-slot frame.
3. Choose one playing-surface construction:
   - support the glass-backed tile board in a case-top opening, as on the
     prototype; or
   - attach the tiles and supported sensors directly to the intact case top.
4. For a recessed glass board, support the plate on a level distributed ledge,
   protect its edges, and retain it against lift and horizontal movement. Cut
   only the printed case, not glass with unknown tempering.
5. Reconnect the eight sensor banks with strain relief and enough service loop
   to remove the body again. Keep every ribbon outside the carriage envelope.
6. Check board flatness, carriage clearance, sensor operation, and pickup on
   all 64 squares before fitting cosmetic covers.

The prototype case used a 0.8 mm nozzle, three perimeters, and approximately
2.4 mm walls/top/floor. Other print settings are valid if they meet the same
support, clearance, vibration, and serviceability requirements. See
`PROTOTYPE.md` for photographs and the reason for the recessed glass option.

## 15. Final mechanical and electrical integration

Mount the electromagnet with mechanical retention, add flexible strain relief,
and verify a uniform under-board gap through the full travel. Install the
controller so driver potentiometers, fuses, terminals, and the USB connector
remain serviceable. Keep the latching cutoff reachable with the board closed.

Additional covers may hide the controller and wiring, but they must remain
removable and must not obstruct cooling, fuse replacement, connector access,
the cutoff, or the body's front slide-out path.

Repeat the bench continuity tests after installation. Then sign and date the
commissioning checklist and follow `COMMISSIONING.md` from the logic-only stage;
do not skip directly to calibration or piece-carrying tests.
