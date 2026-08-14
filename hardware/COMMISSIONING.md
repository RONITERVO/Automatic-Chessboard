# First-power commissioning

Use a multimeter for every voltage step. Do not skip ahead after a failed
measurement.

## Record sheet

| Measurement | Acceptable | Actual |
| --- | --- | --- |
| Protected 24 V bus | 23.0-25.0 V | |
| Buck output before logic connection | 4.90-5.10 V | |
| Nano 5V to GND | 4.80-5.15 V | |
| Driver 1 VDD | 4.80-5.15 V | |
| Driver 2 VDD | 4.80-5.15 V | |
| Driver 1 VMOT | 23.0-25.0 V | |
| Driver 2 VMOT | 23.0-25.0 V | |
| Driver 1 VREF and sense marking | Record; 0.720 V / R100 is prototype value | |
| Driver 2 VREF and sense marking | Record; 0.720 V / R100 is prototype value | |
| A6 released / pressed raw ADC | Above 512 / below 512 | |

## Stage A: power-off checks

1. Disconnect USB and 24 V.
2. Confirm both electrolytic capacitor stripes go to GND.
3. Confirm each flyback diode stripe goes to +24 V.
4. Confirm both drivers face the correct direction by reading pin labels.
5. Confirm four separate 10 kohm resistors connect driver 1 and driver 2
   `STEP` and `DIR` individually to their logic GND pins. Confirm the signal
   inputs are not connected to each other.
6. Confirm each driver's `MS1`, `MS2`, `MS3`, and `ENABLE` pins connect
   directly to logic GND, and its tied `RESET`/`SLEEP` pins connect to 5 V.
7. Confirm no motor-output conductor (`1A`, `1B`, `2A`, or `2B`) connects to
   logic GND, chassis, or a cable shield.
8. Confirm no short between protected +24 V and GND. A capacitor-charging
   reading that rises is normal; a steady near-zero resistance is not.
9. Confirm no short between 5 V and GND.
10. Confirm every motor has two isolated coil pairs and no motor wire is shorted
   to the frame.
11. Confirm the two conductors of each motor coil are twisted as a pair and
    switched-power bundles are separated from logic and sensor wiring.
12. Press each limit switch and verify it closes to GND.

## Stage B: protected input and 5 V logic only

Leave driver `VMOT`, motors, and magnet 24 V disconnected.

1. Apply 24 V and record the protected bus and buck output.
2. Test the latching cutoff three times. The 24 V and 5 V rails must both fall.
3. Boot the Nano and LCD.
4. Connect the Windows app by BLE or, with external power removed, by USB.
5. Run **Diagnostics -> Run safe diagnostics**. Connecting and diagnostics must
   cause no motor or magnet activity.
6. Verify the monitor reports firmware 4.4.0 and fresh telemetry.

For a differently sized or positioned playing field, finish normal switch and
motion commissioning first, then use **Service > Geometry** as documented in
`firmware/README.md`. It requires only a visible magnetic marker; reed sensors,
a calibration sheet, a camera, and either companion app are optional. Rebuild
and upload the reported constants, calibrate, then verify separated squares.

## Stage C: switches and 64 sensors

1. Observe both switch states released.
2. Run the firmware's guided `SWTEST` from the app developer console or a
   serial terminal.
3. Follow its prompts to press/release switch A/D11 and then switch B/A6. It
   must report `SWTEST PASS`; record the pressed A6 raw value.
4. Place one magnet-equipped piece on each square, one at a time.
5. Confirm exactly the matching square changes in the visual board.
6. Remove the piece and confirm the square clears.

Do not continue until all 64 squares pass. If a whole rank is wrong, check the
multiplexer/channel table; do not compensate by relabelling the UI.

## Stage D: magnet branch

Keep motor VMOT disconnected and secure the carriage away from steel objects.

1. Connect the magnet's protected 24 V branch.
2. Use the guarded service magnet control for a one-second pulse.
3. Verify the magnet energizes only when commanded and releases afterward.
4. Trigger the physical cutoff while energized; it must release immediately.
5. If the Nano resets or the LCD corrupts, stop and inspect common-ground
   routing, the flyback diode, and supply decoupling.

## Stage E: one motor at a time

1. Remove all pieces and expose the mechanism.
2. Connect driver 1 VMOT and its motor with power off.
3. Keep the untested driver's VMOT and the protected magnet 24 V branch
   physically disconnected before and throughout the JOG test.
4. Apply power and run `JOG W+` then `JOG W-` for the white driver, or
   `JOG B+` then `JOG B-` for the black driver, from a guarded developer
   console. Each command moves only 20 full steps.
5. Verify smooth motion. Grinding or vibration usually indicates mixed coil
   pairs; stop power before changing wires.
6. Verify direction against the expected switch. Reverse one complete coil
   pair only if necessary.
7. Stop issuing steps and verify the motor retains steady holding torque in
   full-step mode. Weak torque at particular positions or complete loss of
   holding torque is a stop condition, not an acceptable resonance effect.
8. Repeat for driver 2.
9. Record both VREF values and temperatures after 15 minutes of unloaded test.
10. With power removed before every routing change, test the intended final
    cable layout and then rerun both motors. If behavior changes with cable
    position, isolate one motor, fan, magnet/power, or logic bundle at a time as
    described in `WIRING.md`; do not proceed until the final secured layout is
    repeatable.

## Stage F: calibration and endurance

1. Restore both drivers and keep the surface open.
2. Actuate both limit switches by hand while watching the visual state.
3. Start calibration with one hand beside the physical cutoff.
4. Confirm the first approach moves toward the D11/white switch and stops on
   it, then the second approach finds the A6/black switch.
5. Confirm the carriage parks at e6 without striking an end stop.
6. Close the surface and test empty-board sensor stability.
7. Test one piece on direct horizontal, vertical, and diagonal moves. Test a
   knight through connected Play and verify it becomes separate straight
   square-centre drags; no continuous knight or S-shaped carry is permitted.
8. Test an automatic capture first with its direct lower-left boundary lane
   empty, then with that lane blocked but a vertically reachable lane clear.
   Verify a fully blocked exit requests the displayed move manually and causes
   no capture motion.
9. Optionally run the connected magnet-free endurance test documented in
   `../firmware/README.md`, with an empty board and someone beside the cutoff.

## Stop conditions

Cut physical power immediately for uncontrolled motion, smoke, odor, a hot or
swelling capacitor, a slipping belt that can tangle wiring, repeated Nano
resets, lost motor holding torque, a magnet that remains energized, or a limit
switch that does not stop homing. Diagnose with 24 V removed.
