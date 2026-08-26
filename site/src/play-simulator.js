import { Chess } from "chess.js";

const FILES = "abcdefgh";
const RANKS = "12345678";
const STORAGE_KEY = "automatic-chessboard-play-v1";
const PIECES = {
  wp: "♙", wn: "♘", wb: "♗", wr: "♖", wq: "♕", wk: "♔",
  bp: "♟", bn: "♞", bb: "♝", br: "♜", bq: "♛", bk: "♚",
};
const PIECE_NAMES = { p: "pawn", n: "knight", b: "bishop", r: "rook", q: "queen", k: "king" };
const MATERIAL = { p: 1, n: 3, b: 3.2, r: 5, q: 9, k: 0 };

export function squareToCoords(square) {
  return { file: FILES.indexOf(square[0]), rank: RANKS.indexOf(square[1]) };
}

function coordsToSquare(file, rank) {
  return `${FILES[file]}${RANKS[rank]}`;
}

export function planOrthogonalRoute(from, to, occupiedSquares) {
  const blocked = new Set(occupiedSquares);
  blocked.delete(from);
  blocked.delete(to);
  const queue = [[from]];
  const visited = new Set([from]);

  while (queue.length) {
    const path = queue.shift();
    const current = path.at(-1);
    if (current === to) return path;
    const { file, rank } = squareToCoords(current);
    for (const [nextFile, nextRank] of [[file + 1, rank], [file - 1, rank], [file, rank + 1], [file, rank - 1]]) {
      if (nextFile < 0 || nextFile > 7 || nextRank < 0 || nextRank > 7) continue;
      const next = coordsToSquare(nextFile, nextRank);
      if (blocked.has(next) || visited.has(next)) continue;
      visited.add(next);
      queue.push([...path, next]);
    }
  }
  return null;
}

function occupiedSquares(game) {
  const occupied = new Set();
  for (const file of FILES) {
    for (const rank of RANKS) {
      const square = `${file}${rank}`;
      if (game.get(square)) occupied.add(square);
    }
  }
  return occupied;
}

function transportPlan(game, move) {
  const occupied = occupiedSquares(game);
  if (move.flags.includes("e")) {
    const capturedRank = Number(move.to[1]) + (move.color === "w" ? -1 : 1);
    occupied.delete(`${move.to[0]}${capturedRank}`);
  }
  if (move.captured) occupied.delete(move.to);
  const route = planOrthogonalRoute(move.from, move.to, occupied);
  return {
    mode: move.captured ? "CAP" : (route ? "DRAG" : "STAGE"),
    route: route ?? [move.from, move.to],
  };
}

function stableTieBreak(text) {
  let hash = 2166136261;
  for (const char of text) hash = Math.imul(hash ^ char.charCodeAt(0), 16777619);
  return (hash >>> 0) / 0xffffffff;
}

export function chooseComputerMove(game) {
  const legalMoves = game.moves({ verbose: true });
  if (!legalMoves.length) return null;
  const position = game.fen();
  let best = legalMoves[0];
  let bestScore = -Infinity;

  for (const candidate of legalMoves) {
    game.move({ from: candidate.from, to: candidate.to, promotion: candidate.promotion ?? "q" });
    let score = (MATERIAL[candidate.captured] ?? 0) * 12 + (candidate.promotion ? 8 : 0);
    if (game.isCheckmate()) score += 100000;
    else {
      if (game.isCheck()) score += 1.8;
      const replyCapture = Math.max(0, ...game.moves({ verbose: true }).map((reply) => MATERIAL[reply.captured] ?? 0));
      score -= replyCapture * 3.5;
      const { file, rank } = squareToCoords(candidate.to);
      score += (3.5 - Math.abs(file - 3.5) + 3.5 - Math.abs(rank - 3.5)) * 0.06;
    }
    score += stableTieBreak(`${position}:${candidate.san}`) * 0.01;
    game.undo();
    if (score > bestScore) {
      bestScore = score;
      best = candidate;
    }
  }
  return { from: best.from, to: best.to, promotion: best.promotion ?? "q" };
}

export function findLegalMove(game, moveInput) {
  return game.moves({ square: moveInput.from, verbose: true })
    .find((move) => move.to === moveInput.to && (!move.promotion || move.promotion === (moveInput.promotion ?? "q"))) ?? null;
}

function startingPieces() {
  const game = new Chess();
  const pieces = {};
  for (const rank of RANKS) {
    for (const file of FILES) {
      const piece = game.get(`${file}${rank}`);
      if (piece) pieces[`${file}${rank}`] = `${piece.color}${piece.type}`;
    }
  }
  return pieces;
}

function formatTime(milliseconds = 0) {
  const minutes = Math.floor(milliseconds / 60000);
  const seconds = Math.floor(milliseconds / 1000) % 60;
  const tenths = Math.floor(milliseconds / 100) % 10;
  return `${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}.${tenths}`;
}

function hex(value, width = 4) {
  return `0x${Math.max(0, value ?? 0).toString(16).toUpperCase().padStart(width, "0")}`;
}

export function createPlaySimulator({ panel, board, status, history, announce }) {
  const worker = new Worker(new URL("./firmware-worker.js", import.meta.url), { type: "module" });
  const game = new Chess();
  const frames = [];
  const trace = [];
  let orientation = "white";
  let enabled = false;
  let manifest = null;
  let liveFrame = { state: { power: false }, events: [], bootNumber: 0 };
  let viewIndex = -1;
  let selected = null;
  let legalTargets = new Set();
  let pendingHumanMove = null;
  let lastAppliedAiMove = "";
  let lastMove = null;
  let replayTimer = null;
  const power = document.querySelector("#board-power");
  const guided = document.querySelector("#guided-action");
  const buttonA = document.querySelector("#button-a");
  const buttonB = document.querySelector("#button-b");
  const flipButton = document.querySelector("#play-flip");
  const firmwareBuild = document.querySelector("#firmware-build");
  const firmwareLed = document.querySelector("#firmware-led");
  const bluetoothStatus = document.querySelector("#bluetooth-status");
  const lcdLines = [document.querySelector("#lcd-line-1"), document.querySelector("#lcd-line-2")];
  const brainFlow = document.querySelector("#brain-flow");
  const brainMetrics = document.querySelector("#brain-metrics");
  const timeline = document.querySelector("#timeline");
  const timelineTime = document.querySelector("#timeline-time");
  const timelinePlay = document.querySelector("#timeline-play");
  const timelinePlayPath = document.querySelector("#timeline-play-path");
  const timelineLive = document.querySelector("#timeline-live");
  const timelineSpeed = document.querySelector("#timeline-speed");
  const speeds = [1, 4, 16, 0.5];
  let speedIndex = 0;

  function displayedFrame() {
    return viewIndex >= 0 ? frames[viewIndex] : liveFrame;
  }

  function squareCenter(file, rank) {
    const column = orientation === "white" ? file - 1 : 8 - file;
    const row = orientation === "white" ? 8 - rank : rank - 1;
    return { left: `${(column + 0.5) * 12.5}%`, top: `${(row + 0.5) * 12.5}%` };
  }

  function renderBoard(state) {
    const pieces = state.pieces ?? {};
    const fragment = document.createDocumentFragment();
    const files = orientation === "white" ? [...FILES] : [...FILES].reverse();
    const ranks = orientation === "white" ? [...RANKS].reverse() : [...RANKS];
    for (const rank of ranks) {
      for (const file of files) {
        const square = `${file}${rank}`;
        const pieceCode = pieces[square];
        const squareButton = document.createElement("button");
        squareButton.type = "button";
        squareButton.className = `virtual-square ${(FILES.indexOf(file) + RANKS.indexOf(rank)) % 2 ? "is-dark" : "is-light"}`;
        squareButton.dataset.square = square;
        squareButton.setAttribute("role", "gridcell");
        const pieceName = pieceCode ? `${pieceCode[0] === "w" ? "white" : "black"} ${PIECE_NAMES[pieceCode[1]]}` : "empty";
        squareButton.setAttribute("aria-label", `${square} ${pieceName}`);
        squareButton.textContent = PIECES[pieceCode] ?? "";
        squareButton.disabled = viewIndex >= 0 || state.sequence !== 5 || state.humanMoveReady;
        squareButton.classList.toggle("is-selected", square === selected);
        squareButton.classList.toggle("is-legal", legalTargets.has(square));
        squareButton.classList.toggle("is-last", lastMove && (lastMove.from === square || lastMove.to === square));
        squareButton.addEventListener("click", () => selectSquare(square));
        fragment.append(squareButton);
      }
    }
    const motionHead = document.createElement("i");
    motionHead.id = "virtual-motion-head";
    motionHead.classList.toggle("is-moving", Boolean(state.moving || state.magnet));
    motionHead.setAttribute("aria-hidden", "true");
    if (state.head && Number.isFinite(state.head.file) && Number.isFinite(state.head.rank)) {
      const position = squareCenter(state.head.file, state.head.rank);
      motionHead.style.left = position.left;
      motionHead.style.top = position.top;
      motionHead.textContent = state.heldPiece ? PIECES[state.heldPiece] : "";
    }
    board.replaceChildren(fragment, motionHead);
    board.dataset.orientation = orientation;
    board.classList.toggle("magnet-on", Boolean(state.magnet));
  }

  function renderFlow(state, events) {
    const eventKinds = new Set(events.map((event) => event.kind));
    const stages = [
      ["INPUT", eventKinds.has("input") || eventKinds.has("sensor")],
      ["REED SCAN", [4, 5, 7, 8].includes(state.sequence)],
      ["STATE", true],
      ["MICRO-MAX", state.sequence === 6 || state.lcd?.[0]?.startsWith("AI THINKING")],
      ["STEP + MAGNET", state.moving || state.magnet],
    ];
    brainFlow.replaceChildren();
    stages.forEach(([label, active], index) => {
      const item = document.createElement("span");
      item.textContent = label;
      item.classList.toggle("is-active", active);
      brainFlow.append(item);
      if (index < stages.length - 1) {
        const arrow = document.createElement("i");
        arrow.textContent = "›";
        brainFlow.append(arrow);
      }
    });
  }

  function renderMetrics(state) {
    const metrics = state.power ? [
      ["STATE", `${state.sequence} · ${state.stateName}`],
      ["SOURCE", state.source],
      ["PC", hex(state.pc)],
      ["CLOCK", `${(state.clockHz / 1e6).toFixed(0)} MHz · ${state.speed}×`],
      ["RUNTIME", formatTime(state.runtimeMs)],
      ["SRAM LOW", state.minimumFreeRam > 2048 ? "NOT SAMPLED" : `${state.minimumFreeRam} B free`],
      ["SENSORS", `${state.physicalOccupied ?? 0} physical · ${state.sensedOccupied?.length ?? 0} sampled`],
      ["HEAD", state.head?.square?.toUpperCase() ?? "BETWEEN SQUARES"],
      ["MOTORS", `${state.stepRate ?? 0} rises/s · 1000 µs half`],
      ["MAGNET", state.magnet ? "ON · carrying" : "OFF"],
      ["HOME", state.trolleyHomed ? `YES · ${state.trolleySquare.toUpperCase()}` : "NO"],
      ["LIMITS", `A${state.limitA ? "↓" : "↑"} · B${state.limitB ? "↓" : "↑"}`],
    ] : [["STATE", "POWER REMOVED"], ["FIRMWARE", "Loaded, CPU halted"], ["BLUETOOTH", "No device connected"]];
    const fragment = document.createDocumentFragment();
    for (const [label, value] of metrics) {
      const item = document.createElement("div");
      const key = document.createElement("span");
      const output = document.createElement("output");
      key.textContent = label;
      output.textContent = value;
      item.append(key, output);
      fragment.append(item);
    }
    brainMetrics.replaceChildren(fragment);
  }

  function guidedState(state) {
    if (!state.power) return ["POWER ON", false, "power"];
    if (state.sequence === 0) return ["BOOTING", true, "wait"];
    if (state.sequence === 1) return ["PRESS A · GAME", false, "A"];
    if (state.sequence === 2) return ["HEAD SAFE · PRESS A", false, "A"];
    if (state.sequence === 3) return ["CALIBRATING", true, "wait"];
    if (state.sequence === 4) {
      const count = Object.keys(state.pieces ?? {}).length;
      return count < 32 ? [`PLACE START PIECES · ${count}/32`, count > 0, "pieces"] : ["PRESS A · CHECK 32", false, "A"];
    }
    if (state.sequence === 5) return state.humanMoveReady
      ? ["PRESS A · END TURN", false, "A"]
      : ["MOVE A WHITE PIECE", true, "move"];
    if (state.sequence === 6) return ["ARDUINO MOVING", true, "wait"];
    if (state.sequence === 8 && /^[a-h][1-8][a-h][1-8]$/.test(state.aiMove)) {
      const from = state.aiMove.slice(0, 2);
      const to = state.aiMove.slice(2, 4);
      return state.pieces?.[from]
        ? [`PLACE ${from.toUpperCase()}→${to.toUpperCase()}`, false, "ai-manual"]
        : ["PRESS A · CHECK", false, "A"];
    }
    if ([7, 10].includes(state.sequence)) return ["PRESS B · MENU", false, "B"];
    return [state.stateName ?? "RUNNING", true, "wait"];
  }

  function renderTrace(frameIndex) {
    const visible = trace.filter((entry) => entry.frameIndex <= frameIndex).slice(-18);
    const fragment = document.createDocumentFragment();
    for (const entry of visible.reverse()) {
      const item = document.createElement("li");
      const time = document.createElement("time");
      const message = document.createElement("span");
      time.textContent = formatTime(entry.tMs);
      message.textContent = entry.message;
      item.dataset.kind = entry.kind;
      item.append(time, message);
      fragment.append(item);
    }
    history.replaceChildren(fragment);
  }

  function render() {
    const frame = displayedFrame();
    const state = frame?.state ?? { power: false };
    const events = frame?.events ?? [];
    const historical = viewIndex >= 0;
    status.textContent = historical ? `REPLAY · ${state.stateName ?? "POWER OFF"}` : (state.power ? state.stateName : "POWER OFF");
    firmwareBuild.textContent = manifest ? `REAL HEX · ${manifest.firmwareVersion} · ${manifest.sha256.slice(0, 8)}` : "LOADING FIRMWARE";
    firmwareLed.classList.toggle("is-on", Boolean(state.power));
    bluetoothStatus.textContent = "BT · DISCONNECTED";
    power.classList.toggle("is-active", Boolean(state.power));
    power.setAttribute("aria-pressed", String(Boolean(state.power)));
    power.disabled = !manifest;
    lcdLines[0].textContent = (state.lcd?.[0] ?? "POWER OFF").padEnd(16, " ");
    lcdLines[1].textContent = (state.lcd?.[1] ?? "").padEnd(16, " ");
    const [guideLabel, guideDisabled] = guidedState(state);
    guided.textContent = guideLabel;
    guided.disabled = guideDisabled || historical;
    buttonA.textContent = "A";
    buttonB.textContent = "B";
    buttonA.disabled = !state.power || historical || state.sequence === 3 || state.moving;
    buttonB.disabled = !state.power || historical || state.sequence === 3 || state.moving;
    renderBoard(state);
    renderFlow(state, events);
    renderMetrics(state);
    const currentIndex = historical ? viewIndex : Math.max(0, frames.length - 1);
    renderTrace(currentIndex);
    timeline.max = String(Math.max(0, frames.length - 1));
    timeline.value = String(currentIndex);
    timeline.disabled = frames.length < 2;
    timelineTime.textContent = `${formatTime(state.runtimeMs)} · ${currentIndex + 1}/${Math.max(1, frames.length)}`;
    timelineLive.textContent = historical ? "LIVE" : (state.paused ? "RESUME" : "LIVE");
    timelineLive.classList.toggle("is-live", !historical && !state.paused);
    timelineSpeed.textContent = `${speeds[speedIndex]}×`;
    timelinePlayPath.setAttribute("d", !historical && state.power && !state.paused
      ? "M7 5h4v14H7V5Zm6 0h4v14h-4V5Z"
      : "m8 5 11 7-11 7V5Z");
  }

  function applyAiMove(state) {
    const uci = state.aiMove;
    if (!/^[a-h][1-8][a-h][1-8]$/.test(uci) || uci === lastAppliedAiMove || game.turn() !== "b") return;
    try {
      const move = game.move({ from: uci.slice(0, 2), to: uci.slice(2, 4), promotion: "q" });
      lastMove = move;
      lastAppliedAiMove = uci;
      announce(`Production Micro-Max chose ${move.san}`);
    } catch {
      // The physical state remains authoritative if a historical engine edge
      // case is not understood by the browser's display-only chess helper.
    }
  }

  function addFrame(frame) {
    liveFrame = frame;
    const frameIndex = frames.push(structuredClone(frame)) - 1;
    for (const event of frame.events) trace.push({ ...event, frameIndex, bootNumber: frame.bootNumber });
    if (viewIndex < 0) applyAiMove(frame.state);
    if (enabled && viewIndex < 0) render();
  }

  function selectSquare(square) {
    const state = displayedFrame().state;
    if (!enabled || viewIndex >= 0 || state.sequence !== 5 || state.humanMoveReady || game.turn() !== "w") return;
    if (selected && legalTargets.has(square)) {
      const candidate = findLegalMove(game, { from: selected, to: square, promotion: "q" });
      if (!candidate) return;
      pendingHumanMove = candidate;
      worker.postMessage({
        type: "human-move",
        from: candidate.from,
        to: candidate.to,
        captureSquare: candidate.flags.includes("e") ? `${candidate.to[0]}${Number(candidate.to[1]) - 1}` : (candidate.captured ? candidate.to : null),
        rook: candidate.flags.includes("k") ? { from: "h1", to: "f1" } : (candidate.flags.includes("q") ? { from: "a1", to: "d1" } : null),
        promotionPiece: candidate.promotion ? "wq" : null,
      });
      selected = null;
      legalTargets.clear();
      render();
      return;
    }
    const piece = game.get(square);
    if (piece?.color === "w") {
      selected = square;
      legalTargets = new Set(game.moves({ square, verbose: true }).map((move) => move.to));
      announce(`${square} ${PIECE_NAMES[piece.type]} selected`);
    } else {
      selected = null;
      legalTargets.clear();
    }
    render();
  }

  function showHistorical(index) {
    clearInterval(replayTimer);
    replayTimer = null;
    viewIndex = Math.max(0, Math.min(index, frames.length - 1));
    worker.postMessage({ type: "pause", paused: true });
    selected = null;
    legalTargets.clear();
    render();
  }

  function goLive() {
    clearInterval(replayTimer);
    replayTimer = null;
    viewIndex = -1;
    worker.postMessage({ type: "pause", paused: false });
    render();
  }

  worker.addEventListener("message", (event) => {
    if (event.data.type === "ready") {
      manifest = event.data.manifest;
      render();
    } else if (event.data.type === "frame") {
      addFrame(event.data);
    } else if (event.data.type === "move-result") {
      if (event.data.accepted && pendingHumanMove) {
        lastMove = game.move({ from: pendingHumanMove.from, to: pendingHumanMove.to, promotion: pendingHumanMove.promotion ?? "q" });
        lastAppliedAiMove = "";
        announce(`Player moved ${lastMove.san}; reed sensors are tracking it`);
      }
      pendingHumanMove = null;
    } else if (event.data.type === "fatal") {
      status.textContent = "FIRMWARE LOAD FAILED";
      announce(event.data.message);
    }
  });

  worker.addEventListener("error", (event) => {
    status.textContent = "FIRMWARE LAB ERROR";
    announce(event.message || "Firmware worker failed");
  });

  power.addEventListener("click", () => worker.postMessage({ type: "power", enabled: !liveFrame.state.power }));
  buttonA.addEventListener("click", () => worker.postMessage({ type: "button", button: "A" }));
  buttonB.addEventListener("click", () => worker.postMessage({ type: "button", button: "B" }));
  guided.addEventListener("click", () => {
    const state = liveFrame.state;
    const [, , action] = guidedState(state);
    if (action === "power") worker.postMessage({ type: "power", enabled: true });
    else if (action === "A" || action === "B") worker.postMessage({ type: "button", button: action });
    else if (action === "pieces") {
      game.reset();
      lastAppliedAiMove = "";
      lastMove = null;
      worker.postMessage({ type: "place-start", pieces: startingPieces() });
    } else if (action === "ai-manual") {
      worker.postMessage({ type: "manual-move", from: state.aiMove.slice(0, 2), to: state.aiMove.slice(2, 4) });
    }
  });
  flipButton.addEventListener("click", () => {
    orientation = orientation === "white" ? "black" : "white";
    render();
  });
  timeline.addEventListener("input", () => showHistorical(Number(timeline.value)));
  document.querySelector("#timeline-previous").addEventListener("click", () => showHistorical((viewIndex < 0 ? frames.length - 1 : viewIndex) - 1));
  document.querySelector("#timeline-next").addEventListener("click", () => {
    if (viewIndex < 0 || viewIndex >= frames.length - 1) goLive();
    else showHistorical(viewIndex + 1);
  });
  timelineLive.addEventListener("click", goLive);
  timelinePlay.addEventListener("click", () => {
    if (viewIndex < 0) {
      worker.postMessage({ type: "pause", paused: !liveFrame.state.paused });
      return;
    }
    if (replayTimer) {
      clearInterval(replayTimer);
      replayTimer = null;
      render();
      return;
    }
    replayTimer = setInterval(() => {
      if (viewIndex >= frames.length - 1) {
        clearInterval(replayTimer);
        replayTimer = null;
        goLive();
      } else {
        viewIndex++;
        render();
      }
    }, 90);
  });
  timelineSpeed.addEventListener("click", () => {
    speedIndex = (speedIndex + 1) % speeds.length;
    worker.postMessage({ type: "speed", speed: speeds[speedIndex] });
    render();
  });

  render();
  return {
    setEnabled(nextEnabled) {
      enabled = nextEnabled;
      panel.hidden = !enabled;
      if (enabled) {
        worker.postMessage({ type: "snapshot" });
        render();
      } else {
        clearInterval(replayTimer);
        replayTimer = null;
        selected = null;
        legalTargets.clear();
      }
    },
  };
}
