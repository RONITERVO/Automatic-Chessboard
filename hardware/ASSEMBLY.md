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
pull-up. Add the LCD and verify address `0x27`. Add the HC-08 resistors and
cover each resistor lead and solder joint with heat-shrink.

## 7. Build the magnet switch

Wire the TIP120, 1 kohm base resistor, 10 kohm base pull-down, magnet, and
flyback diode. Confirm the diode stripe is on the +24 V side. Keep the magnet's
24 V branch disconnected until the service-output test.

## 8. Prepare the two motor drivers

1. Identify every carrier pin from its silkscreen.
2. Tie `RESET` to `SLEEP` and then to 5 V.
3. Tie `MS1`, `MS2`, `MS3`, and `ENABLE` to GND for the documented full-step
   mode.
4. Add a 100 uF/50 V capacitor directly across each `VMOT`/GND pair.
5. Identify each motor's two coil pairs with a meter.
6. With power removed, connect coils A and B.
7. Set a conservative VREF, then follow the current-limit procedure in
   `WIRING.md`. The prototype's final value is 0.720 V only for its R100 boards
   and donor motors.

## 9. Final harness inspection

- Tug-test every crimp and terminal.
- Cover all exposed power terminals.
- Ensure no belt can touch a cable.
- Ensure the cutoff remains accessible with the board closed.
- Check continuity from every module ground to the GND bus.
- Check that +24 V is not continuous to 5 V, Nano pins, or the frame.
- Sign and date the commissioning checklist.
