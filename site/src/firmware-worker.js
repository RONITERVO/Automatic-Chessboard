import { AvrFirmwareRuntime } from "./firmware-core.js";

let hexText = "";
let manifest = null;
let initialized = false;

let runtime = null;
let retainedEeprom = null;
let powered = false;
let paused = false;
let speed = 1;
let bootNumber = 0;
let nextFrameCycle = 0;
let lastWallTime = performance.now();
let cycleDebt = 0;

function postFrame(force = false) {
  if (!runtime) {
    self.postMessage({ type: "frame", bootNumber, state: { power: false }, events: [] });
    return;
  }
  if (!force && !runtime.changed && runtime.cpu.cycles < nextFrameCycle) return;
  self.postMessage({
    type: "frame",
    bootNumber,
    state: { power: powered, paused, speed, ...runtime.state() },
    events: runtime.takeEvents(),
  });
  nextFrameCycle = runtime.cpu.cycles + manifest.clockHz / 8;
}

function powerOn() {
  if (powered) return;
  runtime = new AvrFirmwareRuntime(hexText, manifest, { eeprom: retainedEeprom });
  powered = true;
  paused = false;
  bootNumber++;
  cycleDebt = 0;
  lastWallTime = performance.now();
  postFrame(true);
}

function powerOff() {
  if (!powered) return;
  retainedEeprom = runtime.eepromBackend.memory.slice();
  runtime.record("power", "Board power removed");
  powered = false;
  paused = true;
  postFrame(true);
}

function pump() {
  const now = performance.now();
  const elapsed = Math.min(100, now - lastWallTime);
  lastWallTime = now;
  if (powered && !paused) {
    cycleDebt += elapsed / 1000 * manifest.clockHz * speed;
    const cycleBudget = Math.min(cycleDebt, manifest.clockHz / 5);
    if (cycleBudget >= 1) {
      runtime.runCycles(cycleBudget);
      cycleDebt -= cycleBudget;
    }
    postFrame();
  }
  setTimeout(pump, 12);
}

function handleMessage(event) {
  const message = event.data;
  if (message.type === "power") {
    if (message.enabled) powerOn();
    else powerOff();
  } else if (message.type === "pause") {
    paused = Boolean(message.paused);
    cycleDebt = 0;
    lastWallTime = performance.now();
    postFrame(true);
  } else if (message.type === "speed") {
    speed = [0.5, 1, 4, 16].includes(message.speed) ? message.speed : 1;
    if (runtime) runtime.record("clock", `Playback speed ${speed}×`);
    postFrame(true);
  } else if (powered && message.type === "button") {
    runtime.pressButton(message.button);
    postFrame(true);
  } else if (powered && message.type === "place-start") {
    runtime.placeStartingPieces(message.pieces);
    postFrame(true);
  } else if (powered && message.type === "human-move") {
    const accepted = runtime.humanMove(message.from, message.to, {
      captureSquare: message.captureSquare,
      rook: message.rook,
      promotionPiece: message.promotionPiece,
    });
    self.postMessage({ type: "move-result", accepted, from: message.from, to: message.to });
    postFrame(true);
  } else if (powered && message.type === "manual-move") {
    runtime.humanMove(message.from, message.to, { captureSquare: message.captureSquare });
    postFrame(true);
  } else if (powered && message.type === "bluetooth-rx") {
    runtime.board.setBluetoothConnected(Boolean(message.connected));
    if (message.connected && Array.isArray(message.bytes)) runtime.board.receiveBluetoothBytes(message.bytes);
    postFrame(true);
  } else if (message.type === "snapshot") {
    postFrame(true);
  }
}

async function initialize(firmwareBase) {
  if (initialized) return;
  initialized = true;
  const baseUrl = new URL(firmwareBase, self.location.href);
  const [hexResponse, manifestResponse] = await Promise.all([
    fetch(new URL("automatic-chessboard-nano.hex", baseUrl)),
    fetch(new URL("automatic-chessboard-nano.json", baseUrl)),
  ]);
  if (!hexResponse.ok || !manifestResponse.ok) {
    throw new Error(`Production Nano firmware assets could not be loaded (HEX ${hexResponse.status}, manifest ${manifestResponse.status})`);
  }
  [hexText, manifest] = await Promise.all([hexResponse.text(), manifestResponse.json()]);
  self.postMessage({ type: "ready", manifest });
  pump();
}

self.addEventListener("message", (event) => {
  if (event.data.type === "initialize") {
    initialize(event.data.firmwareBase).catch((error) => {
      self.postMessage({ type: "fatal", message: error instanceof Error ? error.message : String(error) });
    });
    return;
  }
  if (manifest) handleMessage(event);
});
