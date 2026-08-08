import { readFile } from "node:fs/promises";
import * as THREE from "three";
import { PARTS, PURCHASABLE_PART_IDS } from "./src/catalog.js";
import { COREXY_LAYOUT, createBoardModel } from "./src/model.js";
import { WIRING_STEPS } from "./src/wiring-data.js";

const [html, model, app, wiring, connectionsCsv, sensorMapCsv] = await Promise.all([
  readFile(new URL("./index.html", import.meta.url), "utf8"),
  readFile(new URL("./src/model.js", import.meta.url), "utf8"),
  readFile(new URL("./src/app.js", import.meta.url), "utf8"),
  readFile(new URL("./src/wiring.js", import.meta.url), "utf8"),
  readFile(new URL("../hardware/connections.csv", import.meta.url), "utf8"),
  readFile(new URL("../hardware/sensor-map.csv", import.meta.url), "utf8"),
]);

const failures = [];
const ids = Object.keys(PARTS);

if (new Set(ids).size !== ids.length) failures.push("Component identifiers must be unique.");
if (PURCHASABLE_PART_IDS.length !== ids.length) failures.push("Every modeled component group must be purchasable.");

for (const [id, part] of Object.entries(PARTS)) {
  if (!part.name || !part.color || !part.url) failures.push(`${id} has incomplete catalog data.`);
  if (!/^https:\/\//.test(part.url)) failures.push(`${id} does not use an HTTPS recommendation URL.`);
  if (!model.includes(`"${id}"`)) failures.push(`${id} is not represented in the model.`);
}

const body = html.match(/<body[\s\S]*?<\/body>/i)?.[0] ?? "";
const visibleText = body
  .replace(/<svg[\s\S]*?<\/svg>/gi, "")
  .replace(/<script[\s\S]*?<\/script>/gi, "")
  .replace(/<[^>]+>/g, "")
  .replace(/\s+/g, "");
if (visibleText) failures.push("The page body contains visible text.");

for (const selector of ["#scene", "#toolbar", "#progress", "#part-actions", "#wiring-guide"]) {
  if (!html.includes(selector.slice(1))) failures.push(`Missing interface element ${selector}.`);
}

for (const capability of ["OrbitControls", "OutlinePass", "setXray", "state.purchased", "localStorage", "dblclick", "createWiringGuide"]) {
  if (!app.includes(capability)) failures.push(`Missing interaction capability ${capability}.`);
}

if (!app.includes("controls.autoRotate = false")) failures.push("The 3D camera must remain under manual user control.");
for (const automaticCameraBehavior of ["autoRotateSpeed", "focusPart(", "camera.position.lerp", "controls.target.lerp"]) {
  if (app.includes(automaticCameraBehavior)) failures.push(`Automatic camera behavior is not allowed: ${automaticCameraBehavior}.`);
}
if (html.includes("toggle-rotate")) failures.push("The interface must not offer automatic camera rotation.");

for (const mechanicalDetail of ["395 mm gantry", "2.2 mm tiles"]) {
  if (!model.includes(mechanicalDetail)) failures.push(`Missing realistic mechanics detail ${mechanicalDetail}.`);
}

const mechanicalScene = new THREE.Scene();
const mechanicalRoot = createBoardModel(mechanicalScene);
const corexyRoutes = [];
const corexyMotors = [];
const corexyEndstops = [];
mechanicalRoot.traverse((object) => {
  if (object.name?.startsWith("corexy-belt-")) corexyRoutes.push(object);
  if (object.name?.startsWith("corexy-motor-")) corexyMotors.push(object);
  if (object.name?.startsWith("corexy-endstop-")) corexyEndstops.push(object);
});
if (corexyRoutes.length !== 2 || corexyRoutes.some((route) => route.children.length < 30)) {
  failures.push("The model must contain two complete CoreXY routes with sampled pulley wraps.");
}
if (COREXY_LAYOUT.beltPlanes.length !== 2 || COREXY_LAYOUT.beltPlanes[0] === COREXY_LAYOUT.beltPlanes[1]) {
  failures.push("The CoreXY belt routes must use two distinct height planes.");
}
if (Math.abs(corexyRoutes[0]?.userData.routeLength - corexyRoutes[1]?.userData.routeLength) > 0.01) {
  failures.push("The two CoreXY routes must remain equal in modeled length.");
}
if (corexyMotors.length !== 2 || Math.abs(corexyMotors[0].position.z - corexyMotors[1].position.z) > 0.001) {
  failures.push("Both CoreXY motors must remain paired along the same front edge.");
}
if (corexyMotors.some((motor) => motor.position.z <= COREXY_LAYOUT.front)) {
  failures.push("The CoreXY motors must remain outside the 315 mm front cross rail.");
}
if (corexyEndstops.length !== 2 || corexyEndstops.some((endstop) => endstop.position.z >= 0)) {
  failures.push("Both sequential homing switches must remain on the back edge.");
}
if (Math.abs(corexyEndstops[0]?.position.z - corexyEndstops[1]?.position.z) > 0.001) {
  failures.push("The gantry and trolley homing switches must share the back mounting line.");
}

const connectionIds = connectionsCsv.trim().split(/\r?\n/).slice(1).map((line) => line.split(",", 1)[0]);
const guidedIds = WIRING_STEPS.flatMap((step) => step.connectionIds);
const guidedCounts = new Map(guidedIds.map((id) => [id, (guidedIds.filter((candidate) => candidate === id).length)]));
for (const id of connectionIds) {
  if (!guidedCounts.has(id)) failures.push(`${id} is missing from the guided wiring sequence.`);
  if (guidedCounts.get(id) > 1) failures.push(`${id} appears more than once in the guided wiring sequence.`);
}
for (const id of guidedIds) if (!connectionIds.includes(id)) failures.push(`${id} is not present in hardware/connections.csv.`);

const sensorRows = sensorMapCsv.trim().split(/\r?\n/).slice(1).map((line) => line.split(","));
const sensorSquares = new Set(sensorRows.map((row) => row[3]));
const sensorChannels = new Set(sensorRows.map((row) => `${row[0]}:${row[2]}`));
if (sensorRows.length !== 64 || sensorSquares.size !== 64 || sensorChannels.size !== 64) {
  failures.push("The guided sensor harness requires 64 unique squares and 64 unique MUX channels.");
}
const sensorSteps = WIRING_STEPS.filter((step) => step.sensorMux !== undefined).map((step) => step.sensorMux).sort();
if (sensorSteps.join(",") !== "0,1,2,3") failures.push("The guided wiring sequence must include MUX sensor stages 0 through 3.");
if (!wiring.includes("../../hardware/connections.csv?raw") || !wiring.includes("../../hardware/sensor-map.csv?raw")) {
  failures.push("The browser wiring guide must import the hardware CSV sources of truth directly.");
}
for (const step of WIRING_STEPS) {
  for (const code of step.codes) if (code.length > 42) failures.push(`${step.key} contains long visible instruction text.`);
}

if (failures.length) {
  console.error(failures.join("\n"));
  process.exitCode = 1;
} else {
  console.log(`${ids.length} component groups, ${connectionIds.length} connections, and ${sensorRows.length} sensors validated.`);
}
