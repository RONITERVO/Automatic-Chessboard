import { Chess } from "chess.js";
import { findLegalMove, getManualAiMove, resolveFirmwareBase } from "./play-simulator.js";

const FILES = "abcdefgh";
const RANKS = "12345678";
const PIECE_NAMES = { p: "pawn", n: "knight", b: "bishop", r: "rook", q: "queen", k: "king" };
export const FIRMWARE_SPEEDS = Object.freeze([0.5, 1, 4, 16]);

function arraysEqual(left, right) {
  return left === right || (Array.isArray(left) && Array.isArray(right)
    && left.length === right.length && left.every((value, index) => value === right[index]));
}

function recordsEqual(left, right) {
  if (left === right) return true;
  if (!left || !right) return false;
  const keys = Object.keys(left);
  return keys.length === Object.keys(right).length && keys.every((key) => left[key] === right[key]);
}

export function compactFrame(frame, previousFrame) {
  if (!previousFrame?.state) return frame;
  const state = { ...frame.state };
  for (const key of ["lcd", "serialLines", "expectedOccupied", "sensedOccupied"]) {
    if (arraysEqual(state[key], previousFrame.state[key])) state[key] = previousFrame.state[key];
  }
  for (const key of ["pieces", "head"]) {
    if (recordsEqual(state[key], previousFrame.state[key])) state[key] = previousFrame.state[key];
  }
  return { ...frame, state };
}

export function shouldRecordFrame(frame, previousFrame) {
  if (!previousFrame) return true;
  if (frame.events?.length) return true;
  if (frame.bootNumber !== previousFrame.bootNumber) return true;
  if (frame.state.power !== previousFrame.state.power || frame.state.sequence !== previousFrame.state.sequence) return true;
  if (frame.state.moving || frame.state.magnet) return true;
  return Number(frame.state.runtimeMs ?? 0) - Number(previousFrame.state.runtimeMs ?? 0) >= 500;
}

export function createStartingPieces() {
  const game = new Chess();
  const pieces = {};
  for (const rank of RANKS) {
    for (const file of FILES) {
      const square = `${file}${rank}`;
      const piece = game.get(square);
      if (piece) pieces[square] = `${piece.color}${piece.type}`;
    }
  }
  return pieces;
}

export function startingPositionMatches(pieces = {}) {
  const expected = createStartingPieces();
  const entries = Object.entries(expected);
  return Object.keys(pieces).length === entries.length
    && entries.every(([square, piece]) => pieces[square] === piece);
}

export function getSetupRackPieces(pieces = {}) {
  const expected = createStartingPieces();
  const deficits = {};
  for (const piece of Object.values(expected)) deficits[piece] = (deficits[piece] ?? 0) + 1;
  for (const piece of Object.values(pieces)) deficits[piece] = (deficits[piece] ?? 0) - 1;
  return Object.entries(expected).filter(([square, piece]) => {
    if (pieces[square] === piece || deficits[piece] <= 0) return false;
    deficits[piece] -= 1;
    return true;
  });
}

export function getGuidedAction(state) {
  if (!state.power) return { label: "POWER ON", disabled: false, action: "power" };
  if (state.sequence === 0) return { label: "BOOTING", disabled: true, action: "wait" };
  if (state.sequence === 1) return { label: "PRESS A · GAME", disabled: false, action: "A" };
  if (state.sequence === 2) return { label: "HEAD SAFE · PRESS A", disabled: false, action: "A" };
  if (state.sequence === 3) return { label: "CALIBRATING", disabled: true, action: "wait" };
  if (state.sequence === 4) {
    const count = Object.keys(state.pieces ?? {}).length;
    if (startingPositionMatches(state.pieces)) {
      return { label: "PRESS A · START", disabled: false, action: "A" };
    }
    return {
      label: count ? `RESET START · ${count}→32` : "AUTO SETUP · 0/32",
      disabled: false,
      action: "pieces",
    };
  }
  if (state.lcd?.[0]?.startsWith("AI THINKING")) return { label: "AI THINKING", disabled: true, action: "wait" };
  if (state.sequence === 5) return state.humanMoveReady
    ? { label: state.moveEditStage ? "PRESS A · SET SQUARE" : "PRESS A · CONFIRM MOVE", disabled: false, action: "A" }
    : { label: "PRESS A · DETECT MOVE", disabled: false, action: "A" };
  if (state.sequence === 6) return { label: "ARDUINO MOVING", disabled: true, action: "wait" };
  const manualMove = getManualAiMove(state);
  if (manualMove) return {
    label: `AUTO PLACE ${manualMove.from.toUpperCase()}→${manualMove.to.toUpperCase()}`,
    disabled: false,
    action: "ai-manual",
  };
  if (state.sequence === 8 && /^[a-h][1-8][a-h][1-8]$/.test(state.aiMove ?? "")) {
    return { label: "PRESS A · CHECK", disabled: false, action: "A" };
  }
  if ([7, 10].includes(state.sequence)) return { label: "PRESS B · MENU", disabled: false, action: "B" };
  return { label: state.stateName ?? "RUNNING", disabled: true, action: "wait" };
}

function initialFrame() {
  return { state: { power: false, pieces: {}, stateName: "POWER OFF", speed: 1 }, events: [], bootNumber: 0 };
}

export class FirmwareSession {
  constructor({
    baseUri = document.baseURI,
    workerFactory = () => new Worker(new URL("./firmware-worker.js", import.meta.url), { type: "module" }),
  } = {}) {
    this.worker = workerFactory();
    this.game = new Chess();
    this.frames = [];
    this.trace = [];
    this.listeners = new Set();
    this.liveFrame = initialFrame();
    this.viewIndex = -1;
    this.manifest = null;
    this.ready = false;
    this.error = "";
    this.selected = null;
    this.setupSelection = null;
    this.legalTargets = new Set();
    this.pendingHumanMove = null;
    this.lastAppliedAiMove = "";
    this.lastMove = null;
    this.speed = 1;
    this.timelineTimer = null;
    this.announcement = "";
    this.announcementId = 0;
    this.snapshotValue = null;

    this.worker.addEventListener("message", (event) => this.handleWorkerMessage(event));
    this.worker.addEventListener("error", (event) => {
      this.error = event.message || "Firmware worker failed";
      this.say(this.error);
    });
    this.worker.postMessage({ type: "initialize", firmwareBase: resolveFirmwareBase(baseUri) });
    this.updateSnapshot();
  }

  subscribe = (listener) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  getSnapshot = () => this.snapshotValue;

  displayedFrame() {
    return this.viewIndex >= 0 ? this.frames[this.viewIndex] ?? this.liveFrame : this.liveFrame;
  }

  updateSnapshot() {
    const frame = this.displayedFrame();
    const state = frame?.state ?? initialFrame().state;
    this.snapshotValue = Object.freeze({
      ready: this.ready,
      error: this.error,
      manifest: this.manifest,
      frame,
      state,
      historical: this.viewIndex >= 0,
      viewIndex: this.viewIndex,
      frameCount: this.frames.length,
      selected: this.selected,
      setupSelection: this.setupSelection,
      legalTargets: new Set(this.legalTargets),
      lastMove: this.lastMove,
      speed: this.speed,
      timelinePlaying: Boolean(this.timelineTimer),
      trace: this.trace.filter((entry) => entry.frameIndex <= (this.viewIndex >= 0 ? this.viewIndex : this.frames.length - 1)).slice(-40),
      guided: getGuidedAction(state),
      announcement: this.announcement,
      announcementId: this.announcementId,
    });
    for (const listener of this.listeners) listener();
  }

  say(message) {
    this.announcement = message;
    this.announcementId += 1;
    this.updateSnapshot();
  }

  handleWorkerMessage(event) {
    if (event.data.type === "ready") {
      this.manifest = event.data.manifest;
      this.ready = true;
      this.updateSnapshot();
    } else if (event.data.type === "frame") {
      this.addFrame(event.data);
    } else if (event.data.type === "move-result") {
      if (event.data.accepted && this.pendingHumanMove) {
        this.lastAppliedAiMove = "";
        this.say("Piece placed. Press A to detect, then A again to confirm the displayed move.");
      } else this.pendingHumanMove = null;
      this.updateSnapshot();
    } else if (event.data.type === "fatal") {
      this.error = event.data.message;
      this.say(event.data.message);
    }
  }

  applyAiMove(state) {
    const uci = state.aiMove;
    if (!/^[a-h][1-8][a-h][1-8]$/.test(uci ?? "") || uci === this.lastAppliedAiMove) return;
    try {
      if (this.game.turn() === "w") {
        const human = state.humanMove ?? "";
        if (!/^[a-h][1-8][a-h][1-8]$/.test(human)) return;
        this.game.move({ from: human.slice(0, 2), to: human.slice(2, 4), promotion: "q" });
        this.pendingHumanMove = null;
      }
      this.lastMove = this.game.move({ from: uci.slice(0, 2), to: uci.slice(2, 4), promotion: "q" });
      this.lastAppliedAiMove = uci;
      this.announcement = `Production Micro-Max chose ${this.lastMove.san}`;
      this.announcementId += 1;
    } catch {
      // The physical firmware state remains authoritative for unknown engine edges.
    }
  }

  addFrame(frame) {
    const previousFrame = this.frames.at(-1);
    const compact = compactFrame(frame, previousFrame);
    this.liveFrame = compact;
    if ((compact.state.sequence === 4 && previousFrame?.state.sequence !== 4) ||
        (compact.state.sequence === 3 && [1, 2].includes(previousFrame?.state.sequence))) {
      this.game.reset();
      this.lastAppliedAiMove = "";
      this.lastMove = null;
      this.clearSelection(false);
    }
    if (this.viewIndex < 0) this.applyAiMove(compact.state);
    if (!shouldRecordFrame(compact, previousFrame)) {
      this.updateSnapshot();
      return;
    }
    const frameIndex = this.frames.push(compact) - 1;
    for (const event of compact.events) this.trace.push({ ...event, frameIndex, bootNumber: compact.bootNumber });
    this.updateSnapshot();
  }

  power(enabled = !this.liveFrame.state.power) {
    if (!this.ready || this.viewIndex >= 0) return;
    this.worker.postMessage({ type: "power", enabled });
  }

  pressButton(button) {
    if (!this.liveFrame.state.power || this.viewIndex >= 0) return;
    this.worker.postMessage({ type: "button", button });
  }

  guidedAction() {
    if (this.viewIndex >= 0) return;
    const state = this.liveFrame.state;
    const guide = getGuidedAction(state);
    if (guide.disabled) return;
    if (guide.action === "power") this.power(true);
    else if (guide.action === "A" || guide.action === "B") this.pressButton(guide.action);
    else if (guide.action === "pieces") {
      this.game.reset();
      this.lastAppliedAiMove = "";
      this.lastMove = null;
      this.clearSelection(false);
      this.worker.postMessage({ type: "place-start", pieces: createStartingPieces() });
    } else if (guide.action === "ai-manual") {
      const move = getManualAiMove(state);
      if (move) this.worker.postMessage({ type: "manual-move", ...move });
      this.clearSelection(false);
    }
  }

  selectSquare(square) {
    if (this.viewIndex >= 0) return false;
    const state = this.liveFrame.state;
    if (this.setupSelection) {
      if (state.sequence === 4 && square === this.setupSelection.square) {
        const piece = this.setupSelection.piece;
        this.worker.postMessage({ type: "place-piece", square, piece });
        this.clearSelection(false);
        this.say(`${piece[0] === "w" ? "White" : "Black"} ${PIECE_NAMES[piece[1]]} placed on ${square.toUpperCase()}`);
        return true;
      }
      this.clearSelection();
      return false;
    }
    if (state.sequence === 4) {
      if (this.selected && state.pieces?.[this.selected]) {
        if (square === this.selected) {
          this.clearSelection();
          return true;
        }
        return this.moveSetupPiece(this.selected, square);
      }
      const piece = state.pieces?.[square];
      if (piece) {
        const expected = createStartingPieces();
        this.selected = square;
        this.legalTargets = new Set(Object.entries(expected)
          .filter(([target, expectedPiece]) => expectedPiece === piece && state.pieces?.[target] !== piece)
          .map(([target]) => target));
        this.say(`${square.toUpperCase()} selected; move it to a highlighted start square or off the board`);
        return true;
      }
      this.clearSelection();
      return false;
    }
    const manualMove = getManualAiMove(state);
    if (manualMove) {
      if (this.selected === manualMove.from && square === manualMove.to) {
        this.worker.postMessage({ type: "manual-move", ...manualMove });
        this.clearSelection(false);
        this.say(`${manualMove.from.toUpperCase()} placed on ${manualMove.to.toUpperCase()}; press A to confirm placement`);
        return true;
      }
      if (square === manualMove.from) {
        this.selected = square;
        this.legalTargets = new Set([manualMove.to]);
        this.say(`${square.toUpperCase()} selected; place it on ${manualMove.to.toUpperCase()}`);
        return true;
      }
      this.clearSelection();
      return false;
    }
    if (state.sequence !== 5 || state.humanMoveReady || this.pendingHumanMove || this.game.turn() !== "w") return false;
    if (this.selected && this.legalTargets.has(square)) return this.commitHumanMove(this.selected, square);
    const piece = this.game.get(square);
    if (piece?.color === "w") {
      this.selected = square;
      this.legalTargets = new Set(this.game.moves({ square, verbose: true }).map((move) => move.to));
      this.say(`${square.toUpperCase()} ${PIECE_NAMES[piece.type]} selected`);
      return true;
    }
    this.clearSelection();
    return false;
  }

  commitHumanMove(from, to) {
    const candidate = findLegalMove(this.game, { from, to, promotion: "q" });
    if (!candidate) return false;
    this.pendingHumanMove = candidate;
    this.worker.postMessage({
      type: "human-move",
      from: candidate.from,
      to: candidate.to,
      captureSquare: candidate.flags.includes("e")
        ? `${candidate.to[0]}${Number(candidate.to[1]) - 1}`
        : (candidate.captured ? candidate.to : null),
      rook: candidate.flags.includes("k")
        ? { from: "h1", to: "f1" }
        : (candidate.flags.includes("q") ? { from: "a1", to: "d1" } : null),
      promotionPiece: candidate.promotion ? "wq" : null,
    });
    this.clearSelection();
    return true;
  }

  moveSetupPiece(from, to) {
    const state = this.liveFrame.state;
    if (this.viewIndex >= 0 || state.sequence !== 4 || !state.pieces?.[from] || from === to) {
      this.clearSelection();
      return false;
    }
    const piece = state.pieces[from];
    this.worker.postMessage({ type: "setup-move", from, to });
    this.clearSelection(false);
    this.say(to
      ? `${piece[0] === "w" ? "White" : "Black"} ${PIECE_NAMES[piece[1]]} moved ${from.toUpperCase()} to ${to.toUpperCase()}`
      : `${piece[0] === "w" ? "White" : "Black"} ${PIECE_NAMES[piece[1]]} moved off the board`);
    return true;
  }

  selectSetupPiece(square, piece) {
    const state = this.liveFrame.state;
    if (this.viewIndex >= 0 || state.sequence !== 4 || state.pieces?.[square] === piece) return false;
    this.setupSelection = { square, piece };
    this.selected = `tray:${square}`;
    this.legalTargets = new Set([square]);
    this.say(`${piece[0] === "w" ? "White" : "Black"} ${PIECE_NAMES[piece[1]]}; place it on ${square.toUpperCase()}`);
    return true;
  }

  clearSelection(notify = true) {
    this.selected = null;
    this.setupSelection = null;
    this.legalTargets.clear();
    if (notify) this.updateSnapshot();
  }

  setSpeed(speed) {
    if (!FIRMWARE_SPEEDS.includes(speed)) return;
    this.speed = speed;
    this.worker.postMessage({ type: "speed", speed });
    this.updateSnapshot();
  }

  cycleSpeed() {
    const index = FIRMWARE_SPEEDS.indexOf(this.speed);
    this.setSpeed(FIRMWARE_SPEEDS[(index + 1) % FIRMWARE_SPEEDS.length]);
  }

  setReplayIndex(index) {
    if (!this.frames.length) return;
    this.stopTimelinePlayback(false);
    this.viewIndex = Math.max(0, Math.min(index, this.frames.length - 1));
    this.worker.postMessage({ type: "pause", paused: true });
    this.clearSelection(false);
    this.updateSnapshot();
  }

  previousFrame() {
    this.setReplayIndex((this.viewIndex < 0 ? this.frames.length - 1 : this.viewIndex) - 1);
  }

  nextFrame() {
    if (this.viewIndex < 0 || this.viewIndex >= this.frames.length - 1) this.goLive();
    else this.setReplayIndex(this.viewIndex + 1);
  }

  goLive() {
    this.stopTimelinePlayback(false);
    this.viewIndex = -1;
    this.worker.postMessage({ type: "pause", paused: false });
    this.updateSnapshot();
  }

  toggleTimelinePlayback() {
    if (this.viewIndex < 0) {
      this.worker.postMessage({ type: "pause", paused: !this.liveFrame.state.paused });
      return;
    }
    if (this.timelineTimer) {
      this.stopTimelinePlayback();
      return;
    }
    this.timelineTimer = setInterval(() => {
      if (this.viewIndex >= this.frames.length - 1) this.goLive();
      else {
        this.viewIndex += 1;
        this.updateSnapshot();
      }
    }, 90);
    this.updateSnapshot();
  }

  stopTimelinePlayback(notify = true) {
    if (this.timelineTimer) clearInterval(this.timelineTimer);
    this.timelineTimer = null;
    if (notify) this.updateSnapshot();
  }

  destroy() {
    this.stopTimelinePlayback(false);
    this.worker.terminate();
    this.listeners.clear();
  }
}
