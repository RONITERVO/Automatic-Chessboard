#include <Wire.h>
#include <LiquidCrystal_I2C.h>
#include "global.h"
#include "Micro_Max.h"

LiquidCrystal_I2C lcd(0x27, 16, 2);

enum {SIM_EMPTY, SIM_PAWN, SIM_KNIGHT, SIM_BISHOP, SIM_ROOK, SIM_QUEEN, SIM_KING};
byte step_test_board[8][8];

// Board-square centers are x=1..8, so the playing-field edge is x=0.50.
// Calibration establishes the left limit near x=0.35. x=0.48 is outside
// the playing field while retaining about 25 full steps from that limit.
const unsigned int CAPTURE_SIDE_X_STEPS =
    (SQUARE_SIZE * 12UL + 12UL) / 25UL;
const unsigned int CAPTURE_DROP_SETTLE_MS = 400;

void setup() {
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
  pinMode(BUTTON_B_LIMIT_BLACK, INPUT_PULLUP);

  lcd.init();
  lcd.backlight();
  AI_reset();
  scanSensors();
  syncSensorState();

  lcd.clear();
  lcd.setCursor(0, 0);
  lcd.print(F("AUTOMATIC CHESS"));
  lcd.setCursor(0, 1);
  lcd.print(F("SAFE AI BUILD"));
  delay(1800);
  sequence = main_menu;
  showMainMenu();
}

void loop() {
  switch (sequence) {
    case main_menu:
      if (buttonPressed(BUTTON_A_LIMIT_WHITE)) {
        AI_reset();
        resetMoveTracker();
        after_calibration = setup_check;
        sequence = calibration;
        showCalibration();
      }
      else if (buttonPressed(BUTTON_B_LIMIT_BLACK)) {
        service_item = SERVICE_CALIBRATE;
        sequence = service_menu;
        showServiceMenu();
      }
      break;

    case calibration:
      if (calibrateBoard()) {
        lcd.clear();
        lcd.print(F("CALIBRATION OK"));
        lcd.setCursor(0, 1);
        lcd.print(F("HEAD AT e7"));
        delay(1000);
        sequence = after_calibration;
        if (sequence == setup_check) showSetupCheck();
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
        after_calibration = service_menu;
        sequence = calibration;
        showCalibration();
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
  }
}

// Waits for release so the same input can become an emergency stop during motion.
boolean buttonPressed(byte pin) {
  if (digitalRead(pin) == HIGH) return false;
  delay(20);
  if (digitalRead(pin) == HIGH) return false;

  unsigned long started = millis();
  while (digitalRead(pin) == LOW) {
    if (millis() - started > 2000UL) return false;
  }
  delay(20);
  return true;
}

void returnToMainMenu() {
  setMagnet(false);
  motion_fault = false;
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
      reed_sensor_record[7 - column][row] = high_votes >= 2 ? HIGH : LOW;
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
  for (byte i = lifted_count; i > 0; i--) {
    byte candidate = lifted_squares[i - 1];
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

boolean pulseMotor(byte direction, unsigned int speed_delay, unsigned int steps, boolean monitor_stops) {
  if (monitor_stops && motion_fault) return false;
  configureMotorDirection(direction);
  delayMicroseconds(2);

  for (unsigned int step_count = 0; step_count < steps; step_count++) {
    if (monitor_stops &&
        (digitalRead(BUTTON_A_LIMIT_WHITE) == LOW || digitalRead(BUTTON_B_LIMIT_BLACK) == LOW)) {
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

  digitalWrite(MOTOR_WHITE_DIR, white_delta >= 0 ? HIGH : LOW);
  digitalWrite(MOTOR_BLACK_DIR, black_delta >= 0 ? HIGH : LOW);
  delayMicroseconds(2);

  unsigned long white_accumulator = 0;
  unsigned long black_accumulator = 0;
  for (unsigned int event = 0; event < event_count; event++) {
    if (monitor_stops &&
        (digitalRead(BUTTON_A_LIMIT_WHITE) == LOW ||
         digitalRead(BUTTON_B_LIMIT_BLACK) == LOW)) {
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

boolean homeAxis(byte direction, byte limit_pin) {
  unsigned int steps = 0;
  while (digitalRead(limit_pin) == HIGH && steps < HOME_MAX_STEPS) {
    pulseMotor(direction, SPEED_SLOW, 4, false);
    steps += 4;
  }
  return digitalRead(limit_pin) == LOW;
}

boolean releaseLimitForStepTest(byte limit_pin, byte away_direction) {
  unsigned int steps = 0;
  while (digitalRead(limit_pin) == LOW && steps < STEP_TEST_LIMIT_RELEASE_STEPS) {
    if (!pulseMotor(away_direction, SPEED_SLOW, 1, false)) return false;
    steps++;
  }
  return digitalRead(limit_pin) == HIGH;
}

boolean measureAxisForStepTest(byte direction, byte limit_pin, byte abort_pin,
                               unsigned int &measured_steps, boolean &aborted) {
  measured_steps = 0;
  while (digitalRead(limit_pin) == HIGH && measured_steps < HOME_MAX_STEPS) {
    // The target input is the expected endstop. The other shared button/input
    // remains available as an abort control while this axis is homing.
    if (digitalRead(abort_pin) == LOW) {
      aborted = true;
      return false;
    }
    if (!pulseMotor(direction, SPEED_SLOW, 1, false)) return false;
    measured_steps++;
  }
  return digitalRead(limit_pin) == LOW;
}

boolean restoreStepTestStart(boolean &aborted) {
  unsigned int start_x_steps =
      (unsigned int)(TROLLEY_START_POSITION_X * SQUARE_SIZE + 0.5);
  unsigned int start_y_steps =
      (unsigned int)(TROLLEY_START_POSITION_Y * SQUARE_SIZE + 0.5);

  if (start_x_steps <= STEP_TEST_LIMIT_RELEASE_STEPS ||
      start_y_steps <= STEP_TEST_LIMIT_RELEASE_STEPS) return false;

  // Move off the active black limit before enabling normal E-stop monitoring.
  if (!pulseMotor(R_L, SPEED_SLOW, STEP_TEST_LIMIT_RELEASE_STEPS, false)) return false;
  if (digitalRead(BUTTON_B_LIMIT_BLACK) == LOW) return false;
  if (!pulseMotor(R_L, SPEED_FAST,
                  start_x_steps - STEP_TEST_LIMIT_RELEASE_STEPS, true)) {
    aborted = digitalRead(BUTTON_A_LIMIT_WHITE) == LOW ||
              digitalRead(BUTTON_B_LIMIT_BLACK) == LOW;
    return false;
  }

  // The white limit was already released by a known number of steps before
  // the black-axis measurement, so only the remaining distance is required.
  if (!pulseMotor(T_B, SPEED_FAST,
                  start_y_steps - STEP_TEST_LIMIT_RELEASE_STEPS, true)) {
    aborted = digitalRead(BUTTON_A_LIMIT_WHITE) == LOW ||
              digitalRead(BUTTON_B_LIMIT_BLACK) == LOW;
    return false;
  }

  if (digitalRead(BUTTON_A_LIMIT_WHITE) == LOW ||
      digitalRead(BUTTON_B_LIMIT_BLACK) == LOW) return false;

  trolley_coordinate_X = 5;
  trolley_coordinate_Y = 7;
  trolley_homed = true;
  return true;
}

boolean measureStepTestReference(unsigned int &white_steps,
                                 unsigned int &black_steps,
                                 boolean &aborted) {
  trolley_homed = false;

  // If the black switch is already active at test startup, release it so it
  // can serve as the abort input while the white axis is measured.
  if (digitalRead(BUTTON_B_LIMIT_BLACK) == LOW &&
      !releaseLimitForStepTest(BUTTON_B_LIMIT_BLACK, R_L)) return false;

  if (!measureAxisForStepTest(B_T, BUTTON_A_LIMIT_WHITE,
                              BUTTON_B_LIMIT_BLACK, white_steps, aborted)) return false;

  // Release the first switch by a known distance. This makes it available as
  // the abort input while measuring the second axis.
  if (!pulseMotor(T_B, SPEED_SLOW, STEP_TEST_LIMIT_RELEASE_STEPS, false)) return false;
  if (digitalRead(BUTTON_A_LIMIT_WHITE) == LOW) return false;
  if (digitalRead(BUTTON_B_LIMIT_BLACK) == LOW) {
    aborted = true;
    return false;
  }

  if (!measureAxisForStepTest(L_R, BUTTON_B_LIMIT_BLACK,
                              BUTTON_A_LIMIT_WHITE, black_steps, aborted)) return false;

  return restoreStepTestStart(aborted);
}

boolean calibrateBoard() {
  setMagnet(false);
  motion_fault = false;
  trolley_homed = false;

  if (!homeAxis(B_T, BUTTON_A_LIMIT_WHITE)) {
    motion_fault = true;
    return false;
  }
  if (!homeAxis(L_R, BUTTON_B_LIMIT_BLACK)) {
    motion_fault = true;
    return false;
  }

  delay(300);
  if (!motor(R_L, SPEED_FAST, TROLLEY_START_POSITION_X, false)) return false;
  if (!motor(T_B, SPEED_FAST, TROLLEY_START_POSITION_Y, false)) return false;
  delay(300);

  // Both switches must release after moving away from the homing corner.
  if (digitalRead(BUTTON_A_LIMIT_WHITE) == LOW || digitalRead(BUTTON_B_LIMIT_BLACK) == LOW) {
    motion_fault = true;
    return false;
  }

  trolley_coordinate_X = 5;
  trolley_coordinate_Y = 7;
  trolley_homed = true;
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
  trolley_homed = false;
  AI_reset();
  initializeStepTestBoard();

  lcd.clear();
  lcd.print(F("STEP TEST CAL"));
  lcd.setCursor(0, 1);
  lcd.print(F("A/B=E-STOP"));

  boolean aborted = false;
  unsigned int ignored_white = 0;
  unsigned int ignored_black = 0;
  unsigned int baseline_white = 0;
  unsigned int baseline_black = 0;

  // The first pass calibrates from an unknown position. The second starts at
  // the known e7 service position and establishes the repeatability baseline.
  if (!measureStepTestReference(ignored_white, ignored_black, aborted) ||
      !measureStepTestReference(baseline_white, baseline_black, aborted)) {
    trolley_homed = false;
    lcd.clear();
    lcd.print(aborted ? F("TEST ABORTED") : F("HOME TEST FAIL"));
    lcd.setCursor(0, 1);
    lcd.print(F("CAL REQUIRED"));
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
      aborted = digitalRead(BUTTON_A_LIMIT_WHITE) == LOW ||
                digitalRead(BUTTON_B_LIMIT_BLACK) == LOW;
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

    if (!moveTrolleyStraightTo(5, 7, SPEED_FAST)) {
      aborted = digitalRead(BUTTON_A_LIMIT_WHITE) == LOW ||
                digitalRead(BUTTON_B_LIMIT_BLACK) == LOW;
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
    if (!measureStepTestReference(measured_white, measured_black, aborted)) {
      trolley_homed = false;
      lcd.clear();
      lcd.print(aborted ? F("TEST ABORTED") : F("HOME TEST FAIL"));
      lcd.setCursor(0, 1);
      lcd.print(F("CAL REQUIRED"));
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
  // before the empty head retraces the path.
  setMagnet(false);
  if (use_magnet) delay(CAPTURE_DROP_SETTLE_MS);

  // Follow the exact same rounded path backwards to restore the known square.
  if (!pulseCoreXYCurve(-end_x, -end_y,
                        control2_x - end_x, control2_y - end_y,
                        control1_x - end_x, control1_y - end_y,
                        SPEED_FAST, true)) return false;
  trolley_coordinate_X = file;
  trolley_coordinate_Y = rank;
  return true;
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
  setMagnet(false);

  // Keep the public coordinate at the king's destination, as normal moves do.
  return moveTrolleyStraightTo(to_x, rank, SPEED_FAST);
}

boolean blackPlayerMovement() {
  if (!validMoveText(lastM) || !trolley_homed) {
    motion_fault = true;
    return false;
  }

  byte departure_x = lastM[0] - 'a' + 1;
  byte departure_y = lastM[1] - '0';
  byte arrival_x = lastM[2] - 'a' + 1;
  byte arrival_y = lastM[3] - '0';
  byte displacement_x = abs((int)arrival_x - (int)departure_x);
  byte displacement_y = abs((int)arrival_y - (int)departure_y);

  byte arrival_row = 8 - arrival_y;
  byte arrival_column = arrival_x - 1;
  boolean destination_occupied = reed_sensor_status[arrival_row][arrival_column] == LOW;

  // A black en-passant capture lands diagonally on an empty rank-3 square.
  boolean en_passant = !destination_occupied && departure_y == 4 && arrival_y == 3 &&
                       displacement_x == 1 && displacement_y == 1 &&
                       reed_sensor_status[8 - (arrival_y + 1)][arrival_column] == LOW;

  if (destination_occupied) {
    if (!removeCapturedPiece(arrival_x, arrival_y)) return false;
  }
  else if (en_passant) {
    if (!removeCapturedPiece(arrival_x, arrival_y + 1)) return false;
  }

  if (!moveTrolleyStraightTo(departure_x, departure_y, SPEED_FAST))
    return false;
  boolean castling = departure_x == 5 && departure_y == 8 &&
                     arrival_y == 8 && (arrival_x == 3 || arrival_x == 7);
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

  byte departure_row = 8 - departure_y;
  byte departure_column = departure_x - 1;
  reed_sensor_status[departure_row][departure_column] = HIGH;
  reed_sensor_status[arrival_row][arrival_column] = LOW;
  if (en_passant) reed_sensor_status[8 - (arrival_y + 1)][arrival_column] = HIGH;

  if (departure_x == 5 && departure_y == 8 && arrival_y == 8) {
    if (arrival_x == 7) {
      reed_sensor_status[0][7] = HIGH;
      reed_sensor_status[0][5] = LOW;
    }
    else if (arrival_x == 3) {
      reed_sensor_status[0][0] = HIGH;
      reed_sensor_status[0][3] = LOW;
    }
  }
  return true;
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
      after_calibration = service_menu;
      sequence = calibration;
      showCalibration();
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
      runStepLossTest();
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
