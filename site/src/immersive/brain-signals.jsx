import { useMemo, useRef } from "react";
import { useFrame } from "@react-three/fiber";
import * as THREE from "three";
import { BOARD_LAYOUT } from "../model.js";
import { PanelFace } from "./panel-texture.jsx";

const NODE_POSITIONS = [
  [-20, 16.2, -12],
  [-10, 16.2, -5],
  [0, 16.2, -12],
  [10, 16.2, -5],
  [20, 16.2, -12],
];

function SignalLink({ start, end, active, color, phase }) {
  const pulse = useRef();
  const curve = useMemo(() => new THREE.CatmullRomCurve3([
    new THREE.Vector3(...start),
    new THREE.Vector3((start[0] + end[0]) / 2, start[1] + 1.2, (start[2] + end[2]) / 2),
    new THREE.Vector3(...end),
  ]), [end, start]);
  const geometry = useMemo(() => new THREE.TubeGeometry(curve, 32, 0.11, 8, false), [curve]);
  useFrame(({ clock }) => {
    if (!pulse.current || !active) return;
    pulse.current.position.copy(curve.getPoint((clock.elapsedTime * 0.38 + phase) % 1));
  });
  return (
    <group>
      <mesh geometry={geometry}>
        <meshBasicMaterial color={color} transparent opacity={active ? 0.9 : 0.13} toneMapped={false} />
      </mesh>
      {active && (
        <mesh ref={pulse}>
          <sphereGeometry args={[0.42, 16, 12]} />
          <meshBasicMaterial color="#ffffff" toneMapped={false} />
        </mesh>
      )}
    </group>
  );
}

function BrainNode({ position, title, lines, active, color }) {
  return (
    <group position={position}>
      <mesh position={[0, -0.5, 0]} castShadow>
        <boxGeometry args={[8, 1, 5.3]} />
        <meshPhysicalMaterial
          color={active ? color : "#10171b"}
          emissive={active ? color : "#000000"}
          emissiveIntensity={active ? 0.28 : 0}
          metalness={0.55}
          roughness={0.28}
          transparent
          opacity={0.92}
        />
      </mesh>
      <PanelFace
        title={title}
        lines={lines}
        size={[7.65, 4.8]}
        position={[0, 0.04, 0]}
        colors={{
          background: "#071015",
          border: active ? color : "#26333a",
          foreground: "#b6cbd4",
          accent: active ? "#eafff8" : "#77878e",
          width: 512,
          height: 320,
          titleFont: "800 72px ui-monospace, SFMono-Regular, Consolas, monospace",
          font: "700 58px ui-monospace, SFMono-Regular, Consolas, monospace",
        }}
      />
    </group>
  );
}

function MovingHead({ state }) {
  if (!Number.isFinite(state.head?.file) || !Number.isFinite(state.head?.rank)) return null;
  const x = (state.head.file - 4.5) * BOARD_LAYOUT.squareSize;
  const z = (state.head.rank - 4.5) * BOARD_LAYOUT.squareSize;
  return (
    <group position={[x, 7.2, z]}>
      <mesh rotation={[Math.PI / 2, 0, 0]}>
        <torusGeometry args={[1.15, 0.16, 12, 48]} />
        <meshBasicMaterial color={state.magnet ? "#ffbd4a" : "#61dfff"} transparent opacity={0.9} toneMapped={false} />
      </mesh>
      <pointLight color={state.magnet ? "#ff9f3f" : "#4ac9ff"} intensity={state.moving ? 8 : 3} distance={7} />
      {state.magnet && (
        <mesh position={[0, 1.3, 0]}>
          <cylinderGeometry args={[0.3, 1.25, 2.5, 28, 1, true]} />
          <meshBasicMaterial color="#ffc857" transparent opacity={0.24} side={THREE.DoubleSide} depthWrite={false} />
        </mesh>
      )}
    </group>
  );
}

export function BrainSignals({ snapshot, visible }) {
  const state = snapshot.state;
  if (!visible || !state.power) return null;
  const sequence = Number(state.sequence ?? 0);
  const active = [
    [3, 4, 5, 8].includes(sequence),
    true,
    [5, 6, 8].includes(sequence),
    [6, 8].includes(sequence) || Boolean(state.moving),
    Boolean(state.magnet) || Boolean(state.moving),
  ];
  const nodes = [
    ["INPUT", [`${state.sensedOccupied?.length ?? 0}/64 REEDS`, `A ${state.buttonA ? "LOW" : "HIGH"} · B ${state.buttonB ? "LOW" : "HIGH"}`], "#40dcb1"],
    ["AVR LOOP", [`PC 0x${Number(state.pc ?? 0).toString(16).padStart(4, "0")}`, `${Number(state.clockHz ?? 0) / 1e6 || 16} MHz`], "#58c8ff"],
    ["STATE", [`${sequence} · ${state.stateName}`, state.source ?? "firmware"], "#b98cff"],
    ["MICRO-MAX", [state.aiMove ? `MOVE ${state.aiMove.toUpperCase()}` : "WAITING", state.humanMove ? `RX ${state.humanMove.toUpperCase()}` : "STANDALONE"], "#ff7fc8"],
    ["OUTPUT", [`${state.stepPulses ?? 0} STEP PULSES`, `MAGNET ${state.magnet ? "ON" : "OFF"}`], "#ffbd5a"],
  ];
  return (
    <group name="arduino-signal-visualizer">
      <mesh position={[0, 14.8, -8.5]} receiveShadow>
        <boxGeometry args={[51, 0.35, 15]} />
        <meshPhysicalMaterial color="#061018" transparent opacity={0.54} roughness={0.18} transmission={0.08} />
      </mesh>
      {nodes.map(([title, lines, color], index) => (
        <BrainNode key={title} position={NODE_POSITIONS[index]} title={title} lines={lines} active={active[index]} color={color} />
      ))}
      {NODE_POSITIONS.slice(0, -1).map((position, index) => (
        <SignalLink key={index} start={position} end={NODE_POSITIONS[index + 1]} active={active[index] && active[index + 1]} color={nodes[index + 1][2]} phase={index * 0.21} />
      ))}
      <MovingHead state={state} />
    </group>
  );
}
