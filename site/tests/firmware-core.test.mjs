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

runtime.pressButton("A");
runtime.runCycles(manifest.clockHz / 2);
assert.equal(runtime.state().sequence, 2, "an unknown persisted head enters position recovery");

console.log("Production firmware emulator tests passed.");
