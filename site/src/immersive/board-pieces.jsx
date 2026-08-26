import { useMemo, useRef, useState } from "react";
import * as THREE from "three";
import { BOARD_LAYOUT, pieceProfile } from "../model.js";
import { createStartingPieces } from "../firmware-session.js";
import { boardPositionToSquare, FILES, RANKS, squareToBoardPosition } from "./board-space.js";

const TYPES = { p: "pawn", n: "knight", b: "bishop", r: "rook", q: "queen", k: "king" };
const geometries = Object.fromEntries(Object.values(TYPES).map((type) => [type, new THREE.LatheGeometry(pieceProfile(type), 36)]));
const whiteMaterial = new THREE.MeshPhysicalMaterial({ color: 0xf2eadb, roughness: 0.24, clearcoat: 0.72 });
const blackMaterial = new THREE.MeshPhysicalMaterial({ color: 0x202931, roughness: 0.2, clearcoat: 0.62 });
const magnetMaterial = new THREE.MeshPhysicalMaterial({ color: 0xa9bdc7, metalness: 0.92, roughness: 0.2 });

function PieceMesh({ code, selected }) {
  const type = TYPES[code[1]];
  const material = code[0] === "w" ? whiteMaterial : blackMaterial;
  return (
    <group scale={selected ? 1.08 : 1}>
      <mesh geometry={geometries[type]} material={material} castShadow receiveShadow>
        {selected && <meshPhysicalMaterial color="#fff6c7" emissive="#ffc857" emissiveIntensity={0.36} roughness={0.2} />}
      </mesh>
      <mesh position={[0, 0.06, 0]} material={magnetMaterial}>
        <cylinderGeometry args={[0.25, 0.25, 0.16, 24]} />
      </mesh>
    </group>
  );
}

function DraggablePiece({
  id,
  code,
  homePosition,
  selected,
  beginSelection,
  completeSelection,
  boardRoot,
  boardPlane,
  setOrbitEnabled,
}) {
  const [dragPoint, setDragPoint] = useState(null);
  const dragPointRef = useRef(null);
  const draggedRef = useRef(false);
  const start = useMemo(() => new THREE.Vector3(), []);
  const point = useMemo(() => new THREE.Vector3(), []);

  const project = (event) => {
    if (!event.ray.intersectPlane(boardPlane, point)) return null;
    const local = point.clone();
    boardRoot.current?.worldToLocal(local);
    return local;
  };

  const onPointerDown = (event) => {
    event.stopPropagation();
    event.nativeEvent?.stopImmediatePropagation?.();
    if (!beginSelection()) return;
    const local = project(event) ?? new THREE.Vector3(...homePosition);
    start.copy(local);
    dragPointRef.current = local;
    draggedRef.current = false;
    setDragPoint(local);
    setOrbitEnabled(false);
    event.target.setPointerCapture?.(event.pointerId);
  };

  const onPointerMove = (event) => {
    if (!dragPointRef.current) return;
    event.stopPropagation();
    const local = project(event);
    if (!local) return;
    if (local.distanceTo(start) > 0.45) draggedRef.current = true;
    dragPointRef.current = local;
    setDragPoint(local);
  };

  const onPointerUp = (event) => {
    if (!dragPointRef.current) return;
    event.stopPropagation();
    const local = project(event) ?? dragPointRef.current;
    event.target.releasePointerCapture?.(event.pointerId);
    setOrbitEnabled(true);
    dragPointRef.current = null;
    setDragPoint(null);
    if (draggedRef.current) {
      const square = boardPositionToSquare(local.x, local.z);
      if (square) completeSelection(square);
    }
    draggedRef.current = false;
  };

  const position = dragPoint
    ? [dragPoint.x, BOARD_LAYOUT.surfaceY + 1.0, dragPoint.z]
    : homePosition;
  return (
    <group
      name={id}
      position={position}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
      userData={{ simulationPiece: id }}
    >
      <PieceMesh code={code} selected={selected || Boolean(dragPoint)} />
    </group>
  );
}

function SquareMarker({ square, snapshot, onSelect }) {
  const [x, y, z] = squareToBoardPosition(square);
  const selected = snapshot.selected === square;
  const legal = snapshot.legalTargets.has(square);
  const last = snapshot.lastMove && (snapshot.lastMove.from === square || snapshot.lastMove.to === square);
  const manual = snapshot.state.sequence === 8
    && (snapshot.state.aiMove?.slice(0, 2) === square || snapshot.state.aiMove?.slice(2, 4) === square);
  const color = legal ? "#62f6ba" : (selected ? "#8eefff" : (manual ? "#ffd45a" : "#8eefff"));
  return (
    <group position={[x, y + 0.04, z]}>
      <mesh
        position={[0, 0, 0]}
        onPointerDown={(event) => {
          event.stopPropagation();
          event.nativeEvent?.stopImmediatePropagation?.();
          onSelect(square);
        }}
      >
        <boxGeometry args={[BOARD_LAYOUT.squareSize * 0.96, 0.12, BOARD_LAYOUT.squareSize * 0.96]} />
        <meshBasicMaterial transparent opacity={0} depthWrite={false} />
      </mesh>
      {(last || selected) && (
        <mesh position={[0, 0.08, 0]}>
          <boxGeometry args={[BOARD_LAYOUT.squareSize * 0.9, 0.055, BOARD_LAYOUT.squareSize * 0.9]} />
          <meshBasicMaterial color={selected ? "#66e7ff" : "#ffd45a"} transparent opacity={selected ? 0.28 : 0.15} />
        </mesh>
      )}
      {legal && (
        <mesh position={[0, 0.2, 0]}>
          <cylinderGeometry args={[0.54, 0.54, 0.11, 24]} />
          <meshBasicMaterial color={color} transparent opacity={0.9} toneMapped={false} />
        </mesh>
      )}
    </group>
  );
}

function SetupRacks({ snapshot, session, boardRoot, boardPlane, setOrbitEnabled }) {
  if (snapshot.state.sequence !== 4 || snapshot.historical) return null;
  const expected = createStartingPieces();
  const remaining = Object.entries(expected).filter(([square, code]) => snapshot.state.pieces?.[square] !== code);
  const colors = { w: [], b: [] };
  remaining.forEach((entry) => colors[entry[1][0]].push(entry));
  return (
    <group name="setup-piece-racks">
      {Object.entries(colors).flatMap(([color, entries]) => entries.map(([square, code], index) => {
        const side = color === "w" ? -1 : 1;
        const column = Math.floor(index / 8);
        const row = index % 8;
        const position = [side * (27.2 + column * 3.25), BOARD_LAYOUT.surfaceY, (row - 3.5) * BOARD_LAYOUT.squareSize];
        return (
          <DraggablePiece
            key={`rack-${square}`}
            id={`setup-${square}`}
            code={code}
            homePosition={position}
            selected={snapshot.setupSelection?.square === square}
            beginSelection={() => session.selectSetupPiece(square, code)}
            completeSelection={(destination) => session.selectSquare(destination)}
            boardRoot={boardRoot}
            boardPlane={boardPlane}
            setOrbitEnabled={setOrbitEnabled}
          />
        );
      }))}
    </group>
  );
}

export function InteractiveBoardPieces({ snapshot, session, boardRoot, boardPlane, setOrbitEnabled }) {
  const entries = Object.entries(snapshot.state.pieces ?? {});
  const held = snapshot.state.heldPiece
    && Number.isFinite(snapshot.state.head?.file)
    && Number.isFinite(snapshot.state.head?.rank)
    ? [[`held-${snapshot.state.head.square ?? "piece"}`, snapshot.state.heldPiece, [
      (snapshot.state.head.file - 4.5) * BOARD_LAYOUT.squareSize,
      BOARD_LAYOUT.surfaceY + 0.55,
      (snapshot.state.head.rank - 4.5) * BOARD_LAYOUT.squareSize,
    ]]]
    : [];
  return (
    <group name="interactive-chess-state">
      {FILES.split("").flatMap((file) => RANKS.split("").map((rank) => (
        <SquareMarker key={`${file}${rank}`} square={`${file}${rank}`} snapshot={snapshot} onSelect={(square) => session.selectSquare(square)} />
      )))}
      {entries.map(([square, code]) => (
        <DraggablePiece
          key={`${square}-${code}`}
          id={`piece-${square}`}
          code={code}
          homePosition={squareToBoardPosition(square)}
          selected={snapshot.selected === square}
          beginSelection={() => session.selectSquare(square)}
          completeSelection={(destination) => session.selectSquare(destination)}
          boardRoot={boardRoot}
          boardPlane={boardPlane}
          setOrbitEnabled={setOrbitEnabled}
        />
      ))}
      {held.map(([id, code, position]) => (
        <group key={id} position={position}><PieceMesh code={code} selected /></group>
      ))}
      <SetupRacks
        snapshot={snapshot}
        session={session}
        boardRoot={boardRoot}
        boardPlane={boardPlane}
        setOrbitEnabled={setOrbitEnabled}
      />
    </group>
  );
}
