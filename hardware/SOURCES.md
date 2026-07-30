# Component references

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

The fitted driver is marked HR4988SQ rather than Allegro A4988. A mirrored
manufacturer datasheet identifies the same `Itrip = VREF / (8 x Rsense)`
relationship, but builders should still treat the carrier's chip and resistor
markings as part-specific:

- [YONGFUKANG HR4988SQ datasheet mirror](https://www.alldatasheet.com/datasheet-pdf/pdf/1132086/YONGFUKANG/HR4988SQ.html)

The official A3S page lists printer-level specifications but no motor model,
phase current, winding resistance, or torque. No authoritative electrical
specification was found for the unlabelled donor motors, so this guide does not
claim one. The measured working system uses R100 carriers at 0.720 V.
