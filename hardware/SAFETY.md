# Safety rules

This machine combines a 24 V supply, stored energy in motors and capacitors,
an electromagnet, glass switches, and a moving belt carriage. Read this page
before assembling or troubleshooting it.

## Non-negotiable protections

The public design requires all of the following:

- Use an enclosed, regulated 24 V DC supply. Do not build or expose mains-side
  wiring as part of this project.
- Put a **3 A time-delay fuse rated for at least 32 V DC** in the 24 V positive
  lead, as close to the power inlet as practical. This is the conservative
  starting value for the documented load. Use wiring that can safely carry the
  fuse rating; 0.75 mm2 / 18 AWG copper is recommended for the main 24 V path.
- Put a **latching physical cutoff rated for at least 30 V DC and 5 A** after
  the fuse. It must remove power from the drivers, magnet, and logic buck and
  must be reachable without crossing the mechanism.
- Add reverse-polarity protection rated for at least 30 V DC and 5 A. Use a
  properly rated ideal-diode/MOSFET module or series Schottky solution; verify
  its input and output polarity before connecting the board.
- Enclose or guard live terminals, the moving belts, and pinch points. Add
  strain relief where power and motor cables enter the mechanism.
- Keep the device supervised while powered. Keep loose metal, magnetic media,
  and people with magnet-sensitive medical devices away from the carriage.

Measure the 24 V input current during the longest loaded motion. If normal
operation approaches 3 A, calculate a replacement from the measured maximum
input current and the safe current of the smallest wire. **5 A is the maximum
allowed by this guide's recommended wiring**, not an automatic upgrade. Never
increase a fuse simply because it opens; first find the cause.

## Rules that prevent common driver failures

- Never plug, unplug, or rearrange a stepper motor while 24 V is present.
- Never insert a StepStick carrier backwards. Compare `VMOT`, `GND`, `VDD`,
  `1A`, `1B`, `2A`, and `2B` labels before applying power; carrier colors are
  not a pinout standard.
- Mount one 100 uF, 50 V electrolytic directly across `VMOT` and motor `GND`
  at each driver. The negative stripe goes to GND.
- Set the current limit before sustained motor testing. A4988-compatible
  carriers differ in chip, sense-resistor value, and VREF scaling.
- Keep the heatsink electrically clear of pins and solder joints. Stop testing
  if the driver, connector, wire, motor, or capacitor becomes unusually hot,
  smells, discolors, or behaves intermittently.
- Do not use a resistance measurement from the VREF screw to ground to infer
  motor current. Measure powered VREF and identify both sense resistors.

## 5 V and USB rule

The documented buck produces regulated 5.0 V, so connect it to the Nano `5V`
pin. The classic Nano pinout specifies `VIN` for 7-12 V input; feeding 5 V into
`VIN` loses voltage in the onboard regulator and can undervolt the controller.

Classic Nano clones do not all isolate USB 5 V from an externally driven 5 V
rail in the same way. The beginner-safe rule is:

1. Switch off and disconnect the 24 V supply before attaching USB.
2. Verify that the external 5 V rail is at 0 V.
3. Upload or diagnose over USB.
4. Disconnect USB before restoring external power.

If simultaneous USB data and external power are required, use a verified cable
or adapter that keeps D+ and D- connected while disconnecting USB VBUS, or use
a documented power-OR/isolator design. Ordinary "data blocker" adapters do the
opposite and are not suitable. Do not cut or modify an unidentified cable while
it is connected to a computer.

## Safe migration of an existing 5 V-to-VIN build

For the current prototype:

1. Disconnect the 24 V supply and USB. Wait at least 60 seconds.
2. Measure between the 24 V and GND rails; confirm there is no voltage.
3. Disconnect the buck output from Nano `VIN`.
4. With the Nano still disconnected, power only the buck input and adjust its
   output to **5.00 V**. Accept 4.90-5.10 V; target 5.00 V.
5. Remove power again and confirm 0 V.
6. Connect buck `OUT+` to Nano `5V` and `OUT-` to common GND.
7. Run the logic-only stage in `COMMISSIONING.md` before reconnecting 24 V
   loads.

Do not connect 24 V to `VIN`, `5V`, any logic module, or any Nano I/O pin.

## First movement

- Raise or remove the playing surface so the carriage is visible.
- Remove chess pieces, tools, and loose wires.
- Keep one hand next to the physical cutoff, not inside the mechanism.
- Use the Windows app's read-only diagnostics first. Connecting and polling
  must not move the carriage.
- Calibration intentionally moves hardware. Start it only after both limit
  inputs have been tested by hand and the travel directions are known.

Software halt commands are useful for controlled stopping, but BLE can
disconnect and a controller can crash. Use the physical cutoff for unexpected
motion.
