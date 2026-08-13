/*
 * Automatic Chessboard firmware 4.2.0.
 *
 * Substantially modified from "Automated Chessboard" by Greg06:
 * https://www.instructables.com/Automated-Chessboard/
 * Source and this adaptation: CC BY-NC-SA 4.0.
 * See ATTRIBUTION.md and LICENSE.md for lineage, changes, and exceptions.
 */

#include <Wire.h>
#include <EEPROM.h>
#include <avr/pgmspace.h>
#include <SoftwareSerial.h>
#include <hd44780.h>
#include <hd44780ioClass/hd44780_I2Cexp.h>
#include "global.h"
#include "Micro_Max.h"

#define FIRMWARE_VERSION "4.2.0"

// All mutable firmware state is centralized here. global.h contains only
// types, configuration constants, enums, and extern declarations so changing
// hardware configuration cannot silently allocate a second copy of state.
BoardState reed_sensor_status = {{0}};
BoardState reed_sensor_record = {{0}};
BoardState turn_start_status = {{0}};
byte lifted_squares[2] = {NO_SQUARE, NO_SQUARE};
byte lifted_count = 0;
byte move_from = NO_SQUARE;
byte move_to = NO_SQUARE;
byte last_sensor_square = NO_SQUARE;
boolean last_sensor_occupied = false;
boolean human_move_ready = false;
boolean sensor_tracking_error = false;
boolean pending_move_displayed = false;
byte trolley_coordinate_X = CALIBRATION_PARK_FILE;
byte trolley_coordinate_Y = CALIBRATION_PARK_RANK;
boolean trolley_homed = false;
boolean motion_fault = false;
boolean magnet_state = false;
unsigned long magnet_on_since = 0;
char mov[5] = {0, 0, 0, 0, 0};
byte sequence = start_up;
byte after_calibration = setup_check;
byte service_item = SERVICE_CALIBRATE;
byte service_file = 1;
byte service_rank = 1;
unsigned int calibration_park_black_steps = DEFAULT_PARK_BLACK_STEPS;
unsigned int calibration_park_white_steps = DEFAULT_PARK_WHITE_STEPS;

hd44780_I2Cexp lcd;
// Responses use hardware TX/D1, which safely fans out to USB and HC-08 RXD.
// Bluetooth commands use D10 so the onboard USB bridge cannot fight HC-08 TXD.
// The transmit argument is D1 but this receive-only object never writes it.
SoftwareSerial bluetoothInput(BLUETOOTH_RX, 1);

// The compact line protocol keeps the Nano responsible for motion and safety
// while a Windows host can provide full chess rules and Stockfish.
const byte HOST_INPUT_SIZE = 32;
struct HostInputBuffer {
  char data[HOST_INPUT_SIZE];
  byte length;
  boolean overflowed;
};
HostInputBuffer usb_host_input = {{0}, 0, false};
HostInputBuffer bluetooth_host_input = {{0}, 0, false};
boolean remote_mode = false;
boolean remote_human_white = true;
boolean remote_human_move_pending = false;
volatile boolean remote_stop_requested = false;
char remote_move_flags = 0;
char remote_promotion_piece = 0;

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
unsigned int last_home_white_steps = 0;
unsigned int last_home_black_steps = 0;

// Raw multiplexer rows follow the glued-tile wiring documented in
// windows_app/README.md. The installed sensor panel is rotated 180 degrees
// relative to the carriage frame, so normalize both axes once before any
// chess logic or host telemetry sees the board.
const byte SENSOR_ROW_MAP[8] PROGMEM = {7, 6, 1, 0, 3, 2, 5, 4};

byte logicalSensorRow(byte raw_row) {
  return 7 - pgm_read_byte(&SENSOR_ROW_MAP[raw_row]);
}

byte logicalSensorColumn(byte raw_column) {
  return 7 - raw_column;
}
boolean boardSquareOccupied(const BoardState &board, byte row, byte column) {
  return bitRead(board.rows[row], column);
}

void setBoardSquare(BoardState &board, byte row, byte column,
                    boolean occupied) {
  bitWrite(board.rows[row], column, occupied);
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

  const int lcd_status = lcd.begin(16, 2);
  if (lcd_status) {
    Serial.print(F("LCDERR "));
    Serial.println(lcd_status);
  }
  lcd.backlight();
  loadCalibrationProfile();
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
  if (enforceMagnetTimeout()) {
    sequence = fault_screen;
    showMotionFault();
  }
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

    case remote_route_plan:
      // Button B always leaves the transaction safely idle with the magnet off.
      // A partially rearranged board then requires visual/sensor recovery before
      // another game; it is never silently committed as a chess move.
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
