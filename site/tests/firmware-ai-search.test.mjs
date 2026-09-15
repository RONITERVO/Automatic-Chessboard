import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { Chess } from "chess.js";
import { AvrFirmwareRuntime } from "../src/firmware-core.js";

const [hexText, manifest] = await Promise.all([
  readFile(new URL("../public/firmware/automatic-chessboard-nano.hex", import.meta.url), "utf8"),
  readFile(new URL("../public/firmware/automatic-chessboard-nano.json", import.meta.url), "utf8").then(JSON.parse),
]);

const squareIndex = (square) => (8 - Number(square[1])) * 8 + square.charCodeAt(0) - 97;

function writeByte(runtime, name, value) {
  runtime.cpu.data[manifest.symbols[name].address] = value;
}

function piecesFrom(chess) {
  const pieces = {};
  for (const rank of "12345678") {
    for (const file of "abcdefgh") {
      const square = `${file}${rank}`;
      const piece = chess.get(square);
      if (piece) pieces[square] = `${piece.color}${piece.type}`;
    }
  }
  return pieces;
}

function writeOccupied(runtime, name, squares) {
  const symbol = manifest.symbols[name];
  runtime.cpu.data.fill(0, symbol.address, symbol.address + symbol.size);
  for (const square of squares) {
    const index = squareIndex(square);
    runtime.cpu.data[symbol.address + (index >> 3)] |= 1 << (index & 7);
  }
}

function submitHumanMove(runtime, chess, move) {
  const pieces = piecesFrom(chess);
  runtime.board.setPieces(pieces);
  for (const name of ["reed_sensor_status", "reed_sensor_record", "turn_start_status"]) {
    writeOccupied(runtime, name, Object.keys(pieces));
  }
  const aiMoveSymbol = manifest.symbols.lastM;
  runtime.cpu.data.fill(0, aiMoveSymbol.address, aiMoveSymbol.address + aiMoveSymbol.size);
  writeByte(runtime, "sequence", 5);
  writeByte(runtime, "human_move_ready", 0);
  writeByte(runtime, "move_edit_stage", 0);

  runtime.runCycles(manifest.clockHz / 50);
  runtime.board.setPiece(move.from, null);
  const captureSquare = move.captured
    ? (move.flags.includes("e") ? `${move.to[0]}${move.from[1]}` : move.to)
    : null;
  if (captureSquare) runtime.board.setPiece(captureSquare, null);
  runtime.runCycles(manifest.clockHz / 50);
  runtime.board.setPiece(move.to, `${move.color}${move.promotion ?? move.piece}`);
  runtime.runCycles(manifest.clockHz / 50);
  assert.equal(runtime.state().humanMoveReady, false, "movement alone must not trigger a scan");
  runtime.pressButton("A");
  runtime.runCycles(manifest.clockHz / 4);
  assert.equal(runtime.state().humanMoveReady, true, `firmware did not track ${move.from}${move.to}`);

  runtime.pressButton("A");

  let sawThinking = false;
  for (let cycles = 0; cycles < manifest.clockHz * 10; cycles += 10_000) {
    runtime.runCycles(10_000);
    const state = runtime.state();
    sawThinking ||= state.lcd[0].includes("AI THINKING");
    if (state.sequence === 6) return state;
    // This focused harness bypasses head calibration; after a valid AI result,
    // physical motion can therefore advance directly to the motion-fault UI.
    if (state.sequence === 10 && /^[a-h][1-8][a-h][1-8]$/.test(state.aiMove.toLowerCase())) return state;
    assert.notEqual(
      state.sequence,
      1,
      `the AVR rebooted during AI search after ${move.from}${move.to}`,
    );
    if (state.sequence !== 5 && state.sequence !== 0) {
      assert.fail(`AI search left for unexpected firmware state ${state.sequence}: ${state.lcd.join(" / ")}`);
    }
  }
  const timeout = runtime.state();
  assert.fail(
    `AI search did not finish after ${move.from}${move.to}; thinking=${sawThinking}, ` +
    `state=${timeout.sequence}, pc=0x${timeout.pc.toString(16)}, sp=0x${timeout.sp.toString(16)}, ` +
    `minFree=${timeout.minimumFreeRam}, ai=${timeout.aiMove || "--"}`,
  );
}

// This ordinary opening move drove the old binary past the available stack;
// its historical guard permitted up to 31 D() frames. The test protects the
// Nano and browser emulator together, because both execute this exact HEX.
const runtime = new AvrFirmwareRuntime(hexText, manifest);
runtime.runCycles(manifest.clockHz * 4);
const chess = new Chess();
const human = chess.moves({ verbose: true }).find((move) => move.from === "d2" && move.to === "d4");
const searched = submitHumanMove(runtime, chess, human);
chess.move(human);
const aiText = searched.aiMove.toLowerCase();
const ai = chess.move({ from: aiText.slice(0, 2), to: aiText.slice(2, 4), promotion: "q" });
assert.ok(ai, `Micro-Max returned an illegal move ${aiText} after d2d4`);
assert.notEqual(searched.sequence, 1, "AI search must not reboot into the main menu");

console.log(`Production firmware AI stack test passed (d2d4 ${aiText}).`);
