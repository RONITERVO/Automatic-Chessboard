import * as THREE from "three";
import connectionsCsv from "../../hardware/connections.csv?raw";
import sensorMapCsv from "../../hardware/sensor-map.csv?raw";
import { WIRING_STEPS } from "./wiring-data.js";

const parseCsv = (source) => {
  const [header, ...lines] = source.trim().split(/\r?\n/).map((line) => line.split(","));
  return lines.map((values) => Object.fromEntries(header.map((key, index) => [key, values[index] ?? ""])));
};

const connections = parseCsv(connectionsCsv);
const connectionById = new Map(connections.map((connection) => [connection.id, connection]));
const sensors = parseCsv(sensorMapCsv).map((sensor) => ({ ...sensor, mux: Number(sensor.mux), channel: Number(sensor.channel) }));

const vector = (x, y, z) => new THREE.Vector3(x, y, z);
const anchors = Object.freeze({
  "24V supply": vector(25.4, 2.35, 12.8),
  "F1 3A time-delay": vector(24.2, 3.42, 23.8),
  "Latching cutoff": vector(22.9, 4.4, 20.2),
  "Reverse-polarity protection": vector(18.0, 3.15, 23.8),
  "24V distribution": vector(15.1, 3.15, 23.8),
  "Ground distribution": vector(15.1, 2.95, 20.8),
  "Logic fuse": vector(19.7, 3.15, 21.0),
  "5V buck": vector(21.2, 3.15, 23.8),
  "5V distribution": vector(15.6, 3.15, 22.2),
  Nano: vector(0, 3.08, 22.1),
  "Driver 1": vector(-11.9, 3.38, 20.2),
  "Driver 2": vector(11.9, 3.38, 20.2),
  "Motor 1": vector(-21.3, 2.25, -16.2),
  "Motor 2": vector(21.3, 2.25, 16.2),
  "C1 100uF 50V": vector(-13.4, 3.72, 20.2),
  "C2 100uF 50V": vector(10.4, 3.72, 20.2),
  "H2520 electromagnet": vector(3.4, 7.4, 2.1),
  TIP120: vector(8.0, 3.55, 22.0),
  "R1 1k": vector(6.8, 3.2, 22.0),
  "R2 10k": vector(8.7, 3.2, 22.6),
  "Flyback diode": vector(5.4, 6.55, 2.1),
  LCD: vector(-10.5, 3.1, 28.2),
  "Switch A": vector(-16.7, 3.0, -16.0),
  "Switch B": vector(16.7, 3.0, 16.0),
  "R3 10k": vector(1.7, 3.25, 22.8),
  "HC-08": vector(4.0, 3.25, 19.8),
  "HC-08 carrier": vector(4.0, 3.25, 19.8),
  "R4 1k": vector(2.4, 3.2, 20.0),
  "R5 1k": vector(3.3, 3.2, 18.9),
  "R6 2k": vector(4.7, 3.2, 18.9),
});

const muxAnchors = [-12, -4, 4, 12].map((x) => vector(x, 3.2, 23.7));

const netColors = Object.freeze({
  GND: 0xb7c0ca,
  PROTECTED_24V: 0xff445d,
  RAW_24V: 0xff674d,
  FUSED_24V: 0xff8b4f,
  SWITCHED_24V: 0xff5f80,
  BUCK_IN_24V: 0xff6a55,
  VCC_5V: 0x35d8ff,
  I2C_SDA: 0xb98cff,
  I2C_SCL: 0x8f70ff,
  MUX_SIGNAL: 0x5bf1cb,
  MUX_S0: 0x5ce1b8,
  MUX_S1: 0x45cfa6,
  MUX_S2: 0x34b994,
  MUX_S3: 0x25a37f,
  MAGNET_LOW_SIDE: 0xffb84d,
  MAGNET_COMMAND: 0xffd166,
  MAGNET_BASE: 0xffcb5b,
  BLUETOOTH_RX: 0xab82ff,
  BLE_RX_3V3: 0xbe8dff,
  BLE_TX_RAW: 0x9d70ff,
  SERIAL_TX: 0xd1a8ff,
  MOTOR1_DIR: 0xfbbf55,
  MOTOR1_STEP: 0xff9d42,
  MOTOR1_COIL_A: 0xf5c15d,
  MOTOR1_COIL_B: 0xe98d46,
  MOTOR2_DIR: 0x57a8ff,
  MOTOR2_STEP: 0x398cff,
  MOTOR2_COIL_A: 0x69b9ff,
  MOTOR2_COIL_B: 0x3d91df,
  SWITCH_A: 0xf6e58d,
  SWITCH_B: 0xf6d365,
});

function hashPin(value) {
  let hash = 0;
  for (const character of value) hash = (hash * 31 + character.charCodeAt(0)) >>> 0;
  return hash;
}

function componentAnchors(name, pin) {
  if (name === "MUX0..MUX3") return muxAnchors.map((point, index) => terminalPoint(point, `${pin}${index}`, 1.5, 0.75));
  const muxMatch = /^MUX([0-3])$/.exec(name);
  if (muxMatch) return [terminalPoint(muxAnchors[Number(muxMatch[1])], pin, 1.7, 0.75)];
  const anchor = anchors[name];
  if (!anchor) throw new Error(`Missing wiring anchor for ${name}`);
  return [terminalPoint(anchor, pin, name === "Nano" ? 1.8 : 0.9, name === "Nano" ? 0.72 : 0.55)];
}

function terminalPoint(anchor, pin, width, depth) {
  const hash = hashPin(pin);
  const side = hash & 1 ? 1 : -1;
  const along = ((hash >>> 1) % 11) / 10 - 0.5;
  return anchor.clone().add(vector(along * width, 0, side * depth));
}

function routeCurve(source, destination, lane = 0) {
  const distance = source.distanceTo(destination);
  const lift = Math.max(source.y, destination.y) + THREE.MathUtils.clamp(distance * 0.09, 0.8, 3.8) + (lane % 4) * 0.14;
  const midpoint = source.clone().lerp(destination, 0.5);
  midpoint.y = lift;
  return new THREE.CatmullRomCurve3([
    source,
    vector(source.x, lift, source.z),
    midpoint,
    vector(destination.x, lift, destination.z),
    destination,
  ], false, "centripetal", 0.36);
}

function squarePoint(square, leadOffset = 0) {
  const file = square.charCodeAt(0) - 97;
  const rank = Number(square.slice(1)) - 1;
  return vector((file - 3.5) * 3.75 + leadOffset, 8.3, (rank - 3.5) * 3.75);
}

function createWireFromCurve(group, curve, color, stepIndex, lane, radius = 0.052) {
  const material = new THREE.MeshStandardMaterial({
    color,
    emissive: color,
    emissiveIntensity: 0.5,
    transparent: true,
    opacity: 0,
    depthWrite: false,
    roughness: 0.44,
  });
  const mesh = new THREE.Mesh(new THREE.TubeGeometry(curve, 22, radius, 5, false), material);
  mesh.visible = false;
  mesh.renderOrder = 4;
  group.add(mesh);

  const pulseMaterial = new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0 });
  const pulse = new THREE.Mesh(new THREE.SphereGeometry(radius * 2.7, 10, 8), pulseMaterial);
  pulse.visible = false;
  pulse.renderOrder = 5;
  group.add(pulse);
  return { curve, material, mesh, pulse, pulseMaterial, stepIndex, lane, phase: (lane * 0.173) % 1 };
}

function createWire(group, source, destination, color, stepIndex, lane, radius = 0.052) {
  return createWireFromCurve(group, routeCurve(source, destination, lane), color, stepIndex, lane, radius);
}

function buildConnectionWires(group, records) {
  WIRING_STEPS.forEach((step, stepIndex) => {
    step.connectionIds.forEach((id, lane) => {
      const connection = connectionById.get(id);
      if (!connection) throw new Error(`Unknown connection ${id}`);
      const sources = componentAnchors(connection.source, connection.source_pin);
      const destinations = componentAnchors(connection.destination, connection.destination_pin);
      const count = Math.max(sources.length, destinations.length);
      for (let index = 0; index < count; index += 1) {
        const source = sources[Math.min(index, sources.length - 1)];
        const destination = destinations[Math.min(index, destinations.length - 1)];
        const color = netColors[connection.net] ?? (connection.net.includes("GND") ? netColors.GND : 0x79d8ff);
        records.push(createWire(group, source, destination, color, stepIndex, lane + index * 0.7));
      }
    });
  });
}

function buildSensorWires(group, records) {
  for (const sensor of sensors) {
    const stepIndex = WIRING_STEPS.findIndex((step) => step.sensorMux === sensor.mux);
    const reed = squarePoint(sensor.logical_square, -0.52);
    const mux = terminalPoint(muxAnchors[sensor.mux], `C${sensor.channel}`, 2.0, 0.8);
    const signalCurve = new THREE.CatmullRomCurve3([
      reed,
      vector(reed.x, 7.55, reed.z),
      vector(reed.x, 7.3, 17.0 + sensor.mux * 0.2),
      vector(mux.x, 4.2, 20.7 + sensor.mux * 0.18),
      mux,
    ], false, "centripetal", 0.38);
    records.push(createWireFromCurve(group, signalCurve, 0x5bf1cb, stepIndex, sensor.channel, 0.042));

    const groundLead = squarePoint(sensor.logical_square, 0.52);
    const rowBus = vector(groundLead.x, 7.45, 17.2 + sensor.mux * 0.18);
    const groundCurve = new THREE.CatmullRomCurve3([
      groundLead,
      vector(groundLead.x, 7.45, groundLead.z),
      rowBus,
    ], false, "centripetal", 0.38);
    records.push(createWireFromCurve(group, groundCurve, netColors.GND, stepIndex, sensor.channel + 20, 0.032));
  }

  for (let mux = 0; mux < 4; mux += 1) {
    const stepIndex = WIRING_STEPS.findIndex((step) => step.sensorMux === mux);
    const rowZ = 17.2 + mux * 0.18;
    const busCurve = new THREE.CatmullRomCurve3([
      vector(-14.0, 7.45, rowZ),
      vector(0, 7.45, rowZ),
      vector(14.0, 7.45, rowZ),
    ], false, "centripetal", 0.25);
    records.push(createWireFromCurve(group, busCurve, netColors.GND, stepIndex, 40 + mux, 0.055));
    const ground = componentAnchors("Ground distribution", "GND")[0];
    const trunkCurve = new THREE.CatmullRomCurve3([
      vector(14.0, 7.45, rowZ),
      vector(14.0, 5.6, 18.4),
      vector(14.5, 3.8, 20.0),
      ground,
    ], false, "centripetal", 0.36);
    records.push(createWireFromCurve(group, trunkCurve, netColors.GND, stepIndex, 48 + mux, 0.06));
  }
}

export function createWiringGuide(scene) {
  const group = new THREE.Group();
  group.name = "guided-wiring";
  group.visible = false;
  scene.add(group);
  const records = [];
  buildConnectionWires(group, records);
  buildSensorWires(group, records);

  let activeStep = 0;
  let enabled = false;

  return {
    steps: WIRING_STEPS,
    getCodes(index) {
      const step = WIRING_STEPS[index];
      if (step.sensorMux === undefined) return step.codes;
      return sensors
        .filter((sensor) => sensor.mux === step.sensorMux)
        .sort((a, b) => a.channel - b.channel)
        .map((sensor) => `C${sensor.channel}→${sensor.logical_square}`);
    },
    getBounds(index) {
      const box = new THREE.Box3();
      for (const record of records) {
        if (record.stepIndex !== index) continue;
        for (const fraction of [0, 0.25, 0.5, 0.75, 1]) box.expandByPoint(record.curve.getPoint(fraction));
      }
      return box;
    },
    getActiveSensorChannel(time) {
      return WIRING_STEPS[activeStep].sensorMux === undefined ? -1 : Math.floor(time * 0.72) % 16;
    },
    setEnabled(value) {
      enabled = value;
      group.visible = value;
      this.showStep(activeStep);
    },
    showStep(index) {
      activeStep = index;
      const showAll = WIRING_STEPS[index].showAll;
      for (const record of records) {
        const active = enabled && record.stepIndex === index;
        const completed = enabled && (showAll || record.stepIndex < index);
        const sensorRecord = WIRING_STEPS[record.stepIndex].sensorMux !== undefined;
        record.mesh.visible = active || (completed && !sensorRecord);
        record.material.opacity = active ? (sensorRecord ? 0.08 : 0.94) : completed ? (showAll ? (sensorRecord ? 0 : 0.22) : (sensorRecord ? 0 : 0.09)) : 0;
        record.material.emissiveIntensity = active ? 1.25 : 0.22;
        record.pulse.visible = active && !sensorRecord;
        record.pulseMaterial.opacity = active ? 0.95 : 0;
      }
    },
    update(time) {
      if (!enabled) return;
      const activeSensorMux = WIRING_STEPS[activeStep].sensorMux;
      const activeSensorChannel = activeSensorMux === undefined ? -1 : Math.floor(time * 0.72) % 16;
      for (const record of records) {
        if (activeSensorMux !== undefined && record.stepIndex === activeStep) {
          const bus = record.lane >= 40;
          const channel = record.lane >= 20 && record.lane < 36 ? record.lane - 20 : record.lane;
          const highlighted = bus || channel === activeSensorChannel;
          record.material.opacity = highlighted ? (bus ? 0.48 : 0.98) : 0.055;
          record.material.emissiveIntensity = highlighted ? 1.3 : 0.12;
          record.pulse.visible = highlighted && !bus;
          record.pulseMaterial.opacity = highlighted ? 0.98 : 0;
        }
        if (!record.pulse.visible) continue;
        const position = record.curve.getPoint((time * 0.15 + record.phase) % 1);
        record.pulse.position.copy(position);
      }
    },
  };
}

export { connections, sensors };
