import assert from "node:assert/strict";
import { Chess } from "chess.js";
import {
  chooseComputerMove,
  createBoardRenderKey,
  findLegalMove,
  getManualAiMove,
  planOrthogonalRoute,
  resolveFirmwareBase,
  squareToCoords,
} from "../src/play-simulator.js";
import { boardPositionToSquare, squareToBoardPosition, toggleSpatialMode } from "../src/immersive/board-space.js";
import {
  compactFrame,
  createStartingPieces,
  FirmwareSession,
  getGuidedAction,
  getSetupRackPieces,
  shouldRecordFrame,
  startingPositionMatches,
} from "../src/firmware-session.js";

assert.deepEqual(squareToCoords("a1"), { file: 0, rank: 0 });
assert.deepEqual(squareToCoords("h8"), { file: 7, rank: 7 });
assert.equal(
  resolveFirmwareBase("https://ronitervo.github.io/Automatic-Chessboard/"),
  "https://ronitervo.github.io/Automatic-Chessboard/firmware/",
  "firmware files resolve from the deployed document rather than the worker assets directory",
);

assert.deepEqual(squareToBoardPosition("a1"), [-13.125, 9.22, -13.125]);
assert.deepEqual(squareToBoardPosition("h8"), [13.125, 9.22, 13.125]);
assert.equal(boardPositionToSquare(-13.125, -13.125), "a1");
assert.equal(boardPositionToSquare(13.125, 13.125), "h8");
assert.equal(boardPositionToSquare(18, 0), null, "dragging beyond the physical board does not invent a square");
assert.equal(toggleSpatialMode("play", "brain"), "brain", "an inspection mode can be selected");
assert.equal(toggleSpatialMode("brain", "brain"), "play", "pressing an active inspection mode returns to the board");
assert.equal(toggleSpatialMode("brain", "xray"), "xray", "pressing a different inspection mode switches directly");
assert.equal(Object.keys(createStartingPieces()).length, 32);
assert.deepEqual(getGuidedAction({ power: false }), { label: "POWER ON", disabled: false, action: "power" });
assert.equal(getGuidedAction({ power: true, sequence: 4, pieces: {} }).action, "pieces");
const startingPieces = createStartingPieces();
assert.equal(startingPositionMatches(startingPieces), true, "the exact starting board is ready to play");
assert.equal(getGuidedAction({ power: true, sequence: 4, pieces: startingPieces }).action, "A");
const misplacedPieces = { ...startingPieces, e4: startingPieces.e2 };
delete misplacedPieces.e2;
assert.equal(startingPositionMatches(misplacedPieces), false, "32 pieces in the wrong position cannot start a game");
assert.equal(getGuidedAction({ power: true, sequence: 4, pieces: misplacedPieces }).action, "pieces");
assert.deepEqual(getSetupRackPieces(misplacedPieces), [], "a misplaced board piece must be moved instead of duplicated from the rack");
delete misplacedPieces.e4;
assert.deepEqual(getSetupRackPieces(misplacedPieces), [["e2", "wp"]], "a physically missing piece remains available on the setup rack");
const overcrowdedPieces = { ...startingPieces, e3: "wp", e4: "wp", d4: "bp", d5: "bp" };
assert.match(getGuidedAction({ power: true, sequence: 4, pieces: overcrowdedPieces }).label, /36→32/);
assert.equal(getGuidedAction({ power: true, sequence: 8, aiMove: "b8c6", pieces: { b8: "bn" } }).action, "ai-manual");
const recorded = { bootNumber: 1, events: [], state: { power: true, sequence: 5, runtimeMs: 1000, pieces: { e2: "wp" }, lcd: ["YOUR MOVE", "A=END TURN"] } };
const idle = { bootNumber: 1, events: [], state: { power: true, sequence: 5, runtimeMs: 1125, pieces: { e2: "wp" }, lcd: ["YOUR MOVE", "A=END TURN"] } };
assert.equal(shouldRecordFrame(idle, recorded), false, "idle telemetry is thinned for long-running sessions");
assert.equal(shouldRecordFrame({ ...idle, events: [{ kind: "sensor" }] }, recorded), true, "physical events are never dropped from rewind");
const compact = compactFrame(idle, recorded);
assert.equal(compact.state.pieces, recorded.state.pieces, "unchanged board states share memory across the full timeline");
assert.equal(compact.state.lcd, recorded.state.lcd, "unchanged LCD states share memory across the full timeline");

const boardUi = { orientation: "white", historical: false, selected: null, legalTargets: new Set(), lastMove: null };
const idleBoard = { sequence: 5, pieces: { e2: "wp" }, head: { file: 5, rank: 6, square: "e6" } };
assert.equal(
  createBoardRenderKey({ ...idleBoard, pc: 10, runtimeMs: 100 }, boardUi),
  createBoardRenderKey({ ...idleBoard, pc: 20, runtimeMs: 225 }, boardUi),
  "telemetry-only frames preserve the live square elements during human clicks",
);
assert.notEqual(
  createBoardRenderKey(idleBoard, boardUi),
  createBoardRenderKey(idleBoard, { ...boardUi, selected: "e2", legalTargets: new Set(["e3", "e4"]) }),
  "selection changes still redraw the board",
);
assert.deepEqual(
  getManualAiMove({ sequence: 8, aiMove: "b8c6", pieces: { b8: "bn" } }),
  { from: "b8", to: "c6" },
  "the requested manual AI move is selectable on the board",
);
assert.equal(
  getManualAiMove({ sequence: 8, aiMove: "b8c6", pieces: { c6: "bn" } }),
  null,
  "manual AI selection closes after the piece reaches its destination",
);

const openRoute = planOrthogonalRoute("e2", "e4", new Set(["e2"]));
assert.deepEqual(openRoute, ["e2", "e3", "e4"], "open files use square-centre transport");

const blockedRoute = planOrthogonalRoute("a1", "a3", new Set(["a1", "a2", "b1", "b2"]));
assert.equal(blockedRoute, null, "isolated pieces report that staging is required");

const game = new Chess();
assert.equal(findLegalMove(game, { from: "e2", to: "e4", promotion: "q" })?.san, "e4", "promotion defaults do not reject ordinary moves");
game.move("e4");
const computerMove = chooseComputerMove(game);
assert.ok(computerMove, "the virtual opponent returns a move");
assert.doesNotThrow(() => game.move(computerMove), "the virtual opponent always returns a legal move");

const workerMessages = [];
const fakeWorker = {
  addEventListener() {},
  postMessage(message) { workerMessages.push(message); },
  terminate() {},
};
const setupSession = new FirmwareSession({ baseUri: "https://example.test/", workerFactory: () => fakeWorker });
const setupBoard = { ...startingPieces, e4: startingPieces.e2 };
delete setupBoard.e2;
setupSession.game.move("e4");
setupSession.addFrame({ state: { power: true, sequence: 4, pieces: setupBoard, runtimeMs: 1 }, events: [], bootNumber: 1 });
assert.deepEqual(setupSession.game.history(), [], "entering setup starts a fresh chess rules session after an aborted game");
assert.equal(setupSession.selectSquare("e4"), true, "pieces already on the board can be selected during setup");
assert.deepEqual([...setupSession.getSnapshot().legalTargets], ["e2"], "setup highlights the selected piece's missing home square");
assert.equal(setupSession.moveSetupPiece("e4", "e2"), true, "a selected board piece can be repositioned during setup");
assert.deepEqual(workerMessages.at(-1), { type: "setup-move", from: "e4", to: "e2" });
assert.equal(setupSession.moveSetupPiece("e4", null), true, "an extra board piece can be moved back to the setup rack");
assert.deepEqual(workerMessages.at(-1), { type: "setup-move", from: "e4", to: null });
setupSession.destroy();

const confirmedSession = new FirmwareSession({ baseUri: "https://example.test/", workerFactory: () => fakeWorker });
confirmedSession.pendingHumanMove = { from: "e2", to: "e4" };
confirmedSession.handleWorkerMessage({ data: { type: "move-result", accepted: true } });
assert.deepEqual(confirmedSession.game.history(), [], "placing a piece does not commit the software move");
confirmedSession.applyAiMove({ humanMove: "d2d4", aiMove: "b8c6" });
assert.deepEqual(confirmedSession.game.history(), ["d4", "Nc6"], "the browser follows the confirmed firmware move, including corrections");
assert.equal(confirmedSession.pendingHumanMove, null);
assert.equal(getGuidedAction({ power: true, sequence: 5, humanMoveReady: false }).action, "A");
assert.match(getGuidedAction({ power: true, sequence: 5, humanMoveReady: true }).label, /CONFIRM/);
assert.equal(getGuidedAction({ power: true, sequence: 5, lcd: ["AI THINKING"] }).disabled, true);
confirmedSession.destroy();

console.log("Virtual chess simulator tests passed.");
