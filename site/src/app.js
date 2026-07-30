import * as THREE from "three";
import { OrbitControls } from "three/addons/controls/OrbitControls.js";
import { EffectComposer } from "three/addons/postprocessing/EffectComposer.js";
import { RenderPass } from "three/addons/postprocessing/RenderPass.js";
import { OutlinePass } from "three/addons/postprocessing/OutlinePass.js";
import { OutputPass } from "three/addons/postprocessing/OutputPass.js";
import { RoomEnvironment } from "three/addons/environments/RoomEnvironment.js";
import { PARTS, PURCHASABLE_PART_IDS } from "./catalog.js";
import { WIRING_ICON_PATHS } from "./wiring-data.js";
import { createWiringGuide } from "./wiring.js";
import {
  animateModel,
  createBoardModel,
  getPartCenter,
  getPartObjects,
  getRayTargets,
  setDecorativeHarnessVisible,
  setPartVisible,
  setXray,
} from "./model.js";

const canvas = document.querySelector("#scene");
const viewport = document.querySelector("#viewport");
const loading = document.querySelector("#loading");
const actions = document.querySelector("#part-actions");
const swatch = document.querySelector("#part-swatch");
const announcer = document.querySelector("#announcer");
const pointerMark = document.querySelector("#pointer-mark");
const progress = document.querySelector("#progress");
const progressValue = progress.querySelector(".value");
const purchasedButton = document.querySelector("#mark-purchased");
const wiringPanel = document.querySelector("#wiring-guide");
const wiringIndex = document.querySelector("#wiring-step-index");
const wiringIconPath = document.querySelector("#wiring-step-icon-path");
const wiringPinCodes = document.querySelector("#wiring-pin-codes");
const wiringTrack = document.querySelector("#wiring-step-track");
const storageKey = "automatic-chessboard-build-v1";
const reducedMotion = matchMedia("(prefers-reduced-motion: reduce)").matches;

const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true, powerPreference: "high-performance" });
renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
renderer.outputColorSpace = THREE.SRGBColorSpace;
renderer.toneMapping = THREE.ACESFilmicToneMapping;
renderer.toneMappingExposure = 1.08;
renderer.shadowMap.enabled = true;
renderer.shadowMap.type = THREE.PCFShadowMap;

const scene = new THREE.Scene();
scene.fog = new THREE.FogExp2(0x07090d, 0.0115);
const environment = new RoomEnvironment();
const pmrem = new THREE.PMREMGenerator(renderer);
scene.environment = pmrem.fromScene(environment, 0.035).texture;
environment.dispose();
pmrem.dispose();

const camera = new THREE.PerspectiveCamera(34, 1, 0.1, 220);
const initialCamera = new THREE.Vector3(51, 40, 62);
const initialTarget = new THREE.Vector3(2, 4.5, 2);
camera.position.copy(initialCamera);

const controls = new OrbitControls(camera, canvas);
controls.target.copy(initialTarget);
controls.enableDamping = true;
controls.dampingFactor = 0.055;
controls.minDistance = 13;
controls.maxDistance = 105;
controls.maxPolarAngle = Math.PI * 0.88;
controls.autoRotate = !reducedMotion;
controls.autoRotateSpeed = 0.48;
controls.zoomToCursor = true;

const hemi = new THREE.HemisphereLight(0xdbeeff, 0x10151d, 2.25);
scene.add(hemi);
const key = new THREE.DirectionalLight(0xe9f2ff, 5.2);
key.position.set(-25, 42, 28);
key.castShadow = true;
key.shadow.mapSize.set(2048, 2048);
key.shadow.camera.left = -45;
key.shadow.camera.right = 45;
key.shadow.camera.top = 45;
key.shadow.camera.bottom = -45;
key.shadow.camera.near = 1;
key.shadow.camera.far = 105;
key.shadow.bias = -0.00015;
scene.add(key);
const rim = new THREE.DirectionalLight(0x56c9ff, 3.0);
rim.position.set(36, 18, -32);
scene.add(rim);
const warm = new THREE.PointLight(0xffb968, 18, 85, 2);
warm.position.set(-30, 7, -24);
scene.add(warm);

const floor = new THREE.Mesh(
  new THREE.CircleGeometry(62, 96),
  new THREE.MeshPhysicalMaterial({ color: 0x090d12, metalness: 0.38, roughness: 0.68, transparent: true, opacity: 0.82 }),
);
floor.rotation.x = -Math.PI / 2;
floor.position.y = -4.1;
floor.receiveShadow = true;
scene.add(floor);

for (const radius of [28, 43, 58]) {
  const ring = new THREE.Mesh(
    new THREE.TorusGeometry(radius, 0.028, 4, 128),
    new THREE.MeshBasicMaterial({ color: 0x2f6580, transparent: true, opacity: 0.22 }),
  );
  ring.rotation.x = Math.PI / 2;
  ring.position.y = -4.04;
  scene.add(ring);
}

createBoardModel(scene);
const wiringGuide = createWiringGuide(scene);

const composer = new EffectComposer(renderer);
composer.addPass(new RenderPass(scene, camera));
const outline = new OutlinePass(new THREE.Vector2(1, 1), scene, camera);
outline.edgeStrength = 5.2;
outline.edgeGlow = 0.6;
outline.edgeThickness = 1.6;
outline.pulsePeriod = 2.4;
outline.visibleEdgeColor.set(0x78dcff);
outline.hiddenEdgeColor.set(0x1e6078);
composer.addPass(outline);
composer.addPass(new OutputPass());

function restoreState() {
  try {
    const stored = JSON.parse(localStorage.getItem(storageKey) || "{}");
    return {
      purchased: new Set((stored.purchased ?? []).filter((id) => PARTS[id])),
      hidden: new Set((stored.hidden ?? []).filter((id) => PARTS[id])),
      wiringStep: Number.isInteger(stored.wiringStep) ? stored.wiringStep : 0,
      wiringUnlocked: Number.isInteger(stored.wiringUnlocked) ? stored.wiringUnlocked : 0,
    };
  } catch {
    return { purchased: new Set(), hidden: new Set(), wiringStep: 0, wiringUnlocked: 0 };
  }
}

const saved = restoreState();
const state = {
  selected: null,
  purchased: saved.purchased,
  hidden: saved.hidden,
  showPurchased: false,
  exploded: false,
  xray: false,
  wiring: false,
  wiringStep: THREE.MathUtils.clamp(saved.wiringStep, 0, wiringGuide.steps.length - 1),
  wiringUnlocked: THREE.MathUtils.clamp(Math.max(saved.wiringUnlocked, saved.wiringStep), 0, wiringGuide.steps.length - 1),
  explodeAmount: 0,
};

function saveState() {
  localStorage.setItem(storageKey, JSON.stringify({
    purchased: [...state.purchased],
    hidden: [...state.hidden],
    wiringStep: state.wiringStep,
    wiringUnlocked: state.wiringUnlocked,
  }));
}

function announce(message) {
  announcer.textContent = "";
  requestAnimationFrame(() => { announcer.textContent = message; });
}

function updateProgress() {
  const count = state.purchased.size;
  const total = PURCHASABLE_PART_IDS.length;
  const circumference = 2 * Math.PI * 20;
  progressValue.style.strokeDasharray = `${circumference}`;
  progressValue.style.strokeDashoffset = `${circumference * (1 - count / total)}`;
  progress.style.setProperty("--progress", `${count / total}`);
  progress.setAttribute("aria-label", `Toggle purchased parts visibility. ${count} of ${total} component groups purchased.`);
  progress.classList.toggle("has-progress", count > 0);
  progress.classList.toggle("is-complete", count === total);
}

function applyVisibility() {
  for (const id of PURCHASABLE_PART_IDS) {
    const visible = !state.hidden.has(id) && (state.showPurchased || !state.purchased.has(id));
    setPartVisible(id, visible);
  }
  if (state.selected && getPartObjects(state.selected).length === 0) clearSelection();
}

let targetGoal = null;
let cameraGoal = null;

function focusPart(id) {
  const center = getPartCenter(id);
  if (!center) return;
  const direction = camera.position.clone().sub(controls.target).normalize();
  const objects = getPartObjects(id);
  const bounds = new THREE.Box3();
  for (const object of objects) bounds.expandByObject(object);
  const size = bounds.getSize(new THREE.Vector3()).length();
  const distance = THREE.MathUtils.clamp(size * 1.75 + 10, 17, 40);
  targetGoal = center;
  cameraGoal = center.clone().addScaledVector(direction, distance);
}

function focusBounds(bounds) {
  if (bounds.isEmpty()) {
    targetGoal = initialTarget.clone();
    cameraGoal = initialCamera.clone();
    return;
  }
  const center = bounds.getCenter(new THREE.Vector3());
  const size = bounds.getSize(new THREE.Vector3());
  const direction = new THREE.Vector3(1, 0.78, 1).normalize();
  const distance = THREE.MathUtils.clamp(size.length() * 1.18 + 8, 25, 96);
  targetGoal = center;
  cameraGoal = center.clone().addScaledVector(direction, distance);
}

function clearSelection() {
  state.selected = null;
  outline.selectedObjects = [];
  actions.hidden = true;
  togglePressed(purchasedButton, false);
}

function selectPart(id, shouldFocus = true) {
  if (!PARTS[id] || getPartObjects(id).length === 0) return;
  state.selected = id;
  outline.selectedObjects = getPartObjects(id);
  outline.visibleEdgeColor.set(PARTS[id].color);
  outline.hiddenEdgeColor.set(PARTS[id].color).multiplyScalar(0.28);
  swatch.style.backgroundColor = PARTS[id].color;
  actions.hidden = false;
  togglePressed(purchasedButton, state.purchased.has(id));
  if (shouldFocus) focusPart(id);
  announce(PARTS[id].name);
}

function togglePressed(button, enabled) {
  button.classList.toggle("is-active", enabled);
  button.setAttribute("aria-pressed", `${enabled}`);
}

function resetView() {
  targetGoal = initialTarget.clone();
  cameraGoal = initialCamera.clone();
  clearSelection();
}

document.querySelector("#reset-view").addEventListener("click", resetView);

const rotateButton = document.querySelector("#toggle-rotate");
togglePressed(rotateButton, controls.autoRotate);
rotateButton.addEventListener("click", () => {
  controls.autoRotate = !controls.autoRotate;
  togglePressed(rotateButton, controls.autoRotate);
});

const explodeButton = document.querySelector("#toggle-explode");
explodeButton.addEventListener("click", () => {
  state.exploded = !state.exploded;
  togglePressed(explodeButton, state.exploded);
});

const xrayButton = document.querySelector("#toggle-xray");
xrayButton.addEventListener("click", () => {
  state.xray = !state.xray;
  setXray(state.xray);
  togglePressed(xrayButton, state.xray);
});

const wiringButton = document.querySelector("#toggle-wiring");
const wiringPrevious = document.querySelector("#wiring-previous");
const wiringNext = document.querySelector("#wiring-next");
const wiringDots = wiringGuide.steps.map((_, index) => {
  const button = document.createElement("button");
  button.type = "button";
  button.setAttribute("aria-label", `Go to wiring step ${index + 1}`);
  button.addEventListener("click", () => setWiringStep(index));
  wiringTrack.append(button);
  return button;
});
let preWiringState = null;

function updateWiringTrack() {
  wiringDots.forEach((dot, index) => {
    dot.classList.toggle("is-current", index === state.wiringStep);
    dot.classList.toggle("is-complete", index < state.wiringUnlocked);
    dot.disabled = index > state.wiringUnlocked;
    dot.setAttribute("aria-current", index === state.wiringStep ? "step" : "false");
  });
}

function focusWiringStep(step) {
  const bounds = wiringGuide.getBounds(state.wiringStep);
  const highlighted = [];
  for (const partId of step.focus) {
    for (const object of getPartObjects(partId)) {
      bounds.expandByObject(object);
      highlighted.push(object);
    }
  }
  outline.selectedObjects = highlighted;
  outline.visibleEdgeColor.set(0x74dcff);
  outline.hiddenEdgeColor.set(0x173d4c);
  focusBounds(bounds);
}

function setWiringStep(index, unlock = false) {
  const nextIndex = THREE.MathUtils.clamp(index, 0, wiringGuide.steps.length - 1);
  if (!unlock && nextIndex > state.wiringUnlocked) return;
  state.wiringStep = nextIndex;
  if (unlock) state.wiringUnlocked = Math.max(state.wiringUnlocked, nextIndex);
  const step = wiringGuide.steps[nextIndex];
  wiringGuide.showStep(nextIndex);
  wiringIndex.textContent = `${String(nextIndex + 1).padStart(2, "0")} / ${String(wiringGuide.steps.length).padStart(2, "0")}`;
  wiringIconPath.setAttribute("d", WIRING_ICON_PATHS[step.icon]);
  wiringPinCodes.replaceChildren(...wiringGuide.getCodes(nextIndex).map((code) => {
    const chip = document.createElement("span");
    chip.textContent = code;
    return chip;
  }));
  wiringPrevious.disabled = nextIndex === 0;
  wiringNext.disabled = nextIndex === wiringGuide.steps.length - 1;
  wiringNext.setAttribute("aria-label", nextIndex === wiringGuide.steps.length - 2 ? "Complete guided wiring" : "Complete step and continue");
  updateWiringTrack();
  focusWiringStep(step);
  saveState();
  announce(`Wiring step ${nextIndex + 1} of ${wiringGuide.steps.length}`);
}

function advanceWiring() {
  if (state.wiringStep >= wiringGuide.steps.length - 1) return;
  setWiringStep(state.wiringStep + 1, true);
}

function retreatWiring() {
  if (state.wiringStep === 0) return;
  setWiringStep(state.wiringStep - 1);
}

function toggleWiring(enabled = !state.wiring) {
  if (enabled === state.wiring) return;
  state.wiring = enabled;
  viewport.classList.toggle("is-wiring", enabled);
  togglePressed(wiringButton, enabled);
  wiringPanel.hidden = !enabled;

  if (enabled) {
    preWiringState = { autoRotate: controls.autoRotate, exploded: state.exploded, xray: state.xray };
    clearSelection();
    for (const id of PURCHASABLE_PART_IDS) setPartVisible(id, true);
    setPartVisible("pieces", false);
    setPartVisible("pieceMagnets", false);
    setDecorativeHarnessVisible(false);
    controls.autoRotate = false;
    state.exploded = false;
    togglePressed(rotateButton, false);
    togglePressed(explodeButton, false);
    if (!state.xray) {
      state.xray = true;
      setXray(true);
    }
    togglePressed(xrayButton, true);
    rotateButton.disabled = true;
    explodeButton.disabled = true;
    xrayButton.disabled = true;
    wiringGuide.setEnabled(true);
    setWiringStep(state.wiringStep);
  } else {
    wiringGuide.setEnabled(false);
    rotateButton.disabled = false;
    explodeButton.disabled = false;
    xrayButton.disabled = false;
    if (state.xray !== preWiringState.xray) {
      state.xray = preWiringState.xray;
      setXray(state.xray);
    }
    state.exploded = preWiringState.exploded;
    controls.autoRotate = preWiringState.autoRotate;
    togglePressed(rotateButton, controls.autoRotate);
    togglePressed(explodeButton, state.exploded);
    togglePressed(xrayButton, state.xray);
    preWiringState = null;
    applyVisibility();
    setDecorativeHarnessVisible(true);
    resetView();
  }
}

wiringButton.addEventListener("click", () => toggleWiring());
wiringPrevious.addEventListener("click", retreatWiring);
wiringNext.addEventListener("click", advanceWiring);
updateWiringTrack();

document.querySelector("#restore-hidden").addEventListener("click", () => {
  state.hidden.clear();
  saveState();
  applyVisibility();
  announce("Hidden components restored");
});

progress.addEventListener("click", () => {
  state.showPurchased = !state.showPurchased;
  togglePressed(progress, state.showPurchased);
  applyVisibility();
});

document.querySelector("#open-part").addEventListener("click", () => {
  if (state.selected) window.open(PARTS[state.selected].url, "_blank", "noopener,noreferrer");
});

purchasedButton.addEventListener("click", () => {
  if (!state.selected) return;
  const id = state.selected;
  if (state.purchased.has(id)) state.purchased.delete(id);
  else state.purchased.add(id);
  saveState();
  updateProgress();
  applyVisibility();
  if (state.showPurchased) selectPart(id, false);
});

document.querySelector("#hide-part").addEventListener("click", () => {
  if (!state.selected) return;
  state.hidden.add(state.selected);
  saveState();
  applyVisibility();
});

document.querySelector("#clear-selection").addEventListener("click", clearSelection);

const raycaster = new THREE.Raycaster();
const pointer = new THREE.Vector2();
const pointerStart = new THREE.Vector2();
let pointerWasDown = false;
let hoveredId = null;

function raycast(event) {
  const rect = canvas.getBoundingClientRect();
  pointer.x = ((event.clientX - rect.left) / rect.width) * 2 - 1;
  pointer.y = -((event.clientY - rect.top) / rect.height) * 2 + 1;
  raycaster.setFromCamera(pointer, camera);
  return raycaster.intersectObjects(getRayTargets(), false)[0]?.object?.userData?.partId ?? null;
}

canvas.addEventListener("pointerdown", (event) => {
  if (state.wiring) return;
  pointerWasDown = true;
  pointerStart.set(event.clientX, event.clientY);
});

canvas.addEventListener("pointerup", (event) => {
  if (state.wiring) return;
  if (!pointerWasDown) return;
  pointerWasDown = false;
  if (pointerStart.distanceTo(new THREE.Vector2(event.clientX, event.clientY)) > 6) return;
  const id = raycast(event);
  if (id) selectPart(id);
  else clearSelection();
});

canvas.addEventListener("dblclick", (event) => {
  if (state.wiring) return;
  const id = raycast(event);
  if (id) window.open(PARTS[id].url, "_blank", "noopener,noreferrer");
});

canvas.addEventListener("pointermove", (event) => {
  if (state.wiring) return;
  if (event.pointerType === "touch") return;
  hoveredId = raycast(event);
  viewport.classList.toggle("has-hover", Boolean(hoveredId));
  pointerMark.classList.toggle("is-visible", Boolean(hoveredId));
  pointerMark.style.left = `${event.clientX}px`;
  pointerMark.style.top = `${event.clientY}px`;
});

canvas.addEventListener("pointerleave", () => {
  hoveredId = null;
  viewport.classList.remove("has-hover");
  pointerMark.classList.remove("is-visible");
});

window.addEventListener("keydown", (event) => {
  if (state.wiring && event.key === "ArrowRight") advanceWiring();
  if (state.wiring && event.key === "ArrowLeft") retreatWiring();
  if (event.key === "Escape" && state.wiring) toggleWiring(false);
  else if (event.key === "Escape") clearSelection();
  if (event.key === "Home") resetView();
  if (!state.wiring && event.key === "Enter" && state.selected) window.open(PARTS[state.selected].url, "_blank", "noopener,noreferrer");
});

function resize() {
  const width = viewport.clientWidth;
  const height = viewport.clientHeight;
  camera.aspect = width / height;
  camera.updateProjectionMatrix();
  renderer.setSize(width, height, false);
  composer.setSize(width, height);
  outline.resolution.set(width, height);
}

const resizeObserver = new ResizeObserver(resize);
resizeObserver.observe(viewport);
resize();
updateProgress();
applyVisibility();

let previousTime = performance.now();
let firstFrame = true;
let liveSensorChannel = -2;

function render(now = performance.now()) {
  requestAnimationFrame(render);
  const delta = Math.min((now - previousTime) / 1000, 0.05);
  previousTime = now;
  state.explodeAmount = THREE.MathUtils.damp(state.explodeAmount, state.exploded ? 1 : 0, 5.2, delta);
  animateModel(state.explodeAmount);
  wiringGuide.update(now / 1000);
  const nextLiveSensorChannel = state.wiring ? wiringGuide.getActiveSensorChannel(now / 1000) : -1;
  if (nextLiveSensorChannel !== liveSensorChannel) {
    liveSensorChannel = nextLiveSensorChannel;
    [...wiringPinCodes.children].forEach((chip, index) => chip.classList.toggle("is-live", index === liveSensorChannel));
  }

  if (targetGoal && cameraGoal) {
    controls.target.lerp(targetGoal, 1 - Math.exp(-delta * 5.4));
    camera.position.lerp(cameraGoal, 1 - Math.exp(-delta * 4.5));
    if (controls.target.distanceTo(targetGoal) < 0.015 && camera.position.distanceTo(cameraGoal) < 0.02) {
      controls.target.copy(targetGoal);
      camera.position.copy(cameraGoal);
      targetGoal = null;
      cameraGoal = null;
    }
  }

  controls.update(delta);
  composer.render(delta);
  if (firstFrame) {
    firstFrame = false;
    requestAnimationFrame(() => loading.classList.add("is-done"));
  }
}

render();
