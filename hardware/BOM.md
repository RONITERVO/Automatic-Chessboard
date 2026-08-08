# Bill of materials

This BOM is for the modified 24 V prototype documented in this repository, not
for a literal reproduction of the base Instructables build. The prototype
reuses motion hardware from a dismantled JGAurora A3S printer. Equivalent donor
parts are acceptable when they meet the measurable requirements below.

The [machine-readable BOM](bom.csv) separates required specifications from the
tested or recommended reference. Seller titles, colors, and connector shapes
are not specifications; verify markings, dimensions, pin labels, voltage
ratings, and motor coil pairs before assembly.

## Controller, sensing, and interface

| Qty | Part | Required specification | Tested or recommended reference |
| ---: | --- | --- | --- |
| 1 | Arduino Nano | Classic ATmega328P, 5 V, 16 MHz | Nano-compatible USB-C clone in the prototype; [official classic Nano](https://docs.arduino.cc/hardware/nano/) is the reference |
| 1 | USB data cable | Fits the selected Nano and carries data, not charge-only | Match the clone's USB connector |
| 4 | 16-channel multiplexer breakout | CD74HC4067-compatible, 5 V logic, active-low enable, labelled `SIG/S0-S3/EN/VCC/GND` | WAVGAT CD74HC4067 module ([listing](https://www.aliexpress.com/item/32848578672.html)) |
| 64 | Reed switch | Normally open glass reed that closes reliably through the selected tile and piece-magnet gap | Littelfuse MDSR-4-class part or equivalent; prototype switches are glued into printed tile slots |
| 1 | 16x2 I2C LCD | 5 V HD44780 display with a supported PCF8574-family backpack | Common blue 1602 module such as this [1602 backpack assembly](https://www.addicore.com/products/1602-16x2-character-lcd-with-i2c-backpack); firmware auto-detects the backpack rather than requiring address `0x27` |
| 2 | Combination button/endstop | Normally open lever or roller microswitch, accessible to the player and actuated by the carriage before a hard stop | One shared control/endstop per calibration direction; [Omron D2F-01L](https://www.digikey.fi/en/products/detail/omron-electronics-inc-emc-div/D2F-01L/83262) is a reproducible switch option |
| 1 | A6 pull-up | 10 kohm, 1/4 W | Installed between A6 and 5 V inside heat-shrink |
| 1 | BLE module | HC-08 BLE UART carrier that accepts a 5 V supply; UART remains 3.3 V logic | HC-08 carrier such as [DSD TECH SH-HC-08](https://www.deshide.com/product-details_1663307.html); optional when USB-only operation is sufficient |
| 2 | BLE series/divider resistor | 1 kohm, 1/4 W | One in TXD-to-D10 and one in D1-to-RXD divider |
| 1 | BLE divider resistor | 2 kohm, 1/4 W | HC-08 RXD node to GND |

Do not substitute a Nano Every, Nano R4, 3.3 V Nano, or unrelated
multiplexer without reviewing firmware timing, A6 behavior, voltage levels,
memory use, and pinout.

## Motion and electromagnet electronics

| Qty | Part | Required specification | Tested or recommended reference |
| ---: | --- | --- | --- |
| 2 | Stepper driver carrier | A4988/HR4988 StepStick pinout, 24 V capable, adjustable current limit | HR4988SQ red carrier with two `R100` sense resistors; [Pololu A4988 Black Edition](https://www.pololu.com/product/2128) is a documented substitute whose own sense resistance must be used |
| 2 | Driver heatsink | Electrically isolated from pins and sized for the carrier | Adhesive aluminium StepStick heatsink |
| 2 | Stepper motor | Four-wire bipolar NEMA 17, 1.8 degree preferred, at least 0.20 N m holding torque; rated phase current must match the configured limit | Unlabelled JGAurora A3S donor motors; [17HS13-0404S1](https://www.omc-stepperonline.com/nema-17-bipolar-1-8deg-26ncm-36-8oz-in-0-4a-12v-42x42x34mm-4-wires-17hs13-0404s1) is a purchasable 0.26 N m, 0.4 A/phase option |
| 2 | Driver capacitor | 100 uF, at least 50 V, electrolytic, 105 C preferred, mounted directly at `VMOT`/GND | 100 uF 50 V low-impedance radial capacitor such as [Panasonic EEU-FR1H101](https://www.digikey.fi/en/products/detail/panasonic-electronic-components/EEU-FR1H101/3561182) |
| 1 | Electromagnet | 24 V DC, approximately 2.8 W, 25 mm diameter x 20 mm high or mechanically compatible | [H2520 24 V / 2.8 W](https://zslanda.en.made-in-china.com/product/rtypxJCcOURO/China-Landa-H2520-Round-Holding-Electromagnet-100n-25mm-Diameter-20mm-Height-24V-12V-DC-Cast-Iron-Mini-Magnet.html) |
| 1 | Magnet switch transistor | TIP120 NPN Darlington, or a separately validated logic-level N-MOSFET circuit | [onsemi TIP120G](https://www.digikey.fi/en/products/detail/onsemi/TIP120G/920293), no heatsink required at the prototype's approximately 117 mA nominal load |
| 1 | Flyback diode | At least 1 A and 50 V; reverse-connected across the magnet during normal operation | 1N4004 through 1N4007; stripe toward +24 V |
| 1 | Base resistor | 1 kohm, 1/4 W | Nano D6 to TIP120 base |
| 1 | Base pull-down | 10 kohm, 1/4 W | Added between TIP120 base and GND for a defined off state during reset |

The driver current limit is set from the motor's rated phase current and the
actual carrier sense resistors. The prototype's 0.720 V reference is valid only
for its `R100` carriers and donor motors; it is not a universal A4988 setting.

## Power and protection

| Qty | Part | Minimum requirement | Tested or recommended reference |
| ---: | --- | --- | --- |
| 1 | 24 V DC supply | Enclosed, regulated, isolated; 3 A minimum, 5 A recommended | Prototype uses an unbranded 24 V 10 A brick, allowed only with the documented fuse; [Mean Well GST90A24-P1M](https://www.meanwell-web.com/content/files/pdfs/productPdfs/MW/GST90A/GST90A-spec.pdf) is a reproducible enclosed option |
| 1 | Buck converter | 24 V input, regulated 5.0 V output, at least 1 A continuous; 36 V-or-higher input and 2 A output rating recommended | Adjustable prototype buck; [Pololu D24V25F5](https://www.pololu.com/product/2850) is a fixed 5 V option |
| 1 | Main fuse and holder | 3 A time-delay starting value, at least 32 V DC, close to the inlet | Covered inline or panel holder |
| 1 | Latching cutoff | Normally closed power path, reachable, at least 30 V DC and 5 A | Two-pole emergency-stop or latching power switch |
| 1 | Reverse-polarity stage | At least 30 V DC and 5 A continuous | Rated ideal-diode/MOSFET module such as the [Pololu 4-75 V reverse protector](https://www.pololu.com/product/5358), or an engineered Schottky solution |
| 1 | Power distribution block | Covered, keyed or clearly polarized, rated above the main fuse | Separate protected +24 V and GND distribution |
| 1 | Logic fuse | 1 A, at least 32 V DC, on the buck input or suitably rated output | Inline holder or fused distribution position |
| as needed | Main power wire | 0.75 mm2 / 18 AWG copper recommended | Red/black stranded copper |
| as needed | Motor and branch wire | 0.34 mm2 / 22 AWG copper recommended | Stranded donor harness wire is acceptable after inspection |
| as needed | Logic and sensor wire | 0.14-0.25 mm2 / 26-24 AWG copper, strain-relieved | Ribbon cable and flexible hookup wire |
| as needed | Insulation and strain relief | Heat-shrink, cable ties/mounts, terminal covers, grommets, and ferrules for screw terminals | Select for the actual enclosure and cable exits |

## Interconnect and controller construction

| Qty | Part | Requirement or suggested starting quantity |
| ---: | --- | --- |
| 1 | Controller board | Rework the existing fabricated long PCB as documented, use 2.54 mm plated perfboard, or make a purpose-built board that implements `connections.csv`; the archived KiCad files are not a current fabrication release |
| as needed | 2.54 mm female headers | Socket the Nano and both StepStick carriers so their labels and orientation remain inspectable |
| as needed | Locking low-voltage connectors | One connector per motor, switch, magnet, display, BLE module, and removable sensor bank; current rating must suit the circuit |
| as needed | Screw terminals | Covered terminals rated above the circuit voltage and current for power branches and any non-locking removable wiring |
| 8 banks | Sensor harness | Eight square signals plus a common return per eight-square bank; label both ends with squares and MUX channels |
| as needed | Solder and assembly consumables | Electronics-grade solder, flux, heat-shrink, ferrules/crimps, standoffs, and nonconductive mounting hardware |

Breadboards and loose Dupont jumpers are suitable for logic-only experiments,
not for the finished moving 24 V machine.

## Donor-printer motion hardware

The following is the confirmed cut list for the current 300 mm board and the
matching donor-rail set. These are finished extrusion lengths, not approximate
frame dimensions. The prototype uses salvaged 3D-printer parts, so still check
that a substitute bracket and wheel stack matches this geometry before cutting
new material or ordering exact screw lengths.

| Qty | Part | Requirement or recommended option |
| ---: | --- | --- |
| 2 | Side frame rail | 20 x 20 mm V-slot aluminium, **345 mm finished length each** |
| 1 | Long frame cross rail | 20 x 20 mm V-slot aluminium, **350 mm finished length** |
| 1 | Short frame cross rail | 20 x 20 mm V-slot aluminium, **315 mm finished length** |
| 1 | Moving gantry rail | 20 x 20 mm V-slot aluminium, **395 mm finished length**; this is the rail carried by the outer motion system |
| 8 | Mini V-wheel | Matches the selected V-slot profile; no flat spots or bearing play |
| 4 | Eccentric spacer | M5, compatible with the wheel plate; used to remove play without binding |
| 4 | Fixed wheel spacer | 5 mm ID, approximately 6 mm high, matching the wheel stack |
| 8 | Precision shim | Approximately 8 mm OD x 5 mm ID x 1 mm, one per V-wheel where required by the donor stack |
| 8 | M5 locking nut | Nyloc or equivalent for wheel axles |
| 2 | GT2 drive pulley | 20 teeth, 2 mm pitch, 6 mm belt width, 5 mm motor bore, dual set screws preferred |
| up to 8 | GT2 idler pulley | Sized for 6 mm belt and the selected M5 axle stack; use smooth idlers against the belt back and toothed idlers where the tooth side bends |
| about 4 m | GT2 timing belt | 2 mm pitch, 6 mm wide, glass-fibre or steel reinforced; enough for both CoreXY paths plus tensioning allowance |
| 8-10 | 90-degree frame bracket | For 20 x 20 extrusion; aluminium or the separately attributed printable bracket after load testing |
| about 30 | M5 T-nut | Matches the extrusion slot; include spares before closing rail ends |
| assorted | M5 screws | Start with M5 x 8/10/12 mm for brackets and M5 x 20/25/30/35 mm for wheels and idlers; confirm every stack before tightening |
| 8 | M3 motor screw | M3 x 8-10 mm, or the depth required by the donor NEMA 17 motor without bottoming in the motor |
| as needed | M2/M3 printed-part hardware | Screws, washers, and locknuts sized to the switch, electronics, magnet, and printed support holes |
| 1 set | Belt anchors/tensioners | Secure both belt ends without sharp bends or tooth damage and permit independent tension adjustment |

The optional printable V-slot profile and corner-bracket packages are indexed
under [`printFiles`](printFiles/README.md). When making printed replacements,
produce the same five finished lengths: `345, 345, 350, 315, and 395 mm`. Do
not uniformly scale a profile to change its length, because that would also
change the 20 x 20 mm cross-section. Extend, trim, or segment it only along its
long axis. Printed plastic is not assumed to match aluminium stiffness, wear
resistance, or dimensional stability.

## Board, pieces, and printed parts

| Qty | Part | Requirement or recommended option |
| ---: | --- | --- |
| 64 | Printed board tile | 37.5 mm pitch with a protected reed-switch pocket and repeatable top height |
| 1 | Enclosure/frame interface | Supports a flat 300 x 300 mm playing field while preserving access to cutoff and service points |
| 4 | TPU frame spacer | Use the project-authored left/right front/back spacer models where they match the built case |
| 1 set | Chess pieces | Bases must accept magnets and slide reliably without scratching or catching tile joints |
| 32 | Piece magnet | [5 x 2 mm neodymium disc](https://www.supermagnete.fi/disc-magnets-neodymium/disc-magnet-5mm-2mm_S-05-02-N) is the current recommended starting point; use consistent pole orientation and validate every piece on every square |
| as needed | Low-friction base material | Thin felt or equivalent after confirming the added gap still closes every reed switch |
| as needed | Adhesive and cable retention | Compatible with tile, magnet, and wire materials; adhesive must not be the sole magnet-carriage safety retention |

Do not purchase the source project's exact rail cuts, button arrangement,
12 V electromagnet, or power supply for this build. This firmware and wiring
contract describe a different 24 V prototype with shared button/endstops and
project-specific geometry.
