# Component references

## Design lineage

This hardware is a modified implementation of Greg06's
[Automated Chessboard](https://www.instructables.com/Automated-Chessboard/).
That project provides the high-level CoreXY, magnetic-piece, reed-sensor, and
multiplexer architecture. Its page is marked
[CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/).

The BOM, wiring tables, safety additions, measured dimensions, and assembly
instructions in this repository describe the current 24 V donor-printer build
and are independently written. The base project's fixed rail cuts, 12 V power
arrangement, separate arcade buttons and limit switches, and exact component
shopping list are not specifications for this version. See the repository
[`ATTRIBUTION.md`](../ATTRIBUTION.md) for a change summary.

## Electrical references

These primary sources define electrical behavior. Marketplace listings are
kept only in `BOM.md` to identify the tested form factor; a seller may change a
product without changing its title.

- [Arduino Nano hardware page](https://docs.arduino.cc/hardware/nano/)
- [Arduino Nano classic pinout](https://docs.arduino.cc/resources/pinouts/A000005-full-pinout.pdf)
- [Allegro A4988 datasheet](https://www.allegromicro.com/-/media/files/datasheets/a4988-datasheet.ashx)
- [Pololu A4988 carrier application notes](https://www.pololu.com/product/1182)
- [TI CD74HC4067 product page and datasheet](https://www.ti.com/product/CD74HC4067)
- [onsemi TIP120 datasheet](https://www.onsemi.com/pdf/datasheet/tip120-d.pdf)
- [JGMaker official A3S product page](https://www.jgmaker3d.com/products/a3s)

The current procurement options shown by the interactive explorer are examples
chosen to satisfy these requirements. They are not the source of the firmware
pin contract and do not override a manufacturer datasheet.

The fitted driver is marked HR4988SQ rather than Allegro A4988. A mirrored
manufacturer datasheet identifies the same `Itrip = VREF / (8 x Rsense)`
relationship, but builders should still treat the carrier's chip and resistor
markings as part-specific:

- [YONGFUKANG HR4988SQ datasheet mirror](https://www.alldatasheet.com/datasheet-pdf/pdf/1132086/YONGFUKANG/HR4988SQ.html)

The official A3S page lists printer-level specifications but no motor model,
phase current, winding resistance, or torque. No authoritative electrical
specification was found for the unlabelled donor motors, so this guide does not
claim one. The measured working system uses R100 carriers at 0.720 V.
