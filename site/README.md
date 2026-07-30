# Interactive build explorer

This directory contains the text-free, parts-level 3D build explorer published through GitHub Pages. The assembly is generated with Three.js geometry at runtime, so no opaque binary CAD asset is required and every selectable component group remains reviewable in source control.

## Interaction map

- Drag to orbit, use the wheel or pinch gesture to zoom, and right-drag to pan.
- Select a component to focus it and reveal its icon-only action dock.
- Open its exact recommendation, mark it purchased, hide it temporarily, or clear the selection from that dock.
- Double-click a component to open its recommendation directly.
- The top-left controls reset the view, toggle rotation, explode the assembly, make the board transparent, and restore temporarily hidden parts.
- Guided wiring opens a 17-stage animated harness sequence. It starts with power removed, builds protected 24 V and regulated 5 V first, maps the four 16-channel sensor banks, then adds interfaces, the magnet switch, motor drivers, and only finally the three protected 24 V load branches.
- The progress ring toggles the visibility of purchased components.
- Purchase and hidden-part state is stored locally in the browser under `automatic-chessboard-build-v1`.

The main build view contains no visible explanatory text. Guided wiring uses only short terminal and measurement codes such as `D2→DIR` and `OUT=5.00V`; it never presents instructional paragraphs. Accessible names and a live announcement region preserve keyboard and screen-reader usability.

The wiring animation imports `hardware/connections.csv` and `hardware/sensor-map.csv` directly during the build. `validate.mjs` rejects a missing, duplicated, or unknown point-to-point connection and rejects anything other than 64 unique MUX channels and logical squares.

## Development

```powershell
npm install
npm run validate
npm run dev
```

`npm run build` creates the deployable `dist` directory. Component recommendation URLs and accessibility names are centralized in `src/catalog.js`.
