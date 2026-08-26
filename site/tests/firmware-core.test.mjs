import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { AvrFirmwareRuntime, Lcd1602, parseIntelHex } from "../src/firmware-core.js";

const tinyHex = ":020000000102FB\n:00000001FF\n";
assert.deepEqual([...parseIntelHex(tinyHex, 4)], [1, 2, 255, 255]);
assert.throws(() => parseIntelHex(":020000000102FC\n:00000001FF\n", 4), /checksum/);

const lcd = new Lcd1602();
const pulse = (nibble, data = false) => {
  const base = (nibble << 4) | (data ? 1 : 0);
  lcd.writeExpander(base | 4);
  lcd.writeExpander(base);
};
pulse(2);
pulse(0); pulse(1);
pulse(8); pulse(0);
for (const char of "READY") {
  pulse(char.charCodeAt(0) >> 4, true);
  pulse(char.charCodeAt(0) & 15, true);
}
assert.equal(lcd.lines[0].slice(0, 5), "READY");

const [hexText, manifest] = await Promise.all([
  readFile(new URL("../public/firmware/automatic-chessboard-nano.hex", import.meta.url), "utf8"),
  readFile(new URL("../public/firmware/automatic-chessboard-nano.json", import.meta.url), "utf8").then(JSON.parse),
]);
const runtime = new AvrFirmwareRuntime(hexText, manifest);
runtime.runCycles(manifest.clockHz * 4);
const booted = runtime.state();
assert.equal(booted.firmwareVersion, "5.0.1");
assert.equal(booted.sequence, 1, "the production binary reaches its main menu");
assert.match(booted.lcd[0], /A:GAME B:CAL/);
assert.equal(booted.bluetoothConnected, false);
assert.equal(booted.sensedOccupied.length, 0, "an empty simulated board leaves every reed input open");
assert.ok(booted.serialLines.includes("READY 5.0.1"));

runtime.placePiece("e2", "wp");
assert.equal(runtime.state().pieces.e2, "wp", "individual setup pieces close their real simulated reed channel");
assert.ok(runtime.takeEvents().some((event) => event.kind === "sensor" && event.message.includes("E2")));

runtime.pressButton("A");
runtime.runCycles(manifest.clockHz / 2);
assert.equal(runtime.state().sequence, 2, "an unknown persisted head enters position recovery");

runtime.cpu.data[manifest.symbols.sequence.address] = 6;
runtime.board.whiteMotor = 354;
runtime.board.blackMotor = 354;
runtime.board.refreshInputs();
assert.equal(runtime.state().limitA, true, "the white home switch remains available during an AI capture");
assert.equal(runtime.state().limitB, false, "the black home switch waits for the calibration corner");
runtime.board.whiteMotor = 0;
runtime.board.blackMotor = 0;
runtime.board.refreshInputs();
assert.equal(runtime.state().limitB, true, "capture homing can reach the second switch outside the calibration screen");

const captureRuntime = new AvrFirmwareRuntime(hexText, manifest);
captureRuntime.runCycles(manifest.clockHz * 4);
const writeByte = (name, value) => {
  captureRuntime.cpu.data[manifest.symbols[name].address] = value;
};
const writeText = (name, value) => {
  const symbol = manifest.symbols[name];
  captureRuntime.cpu.data.fill(0, symbol.address, symbol.address + symbol.size);
  [...value].forEach((character, index) => {
    captureRuntime.cpu.data[symbol.address + index] = character.charCodeAt(0);
  });
};
for (const name of ["reed_sensor_status", "reed_sensor_record"]) {
  const address = manifest.symbols[name].address;
  captureRuntime.cpu.data.fill(0, address, address + 8);
  captureRuntime.cpu.data[address + 2] = 1 << 3; // d6
  captureRuntime.cpu.data[address + 3] = 1 << 3; // d5
}
captureRuntime.board.setPieces({ d6: "br", d5: "wp" });
writeText("lastM", "d6d5");
writeByte("trolley_homed", 1);
writeByte("trolley_position_known", 1);
writeByte("trolley_coordinate_X", 5);
writeByte("trolley_coordinate_Y", 6);
writeByte("motion_fault", 0);
writeByte("sequence", 6);
for (let elapsed = 0; elapsed < 12_000 && captureRuntime.state().sequence === 6; elapsed += 250) {
  captureRuntime.runCycles(manifest.clockHz / 4);
}
const captured = captureRuntime.state();
assert.equal(captured.motionFault, false, "capture-bin homing does not create a false motion fault");
assert.equal(captured.trolleyHomed, true, "the real firmware re-establishes its home reference after a capture");
assert.equal(captured.sequence, 5, "the real firmware returns control to the human after completing the AI capture");
assert.deepEqual(captured.pieces, { d5: "br" }, "the captured piece reaches the bin and the moving piece reaches its destination");

console.log("Production firmware emulator tests passed.");
