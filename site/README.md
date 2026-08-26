# Immersive Automatic Chessboard simulator

This Vite + React Three Fiber application presents the Automatic Chessboard as one continuous three-dimensional object. The procedural hardware model, chess pieces, setup trays, LCD, buttons, Arduino signal view, wiring guide, and rewind controls all occupy the same world. There is no duplicate flat chessboard UI.

The model is authored in centimetres and mounted into the scene at `0.01` scale, so one scene unit is one metre in WebXR. It sits at real table height and the same R3F pointer handlers accept desktop mouse rays, touch input, XR controllers, tracked hands, gaze, and transient XR pointers.

## Experience

- Orbit with drag, zoom with the wheel or pinch, and pan with the secondary pointer gesture.
- Press the modeled `POWER`, `A`, `B`, or illuminated next-action control exactly as on the physical board.
- During setup, select or drag each piece from the two physical side trays to its expected reed square. `AUTO/FINISH SETUP` remains available when a visitor wants to continue without placing all 32 pieces manually.
- During play, press or drag a piece once. Legal destinations illuminate on the physical tiles. The same interaction handles the firmware's manual AI-placement fallback, including knight moves.
- Use the physical timeline to step, play, scrub any recorded point, and return to the live Arduino. Meaningful events and motion stay frame-accurate; unchanged idle data is shared and sampled at a lower cadence for long sessions.
- Switch among `PLAY`, `BRAIN`, `X-RAY`, and `WIRE`. Brain mode exposes live reed input, AVR program counter and loop, firmware state, Micro-Max, STEP pulses, moving head, and magnet output inside the exploded board. Wiring mode animates all source-of-truth harness stages.
- Press `VR` or `AR` on a supported secure-context browser. Unsupported browsers keep the complete desktop/touch experience and report why XR could not start.

The simulation executes the compiled production Nano HEX with AVR8js. It models the PCF8574/I²C LCD backpack, four 16-channel reed multiplexers, physical buttons/endstops, EEPROM, CoreXY STEP/DIR outputs, electromagnet, and UART telemetry at their electrical boundaries. Motor movement follows the step pulses and timing emitted by the real firmware.

Bluetooth is deliberately disconnected in standalone mode. The worker already has a byte/transport boundary for a future user-authorized Web Bluetooth adapter, but the page does not request or connect to hardware today.

This is a command-accurate hardware visualization, not a rigid-body physics simulation. It does not claim to model belt stretch, inertia, missed steps, magnetic field strength, piece collisions, or real-world tolerances.

## Architecture

- `src/firmware-core.js` and `src/firmware-worker.js`: real AVR runtime and peripheral adapter.
- `src/firmware-session.js`: framework-facing orchestration, chess rules, guided actions, announcements, and complete replay history.
- `src/model.js` and `src/wiring.js`: reviewable procedural hardware and harness geometry sourced from the hardware CSV files.
- `src/immersive/`: the shared R3F scene, spatial controls, physical pieces, Arduino signal visualization, responsive camera, and WebXR input surface.
- `src/main.jsx`: React application entry point.

The boundary is intentional: hardware complexity stays in the adapter, orchestration stays independent of rendering, and scene components only translate state into visible objects and pointer actions.

## Development and verification

```powershell
./firmware/build.ps1 -HardwareProfile nano
cd site
npm install
npm run validate
npm test
npm run build
npm run dev -- --host 127.0.0.1 --base /Automatic-Chessboard/
```

Open `http://127.0.0.1:5173/Automatic-Chessboard/`. Test the running page in a real browser at that deployment subpath; a successful build alone is not an interaction test. The GitHub Pages workflow performs the resource-budgeted Nano compile before building the site, so it cannot silently deploy a stale hand-maintained firmware copy.

The validation gate preserves the 23 modeled component groups, 84 wiring connections, 64 unique reed channels, CoreXY geometry, production-firmware boundary, spatial interaction surface, brain visualization, and absence of a duplicate 2D board.
