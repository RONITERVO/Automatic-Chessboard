import {
  AVRADC,
  AVRClock,
  AVREEPROM,
  AVRIOPort,
  AVRTWI,
  AVRTimer,
  AVRUSART,
  CPU,
  EEPROMMemoryBackend,
  adcConfig,
  avrInstruction,
  clockConfig,
  eepromConfig,
  portBConfig,
  portCConfig,
  portDConfig,
  timer0Config,
  timer1Config,
  timer2Config,
  twiConfig,
  usart0Config,
} from "avr8js";

const FILES = "abcdefgh";
const SENSOR_ROW_MAP = [7, 6, 1, 0, 3, 2, 5, 4];
const LCD_ADDRESS = 0x27;
const STEP_PITCH = 188;
const PARK_FILE = 5;
const PARK_RANK = 6;
const PARK_RAW_X = 871;
const PARK_RAW_Y = -354;

export const FIRMWARE_STATES = [
  ["POWER ON", "Automatic_Chessboard_V3_27_i2c_value.ino"],
  ["MAIN MENU", "FirmwareStandalone.ino"],
  ["POSITION RECOVERY", "PositionJournal.ino"],
  ["CALIBRATION", "FirmwareMotion.ino"],
  ["SETUP CHECK", "FirmwareSensors.ino"],
  ["HUMAN TURN", "FirmwareSensors.ino"],
  ["MICRO-MAX MOVE", "FirmwarePieces.ino"],
  ["UNDO REQUIRED", "FirmwareStandalone.ino"],
  ["MANUAL AI MOVE", "FirmwareStandalone.ino"],
  ["GAME OVER", "FirmwareStandalone.ino"],
  ["MOTION FAULT", "FirmwareMotion.ino"],
  ["SERVICE", "FirmwareHost.ino"],
  ["HOST ALIGNMENT", "FirmwareHost.ino"],
  ["REMOTE SETUP", "FirmwareHost.ino"],
  ["REMOTE HUMAN", "FirmwareHost.ino"],
  ["WAITING FOR HOST", "FirmwareHost.ino"],
  ["REMOTE UNDO", "FirmwareHost.ino"],
  ["REMOTE SENSOR CHECK", "FirmwareHost.ino"],
  ["PROMOTION", "FirmwareHost.ino"],
  ["MANUAL MOTION", "FirmwareHost.ino"],
  ["ROUTE PLAN", "FirmwareHost.ino"],
];

export function parseIntelHex(text, flashBytes = 32768) {
  const program = new Uint8Array(flashBytes);
  program.fill(0xff);
  let upperAddress = 0;
  for (const [index, rawLine] of text.trim().split(/\r?\n/).entries()) {
    const line = rawLine.trim();
    if (!line) continue;
    if (!/^:[0-9a-f]+$/i.test(line)) throw new Error(`Invalid Intel HEX at line ${index + 1}`);
    const record = Uint8Array.from(line.slice(1).match(/../g).map((byte) => Number.parseInt(byte, 16)));
    if ((record.reduce((sum, byte) => sum + byte, 0) & 0xff) !== 0) {
      throw new Error(`Intel HEX checksum failed at line ${index + 1}`);
    }
    const length = record[0];
    const address = (record[1] << 8) | record[2];
    const type = record[3];
    if (type === 0) {
      const destination = upperAddress + address;
      if (destination + length > program.length) throw new Error("Firmware exceeds ATmega328P flash");
      program.set(record.slice(4, 4 + length), destination);
    } else if (type === 4) {
      upperAddress = ((record[4] << 8) | record[5]) << 16;
    } else if (type === 1) {
      break;
    }
  }
  return program;
}

export class Lcd1602 {
  constructor(onChange = () => {}) {
    this.onChange = onChange;
    this.ddram = new Array(80).fill(" ");
    this.address = 0;
    this.pendingNibble = null;
    this.fourBit = false;
    this.lastExpander = 0xff;
    this.lastLines = this.lines.join("\n");
  }

  get lines() {
    return [this.ddram.slice(0, 16).join(""), this.ddram.slice(0x40, 0x50).join("")];
  }

  writeExpander(value) {
    const enableWasHigh = Boolean(this.lastExpander & 0x04);
    const enableIsHigh = Boolean(value & 0x04);
    this.lastExpander = value;
    if (!enableWasHigh || enableIsHigh || (value & 0x02)) return;
    const nibble = (value >> 4) & 0x0f;
    const data = Boolean(value & 0x01);
    if (!this.fourBit) {
      if (!data && nibble === 2) {
        this.fourBit = true;
        this.pendingNibble = null;
      }
      return;
    }
    if (!this.pendingNibble || this.pendingNibble.data !== data) {
      this.pendingNibble = { nibble, data };
      return;
    }
    const byte = (this.pendingNibble.nibble << 4) | nibble;
    this.pendingNibble = null;
    if (data) this.writeData(byte);
    else this.command(byte);
    const nextLines = this.lines.join("\n");
    if (nextLines !== this.lastLines) {
      this.lastLines = nextLines;
      this.onChange(this.lines);
    }
  }

  command(value) {
    if (value === 0x01) {
      this.ddram.fill(" ");
      this.address = 0;
    } else if (value === 0x02) {
      this.address = 0;
    } else if (value & 0x80) {
      this.address = value & 0x7f;
    } else if ((value & 0x10) && (value & 0x04)) {
      this.address = (this.address + 1) & 0x7f;
    } else if ((value & 0x10) && !(value & 0x04)) {
      this.address = (this.address - 1) & 0x7f;
    }
  }

  writeData(value) {
    if (this.address < this.ddram.length) this.ddram[this.address] = String.fromCharCode(value);
    this.address = (this.address + 1) & 0x7f;
  }
}

function squareFromSensorChannel(mux, channel) {
  const scanColumn = [6, 4, 2, 0][mux] + (channel >= 8 ? 1 : 0);
  const rawRow = 7 - scanColumn;
  const logicalRow = 7 - SENSOR_ROW_MAP[rawRow];
  const logicalColumn = 7 - (channel & 7);
  return `${FILES[logicalColumn]}${8 - logicalRow}`;
}

function nearestSquare(file, rank) {
  const roundedFile = Math.round(file);
  const roundedRank = Math.round(rank);
  if (roundedFile < 1 || roundedFile > 8 || roundedRank < 1 || roundedRank > 8) return null;
  return `${FILES[roundedFile - 1]}${roundedRank}`;
}

function clonePieces(pieces) {
  return Object.fromEntries([...pieces.entries()].sort(([a], [b]) => a.localeCompare(b)));
}

class BoardPeripheral {
  constructor(runtime, portB, portC, portD, adc) {
    this.runtime = runtime;
    this.portB = portB;
    this.portC = portC;
    this.portD = portD;
    this.adc = adc;
    this.pieces = new Map();
    this.heldPiece = null;
    this.userButtonA = false;
    this.userButtonB = false;
    this.bluetoothConnected = false;
    this.bluetoothRxLevel = true;
    this.limitA = false;
    this.limitB = false;
    this.whiteMotor = 1225;
    this.blackMotor = -517;
    this.lastPortB = 0;
    this.lastPortC = 0;
    this.lastPortD = 0;
    this.totalStepPulses = 0;
    this.recentStepCycles = [];
    this.lastStepCycle = -Infinity;
    this.moving = false;
    this.magnet = false;
    this.muxInputHigh = null;

    portB.addListener((value, oldValue) => this.onPortB(value, oldValue));
    portC.addListener((value) => {
      this.lastPortC = value;
      this.refreshMuxInput();
    });
    portD.addListener((value, oldValue) => this.onPortD(value, oldValue));
    this.refreshInputs();
  }

  get rawX() { return (this.whiteMotor - this.blackMotor) / 2; }
  get rawY() { return -(this.whiteMotor + this.blackMotor) / 2; }
  get headFile() { return PARK_FILE + (this.rawX - PARK_RAW_X) / STEP_PITCH; }
  get headRank() { return PARK_RANK + (this.rawY - PARK_RAW_Y) / STEP_PITCH; }

  setPieces(nextPieces) {
    this.pieces = new Map(Object.entries(nextPieces));
    this.heldPiece = null;
    this.refreshMuxInput();
  }

  setPiece(square, piece) {
    if (piece) this.pieces.set(square, piece);
    else this.pieces.delete(square);
    this.refreshMuxInput();
  }

  setButton(button, pressed) {
    if (button === "A") this.userButtonA = pressed;
    else this.userButtonB = pressed;
    this.refreshInputs();
  }

  refreshInputs() {
    // The switches are physical inputs, so they remain available during every
    // firmware state. Captures deliberately re-home while sequence is still
    // player_black; gating them to the calibration screen makes that recovery
    // run past both virtual switches and end in a false motion fault.
    this.limitA = this.rawX <= 0.5;
    this.limitB = this.limitA && this.rawY >= -0.5;
    this.portB.setPin(3, !(this.userButtonA || this.limitA));
    this.portB.setPin(2, this.bluetoothRxLevel);
    this.adc.channelValues[6] = this.userButtonB || this.limitB ? 0 : 5;
    this.refreshMuxInput();
  }

  setBluetoothConnected(connected) {
    this.bluetoothConnected = Boolean(connected);
    this.runtime.record("bluetooth", connected ? "Bluetooth transport connected" : "Bluetooth transport disconnected");
  }

  receiveBluetoothBytes(bytes) {
    if (!this.bluetoothConnected) return false;
    const bitMs = 1000 / 9600;
    let offset = 0;
    for (const byte of bytes) {
      const bits = [false, ...Array.from({ length: 8 }, (_, bit) => Boolean(byte & (1 << bit))), true];
      for (const level of bits) {
        this.runtime.schedule(offset, () => {
          this.bluetoothRxLevel = level;
          this.portB.setPin(2, level);
        });
        offset += bitMs;
      }
    }
    this.runtime.record("bluetooth", `${bytes.length} Bluetooth byte${bytes.length === 1 ? "" : "s"} received`);
    return true;
  }

  refreshMuxInput() {
    const select = [
      Boolean(this.lastPortB & (1 << 5)),
      Boolean(this.lastPortB & (1 << 1)),
      Boolean(this.lastPortB & (1 << 0)),
      Boolean(this.lastPortD & (1 << 7)),
    ];
    const mux = select.findIndex((high) => !high);
    let occupied = false;
    if (mux >= 0) {
      const channel = ((this.lastPortC >> 3) & 1) |
        (((this.lastPortC >> 2) & 1) << 1) |
        (((this.lastPortC >> 1) & 1) << 2) |
        ((this.lastPortC & 1) << 3);
      occupied = this.pieces.has(squareFromSensorChannel(mux, channel));
    }
    const nextHigh = !occupied;
    if (nextHigh !== this.muxInputHigh) {
      this.muxInputHigh = nextHigh;
      this.portB.setPin(4, nextHigh);
    }
  }

  onPortB(value, oldValue) {
    this.lastPortB = value;
    this.refreshMuxInput();
    if ((value ^ oldValue) & ((1 << 5) | (1 << 1) | (1 << 0))) this.refreshMuxInput();
  }

  onPortD(value, oldValue) {
    this.lastPortD = value;
    const magnet = Boolean(value & (1 << 6));
    if (magnet !== this.magnet) this.setMagnet(magnet);
    if ((value & (1 << 3)) && !(oldValue & (1 << 3))) {
      this.whiteMotor += value & (1 << 2) ? 1 : -1;
      this.onStep();
    }
    if ((value & (1 << 5)) && !(oldValue & (1 << 5))) {
      this.blackMotor += value & (1 << 4) ? 1 : -1;
      this.onStep();
    }
    this.refreshInputs();
  }

  onStep() {
    const cycle = this.runtime.cpu.cycles;
    this.totalStepPulses++;
    this.lastStepCycle = cycle;
    this.recentStepCycles.push(cycle);
    const oneSecondAgo = cycle - this.runtime.clockHz;
    while (this.recentStepCycles[0] < oneSecondAgo) this.recentStepCycles.shift();
    if (!this.moving) {
      this.moving = true;
      this.runtime.record("motion", "STEP pulses started");
    }
  }

  settleMotion() {
    const oneSecondAgo = this.runtime.cpu.cycles - this.runtime.clockHz;
    while (this.recentStepCycles[0] < oneSecondAgo) this.recentStepCycles.shift();
    if (this.moving && this.runtime.cpu.cycles - this.lastStepCycle > this.runtime.clockHz / 20) {
      this.moving = false;
      this.runtime.record("motion", "STEP pulses stopped");
    }
  }

  setMagnet(enabled) {
    this.magnet = enabled;
    const square = nearestSquare(this.headFile, this.headRank);
    if (enabled) {
      if (square && this.pieces.has(square)) {
        this.heldPiece = { piece: this.pieces.get(square), from: square };
        this.pieces.delete(square);
        this.refreshMuxInput();
      }
      this.runtime.record("magnet", `Electromagnet ON${square ? ` at ${square.toUpperCase()}` : ""}`);
    } else {
      if (this.heldPiece) {
        if (square) this.pieces.set(square, this.heldPiece.piece);
        this.runtime.record("piece", square
          ? `${this.heldPiece.piece} placed on ${square.toUpperCase()}`
          : `${this.heldPiece.piece} released into capture bin`);
        this.heldPiece = null;
        this.refreshMuxInput();
      }
      this.runtime.record("magnet", "Electromagnet OFF");
    }
  }

  state() {
    return {
      pieces: clonePieces(this.pieces),
      physicalOccupied: this.pieces.size + (this.heldPiece ? 1 : 0),
      heldPiece: this.heldPiece?.piece ?? null,
      head: {
        file: this.headFile,
        rank: this.headRank,
        square: nearestSquare(this.headFile, this.headRank),
      },
      magnet: this.magnet,
      moving: this.moving,
      stepPulses: this.totalStepPulses,
      stepRate: this.recentStepCycles.length,
      limitA: this.limitA,
      limitB: this.limitB,
      buttonA: this.userButtonA,
      buttonB: this.userButtonB,
    };
  }
}

export class AvrFirmwareRuntime {
  constructor(hexText, manifest, { eeprom } = {}) {
    this.manifest = manifest;
    this.clockHz = manifest.clockHz;
    this.events = [];
    this.scheduled = [];
    this.changed = true;
    this.instructions = 0;
    this.lastSequence = -1;
    this.serialLines = [];
    this.program = parseIntelHex(hexText);
    this.cpu = new CPU(new Uint16Array(this.program.buffer));
    this.clock = new AVRClock(this.cpu, this.clockHz, clockConfig);
    this.timers = [
      new AVRTimer(this.cpu, timer0Config),
      new AVRTimer(this.cpu, timer1Config),
      new AVRTimer(this.cpu, timer2Config),
    ];
    this.portB = new AVRIOPort(this.cpu, portBConfig);
    this.portC = new AVRIOPort(this.cpu, portCConfig);
    this.portD = new AVRIOPort(this.cpu, portDConfig);
    this.adc = new AVRADC(this.cpu, adcConfig);
    this.eepromBackend = new EEPROMMemoryBackend(1024);
    if (eeprom) this.eepromBackend.memory.set(eeprom);
    this.eeprom = new AVREEPROM(this.cpu, this.eepromBackend, eepromConfig);
    this.usart = new AVRUSART(this.cpu, usart0Config, this.clockHz);
    this.usart.onLineTransmit = (line) => {
      const clean = line.replace(/\r$/, "");
      this.serialLines.push(clean);
      this.serialLines = this.serialLines.slice(-8);
      this.record("serial", clean);
    };
    this.lcd = new Lcd1602((lines) => this.record("lcd", lines.join(" / ").trim()));
    this.twi = new AVRTWI(this.cpu, twiConfig, this.clockHz);
    let pcf8574Selected = false;
    let pcf8574Latch = 0xff;
    this.twi.eventHandler = {
      start: () => this.twi.completeStart(),
      stop: () => {
        pcf8574Selected = false;
        this.twi.completeStop();
      },
      connectToSlave: (address) => {
        pcf8574Selected = address === LCD_ADDRESS;
        this.twi.completeConnect(pcf8574Selected);
      },
      writeByte: (value) => {
        if (pcf8574Selected) {
          pcf8574Latch = value;
          this.lcd.writeExpander(value);
        }
        this.twi.completeWrite(pcf8574Selected);
      },
      readByte: () => this.twi.completeRead(pcf8574Selected ? pcf8574Latch : 0xff),
    };
    this.board = new BoardPeripheral(this, this.portB, this.portC, this.portD, this.adc);
    this.record("power", "ATmega328P reset vector entered");
  }

  readByte(name) {
    const symbol = this.manifest.symbols[name];
    return symbol ? this.cpu.data[symbol.address] : 0;
  }

  readWord(name, signed = false) {
    const symbol = this.manifest.symbols[name];
    if (!symbol) return 0;
    return signed
      ? this.cpu.dataView.getInt16(symbol.address, true)
      : this.cpu.dataView.getUint16(symbol.address, true);
  }

  readText(name) {
    const symbol = this.manifest.symbols[name];
    if (!symbol) return "";
    return [...this.cpu.data.slice(symbol.address, symbol.address + symbol.size)]
      .filter(Boolean).map((value) => String.fromCharCode(value)).join("");
  }

  readBoard(name) {
    const symbol = this.manifest.symbols[name];
    if (!symbol) return [];
    const occupied = [];
    for (let row = 0; row < 8; row++) {
      const bits = this.cpu.data[symbol.address + row];
      for (let column = 0; column < 8; column++) {
        if (bits & (1 << column)) occupied.push(`${FILES[column]}${8 - row}`);
      }
    }
    return occupied;
  }

  record(kind, message) {
    const tMs = this.cpu ? this.cpu.cycles / this.clockHz * 1000 : 0;
    const previous = this.events.at(-1);
    if (kind === "lcd" && previous?.kind === "lcd" && tMs - previous.tMs < 5) {
      previous.message = message;
      previous.tMs = tMs;
    } else {
      this.events.push({ kind, message, tMs });
    }
    this.changed = true;
  }

  schedule(delayMs, action) {
    this.scheduled.push({ cycle: this.cpu.cycles + delayMs / 1000 * this.clockHz, action });
    this.scheduled.sort((a, b) => a.cycle - b.cycle);
  }

  pressButton(button) {
    this.board.setButton(button, true);
    this.record("input", `Button ${button} pressed`);
    this.schedule(80, () => {
      this.board.setButton(button, false);
      this.record("input", `Button ${button} released`);
    });
  }

  placeStartingPieces(pieces) {
    this.board.setPieces({});
    Object.entries(pieces).forEach(([square, piece], index) => {
      this.schedule(index * 45, () => {
        this.board.setPiece(square, piece);
        this.record("sensor", `${piece} placed on ${square.toUpperCase()} · reed closed`);
      });
    });
  }

  placePiece(square, piece) {
    this.board.setPiece(square, piece);
    this.record("sensor", `${piece} placed on ${square.toUpperCase()} · reed closed`);
  }

  setupMove(from, to) {
    const piece = this.board.pieces.get(from);
    if (!piece || from === to) return false;
    const displaced = to ? this.board.pieces.get(to) : null;
    this.board.setPiece(from, null);
    this.record("sensor", `${from.toUpperCase()} opened · setup piece lifted`);
    if (to) {
      this.board.setPiece(to, piece);
      this.record("sensor", `${to.toUpperCase()} closed · setup piece placed`);
      if (displaced) this.record("piece", `${displaced} returned to setup rack`);
    } else {
      this.record("piece", `${piece} returned to setup rack`);
    }
    return true;
  }

  humanMove(from, to, { captureSquare = null, rook = null, promotionPiece = null } = {}) {
    const piece = this.board.pieces.get(from);
    if (!piece) return false;
    this.board.setPiece(from, null);
    this.record("sensor", `${from.toUpperCase()} opened · piece lifted`);
    if (captureSquare && this.board.pieces.has(captureSquare)) {
      this.schedule(140, () => {
        this.board.setPiece(captureSquare, null);
        this.record("sensor", `${captureSquare.toUpperCase()} opened · captured piece lifted`);
      });
    }
    this.schedule(320, () => {
      this.board.setPiece(to, promotionPiece ?? piece);
      this.record("sensor", `${to.toUpperCase()} closed · piece placed`);
    });
    if (rook && this.board.pieces.has(rook.from)) {
      const rookPiece = this.board.pieces.get(rook.from);
      this.schedule(520, () => {
        this.board.setPiece(rook.from, null);
        this.record("sensor", `${rook.from.toUpperCase()} opened · rook lifted`);
      });
      this.schedule(720, () => {
        this.board.setPiece(rook.to, rookPiece);
        this.record("sensor", `${rook.to.toUpperCase()} closed · rook placed`);
      });
    }
    return true;
  }

  runCycles(cycles) {
    const target = this.cpu.cycles + cycles;
    while (this.cpu.cycles < target) {
      while (this.scheduled.length && this.scheduled[0].cycle <= this.cpu.cycles) {
        this.scheduled.shift().action();
      }
      avrInstruction(this.cpu);
      this.cpu.tick();
      this.instructions++;
    }
    this.board.settleMotion();
    const sequence = this.readByte("sequence");
    if (sequence !== this.lastSequence) {
      this.lastSequence = sequence;
      const state = FIRMWARE_STATES[sequence] ?? [`STATE ${sequence}`, "firmware"];
      this.record("state", `${state[0]} · ${state[1]}`);
    }
    this.board.refreshInputs();
  }

  takeEvents() {
    const result = this.events;
    this.events = [];
    this.changed = false;
    return result;
  }

  state() {
    const sequence = this.readByte("sequence");
    const stateInfo = FIRMWARE_STATES[sequence] ?? [`STATE ${sequence}`, "firmware"];
    return {
      actualFirmware: true,
      firmwareVersion: this.manifest.firmwareVersion,
      firmwareHash: this.manifest.sha256,
      clockHz: this.clockHz,
      cycles: this.cpu.cycles,
      runtimeMs: this.cpu.cycles / this.clockHz * 1000,
      instructions: this.instructions,
      pc: this.cpu.pc * 2,
      sp: this.cpu.SP,
      sreg: this.cpu.SREG,
      sequence,
      stateName: stateInfo[0],
      source: stateInfo[1],
      lcd: this.lcd.lines,
      bluetoothConnected: this.board.bluetoothConnected,
      remoteMode: this.readByte("remote_mode"),
      trolleyHomed: Boolean(this.readByte("trolley_homed")),
      trolleyPositionKnown: Boolean(this.readByte("trolley_position_known")),
      trolleySquare: `${FILES[Math.max(0, this.readByte("trolley_coordinate_X") - 1)] ?? "?"}${this.readByte("trolley_coordinate_Y") || "?"}`,
      motionFault: Boolean(this.readByte("motion_fault")),
      humanMoveReady: Boolean(this.readByte("human_move_ready")),
      moveEditStage: this.readByte("move_edit_stage"),
      moveFrom: this.readByte("move_from"),
      moveTo: this.readByte("move_to"),
      humanMove: this.readText("mov"),
      aiMove: this.readText("lastM"),
      expectedOccupied: this.readBoard("reed_sensor_status"),
      sensedOccupied: this.readBoard("reed_sensor_record"),
      minimumFreeRam: this.readWord("minimum_free_ram", true),
      homeWhiteSteps: this.readWord("last_home_white_steps"),
      homeBlackSteps: this.readWord("last_home_black_steps"),
      serialLines: [...this.serialLines],
      ...this.board.state(),
    };
  }
}
