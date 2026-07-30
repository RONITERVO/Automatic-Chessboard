# Legacy controller PCB reference only

**Do not manufacture this PCB. Do not generate Gerbers from it.**

These KiCad files are preserved because their long component layout matches
the physical prototype and can help explain how the modules were arranged.
They are not the electrical source of truth.

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
pick-and-place files, or fabrication ZIP.

Files:

- `automatic_chessboard_copy.kicad_pcb`: long board layout matching the
  photographed controller form.
- `automatic_chessboard_copy.kicad_pro`: historical KiCad project settings.
- `reference-schematic.kicad_sch`: related historical circuit drawing; layout
  and present-day wiring do not fully match it.
