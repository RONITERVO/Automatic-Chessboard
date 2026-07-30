# Bill of materials

The [machine-readable BOM](bom.csv) separates tested prototype parts from the
requirements that substitute parts must meet. Seller titles and PCB colors are
not specifications; check markings and pin labels when parts arrive.

## Core electronics

| Qty | Part | Required specification | Tested reference |
| ---: | --- | --- | --- |
| 1 | Arduino Nano | Classic ATmega328P, 5 V, 16 MHz | Nano-compatible USB-C clone in the prototype |
| 4 | 16-channel multiplexer breakout | CD74HC4067-compatible, 5 V logic, active-low enable, labelled `SIG/S0-S3/EN/VCC/GND` | WAVGAT CD74HC4067 module ([listing](https://www.aliexpress.com/item/32848578672.html)) |
| 64 | Reed switch | Normally open glass reed, closes near the piece magnet | Glued into slots under printed tiles |
| 2 | Stepper driver carrier | A4988/HR4988 StepStick pinout, 24 V capable, heatsink | HR4988SQ red carrier, two `R100` resistors ([listing](https://www.aliexpress.com/item/1005003871761814.html)) |
| 2 | Stepper motor | Four-wire bipolar NEMA 17, 1.8 degree preferred, at least 0.20 N m holding torque; rated current must match configured limit | Unlabelled JGAurora A3S donor motors |
| 2 | Driver capacitor | 100 uF, at least 50 V, electrolytic, 105 C preferred | 100 uF 50 V on the underside of the prototype PCB |
| 1 | Electromagnet | 24 V DC, around 2.8 W, suitable size/force for carriage | H2520 24 V / 2.8 W |
| 1 | Magnet switch transistor | TIP120 NPN Darlington, or validated logic-level N-MOSFET alternative | TIP120, no heatsink at 117 mA load |
| 1 | Flyback diode | 1N4004 through 1N4007 or equivalent, at least 1 A and 50 V | Axial diode under PCB; stripe toward +24 V |
| 1 | Base resistor | 1 kohm, 1/4 W | D6 to TIP120 base |
| 1 | Base pull-down | 10 kohm, 1/4 W | Safety addition for public build |
| 1 | 16x2 I2C LCD | 5 V PCF8574 backpack, address `0x27` | 16x2 blue LCD |
| 2 | Combination button/endstop | Normally open momentary microswitch | One per calibration direction |
| 1 | A6 pull-up | 10 kohm, 1/4 W | Hidden under heat-shrink in prototype |
| 1 | BLE module | HC-08 BLE UART carrier that accepts 5 V supply; UART remains 3.3 V logic | HC-08 carrier |
| 2 | BLE series/divider resistor | 1 kohm, 1/4 W | One in TXD-to-D10, one in D1-to-RXD divider |
| 1 | BLE divider resistor | 2 kohm, 1/4 W | HC-08 RXD node to GND |

Do not substitute a Nano Every, Nano R4, 3.3 V Nano, or different multiplexer
without reviewing firmware timing, A6 behavior, voltage levels, and pinout.

## Power and protection

| Qty | Part | Minimum requirement |
| ---: | --- | --- |
| 1 | 24 V DC supply | Enclosed, regulated, isolated; 3 A minimum, 5 A recommended. A 10 A supply is acceptable only with the documented fuse. |
| 1 | Buck converter | 24 V input, regulated 5.0 V output, at least 1 A continuous; 36 V-or-higher input rating and 2 A rating recommended |
| 1 | Main fuse and holder | 3 A time-delay starting value, at least 32 V DC, installed near inlet; 5 A maximum only after load measurement |
| 1 | Latching cutoff | Normally closed power path; at least 30 V DC and 5 A |
| 1 | Reverse-polarity stage | At least 30 V DC and 5 A continuous |
| 1 | Power distribution block | Covered, keyed or clearly polarized, rated above the main fuse |
| 1 | Logic fuse | 1 A, at least 32 V DC, on the buck input or suitably rated output |
| as needed | Main power wire | 0.75 mm2 / 18 AWG copper recommended |
| as needed | Motor and branch wire | 0.34 mm2 / 22 AWG copper recommended |
| as needed | Logic and sensor wire | 0.14-0.25 mm2 / 26-24 AWG copper; strain-relieved |
| as needed | Insulation and strain relief | Heat-shrink, cable ties/mounts, terminal covers, ferrules for screw terminals |

## Mechanical items

The tested machine reuses V-slot aluminum extrusion, V-wheels, GT2-style belt
and pulleys, and printed brackets from a 3D printer. Exact frame parts are not
mandatory. See `MECHANICAL.md` for the dimensions and interfaces the firmware
actually depends on.
