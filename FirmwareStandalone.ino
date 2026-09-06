/* Standalone buttons, LCD screens, and human-vs-Micro-Max play. */

// Waits for release so the same input can become an emergency stop during motion.
byte readControlPin(byte pin) {
#if defined(ACB_PROFILE_MKS_GEN_L_V1)
  return digitalRead(pin);
#else
  if (pin == BUTTON_B_LIMIT_BLACK) return analogRead(pin) >= 512 ? HIGH : LOW;
  return digitalRead(pin);
#endif
}

boolean buttonPressed(byte pin) {
  if (readControlPin(pin) == HIGH) return false;
  delay(20);
  if (readControlPin(pin) == HIGH) return false;

  unsigned long started = millis();
  while (readControlPin(pin) == LOW) {
    if (millis() - started > 2000UL) {
      if (pin == BUTTON_B_LIMIT_BLACK && (sequence == player_white || sequence == remote_human)) {
        if (remote_mode) stopRemoteSession();
        else returnToMainMenu();
        while (readControlPin(pin) == LOW) processHostSerial();
      }
      return false;
    }
  }
  delay(20);
  return true;
}

void returnToMainMenu() {
  setMagnet(false);
  remote_mode = false;
  remote_human_move_pending = false;
  resetMoveTracker();
  AI_reset();
  sequence = main_menu;
  showMainMenu();
}

void showMainMenu() {
  lcd.clear();
  lcd.setCursor(0, 0);
  lcd.print(F("A:GAME B:CAL"));
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

void showManualAiMove() {
  lcd.clear();
  lcd.setCursor(0, 0);
  if (move_from == NO_SQUARE) {
    lcd.print(F("MANUAL "));
    printMove(lastM);
  }
  else {
    lcd.print(F("REMOVE "));
    printSquare(move_from);
  }
  lcd.setCursor(0, 1);
  lcd.print(F("A=READY B=MENU"));
}

void prepareManualAiPlacement() {
  byte from_file = lastM[0] - 'a' + 1;
  byte from_rank = lastM[1] - '0';
  byte to_file = lastM[2] - 'a' + 1;
  byte to_rank = lastM[3] - '0';
  move_from = NO_SQUARE;
  // The player confirmed capture removal. Retry using software occupancy.
  if (carriedPathClear(from_file, from_rank, to_file, to_rank, 0, 0) ||
      carriedRouteClear(from_file, from_rank, to_file, to_rank, NO_SQUARE)) {
    beginAiTurn();
    return;
  }
  setBoardSquare(reed_sensor_status, 8 - from_rank, from_file - 1, false);
  setBoardSquare(reed_sensor_status, 8 - to_rank, to_file - 1, true);
  showManualAiMove();
}

void showPendingMove() {
  lcd.clear();
  lcd.setCursor(0, 0);
  lcd.print(move_edit_stage == 1 ? F("FROM ") : move_edit_stage == 2 ? F("TO ") : F("MOVE "));
  printSquare(move_from);
  lcd.print('-');
  printSquare(move_to);
  lcd.setCursor(0, 1);
  lcd.print(move_edit_stage ? F("A=SET B=NEXT") : F("A=YES B=EDIT"));
}

void beginAiTurn() {
  lcd.clear();
  lcd.print(F("AI MOVE "));
  printMove(lastM);
  lcd.setCursor(0, 1);
  lcd.print(F("KEEP HANDS CLEAR"));
  delay(800);
  sequence = player_black;
}

void beginHumanTurn() {
  copySensorTable(reed_sensor_status, turn_start_status);
  resetMoveTracker();
  sequence = player_white;
  lcd.clear();
  lcd.setCursor(0, 0);
  lcd.print(F("YOUR MOVE"));
  lcd.setCursor(0, 1);
  lcd.print(F("A=DETECT B=EDIT"));
}

void finishHumanTurn() {
  if (!confirmHumanMove()) return;

  squareToMoveChars(move_from, move_to);
  lcd.clear();
  lcd.print(F("AI THINKING"));
  lcd.setCursor(0, 1);
  printMove(mov);

  byte ai_result = AI_HvsC(reed_sensor_status.rows);
  copySensorTable(reed_sensor_status, reed_sensor_record);
  if (ai_result == AI_INVALID_MOVE) {
    move_edit_stage = 1;
    showPendingMove();
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

  beginAiTurn();
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
