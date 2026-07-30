# Interactive build explorer

This directory contains the text-free, parts-level 3D build explorer published through GitHub Pages. The assembly is generated with Three.js geometry at runtime, so no opaque binary CAD asset is required and every selectable component group remains reviewable in source control.

## Interaction map

- Drag to orbit, use the wheel or pinch gesture to zoom, and right-drag to pan.
- Select a component to focus it and reveal its icon-only action dock.
- Open its exact recommendation, mark it purchased, hide it temporarily, or clear the selection from that dock.
- Double-click a component to open its recommendation directly.
- The top-left controls reset the view, toggle rotation, explode the assembly, make the board transparent, and restore temporarily hidden parts.
- The progress ring toggles the visibility of purchased components.
- Purchase and hidden-part state is stored locally in the browser under `automatic-chessboard-build-v1`.

The rendered page contains no visible explanatory text. Accessible names and a live announcement region preserve keyboard and screen-reader usability.

## Development

```powershell
npm install
npm run validate
npm run dev
```

`npm run build` creates the deployable `dist` directory. Component recommendation URLs and accessibility names are centralized in `src/catalog.js`.
