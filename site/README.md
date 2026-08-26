# Interactive build explorer

This directory contains the parts-level 3D build explorer and production-firmware simulation lab published through GitHub Pages. The assembly is generated with Three.js geometry at runtime, so no opaque binary CAD asset is required and every selectable component group remains reviewable in source control.

## Interaction map

- Drag to orbit, use the wheel or pinch gesture to zoom, and right-drag to pan.
- Select a component to highlight it and reveal its icon-only action dock without changing the camera.
- Open its exact recommendation, mark it purchased, hide it temporarily, or clear the selection from that dock.
- Double-click a component to open its recommendation directly.
- The top-left controls reset the view, explode the assembly, make the board transparent, and restore temporarily hidden parts.
- The chess-knight control opens an ATmega328P lab that executes the compiled production Nano HEX with AVR8js. It does not reimplement the firmware state machine or Micro-Max opponent in JavaScript.
- The lab models the board peripherals at their electrical boundaries: the PCF8574/I²C LCD backpack, the four 16-channel reed multiplexers, both buttons/endstops, EEPROM, CoreXY STEP/DIR lines, the electromagnet output, and USB UART telemetry.
- Power-on, position recovery, sequential two-switch calibration, setup placement, human sensor gestures, Micro-Max thinking, automatic movement, and manual-placement fallbacks are visible in one event trace. The timeline can pause, step, scrub, and replay every recorded observable state from power-on.
- Motor animation is derived from the firmware's emitted STEP pulses at its compiled 16 MHz timing. The mechanism is functional rather than rigid-body physics: it shows what the controller commanded, not belt stretch, inertia, missed steps, magnetic field strength, or collisions.
- Bluetooth deliberately remains disconnected in this standalone mode. A byte/transport adapter boundary is present for a future user-authorized Web Bluetooth bridge, but this page never requests or connects to a device.
- Camera movement is always manual: there is no automatic rotation, selection zoom, wiring-step reframing, or camera tweening.
- Guided wiring opens a 17-stage animated harness sequence. It starts with power removed, builds protected 24 V and regulated 5 V first, maps the four 16-channel sensor banks, then adds interfaces, the magnet switch, motor drivers, and only finally the three protected 24 V load branches.
- The progress ring toggles the visibility of purchased components.
- Purchase and hidden-part state is stored locally in the browser under `automatic-chessboard-build-v1`.

The main build view contains no visible explanatory paragraphs. Guided wiring and the firmware lab use compact state, LCD, and signal readouts. Accessible names and live announcement regions preserve keyboard and screen-reader usability.

The wiring animation imports `hardware/connections.csv` and `hardware/sensor-map.csv` directly during the build. `validate.mjs` rejects a missing, duplicated, or unknown point-to-point connection and rejects anything other than 64 unique MUX channels and logical squares.

## Development

### Visual Studio Code

Open the repository root in VS Code, then use **Terminal > Run Task > Site: Start development server**. This starts Vite in the `site` directory, opens the local site in the default browser, and reloads it as source files change. Stop it with `Ctrl+C` in the dedicated terminal.

The Live Server extension is not suitable for this site because it serves files without processing the Three.js package imports or the raw CSV imports used by the wiring guide.

### Command line

```powershell
./firmware/build.ps1 -HardwareProfile nano
cd site
npm install
npm run validate
npm run dev
```

Run the firmware command from the repository root before entering `site`. `npm run dev` and `npm run build` export the resulting HEX and ELF symbol addresses into generated `site/public/firmware/` assets. GitHub Pages performs the same resource-budgeted Nano compile before building the site, so the lab cannot silently ship a stale hand-maintained firmware copy.

Use `npm run dev:open` to start Vite and open the browser automatically. `npm run build` creates the deployable `dist` directory. Component recommendation URLs and accessibility names are centralized in `src/catalog.js`.
