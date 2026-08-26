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
import { compactFrame, createStartingPieces, getGuidedAction, shouldRecordFrame } from "../src/firmware-session.js";

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

console.log("Virtual chess simulator tests passed.");
