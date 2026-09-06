import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { Chess } from "chess.js";
import { AvrFirmwareRuntime } from "../src/firmware-core.js";

const hex = await readFile(new URL("../public/firmware/automatic-chessboard-nano.hex", import.meta.url), "utf8");
const manifest = JSON.parse(await readFile(new URL("../public/firmware/automatic-chessboard-nano.json", import.meta.url), "utf8"));
const index = (square) => (8 - Number(square[1])) * 8 + square.charCodeAt(0) - 97;
const write = (r, name, value) => { r.cpu.data[manifest.symbols[name].address] = value; };
const run = (r, seconds = 0.25) => r.runCycles(manifest.clockHz * seconds);
const press = (r, button) => { r.pressButton(button); run(r); };
const occupancy = (r) => [...r.state().expectedOccupied].sort();
const pieces = (chess) => Object.fromEntries(chess.board().flat().filter(Boolean).map(p => [p.square, p.color + p.type]));
function command(r, text) {
  for (const char of text + "\n") {
    assert.equal(r.usart.writeByte(char.charCodeAt(0)), true);
    run(r, 0.003);
  }
  run(r, 0.1);
}
function position(fen) {
  const chess = fen ? new Chess(fen) : new Chess();
  const r = new AvrFirmwareRuntime(hex, manifest);
  run(r, 4);
  const table = manifest.symbols.b.address;
  for (let row = 0; row < 8; row++) for (let file = 0; file < 8; file++) r.cpu.data[table + row * 16 + file] = 0;
  const codes = { n: 3, k: 4, b: 5, r: 6, q: 7 };
  for (const [square, piece] of Object.entries(pieces(chess))) {
    const squareIndex = index(square);
    r.cpu.data[table + (squareIndex >> 3) * 16 + (squareIndex & 7)] =
      (piece[0] === "w" ? 8 : 16) | (piece[1] === "p" ? (piece[0] === "w" ? 1 : 2) : codes[piece[1]]);
  }
  for (const name of ["reed_sensor_status", "reed_sensor_record", "turn_start_status"]) {
    const address = manifest.symbols[name].address;
    r.cpu.data.fill(0, address, address + 8);
    for (const square of Object.keys(pieces(chess))) r.cpu.data[address + (index(square) >> 3)] |= 1 << (index(square) & 7);
  }
  r.board.setPieces(pieces(chess));
  write(r, "remote_mode", 1);
  write(r, "remote_human_white", chess.turn() === "w" ? 1 : 0);
  write(r, "sequence", 14);
  write(r, "human_move_ready", 0);
  write(r, "move_edit_stage", 0);
  command(r, "HELLO 5.1.0");
  return { r, chess };
}

// The real compiled firmware must remain idle until A is pressed. Extra reeds
// cannot overwrite the software position, and the second press must not scan.
{
  const { r, chess } = position();
  const before = occupancy(r);
  chess.move("e4");
  r.board.setPieces({ ...pieces(chess), b3: "wp", f4: "wp" });
  run(r, 0.5);
  assert.equal(r.state().humanMoveReady, false);
  assert.deepEqual(occupancy(r), before);
  press(r, "A");
  assert.match(r.state().lcd[0], /MOVE e2-e4/);
  assert.equal(r.state().sequence, 14, "first press only proposes");
  assert.deepEqual(occupancy(r), before);
  r.board.setPieces({}); // Every reed now disagrees, including the move squares.
  press(r, "A");
  assert.equal(r.state().sequence, 15);
  assert.ok(r.state().serialLines.includes("MOVE e2e4"));
  command(r, "ACCEPT");
  assert.deepEqual(occupancy(r), Object.keys(pieces(chess)).sort());
  command(r, "BOARD");
  assert.equal(r.state().motionFault, false);
  assert.deepEqual([...r.state().sensedOccupied].sort(), occupancy(r), "remote BOARD reports software occupancy");
  write(r, "trolley_homed", 1);
  write(r, "trolley_position_known", 1);
  write(r, "trolley_coordinate_X", 5);
  write(r, "trolley_coordinate_Y", 6);
  command(r, "PLAN e7e5---");
  assert.ok(r.state().serialLines.includes("PLAN READY"));
  command(r, "DRAG e7e5");
  run(r, 4);
  assert.ok(r.state().serialLines.includes("MOVED PIECE e7e5"));
  command(r, "COMMIT");
  chess.move("e5");
  assert.equal(r.state().sequence, 14, "robot turn completes with every physical reed open");
  assert.deepEqual(occupancy(r), Object.keys(pieces(chess)).sort());
  assert.equal(r.state().motionFault, false);
}

// Captures, en passant, castling, and promotion update occupancy from the
// confirmed move, never from the noisy snapshot. Correct ambiguous proposals
// using the same LCD square editor a Nano-only player has.
for (const [fen, uci, accept] of [
  ["4k3/8/8/3p4/4P3/8/8/4K3 w - - 0 1", "e4d5", "ACCEPT"],
  ["4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1", "e5d6", "ACCEPT"],
  ["4k3/8/8/8/8/8/8/R3K2R w KQ - 0 1", "e1g1", "ACCEPT"],
  ["4k3/P7/8/8/8/8/8/4K3 w - - 0 1", "a7a8n", "ACCEPT n"],
  ["4k3/8/8/8/3Pp3/8/8/4K3 b - d3 0 1", "e4d3", "ACCEPT"],
  ["r3k2r/8/8/8/8/8/8/4K3 b kq - 0 1", "e8c8", "ACCEPT"],
  ["4k3/8/8/8/8/8/p7/4K3 b - - 0 1", "a2a1r", "ACCEPT r"],
]) {
  const { r, chess } = position(fen);
  chess.move(uci);
  r.board.setPieces(pieces(chess));
  press(r, "A");
  if (r.state().moveFrom !== index(uci.slice(0, 2)) || r.state().moveTo !== index(uci.slice(2, 4))) {
    press(r, "B");
    for (let n = 0; r.state().moveFrom !== index(uci.slice(0, 2)) && n < 64; n++) press(r, "B");
    assert.equal(r.state().moveFrom, index(uci.slice(0, 2)));
    press(r, "A");
    for (let n = 0; r.state().moveTo !== index(uci.slice(2, 4)) && n < 64; n++) press(r, "B");
    assert.equal(r.state().moveTo, index(uci.slice(2, 4)));
    press(r, "A");
  }
  r.board.setPieces({});
  press(r, "A");
  assert.ok(r.state().serialLines.includes(`MOVE ${uci.slice(0, 4)}`));
  command(r, accept);
  assert.deepEqual(occupancy(r), Object.keys(pieces(chess)).sort(), uci);
  assert.equal(r.state().motionFault, false);
}

// Rejection returns to editing without requiring physical undo or changing
// the accepted board. This also covers correction with no useful reed input.
{
  const { r } = position();
  const before = occupancy(r);
  r.board.setPieces({});
  press(r, "A");
  press(r, "A");
  command(r, "REJECT");
  assert.equal(r.state().sequence, 14);
  assert.equal(r.state().moveEditStage, 1);
  assert.deepEqual(occupancy(r), before);
  r.board.setButton("B", true);
  run(r, 2.2);
  r.board.setButton("B", false);
  run(r);
  assert.equal(r.state().sequence, 1, "holding B exits a game");
}

console.log("Production firmware human confirmation and software occupancy tests passed.");
