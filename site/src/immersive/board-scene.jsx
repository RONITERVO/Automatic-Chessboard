import { useEffect, useMemo, useRef, useState } from "react";
import { AdaptiveDpr } from "@react-three/drei/core/AdaptiveDpr.js";
import { OrbitControls } from "@react-three/drei/core/OrbitControls.js";
import { useFrame, useThree } from "@react-three/fiber";
import { useXR, XROrigin } from "@react-three/xr";
import * as THREE from "three";
import { RoomEnvironment } from "three/addons/environments/RoomEnvironment.js";
import { PARTS } from "../catalog.js";
import { animateModel, BOARD_LAYOUT, createBoardModel, setXray } from "../model.js";
import { createWiringGuide } from "../wiring.js";
import { BrainSignals } from "./brain-signals.jsx";
import { InteractiveBoardPieces } from "./board-pieces.jsx";
import { PanelFace } from "./panel-texture.jsx";
import { PhysicalButton, SpatialControls } from "./spatial-controls.jsx";

const BOARD_POSITION = [0, 0.72, -0.7];
const BOARD_SCALE = 0.01;
const CAMERA_HOME = new THREE.Vector3(0.44, 1.25, 0.18);
const PORTRAIT_CAMERA_HOME = new THREE.Vector3(0.58, 1.45, 0.92);
const CAMERA_TARGET = new THREE.Vector3(0, 0.82, -0.68);
const PORTRAIT_CAMERA_TARGET = new THREE.Vector3(0, 0.94, -0.68);

function SceneEnvironment() {
  const { gl, scene } = useThree();
  useEffect(() => {
    const generator = new THREE.PMREMGenerator(gl);
    const room = new RoomEnvironment();
    const texture = generator.fromScene(room, 0.035).texture;
    scene.environment = texture;
    scene.environmentIntensity = 0.38;
    room.dispose();
    generator.dispose();
    return () => {
      if (scene.environment === texture) scene.environment = null;
      texture.dispose();
    };
  }, [gl, scene]);
  return null;
}

function CameraRig({ enabled, resetVersion, onEnabledChange }) {
  const controls = useRef();
  const { camera, size } = useThree();
  const xrSession = useXR((state) => state.session);
  const portrait = size.width / size.height < 0.72;
  const activeTarget = portrait ? PORTRAIT_CAMERA_TARGET : CAMERA_TARGET;

  useEffect(() => {
    if (xrSession) return;
    camera.position.copy(portrait ? PORTRAIT_CAMERA_HOME : CAMERA_HOME);
    camera.fov = portrait ? 48 : 38;
    camera.updateProjectionMatrix();
    document.body.dataset.cameraMode = portrait ? "portrait" : "desktop";
    document.body.dataset.cameraPose = `${camera.position.toArray().map((value) => value.toFixed(2)).join(",")} · ${camera.fov}`;
    camera.lookAt(activeTarget);
    if (controls.current) {
      controls.current.target.copy(activeTarget);
      controls.current.update();
    }
  }, [activeTarget, camera, portrait, resetVersion, size.height, size.width, xrSession]);

  return (
    <OrbitControls
      ref={controls}
      makeDefault
      target={activeTarget.toArray()}
      enabled={enabled && !xrSession}
      enableDamping
      dampingFactor={0.06}
      minDistance={0.32}
      maxDistance={3.2}
      maxPolarAngle={Math.PI * 0.91}
      zoomToCursor
      onStart={() => onEnabledChange?.(true)}
    />
  );
}

function WiringExperience({ guide, step, setStep, visible }) {
  useEffect(() => {
    guide.setEnabled(visible);
    if (visible) guide.showStep(step);
  }, [guide, step, visible]);
  useFrame(({ clock }) => guide.update(clock.elapsedTime));
  if (!visible) return null;
  const definition = guide.steps[step];
  const codes = guide.getCodes(step);
  return (
    <group name="spatial-wiring-instructions">
      <PanelFace
        title={`WIRING ${step + 1}/${guide.steps.length}`}
        lines={[...(definition.codes ?? []), ...codes].slice(0, 7)}
        size={[31, 14]}
        position={[0, 19, -4]}
        colors={{ background: "#070d13", border: "#56d9ff", foreground: "#c7eaf5", accent: "#6fe7ff", width: 768, height: 420, titleFont: "800 62px ui-monospace, SFMono-Regular, Consolas, monospace", font: "700 62px ui-monospace, SFMono-Regular, Consolas, monospace" }}
      />
      <PhysicalButton label="◀" sublabel="STEP" position={[-19, 14.5, -4]} disabled={step === 0} onPress={() => setStep(Math.max(0, step - 1))} />
      <PhysicalButton label="▶" sublabel="STEP" position={[19, 14.5, -4]} disabled={step === guide.steps.length - 1} onPress={() => setStep(Math.min(guide.steps.length - 1, step + 1))} />
    </group>
  );
}

function SelectedPart({ id, clear }) {
  const part = id ? PARTS[id] : null;
  if (!part) return null;
  return (
    <group name="selected-component-card">
      <PanelFace
        title="PHYSICAL COMPONENT"
        lines={[part.name, id.toUpperCase(), "DOUBLE-PRESS MODEL TO CHANGE"]}
        size={[28, 8]}
        position={[0, 20, 12]}
        colors={{ background: "#0a1016", border: part.color, foreground: "#cbd9df", accent: "#ffffff", font: "700 38px ui-monospace, SFMono-Regular, Consolas, monospace" }}
      />
      <PhysicalButton label="CLOSE" sublabel="PART" position={[18, 16, 12]} onPress={clear} />
    </group>
  );
}

export function BoardScene({ snapshot, session, xrStore, enterVR, enterAR }) {
  const [mode, setMode] = useState("play");
  const [orbitEnabled, setOrbitEnabled] = useState(true);
  const [resetVersion, setResetVersion] = useState(0);
  const [wiringStep, setWiringStep] = useState(0);
  const [selectedPart, setSelectedPart] = useState(null);
  const boardRoot = useRef();
  const model = useMemo(() => {
    const root = createBoardModel();
    const displayPieces = root.getObjectByName("pieces");
    if (displayPieces) displayPieces.visible = false;
    return root;
  }, []);
  const wiring = useMemo(() => {
    const root = new THREE.Group();
    return { root, guide: createWiringGuide(root) };
  }, []);
  const boardPlane = useMemo(() => new THREE.Plane(
    new THREE.Vector3(0, 1, 0),
    -(BOARD_POSITION[1] + BOARD_LAYOUT.surfaceY * BOARD_SCALE),
  ), []);
  const immersiveMode = mode === "brain" || mode === "xray" || mode === "wiring";

  useEffect(() => {
    setXray(immersiveMode);
    return () => setXray(false);
  }, [immersiveMode]);

  useFrame(() => animateModel(mode === "xray" || mode === "wiring" ? 1 : (mode === "brain" ? 0.38 : 0)));

  return (
    <>
      <color attach="background" args={["#05080b"]} />
      <fog attach="fog" args={["#05080b", 1.7, 4.6]} />
      <hemisphereLight args={["#d9eeff", "#14202a", 2.4]} />
      <directionalLight position={[-1.8, 3.4, 1.7]} intensity={4.2} castShadow shadow-mapSize={[2048, 2048]} />
      <directionalLight position={[2, 1.8, -2.4]} intensity={2.4} color="#5fcfff" />
      <pointLight position={[-1.2, 1.15, -0.1]} intensity={16} color="#ffb36e" distance={3.2} />
      <SceneEnvironment />

      <mesh position={[0, 0.67, -0.7]} receiveShadow>
        <boxGeometry args={[1.15, 0.07, 1.25]} />
        <meshPhysicalMaterial color="#10161a" roughness={0.58} metalness={0.18} clearcoat={0.14} />
      </mesh>
      <mesh rotation={[-Math.PI / 2, 0, 0]} position={[0, 0, -0.7]} receiveShadow>
        <circleGeometry args={[3.2, 96]} />
        <meshStandardMaterial color="#070b0e" roughness={0.88} />
      </mesh>

      <group ref={boardRoot} position={BOARD_POSITION} scale={BOARD_SCALE}>
        <primitive
          object={model}
          onDoubleClick={(event) => {
            event.stopPropagation();
            const id = event.object?.userData?.partId;
            if (id && PARTS[id]) setSelectedPart(id);
          }}
        />
        <primitive object={wiring.root} />
        <InteractiveBoardPieces snapshot={snapshot} session={session} boardRoot={boardRoot} boardPlane={boardPlane} setOrbitEnabled={setOrbitEnabled} />
        <SpatialControls
          snapshot={snapshot}
          session={session}
          mode={mode}
          setMode={setMode}
          resetCamera={() => setResetVersion((value) => value + 1)}
          enterVR={enterVR}
          enterAR={enterAR}
        />
        <BrainSignals snapshot={snapshot} visible={mode === "brain" || mode === "xray"} />
        <WiringExperience guide={wiring.guide} step={wiringStep} setStep={setWiringStep} visible={mode === "wiring"} />
        <SelectedPart id={selectedPart} clear={() => setSelectedPart(null)} />
      </group>

      <CameraRig enabled={orbitEnabled} resetVersion={resetVersion} onEnabledChange={setOrbitEnabled} />
      <XROrigin position={[0, 0, 0.22]} />
      <AdaptiveDpr pixelated />
    </>
  );
}
