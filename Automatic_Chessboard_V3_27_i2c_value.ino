#include <Wire.h>
#include <EEPROM.h>
#include <avr/pgmspace.h>
#include <SoftwareSerial.h>
#include <LiquidCrystal_I2C.h>
#include "global.h"
#include "Micro_Max.h"

#define FIRMWARE_VERSION "3.29"

LiquidCrystal_I2C lcd(0x27, 16, 2);
// Responses use hardware TX/D1, which safely fans out to USB and HC-08 RXD.
// Bluetooth commands use D10 so the onboard USB bridge cannot fight HC-08 TXD.
// The transmit argument is D1 but this receive-only object never writes it.
SoftwareSerial bluetoothInput(BLUETOOTH_RX, 1);

// The compact line protocol keeps the Nano responsible for motion and safety
// while a Windows host can provide full chess rules and Stockfish.
const byte HOST_INPUT_SIZE = 32;
char host_input[HOST_INPUT_SIZE];
byte host_input_length = 0;
boolean remote_mode = false;
boolean remote_human_white = true;
boolean remote_human_move_pending = false;
volatile boolean remote_stop_requested = false;
char remote_move_flags = 0;
char remote_promotion_piece = 0;

enum {SIM_EMPTY, SIM_PAWN, SIM_KNIGHT, SIM_BISHOP, SIM_ROOK, SIM_QUEEN, SIM_KING};
byte step_test_board[8][8];

// Board-square centers are x=1..8, so the playing-field edge is x=0.50.
// Calibration establishes the left limit near x=0.35. x=0.48 is outside
// the playing field while retaining about 25 full steps from that limit.
const unsigned int CAPTURE_SIDE_X_STEPS =
    (SQUARE_SIZE * 12UL + 12UL) / 25UL;
const unsigned int CAPTURE_DROP_SETTLE_MS = 400;

// Position records are journaled across multiple EEPROM slots. A new UNKNOWN
// record is committed before the first motor step, and a KNOWN record is
// committed only after the head reaches a stable logical position.
const byte POSITION_RECORD_VERSION = 1;
const byte POSITION_STATE_UNKNOWN = 0x5A;
const byte POSITION_STATE_KNOWN = 0xA5;
const byte POSITION_RECORD_COMMIT = 0xC3;
const byte POSITION_RECORD_SLOTS = 32;
const byte POSITION_RECORD_SIZE = 8;
int position_record_slot = -1;
unsigned int position_record_sequence = 0;
boolean trolley_position_known = false;
boolean calibration_lane_confirmed = false;

// Raw multiplexer rows follow the glued-tile wiring documented in
// windows_app/README.md. Normalize them once, before any chess logic or host
// telemetry sees the board. The eight-byte permutation stays in flash.
const byte SENSOR_ROW_MAP[8] PROGMEM = {7, 6, 1, 0, 3, 2, 5, 4};

byte logicalSensorRow(byte raw_row) {
  return pgm_read_byte(&SENSOR_ROW_MAP[raw_row]);
}

byte positionRecordChecksum(unsigned int record_sequence, byte state,
                            byte coordinate_x, byte coordinate_y) {
  return 0x6D ^ lowByte(record_sequence) ^ highByte(record_sequence) ^
         POSITION_RECORD_VERSION ^ state ^ coordinate_x ^ coordinate_y;
}

boolean positionSequenceIsNewer(unsigned int candidate, unsigned int current) {
  return (int)((signed int)(candidate - current)) > 0;
}

boolean readPositionRecord(byte slot, unsigned int &record_sequence,
                           byte &state, byte &coordinate_x,
                           byte &coordinate_y) {
  int address = (int)slot * POSITION_RECORD_SIZE;
  if (EEPROM.read(address + 7) != POSITION_RECORD_COMMIT) return false;

  record_sequence = EEPROM.read(address) |
                    ((unsigned int)EEPROM.read(address + 1) << 8);
  byte version = EEPROM.read(address + 2);
  state = EEPROM.read(address + 3);
  coordinate_x = EEPROM.read(address + 4);
  coordinate_y = EEPROM.read(address + 5);
  byte checksum = EEPROM.read(address + 6);

  if (version != POSITION_RECORD_VERSION ||
      (state != POSITION_STATE_KNOWN && state != POSITION_STATE_UNKNOWN) ||
      checksum != positionRecordChecksum(record_sequence, state,
                                         coordinate_x, coordinate_y)) return false;
  if (state == POSITION_STATE_KNOWN &&
      (coordinate_x < 1 || coordinate_x > 8 ||
       coordinate_y < 1 || coordinate_y > 8)) return false;
  return true;
}

void loadPersistedTrolleyPosition() {
  boolean found = false;
  byte latest_state = POSITION_STATE_UNKNOWN;
  byte latest_x = 0;
  byte latest_y = 0;

  for (byte slot = 0; slot < POSITION_RECORD_SLOTS; slot++) {
    unsigned int record_sequence;
    byte state;
    byte coordinate_x;
    byte coordinate_y;
    if (!readPositionRecord(slot, record_sequence, state,
                            coordinate_x, coordinate_y)) continue;
    if (!found || positionSequenceIsNewer(record_sequence,
                                          position_record_sequence)) {
      found = true;
      position_record_slot = slot;
      position_record_sequence = record_sequence;
      latest_state = state;
      latest_x = coordinate_x;
      latest_y = coordinate_y;
    }
  }

  trolley_position_known = found && latest_state == POSITION_STATE_KNOWN;
  trolley_homed = false;
  if (trolley_position_known) {
    trolley_coordinate_X = latest_x;
    trolley_coordinate_Y = latest_y;
  }
}

void appendPositionRecord(byte state, byte coordinate_x, byte coordinate_y) {
  byte next_slot = position_record_slot < 0 ? 0 :
                   (position_record_slot + 1) % POSITION_RECORD_SLOTS;
  unsigned int next_sequence = position_record_slot < 0 ? 0 :
                               position_record_sequence + 1U;
  int address = (int)next_slot * POSITION_RECORD_SIZE;
  byte checksum = positionRecordChecksum(next_sequence, state,
                                         coordinate_x, coordinate_y);

  // Invalidate the destination first and commit it last. If power disappears
  // during this write, the previous complete journal entry remains newest.
  EEPROM.update(address + 7, 0);
  EEPROM.update(address, lowByte(next_sequence));
  EEPROM.update(address + 1, highByte(next_sequence));
  EEPROM.update(address + 2, POSITION_RECORD_VERSION);
  EEPROM.update(address + 3, state);
  EEPROM.update(address + 4, coordinate_x);
  EEPROM.update(address + 5, coordinate_y);
  EEPROM.update(address + 6, checksum);
  EEPROM.update(address + 7, POSITION_RECORD_COMMIT);

  position_record_slot = next_slot;
  position_record_sequence = next_sequence;
}

void markTrolleyPositionUnknown() {
  if (!trolley_position_known) return;
  appendPositionRecord(POSITION_STATE_UNKNOWN, 0, 0);
  trolley_position_known = false;
}

void rememberTrolleyPosition() {
  if (trolley_position_known) return;
  appendPositionRecord(POSITION_STATE_KNOWN,
                       trolley_coordinate_X, trolley_coordinate_Y);
  trolley_position_known = true;
}

void showPersistedTrolleyPosition() {
  lcd.clear();
  lcd.setCursor(0, 0);
  lcd.print(F("LAST HEAD: "));
  if (trolley_position_known) {
    lcd.print((char)('a' + trolley_coordinate_X - 1));
    lcd.print(trolley_coordinate_Y);
    lcd.setCursor(0, 1);
    lcd.print(F("CAL BEFORE USE"));
  }
  else {
    lcd.print(F("??"));
    lcd.setCursor(0, 1);
    lcd.print(F("POS UNKNOWN"));
  }
  delay(2500);
}

void showPositionRecovery() {
  lcd.clear();
  lcd.setCursor(0, 0);
  lcd.print(F("SET HEAD <= R6"));
  lcd.setCursor(0, 1);
  lcd.print(F("A=READY B=MENU"));
}

void requestCalibration(byte next_sequence) {
  after_calibration = next_sequence;
  calibration_lane_confirmed = false;
  if (trolley_position_known ||
      readControlPin(BUTTON_B_LIMIT_BLACK) == LOW) {
    sequence = calibration;
    showCalibration();
  }
  else {
    sequence = position_recovery;
    showPositionRecovery();
  }
}

void setup() {
  Serial.begin(9600);
  bluetoothInput.begin(9600);

  pinMode(MAGNET, OUTPUT);
  digitalWrite(MAGNET, LOW);

  pinMode(MOTOR_WHITE_STEP, OUTPUT);
  pinMode(MOTOR_WHITE_DIR, OUTPUT);
  pinMode(MOTOR_BLACK_STEP, OUTPUT);
  pinMode(MOTOR_BLACK_DIR, OUTPUT);
  digitalWrite(MOTOR_WHITE_STEP, LOW);
  digitalWrite(MOTOR_BLACK_STEP, LOW);

  for (byte i = 0; i < 4; i++) {
    pinMode(MUX_ADDR[i], OUTPUT);
    digitalWrite(MUX_ADDR[i], LOW);
    pinMode(MUX_SELECT[i], OUTPUT);
    digitalWrite(MUX_SELECT[i], HIGH);
  }
  pinMode(MUX_OUTPUT, INPUT_PULLUP);

  pinMode(BUTTON_A_LIMIT_WHITE, INPUT_PULLUP);
  // A6 has no digital input buffer or internal pull-up. The external 10 kOhm
  // pull-up makes a released switch read near 1023 and a pressed switch near 0.

  lcd.init();
  lcd.backlight();
  loadPersistedTrolleyPosition();
  showPersistedTrolleyPosition();
  AI_reset();
  scanSensors();
  syncSensorState();
  sequence = main_menu;
  showMainMenu();
  Serial.println(F("READY ACB1"));
}

void loop() {
  processHostSerial();

  switch (sequence) {
    case main_menu:
      if (buttonPressed(BUTTON_A_LIMIT_WHITE)) {
        AI_reset();
        resetMoveTracker();
        requestCalibration(setup_check);
      }
      else if (buttonPressed(BUTTON_B_LIMIT_BLACK)) {
        service_item = SERVICE_CALIBRATE;
        sequence = service_menu;
        showServiceMenu();
      }
      break;

    case position_recovery:
      if (buttonPressed(BUTTON_A_LIMIT_WHITE)) {
        calibration_lane_confirmed = true;
        sequence = calibration;
        showCalibration();
      }
      else if (buttonPressed(BUTTON_B_LIMIT_BLACK)) {
        returnToMainMenu();
      }
      break;

    case calibration:
      if (calibrateBoard()) {
        lcd.clear();
        lcd.print(F("CALIBRATION OK"));
        lcd.setCursor(0, 1);
        lcd.print(F("HEAD AT e6"));
        delay(1000);
        sequence = after_calibration;
        if (sequence == setup_check) showSetupCheck();
        else if (sequence == remote_setup_check) showRemoteSetupCheck();
        else showServiceMenu();
      }
      else {
        sequence = fault_screen;
        showMotionFault();
      }
      break;

    case setup_check:
      if (buttonPressed(BUTTON_A_LIMIT_WHITE)) {
        if (startingPositionIsValid()) {
          beginHumanTurn();
        }
        else {
          showStartingMismatch();
        }
      }
      else if (buttonPressed(BUTTON_B_LIMIT_BLACK)) {
        returnToMainMenu();
      }
      break;

    case player_white:
      updateSensorsAndTrackMove();
      if (human_move_ready && !pending_move_displayed) showPendingMove();
      if (buttonPressed(BUTTON_A_LIMIT_WHITE)) finishHumanTurn();
      break;

    case player_black:
      if (!blackPlayerMovement()) {
        sequence = fault_screen;
        showMotionFault();
      }
      else if (!physicalSensorsMatchExpected()) {
        sequence = ai_sensor_check;
        showAiSensorMismatch();
      }
      else {
        syncSensorState();
        beginHumanTurn();
      }
      break;

    case undo_required:
      scanSensors();
      if (recordMatchesTurnStart()) {
        syncSensorState();
        beginHumanTurn();
      }
      else if (buttonPressed(BUTTON_B_LIMIT_BLACK)) {
        returnToMainMenu();
      }
      break;

    case ai_sensor_check:
      if (buttonPressed(BUTTON_A_LIMIT_WHITE)) {
        if (physicalSensorsMatchExpected()) {
          syncSensorState();
          beginHumanTurn();
        }
        else showAiSensorMismatch();
      }
      else if (buttonPressed(BUTTON_B_LIMIT_BLACK)) {
        returnToMainMenu();
      }
      break;

    case game_over_screen:
      if (buttonPressed(BUTTON_B_LIMIT_BLACK) || buttonPressed(BUTTON_A_LIMIT_WHITE)) {
        returnToMainMenu();
      }
      break;

    case fault_screen:
      if (buttonPressed(BUTTON_A_LIMIT_WHITE)) {
        requestCalibration(service_menu);
      }
      else if (buttonPressed(BUTTON_B_LIMIT_BLACK)) {
        returnToMainMenu();
      }
      break;

    case service_menu:
      serviceMenuLoop();
      break;

    case service_sensors:
      serviceSensorLoop();
      break;

    case service_move_file:
      serviceMoveFileLoop();
      break;

    case service_move_rank:
      serviceMoveRankLoop();
      break;

    case remote_setup_check:
      if (buttonPressed(BUTTON_A_LIMIT_WHITE)) {
        if (startingPositionIsValid()) beginRemoteSession();
        else showStartingMismatch();
      }
      else if (buttonPressed(BUTTON_B_LIMIT_BLACK)) {
        stopRemoteSession();
      }
      break;

    case remote_human:
      updateSensorsAndTrackMove();
      if (human_move_ready && !pending_move_displayed) showRemotePendingMove();
      if (buttonPressed(BUTTON_B_LIMIT_BLACK)) stopRemoteSession();
      else if (buttonPressed(BUTTON_A_LIMIT_WHITE)) finishRemoteHumanTurn();
      break;

    case remote_wait_host:
      if (buttonPressed(BUTTON_B_LIMIT_BLACK)) stopRemoteSession();
      break;

    case remote_undo_required:
      scanSensors();
      if (recordMatchesTurnStart()) {
        syncSensorState();
        beginRemoteHumanTurn();
      }
      else if (buttonPressed(BUTTON_B_LIMIT_BLACK)) {
        stopRemoteSession();
      }
      break;

    case remote_sensor_check:
      if (buttonPressed(BUTTON_A_LIMIT_WHITE)) {
        if (physicalSensorsMatchExpected()) {
          syncSensorState();
          finishRemoteComputerTurn();
        }
        else showRemoteSensorMismatch();
      }
      else if (buttonPressed(BUTTON_B_LIMIT_BLACK)) {
        stopRemoteSession();
      }
      break;

    case remote_promotion_wait:
      if (buttonPressed(BUTTON_A_LIMIT_WHITE)) {
        Serial.println(F("PROMOTION OK"));
        beginRemoteHumanTurn();
      }
      else if (buttonPressed(BUTTON_B_LIMIT_BLACK)) {
        stopRemoteSession();
      }
      break;
  }
}

// -------------------------- Windows host protocol -----------------------

void sendHostError(const __FlashStringHelper *message) {
  Serial.print(F("ERR "));
  Serial.println(message);
}

void sendHostStatus() {
  Serial.print(F("STATUS ACB1 "));
  Serial.print(sequence);
  Serial.print(' ');
  Serial.print(trolley_homed ? 1 : 0);
  Serial.print(' ');
  Serial.println(remote_mode ? 1 : 0);
}

int freeRam() {
  extern int __heap_start, *__brkval;
  int stack_top;
  return (int)&stack_top - (__brkval ? (int)__brkval : (int)&__heap_start);
}

void sendHostInfo() {
  Serial.print(F("INFO ACB2 "));
  Serial.print(F(FIRMWARE_VERSION));
  Serial.println(F(" BOARD,TELEM,REMOTE,ESTOP,BTTEST"));
}

void sendTelemetry() {
  int black_raw = analogRead(BUTTON_B_LIMIT_BLACK);
  Serial.print(F("TELEM ACB2 "));
  Serial.print(sequence);
  Serial.print(' ');
  Serial.print(trolley_homed ? 1 : 0);
  Serial.print(' ');
  Serial.print(remote_mode ? 1 : 0);
  Serial.print(' ');
  Serial.print(motion_fault ? 1 : 0);
  Serial.print(' ');
  Serial.print(magnet_state ? 1 : 0);
  Serial.print(' ');
  Serial.print(trolley_coordinate_X);
  Serial.print(' ');
  Serial.print(trolley_coordinate_Y);
  Serial.print(' ');
  Serial.print(readControlPin(BUTTON_A_LIMIT_WHITE) == HIGH ? 1 : 0);
  Serial.print(' ');
  Serial.print(black_raw >= 512 ? 1 : 0);
  Serial.print(' ');
  Serial.print(black_raw);
  Serial.print(' ');
  Serial.print(freeRam());
  Serial.print(' ');
  Serial.println(millis() / 1000UL);
}

void sendSensorSnapshot() {
  scanSensors();
  Serial.print(F("BOARD "));
  const char hex[] = "0123456789ABCDEF";
  for (byte row = 0; row < 8; row++) {
    byte occupied = 0;
    for (byte column = 0; column < 8; column++) {
      if (reed_sensor_record[row][column] == LOW) occupied |= 1 << column;
    }
    Serial.print(hex[occupied >> 4]);
    Serial.print(hex[occupied & 15]);
  }
  Serial.println();
}

void testBluetoothModule() {
  // HC-08 accepts AT only while it is not connected. This diagnostic sends no
  // newline to the module, captures its short reply, then wraps it for USB.
  while (bluetoothInput.available()) bluetoothInput.read();
  Serial.print(F("AT"));
  Serial.flush();
  delay(300);

  char reply[13];
  byte reply_length = 0;
  while (bluetoothInput.available() && reply_length < sizeof(reply) - 1) {
    char value = bluetoothInput.read();
    if (value >= 32 && value <= 126) reply[reply_length++] = value;
  }
  reply[reply_length] = 0;
  Serial.println();
  Serial.print(F("BT "));
  if (reply_length) Serial.println(reply);
  else Serial.println(F("NO REPLY"));
}

void startRemoteSession(boolean human_white) {
  if (sequence != main_menu) {
    sendHostError(F("BUSY"));
    return;
  }

  remote_mode = true;
  remote_human_white = human_white;
  remote_human_move_pending = false;
  remote_promotion_piece = 0;
  AI_reset();
  resetMoveTracker();
  Serial.print(F("OK START "));
  Serial.println(remote_human_white ? 'W' : 'B');
  requestCalibration(remote_setup_check);
}

void stopRemoteSession() {
  returnToMainMenu();
  Serial.println(F("STOPPED"));
}

void processHostCommand(char *line) {
  if (strcmp(line, "PING") == 0 || strcmp(line, "HELLO") == 0) {
    Serial.println(F("PONG ACB1"));
    return;
  }
  if (strcmp(line, "INFO") == 0) {
    sendHostInfo();
    return;
  }
  if (strcmp(line, "TELEM") == 0) {
    sendTelemetry();
    return;
  }
  if (strcmp(line, "STATUS") == 0) {
    sendHostStatus();
    return;
  }
  if (strcmp(line, "BOARD") == 0) {
    sendSensorSnapshot();
    return;
  }
  if (strcmp(line, "BTTEST") == 0) {
    if (sequence == main_menu) testBluetoothModule();
    else sendHostError(F("BUSY"));
    return;
  }
  if (strcmp(line, "STOP") == 0) {
    stopRemoteSession();
    return;
  }
  if (strncmp(line, "START ", 6) == 0 &&
      (line[6] == 'W' || line[6] == 'B') && line[7] == 0) {
    startRemoteSession(line[6] == 'W');
    return;
  }
  if (strcmp(line, "ACCEPT") == 0) {
    if (!remote_mode || sequence != remote_wait_host ||
        !remote_human_move_pending) {
      sendHostError(F("NO MOVE"));
      return;
    }
    remote_human_move_pending = false;
    lcd.clear();
    lcd.print(F("HOST THINKING"));
    lcd.setCursor(0, 1);
    lcd.print(F("B=STOP"));
    Serial.println(F("OK ACCEPT"));
    return;
  }
  if (strcmp(line, "REJECT") == 0) {
    if (!remote_mode || sequence != remote_wait_host ||
        !remote_human_move_pending) {
      sendHostError(F("NO MOVE"));
      return;
    }
    remote_human_move_pending = false;
    sequence = remote_undo_required;
    lcd.clear();
    lcd.print(F("INVALID MOVE"));
    lcd.setCursor(0, 1);
    lcd.print(F("UNDO B=STOP"));
    Serial.println(F("OK REJECT"));
    return;
  }
  if (strncmp(line, "PLAY ", 5) == 0) {
    if (!remote_mode || sequence != remote_wait_host) {
      sendHostError(F("NOT READY"));
      return;
    }
    if (remote_human_move_pending) {
      sendHostError(F("ACCEPT FIRST"));
      return;
    }

    char *move_text = line + 5;
    char *flag_text = strchr(move_text, ' ');
    if (flag_text) {
      *flag_text++ = 0;
      remote_move_flags = *flag_text;
    }
    else remote_move_flags = 0;

    if (!validMoveText(move_text)) {
      sendHostError(F("BAD MOVE"));
      return;
    }
    for (byte i = 0; i < 4; i++) lastM[i] = move_text[i];
    lastM[4] = 0;
    remote_promotion_piece = move_text[4];
    if (remote_promotion_piece == 'q' || remote_promotion_piece == 'r' ||
        remote_promotion_piece == 'b' || remote_promotion_piece == 'n') {
      remote_move_flags = 'P';
    }
    else remote_promotion_piece = 0;
    executeRemoteComputerMove();
    return;
  }
  if (strncmp(line, "GAMEOVER", 8) == 0) {
    if (!remote_mode) {
      sendHostError(F("NO SESSION"));
      return;
    }
    sequence = game_over_screen;
    lcd.clear();
    lcd.print(F("GAME OVER"));
    if (line[8] == ' ') {
      lcd.setCursor(0, 1);
      lcd.print(line + 9);
    }
    Serial.println(F("OK GAMEOVER"));
    return;
  }
  sendHostError(F("COMMAND"));
}

void processHostStream(Stream &input) {
  while (input.available()) {
    char value = input.read();
    if (value == '!') {
      host_input_length = 0;
      tripRemoteEmergencyStop();
      continue;
    }
    if (value == '\r' || value == '\n') {
      if (host_input_length == 0) continue;
      host_input[host_input_length] = 0;
      processHostCommand(host_input);
      host_input_length = 0;
    }
    else if (value >= 32 && value <= 126) {
      if (host_input_length < HOST_INPUT_SIZE - 1) {
        host_input[host_input_length++] = value;
      }
      else {
        host_input_length = 0;
        sendHostError(F("LINE LONG"));
      }
    }
  }
}

void processHostSerial() {
  processHostStream(Serial);
  processHostStream(bluetoothInput);
}

void showRemoteSetupCheck() {
  lcd.clear();
  lcd.print(F("SET START PIECES"));
  lcd.setCursor(0, 1);
  lcd.print(F("A=READY B=STOP"));
  Serial.println(F("SETUP PRESS A"));
}

void beginRemoteSession() {
  Serial.print(F("SESSION "));
  Serial.println(remote_human_white ? 'W' : 'B');
  if (remote_human_white) beginRemoteHumanTurn();
  else {
    sequence = remote_wait_host;
    lcd.clear();
    lcd.print(F("HOST MOVE"));
    lcd.setCursor(0, 1);
    lcd.print(F("B=STOP"));
    Serial.println(F("TURN COMPUTER"));
  }
}

void beginRemoteHumanTurn() {
  copySensorTable(reed_sensor_status, turn_start_status);
  resetMoveTracker();
  remote_human_move_pending = false;
  sequence = remote_human;
  lcd.clear();
  lcd.print(F("YOUR MOVE"));
  lcd.setCursor(0, 1);
  lcd.print(F("A=SEND B=STOP"));
  Serial.println(F("TURN HUMAN"));
}

void showRemotePendingMove() {
  pending_move_displayed = true;
  lcd.clear();
  lcd.print(F("SEND "));
  printSquare(move_from);
  lcd.print('-');
  printSquare(move_to);
  lcd.setCursor(0, 1);
  lcd.print(F("A=SEND B=STOP"));
}

void finishRemoteHumanTurn() {
  if (sensor_tracking_error) {
    sequence = remote_undo_required;
    lcd.clear();
    lcd.print(F("TOO MANY CHANGES"));
    lcd.setCursor(0, 1);
    lcd.print(F("UNDO B=STOP"));
    Serial.println(F("ERR TRACKING"));
    return;
  }
  if (!human_move_ready) {
    lcd.clear();
    lcd.print(F("MOVE NOT READY"));
    lcd.setCursor(0, 1);
    lcd.print(F("LIFT THEN PLACE"));
    delay(1000);
    lcd.clear();
    lcd.print(F("YOUR MOVE"));
    lcd.setCursor(0, 1);
    lcd.print(F("A=SEND B=STOP"));
    return;
  }

  squareToMoveChars(move_from, move_to);
  remote_human_move_pending = true;
  sequence = remote_wait_host;
  lcd.clear();
  lcd.print(F("CHECKING "));
  printMove(mov);
  lcd.setCursor(0, 1);
  lcd.print(F("B=STOP"));
  Serial.print(F("MOVE "));
  Serial.println(mov);
}

void showRemoteSensorMismatch() {
  lcd.clear();
  lcd.print(F("CHECK MOVED PIECE"));
  lcd.setCursor(0, 1);
  lcd.print(F("A=RETRY B=STOP"));
}

void executeRemoteComputerMove() {
  lcd.clear();
  lcd.print(F("PC MOVE "));
  printMove(lastM);
  lcd.setCursor(0, 1);
  lcd.print(F("KEEP HANDS CLEAR"));
  Serial.print(F("MOVING "));
  Serial.println(lastM);
  delay(500);

  if (!computerPlayerMovement(lastM, remote_move_flags)) {
    sequence = fault_screen;
    showMotionFault();
    sendHostError(F("MOTION"));
    return;
  }
  if (!physicalSensorsMatchExpected()) {
    sequence = remote_sensor_check;
    showRemoteSensorMismatch();
    sendHostError(F("SENSORS"));
    return;
  }
  syncSensorState();
  finishRemoteComputerTurn();
}

void finishRemoteComputerTurn() {
  Serial.print(F("DONE "));
  Serial.println(lastM);
  if (remote_move_flags == 'P') {
    sequence = remote_promotion_wait;
    lcd.clear();
    lcd.print(F("REPLACE PAWN "));
    lcd.print((char)toupper(remote_promotion_piece));
    lcd.setCursor(0, 1);
    lcd.print(F("A=READY B=STOP"));
    Serial.print(F("PROMOTE "));
    Serial.println(remote_promotion_piece);
  }
  else beginRemoteHumanTurn();
}

// Waits for release so the same input can become an emergency stop during motion.
byte readControlPin(byte pin) {
  if (pin == BUTTON_B_LIMIT_BLACK) return analogRead(pin) >= 512 ? HIGH : LOW;
  return digitalRead(pin);
}

boolean buttonPressed(byte pin) {
  if (readControlPin(pin) == HIGH) return false;
  delay(20);
  if (readControlPin(pin) == HIGH) return false;

  unsigned long started = millis();
  while (readControlPin(pin) == LOW) {
    if (millis() - started > 2000UL) return false;
  }
  delay(20);
  return true;
}

void returnToMainMenu() {
  setMagnet(false);
  motion_fault = false;
  remote_stop_requested = false;
  remote_mode = false;
  remote_human_move_pending = false;
  AI_reset();
  sequence = main_menu;
  showMainMenu();
}

void showMainMenu() {
  lcd.clear();
  lcd.setCursor(0, 0);
  lcd.print(F("A:GAME  B:TEST"));
  lcd.setCursor(0, 1);
  lcd.print(F("HUMAN vs AI"));
}

void showCalibration() {
  lcd.clear();
  lcd.setCursor(0, 0);
  lcd.print(F("CALIBRATING"));
  lcd.setCursor(0, 1);
  lcd.print(F("KEEP HANDS CLEAR"));
}

void showSetupCheck() {
  lcd.clear();
  lcd.setCursor(0, 0);
  lcd.print(F("SET START PIECES"));
  lcd.setCursor(0, 1);
  lcd.print(F("A=CHECK B=MENU"));
}

void showStartingMismatch() {
  byte occupied = countOccupied(reed_sensor_record);
  lcd.clear();
  lcd.setCursor(0, 0);
  lcd.print(F("START MISMATCH"));
  lcd.setCursor(0, 1);
  lcd.print(F("FOUND:"));
  printTwoDigits(occupied);
  lcd.print(F(" A=TRY"));
}

void showMotionFault() {
  setMagnet(false);
  lcd.clear();
  lcd.setCursor(0, 0);
  lcd.print(F("MOTION STOPPED"));
  lcd.setCursor(0, 1);
  lcd.print(F("A=CAL  B=MENU"));
}

void showCalibrationReferenceFault() {
  lcd.clear();
  lcd.print(F("HOME TEST FAIL"));
  lcd.setCursor(0, 1);
  lcd.print(F("CAL REQUIRED"));
}

void showAiSensorMismatch() {
  lcd.clear();
  lcd.setCursor(0, 0);
  lcd.print(F("CHECK AI PIECE"));
  lcd.setCursor(0, 1);
  lcd.print(F("A=RETRY B=MENU"));
}

void showPendingMove() {
  pending_move_displayed = true;
  lcd.clear();
  lcd.setCursor(0, 0);
  lcd.print(F("MOVE "));
  printSquare(move_from);
  lcd.print('-');
  printSquare(move_to);
  lcd.setCursor(0, 1);
  lcd.print(F("A=END TURN"));
}

void beginHumanTurn() {
  copySensorTable(reed_sensor_status, turn_start_status);
  resetMoveTracker();
  sequence = player_white;
  lcd.clear();
  lcd.setCursor(0, 0);
  lcd.print(F("YOUR MOVE"));
  lcd.setCursor(0, 1);
  lcd.print(F("A=END TURN"));
}

void finishHumanTurn() {
  if (sensor_tracking_error) {
    sequence = undo_required;
    lcd.clear();
    lcd.print(F("TOO MANY CHANGES"));
    lcd.setCursor(0, 1);
    lcd.print(F("UNDO B=MENU"));
    return;
  }

  if (!human_move_ready) {
    lcd.clear();
    lcd.print(F("MOVE NOT READY"));
    lcd.setCursor(0, 1);
    lcd.print(F("LIFT THEN PLACE"));
    delay(1200);
    lcd.clear();
    lcd.print(F("YOUR MOVE"));
    lcd.setCursor(0, 1);
    lcd.print(F("A=END TURN"));
    return;
  }

  squareToMoveChars(move_from, move_to);
  lcd.clear();
  lcd.print(F("AI THINKING"));
  lcd.setCursor(0, 1);
  printMove(mov);

  byte ai_result = AI_HvsC();
  if (ai_result == AI_INVALID_MOVE) {
    sequence = undo_required;
    lcd.clear();
    lcd.print(F("INVALID MOVE"));
    lcd.setCursor(0, 1);
    lcd.print(F("UNDO B=MENU"));
    return;
  }
  if (ai_result == AI_GAME_OVER) {
    sequence = game_over_screen;
    lcd.clear();
    lcd.print(F("GAME OVER"));
    lcd.setCursor(0, 1);
    lcd.print(F("A/B=NEW GAME"));
    return;
  }

  lcd.clear();
  lcd.print(F("AI MOVE "));
  printMove(lastM);
  lcd.setCursor(0, 1);
  lcd.print(F("KEEP HANDS CLEAR"));
  delay(800);
  sequence = player_black;
}

void printMove(const char *move_text) {
  for (byte i = 0; i < 4; i++) lcd.print(move_text[i]);
}

void printSquare(byte square) {
  if (square == NO_SQUARE) {
    lcd.print(F("--"));
    return;
  }
  lcd.print((char)('a' + (square & 7)));
  lcd.print((char)('8' - (square >> 3)));
}

void printTwoDigits(byte value) {
  if (value < 10) lcd.print('0');
  lcd.print(value);
}

void squareToMoveChars(byte from, byte to) {
  mov[0] = 'a' + (from & 7);
  mov[1] = '8' - (from >> 3);
  mov[2] = 'a' + (to & 7);
  mov[3] = '8' - (to >> 3);
  mov[4] = 0;
}

boolean validMoveText(const char *move_text) {
  return move_text[0] >= 'a' && move_text[0] <= 'h' &&
         move_text[1] >= '1' && move_text[1] <= '8' &&
         move_text[2] >= 'a' && move_text[2] <= 'h' &&
         move_text[3] >= '1' && move_text[3] <= '8';
}

// ---------------------------- Sensor handling ----------------------------

void scanSensors() {
  byte column = 6;
  byte row = 0;

  for (byte mux = 0; mux < 4; mux++) {
    digitalWrite(MUX_SELECT[mux], LOW);
    for (byte channel = 0; channel < 16; channel++) {
      for (byte bit_number = 0; bit_number < 4; bit_number++) {
        digitalWrite(MUX_ADDR[bit_number], (channel >> bit_number) & 1);
      }
      // Three-sample majority filtering rejects short multiplexer/reed glitches.
      delayMicroseconds(20);
      byte high_votes = digitalRead(MUX_OUTPUT);
      delayMicroseconds(30);
      high_votes += digitalRead(MUX_OUTPUT);
      delayMicroseconds(30);
      high_votes += digitalRead(MUX_OUTPUT);
      byte raw_row = 7 - column;
      reed_sensor_record[logicalSensorRow(raw_row)][row] =
          high_votes >= 2 ? HIGH : LOW;
      row++;
      if (channel == 7) {
        column++;
        row = 0;
      }
    }
    digitalWrite(MUX_SELECT[mux], HIGH);
    if (mux == 0) column = 4;
    else if (mux == 1) column = 2;
    else if (mux == 2) column = 0;
    row = 0;
  }
}

void syncSensorState() {
  copySensorTable(reed_sensor_record, reed_sensor_status);
  copySensorTable(reed_sensor_record, turn_start_status);
  resetMoveTracker();
}

void copySensorTable(byte source[8][8], byte destination[8][8]) {
  for (byte row = 0; row < 8; row++) {
    for (byte column = 0; column < 8; column++) {
      destination[row][column] = source[row][column];
    }
  }
}

byte countOccupied(byte table[8][8]) {
  byte occupied = 0;
  for (byte row = 0; row < 8; row++) {
    for (byte column = 0; column < 8; column++) {
      if (table[row][column] == LOW) occupied++;
    }
  }
  return occupied;
}

boolean startingPositionIsValid() {
  scanSensors();
  boolean valid = true;
  for (byte row = 0; row < 8; row++) {
    for (byte column = 0; column < 8; column++) {
      byte expected = (row < 2 || row > 5) ? LOW : HIGH;
      if (reed_sensor_record[row][column] != expected) valid = false;
    }
  }
  if (valid) syncSensorState();
  return valid;
}

boolean physicalSensorsMatchExpected() {
  scanSensors();
  for (byte row = 0; row < 8; row++) {
    for (byte column = 0; column < 8; column++) {
      if (reed_sensor_record[row][column] != reed_sensor_status[row][column]) return false;
    }
  }
  return true;
}

boolean recordMatchesTurnStart() {
  for (byte row = 0; row < 8; row++) {
    for (byte column = 0; column < 8; column++) {
      if (reed_sensor_record[row][column] != turn_start_status[row][column]) return false;
    }
  }
  return true;
}

void resetMoveTracker() {
  lifted_squares[0] = NO_SQUARE;
  lifted_squares[1] = NO_SQUARE;
  lifted_count = 0;
  move_from = NO_SQUARE;
  move_to = NO_SQUARE;
  human_move_ready = false;
  sensor_tracking_error = false;
  pending_move_displayed = false;
}

void updateSensorsAndTrackMove() {
  scanSensors();
  for (byte row = 0; row < 8; row++) {
    for (byte column = 0; column < 8; column++) {
      byte old_state = reed_sensor_status[row][column];
      byte new_state = reed_sensor_record[row][column];
      if (old_state == new_state) continue;

      byte square = row * 8 + column;
      last_sensor_square = square;
      last_sensor_occupied = (new_state == LOW);

      if (!human_move_ready) {
        if (old_state == LOW && new_state == HIGH) recordLift(square);
        else if (old_state == HIGH && new_state == LOW) recordPlacement(square);
      }
      reed_sensor_status[row][column] = new_state;
    }
  }
}

void recordLift(byte square) {
  for (byte i = 0; i < lifted_count; i++) {
    if (lifted_squares[i] == square) return;
  }
  if (lifted_count >= 2) {
    sensor_tracking_error = true;
    return;
  }
  lifted_squares[lifted_count++] = square;
}

void recordPlacement(byte destination) {
  if (lifted_count == 0) return;

  byte source = NO_SQUARE;
  // Captures are performed by lifting the moving piece first. Prefer that
  // first lift so en-passant (whose captured pawn is not on the destination)
  // is reported correctly; fall back for a lift-and-replace gesture.
  for (byte i = 0; i < lifted_count; i++) {
    byte candidate = lifted_squares[i];
    if (candidate != destination) {
      source = candidate;
      break;
    }
  }
  if (source == NO_SQUARE) return;

  move_from = source;
  move_to = destination;
  human_move_ready = true;
}

// ---------------------------- Calibration/motion -------------------------

void configureMotorDirection(byte direction) {
  digitalWrite(MOTOR_WHITE_DIR,
               (direction == R_L || direction == T_B || direction == RL_TB) ? HIGH : LOW);
  digitalWrite(MOTOR_BLACK_DIR,
               (direction == B_T || direction == R_L || direction == RL_BT) ? HIGH : LOW);
}

boolean isDiagonalDirection(byte direction) {
  return direction == LR_BT || direction == RL_TB ||
         direction == LR_TB || direction == RL_BT;
}

unsigned int motorStepDelay(unsigned int cruise_delay, unsigned int step_index,
                            unsigned int total_steps) {
  if (cruise_delay >= MOTOR_START_DELAY || total_steps < 2) return cruise_delay;

  unsigned int ramp_steps = min(MOTOR_RAMP_STEPS, total_steps / 2U);
  if (ramp_steps == 0) return cruise_delay;

  unsigned int steps_from_end = total_steps - step_index - 1U;
  unsigned int edge_distance = min(step_index, steps_from_end);
  if (edge_distance >= ramp_steps) return cruise_delay;

  unsigned long delay_range = MOTOR_START_DELAY - cruise_delay;
  return MOTOR_START_DELAY - (unsigned int)(delay_range * edge_distance / ramp_steps);
}

void tripRemoteEmergencyStop() {
  if (remote_stop_requested) return;
  remote_stop_requested = true;
  motion_fault = true;
  trolley_homed = false;
  setMagnet(false);
  sequence = fault_screen;
  showMotionFault();
  Serial.println(F("ESTOP REMOTE"));
}

boolean drainForEmergencyStop(Stream &input) {
  boolean requested = false;
  while (input.available()) {
    if (input.read() == '!') requested = true;
  }
  return requested;
}

boolean pollRemoteEmergencyStop() {
  if (remote_stop_requested) return true;
  boolean requested = drainForEmergencyStop(Serial);
  if (drainForEmergencyStop(bluetoothInput)) requested = true;
  if (!requested) return false;
  tripRemoteEmergencyStop();
  return true;
}

boolean pulseMotor(byte direction, unsigned int speed_delay, unsigned int steps, boolean monitor_stops) {
  if (monitor_stops && motion_fault) return false;
  if (steps == 0) return true;
  markTrolleyPositionUnknown();
  configureMotorDirection(direction);
  delayMicroseconds(2);

  for (unsigned int step_count = 0; step_count < steps; step_count++) {
    if ((step_count & 7) == 0 && pollRemoteEmergencyStop()) return false;
    if (monitor_stops &&
        (readControlPin(BUTTON_A_LIMIT_WHITE) == LOW ||
         readControlPin(BUTTON_B_LIMIT_BLACK) == LOW)) {
      motion_fault = true;
      trolley_homed = false;
      setMagnet(false);
      return false;
    }

    unsigned int current_delay = motorStepDelay(speed_delay, step_count, steps);
    unsigned int low_time = current_delay * 2U - MOTOR_STEP_PULSE_US;

    digitalWrite(MOTOR_WHITE_STEP,
                 (direction == LR_TB || direction == RL_BT) ? LOW : HIGH);
    digitalWrite(MOTOR_BLACK_STEP,
                 (direction == LR_BT || direction == RL_TB) ? LOW : HIGH);
    delayMicroseconds(MOTOR_STEP_PULSE_US);
    digitalWrite(MOTOR_WHITE_STEP, LOW);
    digitalWrite(MOTOR_BLACK_STEP, LOW);
    delayMicroseconds(low_time);
  }
  return true;
}

boolean pulseCoreXYLine(int delta_x_steps, int delta_y_steps,
                        unsigned int speed_delay, boolean monitor_stops) {
  if (monitor_stops && motion_fault) return false;

  // CoreXY transform for the existing motor polarity:
  // white = +X -Y, black = -X -Y.
  long white_delta = (long)delta_x_steps - delta_y_steps;
  long black_delta = -(long)delta_x_steps - delta_y_steps;
  unsigned int white_steps =
      (unsigned int)(white_delta < 0 ? -white_delta : white_delta);
  unsigned int black_steps =
      (unsigned int)(black_delta < 0 ? -black_delta : black_delta);
  unsigned int event_count = max(white_steps, black_steps);
  if (event_count == 0) return true;

  markTrolleyPositionUnknown();
  digitalWrite(MOTOR_WHITE_DIR, white_delta >= 0 ? HIGH : LOW);
  digitalWrite(MOTOR_BLACK_DIR, black_delta >= 0 ? HIGH : LOW);
  delayMicroseconds(2);

  unsigned long white_accumulator = 0;
  unsigned long black_accumulator = 0;
  for (unsigned int event = 0; event < event_count; event++) {
    if ((event & 7) == 0 && pollRemoteEmergencyStop()) return false;
    if (monitor_stops &&
        (readControlPin(BUTTON_A_LIMIT_WHITE) == LOW ||
         readControlPin(BUTTON_B_LIMIT_BLACK) == LOW)) {
      motion_fault = true;
      trolley_homed = false;
      setMagnet(false);
      return false;
    }

    white_accumulator += white_steps;
    black_accumulator += black_steps;
    boolean step_white = white_accumulator >= event_count;
    boolean step_black = black_accumulator >= event_count;
    if (step_white) white_accumulator -= event_count;
    if (step_black) black_accumulator -= event_count;

    unsigned int current_delay = motorStepDelay(speed_delay, event, event_count);
    unsigned int low_time = current_delay * 2U - MOTOR_STEP_PULSE_US;
    digitalWrite(MOTOR_WHITE_STEP, step_white ? HIGH : LOW);
    digitalWrite(MOTOR_BLACK_STEP, step_black ? HIGH : LOW);
    delayMicroseconds(MOTOR_STEP_PULSE_US);
    digitalWrite(MOTOR_WHITE_STEP, LOW);
    digitalWrite(MOTOR_BLACK_STEP, LOW);
    delayMicroseconds(low_time);
  }
  return true;
}

int roundedDivide(long value, long divisor) {
  return (int)(value >= 0 ? (value + divisor / 2) / divisor
                          : (value - divisor / 2) / divisor);
}

boolean pulseCoreXYCurve(int end_x, int end_y, int control1_x, int control1_y,
                         int control2_x, int control2_y,
                         unsigned int speed_delay, boolean monitor_stops) {
  const byte segments = 12;
  const long denominator = (long)segments * segments * segments;
  int current_x = 0;
  int current_y = 0;

  for (byte i = 1; i <= segments; i++) {
    long u = segments - i;
    long x = 3L * u * u * i * control1_x +
             3L * u * i * i * control2_x + (long)i * i * i * end_x;
    long y = 3L * u * u * i * control1_y +
             3L * u * i * i * control2_y + (long)i * i * i * end_y;
    int target_x = roundedDivide(x, denominator);
    int target_y = roundedDivide(y, denominator);
    if (!pulseCoreXYLine(target_x - current_x, target_y - current_y,
                         speed_delay, monitor_stops)) return false;
    current_x = target_x;
    current_y = target_y;
  }
  return current_x == end_x && current_y == end_y;
}

boolean moveTrolleyStraightTo(byte target_x, byte target_y,
                              unsigned int speed_delay) {
  if (!trolley_homed || target_x < 1 || target_x > 8 ||
      target_y < 1 || target_y > 8) {
    motion_fault = true;
    setMagnet(false);
    return false;
  }

  int delta_x = ((int)target_x - trolley_coordinate_X) * (int)SQUARE_SIZE;
  int delta_y = ((int)target_y - trolley_coordinate_Y) * (int)SQUARE_SIZE;
  if (!pulseCoreXYLine(delta_x, delta_y, speed_delay, true)) return false;
  trolley_coordinate_X = target_x;
  trolley_coordinate_Y = target_y;
  rememberTrolleyPosition();
  return true;
}

// A legal sliding move has a clear direct corridor, so the shortest straight
// path gives the weakest magnet the least lateral acceleration. Knights use a
// smooth S-curve through the center of their L-shaped clearance corridor.
boolean moveHeldPieceSmooth(byte from_x, byte from_y, byte to_x, byte to_y) {
  int dx = ((int)to_x - from_x) * (int)SQUARE_SIZE;
  int dy = ((int)to_y - from_y) * (int)SQUARE_SIZE;
  byte squares_x = abs((int)to_x - from_x);
  byte squares_y = abs((int)to_y - from_y);
  boolean ok;

  if (squares_x == 1 && squares_y == 2) {
    ok = pulseCoreXYCurve(dx, dy, dx / 2, 0, dx / 2, dy,
                          SPEED_SLOW, true);
  }
  else if (squares_x == 2 && squares_y == 1) {
    ok = pulseCoreXYCurve(dx, dy, 0, dy / 2, dx, dy / 2,
                          SPEED_SLOW, true);
  }
  else {
    ok = pulseCoreXYLine(dx, dy, SPEED_SLOW, true);
  }

  if (!ok) return false;
  trolley_coordinate_X = to_x;
  trolley_coordinate_Y = to_y;
  rememberTrolleyPosition();
  return true;
}

boolean motor(byte direction, unsigned int speed_delay, float distance, boolean monitor_stops) {
  if (distance < 0.0 || distance > 8.5) {
    motion_fault = true;
    trolley_homed = false;
    setMagnet(false);
    return false;
  }

  // In CoreXY kinematics a board diagonal is driven by one motor. That motor
  // must make twice as many steps as either motor makes for a cardinal move.
  float multiplier = isDiagonalDirection(direction) ? 2.0 : 1.0;
  unsigned int steps = (unsigned int)(distance * SQUARE_SIZE * multiplier + 0.5);
  return pulseMotor(direction, speed_delay, steps, monitor_stops);
}

boolean prepareFirstCalibrationApproach() {
  // Never seek the first (white) switch while the head is in the lane that
  // leads to the second (black) switch at the calibration corner.
  if (readControlPin(BUTTON_B_LIMIT_BLACK) == LOW) {
    calibration_lane_confirmed = false;
    trolley_homed = false;
    if (!pulseMotor(R_L, SPEED_SLOW,
                    CALIBRATION_LANE_CLEARANCE_STEPS, false)) return false;
    return readControlPin(BUTTON_B_LIMIT_BLACK) == HIGH;
  }

  // A coordinate restored from EEPROM is enough for this one safe staging
  // move, but it does not mark the trolley homed for normal chess movement.
  // Only move away from the corner; never pre-stage toward the second switch.
  if (trolley_position_known &&
      trolley_coordinate_Y > CALIBRATION_PARK_RANK) {
    int delta_y = ((int)CALIBRATION_PARK_RANK - trolley_coordinate_Y) *
                  (int)SQUARE_SIZE;
    if (!pulseCoreXYLine(0, delta_y, SPEED_FAST, false)) return false;
    trolley_coordinate_Y = CALIBRATION_PARK_RANK;
    rememberTrolleyPosition();
  }
  if (trolley_position_known) {
    calibration_lane_confirmed = false;
    return true;
  }
  if (!calibration_lane_confirmed) return false;
  calibration_lane_confirmed = false;
  return true;
}

boolean homeAxisMeasured(byte direction, byte limit_pin,
                         unsigned int &measured_steps) {
  measured_steps = 0;
  while (readControlPin(limit_pin) == HIGH && measured_steps < HOME_MAX_STEPS) {
    if (!pulseMotor(direction, SPEED_SLOW, 1, false)) return false;
    measured_steps++;
  }
  return readControlPin(limit_pin) == LOW;
}

boolean moveCalibrationCornerToPark() {
  // Both switches stay pressed until the corner is fully measured. Move
  // directly from that repeatable corner to the exact e6 parking offset.
  if (!motor(R_L, SPEED_FAST, CALIBRATION_PARK_BLACK_OFFSET, false)) return false;
  if (!motor(T_B, SPEED_FAST, CALIBRATION_PARK_WHITE_OFFSET, false)) return false;

  return readControlPin(BUTTON_A_LIMIT_WHITE) == HIGH &&
         readControlPin(BUTTON_B_LIMIT_BLACK) == HIGH;
}

boolean restoreStepTestStart() {
  if (!moveCalibrationCornerToPark()) return false;
  trolley_coordinate_X = CALIBRATION_PARK_FILE;
  trolley_coordinate_Y = CALIBRATION_PARK_RANK;
  trolley_homed = true;
  rememberTrolleyPosition();
  return true;
}

boolean measureStepTestReference(unsigned int &white_steps,
                                 unsigned int &black_steps) {
  if (!prepareFirstCalibrationApproach()) {
    trolley_homed = false;
    return false;
  }
  trolley_homed = false;

  if (!homeAxisMeasured(B_T, BUTTON_A_LIMIT_WHITE, white_steps)) return false;

  // Keep the first switch pressed while finding the second switch. Calibration
  // buttons are endstops only here; use the board power switch for emergency stop.
  if (!homeAxisMeasured(L_R, BUTTON_B_LIMIT_BLACK, black_steps)) return false;

  return restoreStepTestStart();
}

boolean calibrateBoard() {
  setMagnet(false);
  motion_fault = false;
  remote_stop_requested = false;

  if (!prepareFirstCalibrationApproach()) {
    motion_fault = true;
    trolley_homed = false;
    return false;
  }
  trolley_homed = false;

  unsigned int ignored_steps = 0;
  if (!homeAxisMeasured(B_T, BUTTON_A_LIMIT_WHITE, ignored_steps)) {
    motion_fault = true;
    return false;
  }
  if (!homeAxisMeasured(L_R, BUTTON_B_LIMIT_BLACK, ignored_steps)) {
    motion_fault = true;
    return false;
  }

  // Keep both switches pressed until the corner is established, then move
  // directly to e6 without a separate switch-release/backoff stage.
  if (!moveCalibrationCornerToPark()) {
    motion_fault = true;
    return false;
  }
  delay(300);

  trolley_coordinate_X = CALIBRATION_PARK_FILE;
  trolley_coordinate_Y = CALIBRATION_PARK_RANK;
  trolley_homed = true;
  rememberTrolleyPosition();
  return true;
}

void initializeStepTestBoard() {
  const byte back_rank[8] = {
      SIM_ROOK, SIM_KNIGHT, SIM_BISHOP, SIM_QUEEN,
      SIM_KING, SIM_BISHOP, SIM_KNIGHT, SIM_ROOK
  };
  for (byte rank = 0; rank < 8; rank++) {
    for (byte file = 0; file < 8; file++) step_test_board[rank][file] = SIM_EMPTY;
  }
  for (byte file = 0; file < 8; file++) {
    step_test_board[0][file] = back_rank[file];
    step_test_board[1][file] = SIM_PAWN;
    step_test_board[6][file] = SIM_PAWN;
    step_test_board[7][file] = back_rank[file];
  }
}

boolean executeSimulatedChessMove(const char *move) {
  if (!validMoveText(move) || !trolley_homed) return false;
  byte from_x = move[0] - 'a' + 1;
  byte from_y = move[1] - '0';
  byte to_x = move[2] - 'a' + 1;
  byte to_y = move[3] - '0';
  byte piece = step_test_board[from_y - 1][from_x - 1];
  if (piece == SIM_EMPTY) return false;

  boolean destination_occupied = step_test_board[to_y - 1][to_x - 1] != SIM_EMPTY;
  boolean en_passant = piece == SIM_PAWN && !destination_occupied &&
                       abs((int)to_x - from_x) == 1;
  boolean castling = piece == SIM_KING && from_y == to_y &&
                     abs((int)to_x - from_x) == 2;

  if (destination_occupied) {
    if (!removeCapturedPiecePath(to_x, to_y, false)) return false;
  }
  else if (en_passant) {
    if (!removeCapturedPiecePath(to_x, from_y, false)) return false;
  }

  if (!moveTrolleyStraightTo(from_x, from_y, SPEED_FAST)) return false;
  if (castling) {
    if (!moveCastlingPieces(from_x, from_y, to_x, false)) return false;
  }
  else if (!moveHeldPieceSmooth(from_x, from_y, to_x, to_y)) {
    return false;
  }

  step_test_board[from_y - 1][from_x - 1] = SIM_EMPTY;
  if (en_passant) step_test_board[from_y - 1][to_x - 1] = SIM_EMPTY;
  step_test_board[to_y - 1][to_x - 1] =
      (piece == SIM_PAWN && (to_y == 1 || to_y == 8)) ? (byte)SIM_QUEEN : piece;
  if (castling) {
    byte rook_from = to_x > from_x ? 8 : 1;
    byte rook_to = to_x > from_x ? 6 : 4;
    step_test_board[from_y - 1][rook_from - 1] = SIM_EMPTY;
    step_test_board[from_y - 1][rook_to - 1] = SIM_ROOK;
  }
  return true;
}

void showStepTestDifference(unsigned int ply, int white_delta, int black_delta) {
  lcd.clear();
  lcd.print(F("STEP LOSS P"));
  if (ply < 10) lcd.print('0');
  lcd.print(ply);
  lcd.setCursor(0, 1);
  lcd.print(F("W"));
  if (white_delta >= 0) lcd.print('+');
  lcd.print(white_delta);
  lcd.print(F(" B"));
  if (black_delta >= 0) lcd.print('+');
  lcd.print(black_delta);
}

void runStepLossTest() {
  setMagnet(false);
  motion_fault = false;
  remote_stop_requested = false;
  AI_reset();
  initializeStepTestBoard();

  lcd.clear();
  lcd.print(F("STEP TEST CAL"));
  lcd.setCursor(0, 1);
  lcd.print(F("POWER=E-STOP"));

  boolean aborted = false;
  unsigned int ignored_white = 0;
  unsigned int ignored_black = 0;
  unsigned int baseline_white = 0;
  unsigned int baseline_black = 0;

  // The first pass calibrates from the current position. The second starts at
  // the known e6 service position and establishes the repeatability baseline.
  if (!measureStepTestReference(ignored_white, ignored_black) ||
      !measureStepTestReference(baseline_white, baseline_black)) {
    trolley_homed = false;
    showCalibrationReferenceFault();
    delay(2500);
    AI_reset();
    showServiceMenu();
    return;
  }

  unsigned int ply = 0;
  unsigned int game = 1;
  byte consecutive_game_overs = 0;
  while (ply < STEP_TEST_PLIES) {
    lcd.clear();
    lcd.print(F("AI GAME "));
    lcd.print(game);
    lcd.setCursor(0, 1);
    lcd.print(F("THINK "));
    lcd.print(ply + 1);

    byte ai_result = AI_selfPlayMove();
    if (ai_result == AI_GAME_OVER) {
      game++;
      consecutive_game_overs++;
      if (consecutive_game_overs > 2) {
        trolley_homed = false;
        lcd.clear();
        lcd.print(F("AI TEST FAIL"));
        lcd.setCursor(0, 1);
        lcd.print(F("CAL REQUIRED"));
        delay(2500);
        AI_reset();
        showServiceMenu();
        return;
      }
      AI_reset();
      initializeStepTestBoard();
      continue;
    }
    consecutive_game_overs = 0;

    lcd.clear();
    lcd.print(F("G"));
    lcd.print(game);
    lcd.print(F(" P"));
    lcd.print(ply + 1);
    lcd.setCursor(0, 1);
    lcd.print(lastM);
    lcd.print(F(" A/B=STOP"));

    if (!executeSimulatedChessMove(lastM)) {
      aborted = readControlPin(BUTTON_A_LIMIT_WHITE) == LOW ||
                readControlPin(BUTTON_B_LIMIT_BLACK) == LOW;
      trolley_homed = false;
      lcd.clear();
      lcd.print(aborted ? F("TEST ABORTED") : F("MOTION TEST FAIL"));
      lcd.setCursor(0, 1);
      lcd.print(F("CAL REQUIRED"));
      delay(2500);
      AI_reset();
      showServiceMenu();
      return;
    }
    ply++;

    if (ply % STEP_TEST_REFERENCE_INTERVAL != 0 && ply != STEP_TEST_PLIES) continue;

    if (!moveTrolleyStraightTo(CALIBRATION_PARK_FILE,
                               CALIBRATION_PARK_RANK, SPEED_FAST)) {
      aborted = readControlPin(BUTTON_A_LIMIT_WHITE) == LOW ||
                readControlPin(BUTTON_B_LIMIT_BLACK) == LOW;
      trolley_homed = false;
      lcd.clear();
      lcd.print(aborted ? F("TEST ABORTED") : F("MOTION TEST FAIL"));
      lcd.setCursor(0, 1);
      lcd.print(F("CAL REQUIRED"));
      delay(2500);
      AI_reset();
      showServiceMenu();
      return;
    }

    unsigned int measured_white = 0;
    unsigned int measured_black = 0;
    if (!measureStepTestReference(measured_white, measured_black)) {
      trolley_homed = false;
      showCalibrationReferenceFault();
      delay(2500);
      AI_reset();
      showServiceMenu();
      return;
    }

    int white_delta = (int)measured_white - (int)baseline_white;
    int black_delta = (int)measured_black - (int)baseline_black;
    if (abs(white_delta) > (int)STEP_TEST_TOLERANCE ||
        abs(black_delta) > (int)STEP_TEST_TOLERANCE) {
      trolley_homed = false;
      showStepTestDifference(ply, white_delta, black_delta);
      delay(4000);
      AI_reset();
      showServiceMenu();
      return;
    }
  }

  lcd.clear();
  lcd.print(F("STEP TEST PASS"));
  lcd.setCursor(0, 1);
  lcd.print(STEP_TEST_PLIES);
  lcd.print(F(" MOVES OK"));
  delay(3000);
  AI_reset();
  showServiceMenu();
}

boolean moveTrolleyTo(byte target_x, byte target_y, unsigned int speed_delay) {
  return moveTrolleyStraightTo(target_x, target_y, speed_delay);
}

void setMagnet(boolean enabled) {
  if (enabled && motion_fault) return;
  if (enabled == magnet_state) return;

  if (enabled) {
    digitalWrite(MAGNET, HIGH);
    magnet_state = true;
    delay(600);
  }
  else {
    if (magnet_state) delay(600);
    digitalWrite(MAGNET, LOW);
    magnet_state = false;
  }
}

// ---------------------------- AI physical movement -----------------------

boolean removeCapturedPiecePath(byte file, byte rank, boolean use_magnet) {
  if (!moveTrolleyStraightTo(file, rank, SPEED_FAST)) return false;
  if (use_magnet) {
    lcd.clear();
    lcd.print(F("REMOVING CAPTURE"));
    lcd.setCursor(0, 1);
    lcd.print(F("TO LEFT BIN"));
    setMagnet(true);
  }

  int end_x = (int)CAPTURE_SIDE_X_STEPS -
              (int)file * (int)SQUARE_SIZE;
  int end_y = -(int)SQUARE_SIZE / 2;
  int control1_x = 0;
  int control1_y = end_y;
  int control2_x = end_x;
  int control2_y = end_y;
  if (!pulseCoreXYCurve(end_x, end_y, control1_x, control1_y,
                        control2_x, control2_y, SPEED_SLOW, true)) return false;
  // The curve decelerates to the configured start delay before release.
  // setMagnet(false) keeps the head stationary for the existing pre-release
  // delay. The extra dwell after power goes low lets the piece fall clear
  // before the head starts its capture-triggered calibration.
  setMagnet(false);
  if (use_magnet) delay(CAPTURE_DROP_SETTLE_MS);

  // The off-board bin coordinate cannot be stored as a board square. If this
  // capture came from the corner-switch side, move down the bin lane before
  // starting the first calibration approach.
  if (rank > CALIBRATION_PARK_RANK) {
    int staging_steps = ((int)rank - CALIBRATION_PARK_RANK) *
                        (int)SQUARE_SIZE;
    if (!pulseCoreXYLine(0, -staging_steps, SPEED_FAST, false)) return false;
  }
  calibration_lane_confirmed = true;

  if (use_magnet) {
    lcd.clear();
    lcd.print(F("CAPTURE HOMING"));
    lcd.setCursor(0, 1);
    lcd.print(F("KEEP HANDS CLEAR"));
  }

  // Production and the empty-board endurance test both use the same two-axis
  // reference routine. The test omits only magnet and physical drop delays;
  // its bin travel and position correction are real.
  unsigned int ignored_white = 0;
  unsigned int ignored_black = 0;
  return measureStepTestReference(ignored_white, ignored_black);
}

boolean removeCapturedPiece(byte file, byte rank) {
  return removeCapturedPiecePath(file, rank, true);
}

boolean moveCastlingPieces(byte from_x, byte rank, byte to_x,
                           boolean use_magnet) {
  if (use_magnet) setMagnet(true);
  if (!moveHeldPieceSmooth(from_x, rank, to_x, rank)) return false;
  setMagnet(false);

  byte rook_from = to_x > from_x ? 8 : 1;
  byte rook_to = to_x > from_x ? 6 : 4;
  if (!moveTrolleyStraightTo(rook_from, rank, SPEED_FAST)) return false;
  if (use_magnet) setMagnet(true);

  int dx = ((int)rook_to - rook_from) * (int)SQUARE_SIZE;
  int clearance = rank <= 4 ? (int)SQUARE_SIZE / 2 : -(int)SQUARE_SIZE / 2;
  if (!pulseCoreXYCurve(dx, 0, 0, clearance, dx, clearance,
                        SPEED_SLOW, true)) return false;
  trolley_coordinate_X = rook_to;
  trolley_coordinate_Y = rank;
  rememberTrolleyPosition();
  setMagnet(false);

  // Keep the public coordinate at the king's destination, as normal moves do.
  return moveTrolleyStraightTo(to_x, rank, SPEED_FAST);
}

boolean computerPlayerMovement(const char *move_text, char move_flags) {
  if (!validMoveText(move_text) || !trolley_homed) {
    motion_fault = true;
    return false;
  }

  byte departure_x = move_text[0] - 'a' + 1;
  byte departure_y = move_text[1] - '0';
  byte arrival_x = move_text[2] - 'a' + 1;
  byte arrival_y = move_text[3] - '0';
  byte displacement_x = abs((int)arrival_x - (int)departure_x);
  byte displacement_y = abs((int)arrival_y - (int)departure_y);

  byte arrival_row = 8 - arrival_y;
  byte arrival_column = arrival_x - 1;
  boolean destination_occupied = reed_sensor_status[arrival_row][arrival_column] == LOW;

  // In remote mode the Windows rules engine marks en passant explicitly. The
  // second clause preserves Micro-Max's black-only standalone behavior.
  boolean en_passant = move_flags == 'E' ||
                       (!destination_occupied && departure_y == 4 && arrival_y == 3 &&
                        displacement_x == 1 && displacement_y == 1 &&
                        reed_sensor_status[8 - departure_y][arrival_column] == LOW);

  if (destination_occupied) {
    if (!removeCapturedPiece(arrival_x, arrival_y)) return false;
  }
  else if (en_passant) {
    if (!removeCapturedPiece(arrival_x, departure_y)) return false;
  }

  if (!moveTrolleyStraightTo(departure_x, departure_y, SPEED_FAST))
    return false;
  boolean castling = move_flags == 'C' ||
                     (departure_x == 5 && departure_y == arrival_y &&
                      displacement_x == 2);
  if (castling) {
    if (!moveCastlingPieces(departure_x, departure_y, arrival_x, true))
      return false;
  }
  else {
    setMagnet(true);
    if (!moveHeldPieceSmooth(departure_x, departure_y, arrival_x, arrival_y))
      return false;
  }

  setMagnet(false);
  if (motion_fault) return false;
  trolley_coordinate_X = arrival_x;
  trolley_coordinate_Y = arrival_y;
  rememberTrolleyPosition();

  byte departure_row = 8 - departure_y;
  byte departure_column = departure_x - 1;
  reed_sensor_status[departure_row][departure_column] = HIGH;
  reed_sensor_status[arrival_row][arrival_column] = LOW;
  if (en_passant) reed_sensor_status[8 - departure_y][arrival_column] = HIGH;

  if (castling) {
    byte castling_row = 8 - departure_y;
    if (arrival_x == 7) {
      reed_sensor_status[castling_row][7] = HIGH;
      reed_sensor_status[castling_row][5] = LOW;
    }
    else if (arrival_x == 3) {
      reed_sensor_status[castling_row][0] = HIGH;
      reed_sensor_status[castling_row][3] = LOW;
    }
  }
  return true;
}

boolean blackPlayerMovement() {
  return computerPlayerMovement(lastM, 0);
}

// ---------------------------- Persistent service mode --------------------

void showServiceMenu() {
  sequence = service_menu;
  lcd.clear();
  lcd.setCursor(0, 0);
  lcd.print(F("TEST: "));
  switch (service_item) {
    case SERVICE_CALIBRATE: lcd.print(F("CALIBRATE")); break;
    case SERVICE_SENSORS:   lcd.print(F("SENSORS")); break;
    case SERVICE_MOVE:      lcd.print(F("MOVE HEAD")); break;
    case SERVICE_MAGNET:    lcd.print(F("MAGNET "));
                            lcd.print(magnet_state ? F("ON") : F("OFF")); break;
    case SERVICE_STEP_LOSS: lcd.print(F("STEP LOSS")); break;
    case SERVICE_EXIT:      lcd.print(F("EXIT")); break;
  }
  lcd.setCursor(0, 1);
  lcd.print(F("A=RUN  B=NEXT"));
}

void serviceMenuLoop() {
  if (buttonPressed(BUTTON_B_LIMIT_BLACK)) {
    service_item = (service_item + 1) % SERVICE_COUNT;
    showServiceMenu();
    return;
  }
  if (!buttonPressed(BUTTON_A_LIMIT_WHITE)) return;

  switch (service_item) {
    case SERVICE_CALIBRATE:
      requestCalibration(service_menu);
      break;

    case SERVICE_SENSORS:
      scanSensors();
      syncSensorState();
      last_sensor_square = NO_SQUARE;
      sequence = service_sensors;
      showServiceSensors();
      break;

    case SERVICE_MOVE:
      if (!trolley_homed) {
        lcd.clear();
        lcd.print(F("CALIBRATE FIRST"));
        delay(1200);
        showServiceMenu();
      }
      else {
        service_file = trolley_coordinate_X;
        service_rank = trolley_coordinate_Y;
        sequence = service_move_file;
        showMoveFile();
      }
      break;

    case SERVICE_MAGNET:
      setMagnet(!magnet_state);
      lcd.clear();
      lcd.print(F("MAGNET IS "));
      lcd.print(magnet_state ? F("ON") : F("OFF"));
      lcd.setCursor(0, 1);
      lcd.print(F("USE MOVE HEAD"));
      delay(1000);
      showServiceMenu();
      break;

    case SERVICE_STEP_LOSS:
      if (trolley_position_known) runStepLossTest();
      else requestCalibration(service_menu);
      break;

    case SERVICE_EXIT:
      returnToMainMenu();
      break;
  }
}

void showServiceSensors() {
  byte occupied = countOccupied(reed_sensor_record);
  lcd.clear();
  lcd.setCursor(0, 0);
  lcd.print(F("OCC:"));
  printTwoDigits(occupied);
  lcd.print(F(" LAST:"));
  printSquare(last_sensor_square);
  lcd.setCursor(0, 1);
  if (last_sensor_square == NO_SQUARE) lcd.print(F("MOVE A PIECE"));
  else lcd.print(last_sensor_occupied ? F("PIECE ON") : F("PIECE OFF"));
  lcd.setCursor(10, 1);
  lcd.print(F("B=EXIT"));
}

void serviceSensorLoop() {
  if (buttonPressed(BUTTON_B_LIMIT_BLACK)) {
    showServiceMenu();
    return;
  }

  scanSensors();
  boolean changed = false;
  for (byte row = 0; row < 8; row++) {
    for (byte column = 0; column < 8; column++) {
      if (reed_sensor_record[row][column] != reed_sensor_status[row][column]) {
        last_sensor_square = row * 8 + column;
        last_sensor_occupied = reed_sensor_record[row][column] == LOW;
        reed_sensor_status[row][column] = reed_sensor_record[row][column];
        changed = true;
      }
    }
  }
  if (changed) showServiceSensors();
}

void showMoveFile() {
  lcd.clear();
  lcd.print(F("MOVE HEAD FILE"));
  lcd.setCursor(0, 1);
  lcd.print(F("B=NEXT  A="));
  lcd.print((char)('a' + service_file - 1));
}

void serviceMoveFileLoop() {
  if (buttonPressed(BUTTON_B_LIMIT_BLACK)) {
    service_file = service_file == 8 ? 1 : service_file + 1;
    showMoveFile();
  }
  else if (buttonPressed(BUTTON_A_LIMIT_WHITE)) {
    sequence = service_move_rank;
    showMoveRank();
  }
}

void showMoveRank() {
  lcd.clear();
  lcd.print(F("TARGET "));
  lcd.print((char)('a' + service_file - 1));
  lcd.print(service_rank);
  lcd.print(F(" MAG:"));
  lcd.print(magnet_state ? F("ON") : F("OFF"));
  lcd.setCursor(0, 1);
  lcd.print(F("B=NEXT A=GO"));
}

void serviceMoveRankLoop() {
  if (buttonPressed(BUTTON_B_LIMIT_BLACK)) {
    service_rank = service_rank == 8 ? 1 : service_rank + 1;
    showMoveRank();
    return;
  }
  if (!buttonPressed(BUTTON_A_LIMIT_WHITE)) return;

  lcd.clear();
  lcd.print(F("MOVING TO "));
  lcd.print((char)('a' + service_file - 1));
  lcd.print(service_rank);
  lcd.setCursor(0, 1);
  lcd.print(F("A/B = E-STOP"));

  if (!moveTrolleyTo(service_file, service_rank, SPEED_SLOW)) {
    sequence = fault_screen;
    showMotionFault();
    return;
  }

  lcd.clear();
  lcd.print(F("AT "));
  lcd.print((char)('a' + service_file - 1));
  lcd.print(service_rank);
  lcd.setCursor(0, 1);
  lcd.print(F("MAGNET "));
  lcd.print(magnet_state ? F("ON") : F("OFF"));
  delay(900);
  showServiceMenu();
}
