/*
 * Automatic Chessboard firmware 4.4.0.
 *
 * Substantially modified from "Automated Chessboard" by Greg06:
 * https://www.instructables.com/Automated-Chessboard/
 * Source and this adaptation: CC BY-NC-SA 4.0.
 * See ATTRIBUTION.md and LICENSE.md for lineage, changes, and exceptions.
 */

#include <EEPROM.h>
#include <avr/pgmspace.h>
#include "global.h"
#if defined(ACB_PROFILE_MKS_GEN_L_V1)
#include <SoftwareWire.h>
SoftwareWire acbLcdWire(LCD_SOFTWARE_SDA, LCD_SOFTWARE_SCL);
// hd44780_I2Cexp uses the Wire API by name. SoftwareWire supplies the same
// master API on MKS connector pins because SDA/SCL are not broken out there.
#define Wire acbLcdWire
#else
#include <Wire.h>
#include <SoftwareSerial.h>
#endif
#include <hd44780.h>
#include <hd44780ioClass/hd44780_I2Cexp.h>
#if defined(ACB_PROFILE_MKS_GEN_L_V1)
#undef Wire
#endif
#include "Micro_Max.h"

#define FIRMWARE_VERSION "4.4.0"

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
byte service_file = CALIBRATION_PARK_FILE;

hd44780_I2Cexp lcd;
// Both profiles fan replies from hardware TX/D1 to USB and HC-08 RXD. The
// Nano receives on D10; MKS receives on Serial2 RX/D17 at EXP1 pin 3.
#if defined(ACB_PROFILE_MKS_GEN_L_V1)
#define bluetoothInput Serial2
#else
SoftwareSerial bluetoothInput(BLUETOOTH_RX, 1);
#endif

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

#if defined(ACB_PROFILE_MKS_GEN_L_V1)
  // Keep both integrated drivers disabled until STEP and DIR are known. Once
  // enabled they remain holding, matching the always-enabled Nano wiring and
  // preserving the calibrated CoreXY reference between moves.
  pinMode(MOTOR_WHITE_ENABLE, OUTPUT);
  pinMode(MOTOR_BLACK_ENABLE, OUTPUT);
  digitalWrite(MOTOR_WHITE_ENABLE,
               MOTOR_ENABLE_ACTIVE_LOW ? HIGH : LOW);
  digitalWrite(MOTOR_BLACK_ENABLE,
               MOTOR_ENABLE_ACTIVE_LOW ? HIGH : LOW);
#endif

  for (byte i = 0; i < 4; i++) {
    pinMode(MUX_ADDR[i], OUTPUT);
    digitalWrite(MUX_ADDR[i], LOW);
    pinMode(MUX_SELECT[i], OUTPUT);
    digitalWrite(MUX_SELECT[i], HIGH);
  }
  pinMode(MUX_OUTPUT, INPUT_PULLUP);

  pinMode(BUTTON_A_LIMIT_WHITE, INPUT_PULLUP);
#if defined(ACB_PROFILE_MKS_GEN_L_V1)
  pinMode(BUTTON_B_LIMIT_BLACK, INPUT_PULLUP);
#else
  // A6 has no digital input buffer or internal pull-up. The external 10 kOhm
  // pull-up makes a released switch read near 1023 and a pressed switch near 0.
#endif

  const int lcd_status = lcd.begin(16, 2);
  if (lcd_status) {
    Serial.print(F("LCDERR "));
    Serial.println(lcd_status);
  }
  lcd.backlight();
  loadPersistedTrolleyPosition();
  showPersistedTrolleyPosition();
  AI_reset();
  scanSensors();
  syncSensorState();
#if defined(ACB_PROFILE_MKS_GEN_L_V1)
  digitalWrite(MOTOR_WHITE_ENABLE,
               MOTOR_ENABLE_ACTIVE_LOW ? LOW : HIGH);
  digitalWrite(MOTOR_BLACK_ENABLE,
               MOTOR_ENABLE_ACTIVE_LOW ? LOW : HIGH);
#endif
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

    case service_geometry_nudge:
      serviceGeometryNudgeLoop();
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
