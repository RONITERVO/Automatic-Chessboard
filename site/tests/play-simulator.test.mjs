import assert from "node:assert/strict";
import { Chess } from "chess.js";
import { chooseComputerMove, findLegalMove, planOrthogonalRoute, squareToCoords } from "../src/play-simulator.js";

assert.deepEqual(squareToCoords("a1"), { file: 0, rank: 0 });
assert.deepEqual(squareToCoords("h8"), { file: 7, rank: 7 });

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
