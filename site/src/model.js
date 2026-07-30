import * as THREE from "three";
import { RoundedBoxGeometry } from "three/addons/geometries/RoundedBoxGeometry.js";
import { PARTS } from "./catalog.js";

const registry = new Map();
const rayTargets = [];
const xrayMeshes = [];
const sections = [];

const materials = {
  aluminum: new THREE.MeshPhysicalMaterial({ color: 0x252c32, metalness: 0.92, roughness: 0.24 }),
  aluminumEdge: new THREE.MeshPhysicalMaterial({ color: 0x78838a, metalness: 1, roughness: 0.2 }),
  belt: new THREE.MeshStandardMaterial({ color: 0x0d1013, roughness: 0.76, metalness: 0.08 }),
  black: new THREE.MeshPhysicalMaterial({ color: 0x11161c, roughness: 0.35, metalness: 0.56 }),
  ceramicLight: new THREE.MeshPhysicalMaterial({ color: 0xf2f1eb, roughness: 0.22, clearcoat: 0.5 }),
  ceramicDark: new THREE.MeshPhysicalMaterial({ color: 0x16191d, roughness: 0.2, clearcoat: 0.55 }),
  copper: new THREE.MeshPhysicalMaterial({ color: 0xc97942, metalness: 0.8, roughness: 0.25 }),
  glass: new THREE.MeshPhysicalMaterial({ color: 0xbceeff, roughness: 0.08, transmission: 0.76, transparent: true, opacity: 0.74, thickness: 0.18 }),
  gold: new THREE.MeshPhysicalMaterial({ color: 0xe3b34d, metalness: 0.84, roughness: 0.22 }),
  pcbBlue: new THREE.MeshPhysicalMaterial({ color: 0x075fa8, roughness: 0.38, clearcoat: 0.35 }),
  pcbBlack: new THREE.MeshPhysicalMaterial({ color: 0x101820, roughness: 0.34, clearcoat: 0.28 }),
  pcbGreen: new THREE.MeshPhysicalMaterial({ color: 0x0b6846, roughness: 0.4, clearcoat: 0.28 }),
  red: new THREE.MeshPhysicalMaterial({ color: 0xc92f36, roughness: 0.31, clearcoat: 0.45 }),
  rubber: new THREE.MeshStandardMaterial({ color: 0x171b20, roughness: 0.88 }),
  screen: new THREE.MeshPhysicalMaterial({ color: 0x0a2a71, emissive: 0x0c4fb6, emissiveIntensity: 0.34, roughness: 0.14, clearcoat: 0.8 }),
  steel: new THREE.MeshPhysicalMaterial({ color: 0xaab5bb, metalness: 0.95, roughness: 0.22 }),
  whitePiece: new THREE.MeshPhysicalMaterial({ color: 0xe9e4d8, roughness: 0.28, clearcoat: 0.55 }),
  blackPiece: new THREE.MeshPhysicalMaterial({ color: 0x262b31, roughness: 0.26, clearcoat: 0.5 }),
  wireBlue: new THREE.MeshStandardMaterial({ color: 0x208fea, roughness: 0.55 }),
  wireRed: new THREE.MeshStandardMaterial({ color: 0xd94045, roughness: 0.55 }),
  wireYellow: new THREE.MeshStandardMaterial({ color: 0xe4ba42, roughness: 0.55 }),
};

function roundedBox(width, height, depth, material, radius = 0.12, segments = 3) {
  const mesh = new THREE.Mesh(new RoundedBoxGeometry(width, height, depth, segments, radius), material);
  mesh.castShadow = true;
  mesh.receiveShadow = true;
  return mesh;
}

function cylinder(radius, height, material, radialSegments = 32) {
  const mesh = new THREE.Mesh(new THREE.CylinderGeometry(radius, radius, height, radialSegments), material);
  mesh.castShadow = true;
  mesh.receiveShadow = true;
  return mesh;
}

function tag(root, partId) {
  if (!PARTS[partId]) throw new Error(`Unknown part id: ${partId}`);
  if (!registry.has(partId)) registry.set(partId, []);
  root.traverse((object) => {
    if (!object.isMesh || object.userData.partId) return;
    object.userData.partId = partId;
    registry.get(partId).push(object);
    rayTargets.push(object);
  });
  return root;
}

function makeSection(name, explodeOffset) {
  const group = new THREE.Group();
  group.name = name;
  group.userData.home = new THREE.Vector3();
  group.userData.explodeOffset = explodeOffset;
  sections.push(group);
  return group;
}

function screw(x, y, z, parent, scale = 1) {
  const head = cylinder(0.24 * scale, 0.12 * scale, materials.steel, 24);
  head.position.set(x, y, z);
  const slot = roundedBox(0.28 * scale, 0.025 * scale, 0.045 * scale, materials.black, 0.01);
  slot.position.set(x, y + 0.07 * scale, z);
  parent.add(head, slot);
}

function tube(points, radius, material, closed = false) {
  const curve = new THREE.CatmullRomCurve3(points, closed, "centripetal", 0.45);
  const mesh = new THREE.Mesh(new THREE.TubeGeometry(curve, Math.max(24, points.length * 12), radius, 8, closed), material);
  mesh.castShadow = true;
  return mesh;
}

function rail(length, alongX = true) {
  const group = new THREE.Group();
  const body = roundedBox(alongX ? length : 2, 2, alongX ? 2 : length, materials.aluminum, 0.13);
  group.add(body);
  const grooveMaterial = new THREE.MeshStandardMaterial({ color: 0x090c0f, roughness: 0.7, metalness: 0.45 });
  for (const side of [-1, 1]) {
    const groove = roundedBox(alongX ? length - 0.2 : 0.08, 0.13, alongX ? 0.08 : length - 0.2, grooveMaterial, 0.025);
    groove.position.set(alongX ? 0 : side * 1.005, 0, alongX ? side * 1.005 : 0);
    group.add(groove);
    const topGroove = roundedBox(alongX ? length - 0.2 : 0.12, 0.08, alongX ? 0.12 : length - 0.2, grooveMaterial, 0.025);
    topGroove.position.set(alongX ? 0 : side * 0.5, 1.005, alongX ? side * 0.5 : 0);
    group.add(topGroove);
  }
  return tag(group, "frame");
}

function pulley(x, y, z, parent, partId = "motion") {
  const group = new THREE.Group();
  group.position.set(x, y, z);
  const wheel = cylinder(0.72, 0.46, materials.steel, 32);
  const hub = cylinder(0.22, 0.62, materials.black, 24);
  group.add(wheel, hub);
  for (const offset of [-0.28, 0.28]) {
    const lip = new THREE.Mesh(new THREE.TorusGeometry(0.67, 0.05, 8, 32), materials.aluminumEdge);
    lip.rotation.x = Math.PI / 2;
    lip.position.y = offset;
    group.add(lip);
  }
  parent.add(tag(group, partId));
}

function stepperMotor(x, y, z, rotation, parent) {
  const group = new THREE.Group();
  group.position.set(x, y, z);
  group.rotation.y = rotation;
  const body = roundedBox(4.05, 3.3, 4.05, materials.black, 0.24);
  const front = roundedBox(4.22, 0.22, 4.22, materials.aluminumEdge, 0.18);
  front.position.y = 1.7;
  const back = front.clone();
  back.position.y = -1.7;
  const shaft = cylinder(0.25, 1.1, materials.steel, 24);
  shaft.position.y = 2.3;
  group.add(body, front, back, shaft);
  for (const sx of [-1.55, 1.55]) for (const sz of [-1.55, 1.55]) screw(sx, 1.86, sz, group, 0.65);
  parent.add(tag(group, "motors"));
}

function microswitch(x, y, z, rotation, parent) {
  const group = new THREE.Group();
  group.position.set(x, y, z);
  group.rotation.y = rotation;
  const body = roundedBox(1.4, 0.65, 0.72, materials.black, 0.1);
  const lever = roundedBox(1.65, 0.06, 0.16, materials.steel, 0.025);
  lever.position.set(0.45, 0.5, 0);
  lever.rotation.z = -0.18;
  const roller = cylinder(0.18, 0.2, materials.steel, 20);
  roller.rotation.x = Math.PI / 2;
  roller.position.set(1.25, 0.36, 0);
  group.add(body, lever, roller);
  parent.add(tag(group, "endstops"));
}

function boardModule(width, depth, material, partId, height = 0.18) {
  const group = new THREE.Group();
  group.add(roundedBox(width, height, depth, material, 0.1));
  return tag(group, partId);
}

function pinRows(group, width, depth, count, axis = "x") {
  for (let i = 0; i < count; i += 1) {
    const t = count === 1 ? 0 : i / (count - 1) - 0.5;
    for (const side of [-1, 1]) {
      const pin = roundedBox(0.08, 0.28, 0.08, materials.gold, 0.015);
      pin.position.set(axis === "x" ? t * (width - 0.35) : side * (width / 2 - 0.12), 0.2, axis === "x" ? side * (depth / 2 - 0.12) : t * (depth - 0.35));
      group.add(pin);
    }
  }
}

function chip(group, width, depth, x = 0, z = 0) {
  const ic = roundedBox(width, 0.28, depth, materials.pcbBlack, 0.05);
  ic.position.set(x, 0.2, z);
  group.add(ic);
  const pins = Math.max(4, Math.round(width / 0.22));
  for (let i = 0; i < pins; i += 1) {
    const px = x + (i / (pins - 1) - 0.5) * (width - 0.16);
    for (const side of [-1, 1]) {
      const leg = roundedBox(0.07, 0.05, 0.25, materials.steel, 0.01);
      leg.position.set(px, 0.12, z + side * (depth / 2 + 0.1));
      group.add(leg);
    }
  }
}

function nano(parent, x, y, z) {
  const group = boardModule(4.5, 1.8, materials.pcbBlue, "controller");
  group.position.set(x, y, z);
  pinRows(group, 4.5, 1.8, 15);
  chip(group, 1.25, 1.25, 0.15, 0);
  const usb = roundedBox(1.05, 0.48, 0.88, materials.steel, 0.09);
  usb.position.set(1.85, 0.3, 0);
  group.add(usb);
  parent.add(group);
}

function multiplexer(parent, x, y, z) {
  const group = boardModule(4.8, 2.0, materials.pcbBlue, "multiplexers");
  group.position.set(x, y, z);
  chip(group, 1.05, 0.62);
  pinRows(group, 4.8, 2, 16);
  parent.add(group);
}

function driver(parent, x, y, z) {
  const group = boardModule(2.05, 1.55, materials.pcbBlack, "drivers");
  group.position.set(x, y, z);
  pinRows(group, 2.05, 1.55, 8);
  chip(group, 0.9, 0.9);
  const sink = new THREE.Group();
  for (let i = -3; i <= 3; i += 1) {
    const fin = roundedBox(0.11, 0.62, 1.05, materials.aluminumEdge, 0.015);
    fin.position.set(i * 0.18, 0.56, 0);
    sink.add(fin);
  }
  group.add(sink);
  parent.add(group);

  const capGroup = new THREE.Group();
  capGroup.position.set(x - 1.5, y + 0.55, z);
  const can = cylinder(0.38, 0.92, materials.black, 30);
  const top = cylinder(0.36, 0.03, materials.aluminumEdge, 30);
  top.position.y = 0.475;
  capGroup.add(can, top);
  parent.add(tag(capGroup, "driverCaps"));
}

function lcd(parent, x, y, z) {
  const group = boardModule(8.0, 3.7, materials.pcbGreen, "display");
  group.position.set(x, y, z);
  group.rotation.x = -0.2;
  const bezel = roundedBox(7.2, 0.45, 2.65, materials.black, 0.16);
  bezel.position.y = 0.32;
  const screen = roundedBox(6.45, 0.1, 1.8, materials.screen, 0.08);
  screen.position.y = 0.58;
  group.add(bezel, screen);
  for (const sx of [-3.55, 3.55]) for (const sz of [-1.35, 1.35]) screw(sx, 0.25, sz, group, 0.55);
  parent.add(group);
}

function bluetooth(parent, x, y, z) {
  const group = boardModule(3.55, 1.52, materials.pcbGreen, "bluetooth");
  group.position.set(x, y, z);
  chip(group, 1.05, 0.88, -0.45, 0);
  const antenna = new THREE.Line(
    new THREE.BufferGeometry().setFromPoints([
      new THREE.Vector3(0.35, 0.17, -0.45), new THREE.Vector3(0.75, 0.17, -0.45),
      new THREE.Vector3(0.75, 0.17, 0.45), new THREE.Vector3(1.12, 0.17, 0.45),
      new THREE.Vector3(1.12, 0.17, -0.45), new THREE.Vector3(1.5, 0.17, -0.45),
    ]),
    new THREE.LineBasicMaterial({ color: 0xd3b044 }),
  );
  group.add(antenna);
  parent.add(group);
}

function powerModule(parent, x, y, z, width, depth, partId, material = materials.pcbGreen) {
  const group = boardModule(width, depth, material, partId);
  group.position.set(x, y, z);
  chip(group, Math.min(1.0, width * 0.42), Math.min(0.9, depth * 0.55));
  for (const sx of [-1, 1]) {
    const terminal = roundedBox(0.5, 0.48, 0.72, materials.pcbGreen, 0.08);
    terminal.position.set(sx * (width / 2 - 0.34), 0.3, 0);
    group.add(terminal);
  }
  parent.add(group);
}

function reedSwitch(x, z, parent) {
  const group = new THREE.Group();
  group.position.set(x, 8.56, z);
  const capsule = cylinder(0.09, 1.48, materials.glass, 16);
  capsule.rotation.z = Math.PI / 2;
  const leadA = roundedBox(1.1, 0.035, 0.035, materials.steel, 0.01);
  leadA.position.x = -1.27;
  const leadB = leadA.clone();
  leadB.position.x = 1.27;
  const bladeA = roundedBox(0.6, 0.025, 0.025, materials.steel, 0.008);
  bladeA.position.set(-0.23, 0, 0);
  const bladeB = bladeA.clone();
  bladeB.position.x = 0.23;
  group.add(capsule, leadA, leadB, bladeA, bladeB);
  parent.add(tag(group, "reedSwitches"));
}

function pieceProfile(type) {
  const profiles = {
    pawn: [[0, 0], [.78, .08], [.86, .22], [.7, .42], [.48, .72], [.42, 1.05], [.58, 1.3], [.52, 1.52], [.32, 1.68], [.42, 1.95], [0, 2.12]],
    rook: [[0, 0], [.84, .08], [.9, .26], [.68, .42], [.58, 1.55], [.76, 1.68], [.76, 2.08], [.5, 2.08], [.5, 2.28], [0, 2.28]],
    knight: [[0, 0], [.88, .08], [.92, .25], [.66, .48], [.58, 1.15], [.72, 1.7], [.48, 2.25], [.18, 2.58], [0, 2.72]],
    bishop: [[0, 0], [.9, .08], [.96, .25], [.7, .48], [.52, 1.22], [.36, 1.52], [.53, 1.78], [.32, 2.18], [.42, 2.55], [0, 2.78]],
    queen: [[0, 0], [.96, .08], [1, .26], [.72, .5], [.58, 1.38], [.72, 1.7], [.5, 2.02], [.76, 2.42], [.5, 2.68], [.26, 2.86], [0, 3]],
    king: [[0, 0], [1, .08], [1.04, .28], [.74, .52], [.6, 1.48], [.78, 1.82], [.52, 2.14], [.6, 2.58], [.25, 2.83], [.2, 3.18], [0, 3.34]],
  };
  return profiles[type].map(([x, y]) => new THREE.Vector2(x, y));
}

function chessPiece(type, dark, x, z, parent) {
  const group = new THREE.Group();
  group.position.set(x, 9.68, z);
  const body = new THREE.Mesh(new THREE.LatheGeometry(pieceProfile(type), 36), dark ? materials.blackPiece : materials.whitePiece);
  body.castShadow = true;
  body.receiveShadow = true;
  body.userData.partId = "pieces";
  if (!registry.has("pieces")) registry.set("pieces", []);
  registry.get("pieces").push(body);
  rayTargets.push(body);
  group.add(body);
  const magnet = cylinder(0.25, 0.16, materials.steel, 24);
  magnet.position.y = 0.05;
  group.add(tag(magnet, "pieceMagnets"));
  parent.add(group);
}

function createPieces(parent) {
  const order = ["rook", "knight", "bishop", "queen", "king", "bishop", "knight", "rook"];
  for (let file = 0; file < 8; file += 1) {
    const x = (file - 3.5) * 3.75;
    chessPiece(order[file], false, x, -13.125, parent);
    chessPiece("pawn", false, x, -9.375, parent);
    chessPiece("pawn", true, x, 9.375, parent);
    chessPiece(order[file], true, x, 13.125, parent);
  }
}

function createMechanics(parent) {
  const rails = [
    [0, 0, -18.5, true], [0, 0, 18.5, true], [-18.5, 0, 0, false], [18.5, 0, 0, false],
  ];
  for (const [x, y, z, alongX] of rails) {
    const r = rail(39, alongX);
    r.position.set(x, y, z);
    parent.add(r);
  }
  for (const x of [-18.5, 18.5]) for (const z of [-18.5, 18.5]) pulley(x, 1.55, z, parent);
  const belt = tube([
    new THREE.Vector3(-18.5, 1.62, -18.5), new THREE.Vector3(18.5, 1.62, -18.5),
    new THREE.Vector3(18.5, 1.62, 18.5), new THREE.Vector3(-18.5, 1.62, 18.5),
  ], 0.115, materials.belt, true);
  parent.add(tag(belt, "motion"));

  stepperMotor(-21.3, 0.25, -16.2, 0, parent);
  stepperMotor(21.3, 0.25, 16.2, Math.PI, parent);

  const gantry = rail(34, true);
  gantry.position.set(0, 3.4, 2.1);
  parent.add(gantry);
  const trolley = roundedBox(5.3, 0.72, 4.7, materials.black, 0.28);
  trolley.position.set(3.4, 4.72, 2.1);
  parent.add(tag(trolley, "motion"));
  for (const x of [1.45, 5.35]) for (const z of [0.55, 3.65]) pulley(x, 4.78, z, parent);

  const magnetGroup = new THREE.Group();
  magnetGroup.position.set(3.4, 6.2, 2.1);
  const coil = cylinder(1.25, 2.0, materials.black, 40);
  const face = cylinder(1.14, 0.08, materials.steel, 40);
  face.position.y = 1.03;
  magnetGroup.add(coil, face);
  parent.add(tag(magnetGroup, "magnet"));

  microswitch(-16.7, 2.4, -16.0, Math.PI / 2, parent);
  microswitch(16.7, 2.4, 16.0, -Math.PI / 2, parent);
}

function createDeck(parent) {
  const base = roundedBox(32.3, 0.65, 32.3, materials.black, 0.45);
  base.position.y = 8.82;
  parent.add(tag(base, "deck"));
  xrayMeshes.push(base);
  for (let rank = 0; rank < 8; rank += 1) {
    for (let file = 0; file < 8; file += 1) {
      const tile = roundedBox(3.68, 0.42, 3.68, (file + rank) % 2 ? materials.ceramicDark : materials.ceramicLight, 0.13);
      tile.position.set((file - 3.5) * 3.75, 9.34, (rank - 3.5) * 3.75);
      parent.add(tag(tile, "deck"));
      xrayMeshes.push(tile);
    }
  }
  const borderMaterial = new THREE.MeshPhysicalMaterial({ color: 0xa72128, roughness: 0.25, clearcoat: 0.65 });
  for (const [x, z, w, d] of [[0, -16, 32.6, 1.7], [0, 16, 32.6, 1.7], [-16, 0, 1.7, 32.6], [16, 0, 1.7, 32.6]]) {
    const border = roundedBox(w, 0.75, d, borderMaterial, 0.32);
    border.position.set(x, 9.05, z);
    parent.add(tag(border, "deck"));
    xrayMeshes.push(border);
  }
}

function createSensors(parent) {
  for (let rank = 0; rank < 8; rank += 1) {
    for (let file = 0; file < 8; file += 1) reedSwitch((file - 3.5) * 3.75, (rank - 3.5) * 3.75, parent);
  }
  for (let file = 0; file < 8; file += 1) {
    const ribbon = tube([
      new THREE.Vector3((file - 3.5) * 3.75, 8.3, 13.1),
      new THREE.Vector3((file - 3.5) * 3.75, 7.8, 16.2),
      new THREE.Vector3((file - 3.5) * 3.75 * 0.82, 4.5, 19.2),
    ], 0.055, materials.wireBlue);
    parent.add(tag(ribbon, "reedSwitches"));
  }
}

function createElectronics(parent) {
  const tray = roundedBox(35, 0.42, 7.1, materials.pcbGreen, 0.28);
  tray.position.set(0, 2.1, 22.1);
  parent.add(tag(tray, "pcb"));
  for (const x of [-16.2, 16.2]) for (const z of [19.4, 24.8]) screw(x, 2.36, z, parent, 0.75);
  nano(parent, 0, 2.55, 22.1);
  for (const x of [-12, -4, 4, 12]) multiplexer(parent, x, 2.55, 23.7);
  driver(parent, -11.9, 2.75, 20.2);
  driver(parent, 11.9, 2.75, 20.2);
  bluetooth(parent, 4.0, 2.7, 19.8);
  lcd(parent, -10.5, 2.1, 28.2);

  const transistor = new THREE.Group();
  transistor.position.set(8.0, 2.85, 22.0);
  const body = roundedBox(1.0, 1.45, 0.34, materials.black, 0.09);
  body.rotation.x = -0.05;
  transistor.add(body);
  for (const x of [-0.32, 0, 0.32]) {
    const leg = roundedBox(0.06, 0.72, 0.06, materials.steel, 0.015);
    leg.position.set(x, -0.95, 0);
    transistor.add(leg);
  }
  parent.add(tag(transistor, "magnetDriver"));

  const harnesses = [
    [materials.wireRed, [new THREE.Vector3(8, 2.2, 22), new THREE.Vector3(7, 1.2, 14), new THREE.Vector3(3.4, 5.2, 2.1)]],
    [materials.wireYellow, [new THREE.Vector3(-11.9, 2.4, 20.2), new THREE.Vector3(-15, 1.8, 14), new THREE.Vector3(-21.3, 1.1, -16.2)]],
    [materials.wireBlue, [new THREE.Vector3(11.9, 2.4, 20.2), new THREE.Vector3(17, 1.8, 13), new THREE.Vector3(21.3, 1.1, 16.2)]],
  ];
  for (const [material, points] of harnesses) parent.add(tag(tube(points, 0.095, material), "pcb"));
}

function createPower(parent) {
  const brick = new THREE.Group();
  brick.position.set(25.4, 0.1, 10.4);
  const shell = roundedBox(8.5, 3.6, 4.8, materials.black, 0.38);
  for (let x = -3.2; x <= 3.2; x += 0.8) {
    const rib = roundedBox(0.16, 3.64, 4.3, materials.rubber, 0.04);
    rib.position.x = x;
    brick.add(rib);
  }
  brick.add(shell);
  parent.add(tag(brick, "powerSupply"));

  const panel = roundedBox(12.5, 0.42, 5.8, materials.aluminum, 0.3);
  panel.position.set(21.2, 1.9, 22.7);
  parent.add(tag(panel, "frame"));
  powerModule(parent, 18.0, 2.5, 23.8, 2.7, 1.8, "reverseProtection");
  powerModule(parent, 21.2, 2.5, 23.8, 2.4, 1.8, "logicPower");

  const fuse = new THREE.Group();
  fuse.position.set(24.2, 2.8, 23.8);
  const holder = roundedBox(2.5, 0.8, 1.5, materials.black, 0.24);
  const cover = roundedBox(1.3, 0.35, 0.9, new THREE.MeshPhysicalMaterial({ color: 0xe8b143, transparent: true, opacity: 0.72, roughness: 0.2 }), 0.16);
  cover.position.y = 0.48;
  fuse.add(holder, cover);
  parent.add(tag(fuse, "fuse"));

  const cutoff = new THREE.Group();
  cutoff.position.set(22.9, 4.05, 20.2);
  const yellow = roundedBox(3.7, 0.42, 3.7, new THREE.MeshPhysicalMaterial({ color: 0xf0c938, roughness: 0.32 }), 0.35);
  const neck = cylinder(0.7, 0.95, materials.black, 32);
  neck.position.y = 0.65;
  const mushroomStem = cylinder(0.95, 0.75, materials.red, 36);
  mushroomStem.position.y = 1.35;
  const mushroom = new THREE.Mesh(new THREE.CylinderGeometry(1.6, 1.25, 0.65, 40), materials.red);
  mushroom.position.y = 1.85;
  mushroom.castShadow = true;
  cutoff.add(yellow, neck, mushroomStem, mushroom);
  parent.add(tag(cutoff, "cutoff"));

  const powerCable = tube([
    new THREE.Vector3(25.4, 0.2, 12.8), new THREE.Vector3(26.5, 0.5, 17),
    new THREE.Vector3(24.2, 2.5, 23.8), new THREE.Vector3(18, 2.3, 23.8),
  ], 0.16, materials.wireRed);
  parent.add(tag(powerCable, "powerSupply"));
}

export function createBoardModel(scene) {
  const root = new THREE.Group();
  root.name = "automatic-chessboard";
  scene.add(root);

  const mechanics = makeSection("mechanics", new THREE.Vector3(0, -2.2, 0));
  const sensors = makeSection("sensors", new THREE.Vector3(0, 3.8, 0));
  const deck = makeSection("deck", new THREE.Vector3(0, 8.0, 0));
  const pieces = makeSection("pieces", new THREE.Vector3(0, 14.0, 0));
  const electronics = makeSection("electronics", new THREE.Vector3(0, 0.8, 10.0));
  const power = makeSection("power", new THREE.Vector3(8.5, 0, 5.5));
  root.add(mechanics, sensors, deck, pieces, electronics, power);

  createMechanics(mechanics);
  createSensors(sensors);
  createDeck(deck);
  createPieces(pieces);
  createElectronics(electronics);
  createPower(power);

  const undertray = roundedBox(43, 0.42, 43, new THREE.MeshPhysicalMaterial({ color: 0x080b0f, metalness: 0.35, roughness: 0.48 }), 1.0);
  undertray.position.y = -1.25;
  undertray.receiveShadow = true;
  root.add(tag(undertray, "frame"));

  return root;
}

export function animateModel(explodeFactor) {
  for (const section of sections) {
    const home = section.userData.home;
    const target = home.clone().addScaledVector(section.userData.explodeOffset, explodeFactor);
    section.position.lerp(target, 0.095);
  }
}

export function setXray(enabled) {
  for (const mesh of xrayMeshes) {
    if (!mesh.userData.originalMaterial) mesh.userData.originalMaterial = mesh.material;
    if (enabled) {
      const material = mesh.userData.originalMaterial.clone();
      material.transparent = true;
      material.opacity = 0.14;
      material.depthWrite = false;
      material.roughness = 0.1;
      mesh.material = material;
      mesh.userData.noPick = true;
    } else if (mesh.material !== mesh.userData.originalMaterial) {
      mesh.material.dispose();
      mesh.material = mesh.userData.originalMaterial;
      mesh.userData.noPick = false;
    }
  }
}

export function setPartVisible(partId, visible) {
  for (const object of registry.get(partId) ?? []) object.visible = visible;
}

export function getPartObjects(partId) {
  return (registry.get(partId) ?? []).filter((object) => object.visible);
}

export function getRayTargets() {
  return rayTargets.filter((object) => object.visible && !object.userData.noPick);
}

export function getPartCenter(partId, target = new THREE.Vector3()) {
  const box = new THREE.Box3();
  for (const object of getPartObjects(partId)) box.expandByObject(object);
  if (box.isEmpty()) return null;
  return box.getCenter(target);
}
