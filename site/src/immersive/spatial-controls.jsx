import { useMemo, useRef, useState } from "react";
import { PanelFace } from "./panel-texture.jsx";

const CONTROL_Y = 10.4;

export function PhysicalButton({
  label,
  sublabel = "",
  position,
  size = [4.1, 1.05, 3.2],
  color = "#17313b",
  active = false,
  disabled = false,
  onPress,
}) {
  const [hovered, setHovered] = useState(false);
  const [pressed, setPressed] = useState(false);
  const pressedRef = useRef(false);
  const faceLines = useMemo(() => [label, sublabel], [label, sublabel]);
  const lift = pressed ? 0.02 : 0.18;
  return (
    <group
      position={position}
      onPointerEnter={(event) => { event.stopPropagation(); setHovered(true); }}
      onPointerLeave={() => setHovered(false)}
      onPointerDown={(event) => {
        event.stopPropagation();
        event.nativeEvent?.stopImmediatePropagation?.();
        document.body.dataset.lastSpatialInput = label;
        if (!disabled) {
          pressedRef.current = true;
          setPressed(true);
          onPress?.();
        }
      }}
      onPointerUp={(event) => {
        event.stopPropagation();
        pressedRef.current = false;
        setPressed(false);
      }}
      onPointerCancel={() => {
        pressedRef.current = false;
        setPressed(false);
      }}
      userData={{ simulationControl: label }}
    >
      <mesh position={[0, -0.08, 0]} castShadow receiveShadow>
        <boxGeometry args={size} />
        <meshPhysicalMaterial color="#080d11" metalness={0.65} roughness={0.28} />
      </mesh>
      <mesh position={[0, size[1] / 2 + lift, 0]} castShadow>
        <boxGeometry args={[size[0] * 0.91, 0.32, size[2] * 0.86]} />
        <meshPhysicalMaterial
          color={disabled ? "#171b1e" : (active || hovered ? "#24715f" : color)}
          emissive={active || hovered ? "#38d7aa" : "#000000"}
          emissiveIntensity={active ? 0.52 : (hovered ? 0.2 : 0)}
          roughness={0.24}
          clearcoat={0.55}
        />
      </mesh>
      <PanelFace
        lines={faceLines}
        size={[size[0] * 0.82, size[2] * 0.7]}
        position={[0, size[1] / 2 + lift + 0.17, 0]}
        colors={{
          background: "rgba(0,0,0,0)",
          border: "rgba(0,0,0,0)",
          foreground: disabled ? "#59636a" : "#d9fff2",
          accent: disabled ? "#59636a" : "#d9fff2",
          width: 512,
          height: 256,
          font: "800 106px ui-monospace, SFMono-Regular, Consolas, monospace",
        }}
      />
    </group>
  );
}

function TimelineTrack({ snapshot, session }) {
  const dragging = useRef(false);
  const count = snapshot.frameCount;
  const fraction = count < 2 || snapshot.viewIndex < 0 ? 1 : snapshot.viewIndex / (count - 1);
  const seek = (event) => {
    if (!count) return;
    const value = Math.max(0, Math.min(1, event.uv?.x ?? 1));
    session.setReplayIndex(Math.round(value * (count - 1)));
  };
  return (
    <group position={[0, CONTROL_Y + 0.15, 34.2]}>
      <mesh
        rotation={[-Math.PI / 2, 0, 0]}
        onPointerDown={(event) => {
          event.stopPropagation();
          dragging.current = true;
          event.target.setPointerCapture?.(event.pointerId);
          seek(event);
        }}
        onPointerMove={(event) => {
          if (!dragging.current) return;
          event.stopPropagation();
          seek(event);
        }}
        onPointerUp={(event) => {
          event.stopPropagation();
          dragging.current = false;
          event.target.releasePointerCapture?.(event.pointerId);
          seek(event);
        }}
      >
        <planeGeometry args={[29, 2.8]} />
        <meshBasicMaterial transparent opacity={0} depthWrite={false} />
      </mesh>
      <mesh position={[0, 0, 0]}>
        <boxGeometry args={[27, 0.13, 0.34]} />
        <meshStandardMaterial color="#23323a" metalness={0.72} roughness={0.25} />
      </mesh>
      <mesh position={[-13.5 + 13.5 * fraction, 0.09, 0]}>
        <boxGeometry args={[Math.max(0.01, 27 * fraction), 0.16, 0.38]} />
        <meshBasicMaterial color="#68efbd" toneMapped={false} />
      </mesh>
      <mesh position={[-13.5 + 27 * fraction, 0.35, 0]} castShadow>
        <cylinderGeometry args={[0.55, 0.55, 0.55, 28]} />
        <meshPhysicalMaterial color={snapshot.historical ? "#ffd16a" : "#6affc5"} emissive="#50d9a8" emissiveIntensity={0.5} />
      </mesh>
    </group>
  );
}

function formatTime(milliseconds = 0) {
  const seconds = Math.max(0, milliseconds / 1000);
  const minutes = Math.floor(seconds / 60);
  return `${String(minutes).padStart(2, "0")}:${(seconds % 60).toFixed(1).padStart(4, "0")}`;
}

export function SpatialControls({ snapshot, session, mode, setMode, resetCamera, enterVR, enterAR }) {
  const state = snapshot.state;
  const lcdLines = useMemo(() => state.lcd?.length ? state.lcd : ["POWER OFF", ""], [state.lcd]);
  const identityLines = useMemo(() => [
    state.power ? state.stateName : (snapshot.ready ? "POWER REMOVED" : "LOADING HEX"),
    `REAL HEX ${state.firmwareVersion ?? snapshot.manifest?.firmwareVersion ?? "--"} · ${String(state.firmwareHash ?? snapshot.manifest?.sha256 ?? "").slice(0, 8) || "--------"}`,
    `BT ${state.bluetoothConnected ? "CONNECTED" : "DISCONNECTED"} · ${snapshot.historical ? "REPLAY" : "LIVE"} · ${snapshot.speed}x`,
  ], [snapshot.historical, snapshot.manifest, snapshot.ready, snapshot.speed, state]);
  const timelineLines = useMemo(() => [
    snapshot.historical ? `FRAME ${snapshot.viewIndex + 1}/${snapshot.frameCount}` : `LIVE · ${snapshot.frameCount} FRAMES`,
    `${formatTime(state.runtimeMs)} · ${Number(state.instructions ?? 0).toLocaleString()} AVR INSTRUCTIONS`,
  ], [snapshot.frameCount, snapshot.historical, snapshot.viewIndex, state.instructions, state.runtimeMs]);

  return (
    <group name="physical-simulator-console">
      <mesh position={[0, 8.95, 31]} receiveShadow castShadow>
        <boxGeometry args={[54, 1.25, 15]} />
        <meshPhysicalMaterial color="#10171d" metalness={0.72} roughness={0.3} clearcoat={0.32} />
      </mesh>

      <PanelFace
        title="16 × 2 LCD · I²C"
        lines={lcdLines}
        size={[17.5, 4.4]}
        position={[-11.2, CONTROL_Y + 0.04, 27.6]}
        colors={{ background: "#061a0c", border: "#2f8148", foreground: "#d1ffac", accent: "#c8ff9b" }}
      />
      <PanelFace
        title="ATMEGA328P"
        lines={identityLines}
        size={[17.5, 5.8]}
        position={[9.2, CONTROL_Y + 0.04, 27.7]}
        colors={{ background: "#071018", border: "#34718c", foreground: "#a5d9e9", accent: "#74e6ff", font: "700 38px ui-monospace, SFMono-Regular, Consolas, monospace" }}
      />

      <PhysicalButton label="POWER" sublabel={state.power ? "OFF" : "ON"} position={[-23.2, CONTROL_Y, 27.7]} color="#5b2027" active={state.power} disabled={!snapshot.ready || snapshot.historical} onPress={() => session.power()} />
      <PhysicalButton label="A" sublabel="SELECT" position={[-23.2, CONTROL_Y, 32.3]} disabled={!state.power || snapshot.historical} onPress={() => session.pressButton("A")} />
      <PhysicalButton label="B" sublabel="BACK" position={[20.8, CONTROL_Y, 32.3]} disabled={!state.power || snapshot.historical} onPress={() => session.pressButton("B")} />
      <PhysicalButton label={snapshot.guided.label} sublabel="NEXT ACTION" position={[20.8, CONTROL_Y, 27.7]} size={[7.5, 1.05, 3.2]} active={!snapshot.guided.disabled} disabled={snapshot.guided.disabled || snapshot.historical} onPress={() => session.guidedAction()} />

      <TimelineTrack snapshot={snapshot} session={session} />
      <PhysicalButton label="◀" sublabel="PREV" position={[-20.2, CONTROL_Y, 34.2]} size={[3.2, 1, 2.8]} disabled={!snapshot.frameCount} onPress={() => session.previousFrame()} />
      <PhysicalButton label={snapshot.timelinePlaying ? "Ⅱ" : "▶"} sublabel="PLAY" position={[-16.6, CONTROL_Y, 34.2]} size={[3.2, 1, 2.8]} disabled={!snapshot.frameCount} onPress={() => session.toggleTimelinePlayback()} />
      <PhysicalButton label="▶" sublabel="NEXT" position={[16.6, CONTROL_Y, 34.2]} size={[3.2, 1, 2.8]} disabled={!snapshot.frameCount} onPress={() => session.nextFrame()} />
      <PhysicalButton label={snapshot.historical ? "LIVE" : `${snapshot.speed}x`} sublabel={snapshot.historical ? "RETURN" : "SPEED"} position={[20.2, CONTROL_Y, 34.2]} size={[3.2, 1, 2.8]} onPress={() => snapshot.historical ? session.goLive() : session.cycleSpeed()} />
      <PanelFace lines={timelineLines} size={[14, 2.5]} position={[0, CONTROL_Y + 0.04, 31.8]} colors={{ background: "#080d11", border: "#263840", foreground: "#a9bcc5", accent: snapshot.historical ? "#ffd16a" : "#6affc5", font: "700 42px ui-monospace, SFMono-Regular, Consolas, monospace" }} />

      <group position={[0, CONTROL_Y, -28.4]} name="mode-dock">
        {[
          ["PLAY", "BOARD", -14, "play"],
          ["BRAIN", "SIGNALS", -5, "brain"],
          ["X-RAY", "INSIDE", 5, "xray"],
          ["WIRE", "BUILD", 14, "wiring"],
        ].map(([label, sublabel, x, value]) => (
          <PhysicalButton key={value} label={label} sublabel={sublabel} position={[x, 0, 0]} size={[8, 1.05, 3.2]} active={mode === value} onPress={() => setMode(value)} />
        ))}
        <PhysicalButton label="VIEW" sublabel="RESET" position={[23, 0, 0]} size={[6, 1.05, 3.2]} onPress={resetCamera} />
      </group>
      <group position={[-22.5, CONTROL_Y, -33]} name="xr-dock">
        <PhysicalButton label="VR" sublabel="1:1 MODE" position={[0, 0, 0]} size={[6.2, 1.05, 3.2]} onPress={enterVR} />
        <PhysicalButton label="AR" sublabel="MY ROOM" position={[7, 0, 0]} size={[6.2, 1.05, 3.2]} onPress={enterAR} />
      </group>
    </group>
  );
}
