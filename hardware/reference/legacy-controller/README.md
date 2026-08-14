# Working prototype PCB and legacy design reference

The existing fabricated long PCB is functional and runs the photographed
prototype after external wire and resistor modifications. **Do not manufacture
the archived PCB design unchanged and do not generate production Gerbers from
it.**

These KiCad files are preserved because their long component layout matches
the physical working board and can help explain how the modules were arranged.
They predate current firmware 4.5.0 wiring and are not the electrical source of
truth or a verified fabrication release.

## Required rework for the current prototype

When reusing an already fabricated board, implement the complete
`../../connections.csv` contract. In particular:

- leave the legacy D10 switch connector unused;
- connect HC-08 `TXD` through 1 kohm to Nano `D10`;
- wire the second normally-open switch between Nano `A6` and GND;
- add a 10 kohm pull-up from `A6` to 5 V;
- connect Nano `D1/TX` through 1 kohm to HC-08 `RXD`, and add 2 kohm from that
  divider node to GND; and
- complete the sensor, motor, power, and load connections that are not routed
  by the board using insulated, strain-relieved external wiring.

Do not plug the second switch into the old D10 position: D10 is now the
firmware's receive input for Bluetooth. Verify every modification by net name
and meter continuity rather than copying wire colors from a photograph. The
[working-prototype page](../../PROTOTYPE.md) shows the physical board and its
external rework.

Known limitations include:

- sensor-channel and several motor/load connections were completed with
  external wiring rather than PCB traces;
- the historical schematic contains 89 explicit no-connect markers, including
  the multiplexer channel area;
- the driver `RESET`/`SLEEP` pair is not explicitly routed to 5 V;
- it predates the HC-08 on D10, the A6 switch/pull-up change, and the current
  5 V power recommendation;
- the historical power labels include a 12 V/VIN arrangement that is not used
  by the current 24 V plus regulated-5 V design;
- no current-limit, fuse, emergency cutoff, reverse-polarity, enclosure, or
  production verification is encoded in the PCB source;
- no DRC/ERC or manufacturing review has been certified for these files.

Use `../../WIRING.md`, `../../connections.csv`, and `../../sensor-map.csv` for
the current build. The archive intentionally contains no Gerbers, drill files,
pick-and-place files, or fabrication ZIP. An existing board being operational
does not remove the need for the current public-build fuses, cutoff, polarity
protection, verified 5 V supply, and commissioning checks.

Files:

- `automatic_chessboard_copy.kicad_pcb`: long board layout matching the
  photographed controller form.
- `automatic_chessboard_copy.kicad_pro`: historical KiCad project settings.
- `reference-schematic.kicad_sch`: related historical circuit drawing; layout
  and present-day wiring do not fully match it.
