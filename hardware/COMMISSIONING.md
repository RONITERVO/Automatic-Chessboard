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
6. Confirm no short between protected +24 V and GND. A capacitor-charging
   reading that rises is normal; a steady near-zero resistance is not.
7. Confirm no short between 5 V and GND.
8. Confirm every motor has two isolated coil pairs and no motor wire is shorted
   to the frame.
9. Press each limit switch and verify it closes to GND.

## Stage B: protected input and 5 V logic only

Leave driver `VMOT`, motors, and magnet 24 V disconnected.

1. Apply 24 V and record the protected bus and buck output.
2. Test the latching cutoff three times. The 24 V and 5 V rails must both fall.
3. Boot the Nano and LCD.
4. Connect the Windows app by BLE or, with external power removed, by USB.
5. Run **Diagnostics -> Run safe diagnostics**. Connecting and diagnostics must
   cause no motor or magnet activity.
6. Verify the monitor reports firmware 4.0.0 and fresh telemetry.

## Stage C: switches and 64 sensors

1. Observe both switch states released.
2. Press switch A/D11; only the white/A input should change.
3. Press switch B/A6; only the black/B input should change.
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
3. Apply power and run only the smallest guarded service move.
4. Verify smooth motion. Grinding or vibration usually indicates mixed coil
   pairs; stop power before changing wires.
5. Verify direction against the expected switch. Reverse one complete coil
   pair only if necessary.
6. Repeat for driver 2.
7. Record both VREF values and temperatures after 15 minutes of unloaded test.

## Stage F: calibration and endurance

1. Restore both drivers and keep the surface open.
2. Actuate both limit switches by hand while watching the visual state.
3. Start calibration with one hand beside the physical cutoff.
4. Confirm the first approach moves toward the D11/white switch and stops on
   it, then the second approach finds the A6/black switch.
5. Confirm the carriage parks at e6 without striking an end stop.
6. Close the surface and test empty-board sensor stability.
7. Test one piece on short straight moves, then diagonal and knight paths.
8. Optionally run the connected magnet-free endurance test documented in
   `../firmware/README.md`, with an empty board and someone beside the cutoff.

## Stop conditions

Cut physical power immediately for uncontrolled motion, smoke, odor, a hot or
swelling capacitor, a slipping belt that can tangle wiring, repeated Nano
resets, a magnet that remains energized, or a limit switch that does not stop
homing. Diagnose with 24 V removed.
