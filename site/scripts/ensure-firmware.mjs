import { createHash } from "node:crypto";
import { copyFile, mkdir, readFile, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";

const siteRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const firmwareName = "Automatic_Chessboard_V3_27_i2c_value.ino";
const buildRoot = path.resolve(siteRoot, "../build/nano");
const sourceHex = path.join(buildRoot, `${firmwareName}.hex`);
const sourceSymbols = path.join(buildRoot, `${firmwareName}.nm`);
const outputRoot = path.join(siteRoot, "public/firmware");
const outputHex = path.join(outputRoot, "automatic-chessboard-nano.hex");
const outputManifest = path.join(outputRoot, "automatic-chessboard-nano.json");

const exportedSymbols = new Set([
  "after_calibration",
  "human_move_ready",
  "lastM",
  "last_home_black_steps",
  "last_home_white_steps",
  "lifted_count",
  "lifted_squares",
  "magnet_state",
  "minimum_free_ram",
  "motion_fault",
  "mov",
  "move_from",
  "move_to",
  "pending_move_displayed",
  "reed_sensor_record",
  "reed_sensor_status",
  "remote_mode",
  "sensor_tracking_error",
  "sequence",
  "trolley_coordinate_X",
  "trolley_coordinate_Y",
  "trolley_homed",
  "trolley_position_known",
  "turn_start_status",
]);

function parseSymbols(text) {
  const result = {};
  for (const line of text.split(/\r?\n/)) {
    const match = line.match(/^([0-9a-fA-F]+)\s+([0-9a-fA-F]+)\s+[bBdD]\s+(.+)$/);
    if (!match || !exportedSymbols.has(match[3])) continue;
    const elfAddress = Number.parseInt(match[1], 16);
    result[match[3]] = {
      address: elfAddress >= 0x800000 ? elfAddress - 0x800000 : elfAddress,
      size: Number.parseInt(match[2], 16),
    };
  }
  const missing = [...exportedSymbols].filter((name) => !result[name]);
  if (missing.length) throw new Error(`Firmware symbols missing from Nano ELF: ${missing.join(", ")}`);
  return result;
}

const [hex, nm] = await Promise.all([
  readFile(sourceHex, "utf8"),
  readFile(sourceSymbols, "utf8"),
]);
const digest = createHash("sha256").update(hex).digest("hex");
const manifest = {
  board: "Arduino Nano / ATmega328P",
  clockHz: 16_000_000,
  firmwareVersion: "5.0.1",
  hardwareProfile: "nano",
  sha256: digest,
  symbols: parseSymbols(nm),
};

await mkdir(outputRoot, { recursive: true });
await Promise.all([
  copyFile(sourceHex, outputHex),
  writeFile(outputManifest, `${JSON.stringify(manifest, null, 2)}\n`, "utf8"),
]);
console.log(`Prepared Nano firmware ${digest.slice(0, 12)} for the browser lab.`);
