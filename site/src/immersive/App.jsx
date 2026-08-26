import { useEffect, useState, useSyncExternalStore } from "react";
import { Canvas } from "@react-three/fiber";
import { createXRStore, XR } from "@react-three/xr";
import { FirmwareSession } from "../firmware-session.js";
import { BoardScene } from "./board-scene.jsx";

const xrStore = createXRStore({
  offerSession: false,
  emulate: false,
  hand: true,
  controller: true,
  gaze: true,
  screenInput: true,
  transientPointer: true,
});

export function App() {
  const [session] = useState(() => new FirmwareSession());
  const snapshot = useSyncExternalStore(session.subscribe, session.getSnapshot, session.getSnapshot);

  useEffect(() => () => session.destroy(), [session]);

  const enterXR = async (kind) => {
    if (!("xr" in navigator)) {
      session.say("WebXR is not available in this browser; desktop and touch controls remain active");
      return;
    }
    try {
      if (kind === "ar") await xrStore.enterAR();
      else await xrStore.enterVR();
    } catch (error) {
      session.say(`${kind.toUpperCase()} could not start: ${error instanceof Error ? error.message : String(error)}`);
    }
  };

  return (
    <main
      id="experience"
      aria-label="One-to-one automatic chessboard simulator"
      data-firmware-ready={snapshot.ready}
      data-power={Boolean(snapshot.state.power)}
      data-sequence={snapshot.state.sequence ?? -1}
      data-state={snapshot.state.stateName ?? "POWER OFF"}
      data-frame-count={snapshot.frameCount}
      data-view-index={snapshot.viewIndex}
      data-selected-square={snapshot.selected ?? ""}
      data-legal-targets={[...snapshot.legalTargets].join(",")}
    >
      <Canvas
        shadows="percentage"
        dpr={[1, 2]}
        camera={{ position: [0.44, 1.25, 0.18], fov: 38, near: 0.02, far: 20 }}
        gl={{ antialias: true, alpha: false, powerPreference: "high-performance" }}
        onCreated={({ gl }) => {
          gl.outputColorSpace = "srgb";
          gl.toneMappingExposure = 1.05;
        }}
      >
        <XR store={xrStore}>
          <BoardScene
            snapshot={snapshot}
            session={session}
            enterVR={() => enterXR("vr")}
            enterAR={() => enterXR("ar")}
          />
        </XR>
      </Canvas>
      <div className="sr-only" aria-live="polite">{snapshot.announcement}</div>
      <section className="sr-only" aria-label="Accessible simulator controls">
        <output aria-label="Firmware state">{snapshot.state.stateName ?? "POWER OFF"}</output>
        <output aria-label="LCD line one">{snapshot.state.lcd?.[0] ?? ""}</output>
        <output aria-label="LCD line two">{snapshot.state.lcd?.[1] ?? ""}</output>
        <output aria-label="Timeline status">{snapshot.historical ? `REPLAY FRAME ${snapshot.viewIndex + 1} OF ${snapshot.frameCount}` : `LIVE · ${snapshot.frameCount} RECORDED FRAMES`}</output>
        <output aria-label="Selected square">{snapshot.selected ? `${snapshot.selected.toUpperCase()} · LEGAL ${[...snapshot.legalTargets].join(" ").toUpperCase()}` : "NONE"}</output>
        <button type="button" onClick={() => session.power()}>{snapshot.state.power ? "Power off" : "Power on"}</button>
        <button type="button" onClick={() => session.pressButton("A")}>Press A</button>
        <button type="button" onClick={() => session.pressButton("B")}>Press B</button>
        <button type="button" onClick={() => session.guidedAction()}>Next physical action: {snapshot.guided.label}</button>
        <button type="button" onClick={() => session.previousFrame()}>Previous recorded state</button>
        <button type="button" onClick={() => session.nextFrame()}>Next recorded state</button>
        <button type="button" onClick={() => session.goLive()}>Return to live firmware</button>
      </section>
      {snapshot.error && <output id="fatal-error" role="alert">{snapshot.error}</output>}
      <aside id="accessibility-help" className="sr-only">
        The board is interactive in three dimensions. Orbit with drag, zoom with the wheel or pinch, and activate the physical controls on the model.
      </aside>
    </main>
  );
}
