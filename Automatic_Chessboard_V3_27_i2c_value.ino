#include <Wire.h>
#include <LiquidCrystal_I2C.h>
#include "global.h"
#include "Micro_Max.h"

LiquidCrystal_I2C lcd(0x27, 16, 2);

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
  // SPEED_SLOW is also the safe starting/stopping speed. Slow carrying moves
  // already use this delay, so they remain constant-speed.
  if (cruise_delay >= SPEED_SLOW || total_steps < 2) return cruise_delay;

  unsigned int ramp_steps = min(MOTOR_RAMP_STEPS, total_steps / 2U);
  if (ramp_steps == 0) return cruise_delay;

  unsigned int steps_from_end = total_steps - step_index - 1U;
  unsigned int edge_distance = min(step_index, steps_from_end);
  if (edge_distance >= ramp_steps) return cruise_delay;

  unsigned long delay_range = SPEED_SLOW - cruise_delay;
  return SPEED_SLOW - (unsigned int)(delay_range * edge_distance / ramp_steps);
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

boolean runStepTestPattern() {
  const byte waypoint_x[] = {2, 7, 7, 2, 5};
  const byte waypoint_y[] = {2, 2, 7, 7, 7};
  const byte waypoint_count = sizeof(waypoint_x) / sizeof(waypoint_x[0]);

  for (byte i = 0; i < waypoint_count; i++) {
    if (!moveTrolleyTo(waypoint_x[i], waypoint_y[i], SPEED_FAST)) return false;
  }
  return true;
}

void showStepTestDifference(unsigned int cycle, int white_delta, int black_delta) {
  lcd.clear();
  lcd.print(F("STEP LOSS C"));
  if (cycle < 10) lcd.print('0');
  lcd.print(cycle);
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
    showServiceMenu();
    return;
  }

  for (unsigned int cycle = 1; cycle <= STEP_TEST_CYCLES; cycle++) {
    lcd.clear();
    lcd.print(F("STEP TEST "));
    if (cycle < 10) lcd.print('0');
    lcd.print(cycle);
    lcd.print('/');
    lcd.print(STEP_TEST_CYCLES);
    lcd.setCursor(0, 1);
    lcd.print(F("A/B=E-STOP"));

    if (!runStepTestPattern()) {
      aborted = digitalRead(BUTTON_A_LIMIT_WHITE) == LOW ||
                digitalRead(BUTTON_B_LIMIT_BLACK) == LOW;
      trolley_homed = false;
      lcd.clear();
      lcd.print(aborted ? F("TEST ABORTED") : F("MOTION TEST FAIL"));
      lcd.setCursor(0, 1);
      lcd.print(F("CAL REQUIRED"));
      delay(2500);
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
      showServiceMenu();
      return;
    }

    int white_delta = (int)measured_white - (int)baseline_white;
    int black_delta = (int)measured_black - (int)baseline_black;
    if (abs(white_delta) > (int)STEP_TEST_TOLERANCE ||
        abs(black_delta) > (int)STEP_TEST_TOLERANCE) {
      trolley_homed = false;
      showStepTestDifference(cycle, white_delta, black_delta);
      delay(4000);
      showServiceMenu();
      return;
    }
  }

  lcd.clear();
  lcd.print(F("STEP TEST PASS"));
  lcd.setCursor(0, 1);
  lcd.print(STEP_TEST_CYCLES);
  lcd.print(F(" CYCLES OK"));
  delay(3000);
  showServiceMenu();
}

boolean moveTrolleyTo(byte target_x, byte target_y, unsigned int speed_delay) {
  if (!trolley_homed || target_x < 1 || target_x > 8 || target_y < 1 || target_y > 8) {
    motion_fault = true;
    setMagnet(false);
    return false;
  }

  byte distance_x = abs((int)target_x - (int)trolley_coordinate_X);
  byte distance_y = abs((int)target_y - (int)trolley_coordinate_Y);

  if (target_x > trolley_coordinate_X) {
    if (!motor(T_B, speed_delay, distance_x, true)) return false;
  }
  else if (target_x < trolley_coordinate_X) {
    if (!motor(B_T, speed_delay, distance_x, true)) return false;
  }
  trolley_coordinate_X = target_x;

  if (target_y > trolley_coordinate_Y) {
    if (!motor(L_R, speed_delay, distance_y, true)) return false;
  }
  else if (target_y < trolley_coordinate_Y) {
    if (!motor(R_L, speed_delay, distance_y, true)) return false;
  }
  trolley_coordinate_Y = target_y;
  return true;
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

boolean removeCapturedPiece(byte file, byte rank) {
  if (!moveTrolleyTo(file, rank, SPEED_FAST)) return false;
  setMagnet(true);
  if (!motor(R_L, SPEED_SLOW, 0.5, true)) return false;
  if (!motor(B_T, SPEED_SLOW, file - 0.5, true)) return false;
  setMagnet(false);
  if (!motor(L_R, SPEED_FAST, 0.5, true)) return false;
  if (!motor(T_B, SPEED_FAST, file - 0.5, true)) return false;
  trolley_coordinate_X = file;
  trolley_coordinate_Y = rank;
  return true;
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

  if (!moveTrolleyTo(departure_x, departure_y, SPEED_FAST)) return false;
  setMagnet(true);

  // Knight movement.
  if ((displacement_x == 1 && displacement_y == 2) ||
      (displacement_x == 2 && displacement_y == 1)) {
    if (displacement_y == 2) {
      if (!motor(departure_x < arrival_x ? T_B : B_T, SPEED_SLOW, displacement_x * 0.5, true)) return false;
      if (!motor(departure_y < arrival_y ? L_R : R_L, SPEED_SLOW, displacement_y, true)) return false;
      if (!motor(departure_x < arrival_x ? T_B : B_T, SPEED_SLOW, displacement_x * 0.5, true)) return false;
    }
    else {
      if (!motor(departure_y < arrival_y ? L_R : R_L, SPEED_SLOW, displacement_y * 0.5, true)) return false;
      if (!motor(departure_x < arrival_x ? T_B : B_T, SPEED_SLOW, displacement_x, true)) return false;
      if (!motor(departure_y < arrival_y ? L_R : R_L, SPEED_SLOW, displacement_y * 0.5, true)) return false;
    }
  }
  // Straight diagonal movement.
  else if (displacement_x == displacement_y) {
    byte direction;
    if (departure_x > arrival_x && departure_y > arrival_y) direction = RL_BT;
    else if (departure_x > arrival_x && departure_y < arrival_y) direction = LR_BT;
    else if (departure_x < arrival_x && departure_y > arrival_y) direction = RL_TB;
    else direction = LR_TB;
    if (!motor(direction, SPEED_SLOW, displacement_x, true)) return false;
  }
  // Black kingside castling: king e8-g8, then rook h8-f8.
  else if (departure_x == 5 && departure_y == 8 && arrival_x == 7 && arrival_y == 8) {
    if (!motor(R_L, SPEED_SLOW, 0.5, true)) return false;
    if (!motor(T_B, SPEED_SLOW, 2, true)) return false;
    setMagnet(false);
    if (!motor(T_B, SPEED_FAST, 1, true)) return false;
    if (!motor(L_R, SPEED_FAST, 0.5, true)) return false;
    setMagnet(true);
    if (!motor(B_T, SPEED_SLOW, 2, true)) return false;
    setMagnet(false);
    if (!motor(T_B, SPEED_FAST, 1, true)) return false;
    if (!motor(R_L, SPEED_FAST, 0.5, true)) return false;
    setMagnet(true);
    if (!motor(L_R, SPEED_SLOW, 0.5, true)) return false;
  }
  // Black queenside castling: king e8-c8, then rook a8-d8.
  else if (departure_x == 5 && departure_y == 8 && arrival_x == 3 && arrival_y == 8) {
    if (!motor(R_L, SPEED_SLOW, 0.5, true)) return false;
    if (!motor(B_T, SPEED_SLOW, 2, true)) return false;
    setMagnet(false);
    if (!motor(B_T, SPEED_FAST, 2, true)) return false;
    if (!motor(L_R, SPEED_FAST, 0.5, true)) return false;
    setMagnet(true);
    if (!motor(T_B, SPEED_SLOW, 3, true)) return false;
    setMagnet(false);
    if (!motor(B_T, SPEED_FAST, 1, true)) return false;
    if (!motor(R_L, SPEED_FAST, 0.5, true)) return false;
    setMagnet(true);
    if (!motor(L_R, SPEED_SLOW, 0.5, true)) return false;
  }
  else if (displacement_y == 0) {
    if (!motor(departure_x > arrival_x ? B_T : T_B, SPEED_SLOW, displacement_x, true)) return false;
  }
  else if (displacement_x == 0) {
    if (!motor(departure_y > arrival_y ? R_L : L_R, SPEED_SLOW, displacement_y, true)) return false;
  }
  else {
    motion_fault = true;
    setMagnet(false);
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
