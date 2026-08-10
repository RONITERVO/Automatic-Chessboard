/* USB/BLE protocol and connected play orchestration. */

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
  Serial.println(F(" BOARD,TELEM,REMOTE,ESTOP,BTTEST,CALIBRATE,MANUAL,SENSORFRAME,DEVPATH"));
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
    byte occupied = reed_sensor_record.rows[row];
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
  if (motion_fault) {
    sendHostError(F("FAULT"));
    return;
  }
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

boolean validSquareText(const char *square) {
  return square[0] >= 'a' && square[0] <= 'h' &&
         square[1] >= '1' && square[1] <= '8' && square[2] == 0;
}

void printHostSquare(byte file, byte rank) {
  Serial.print((char)('a' + file - 1));
  Serial.print(rank);
}

boolean sensorSquareOccupied(byte file, byte rank) {
  return boardSquareOccupied(reed_sensor_record, 8 - rank, file - 1);
}

void finishHostManualMotion(boolean succeeded) {
  setMagnet(false);
  if (!succeeded || motion_fault) {
    motion_fault = true;
    trolley_homed = false;
    sequence = fault_screen;
    showMotionFault();
    sendHostError(F("MOTION"));
    return;
  }
  sequence = main_menu;
  showMainMenu();
}

void runHostCalibration() {
  if (motion_fault) {
    sendHostError(F("FAULT"));
    return;
  }
  if (sequence != main_menu || remote_mode) {
    sendHostError(F("BUSY"));
    return;
  }

  sequence = calibration;
  showCalibration();
  Serial.println(F("CALIBRATING"));
  if (!calibrateBoard() || !trolley_homed || magnet_state ||
      trolley_coordinate_X != CALIBRATION_PARK_FILE ||
      trolley_coordinate_Y != CALIBRATION_PARK_RANK) {
    finishHostManualMotion(false);
    return;
  }

  sequence = main_menu;
  showMainMenu();
  Serial.print(F("CALIBRATED "));
  printHostSquare(trolley_coordinate_X, trolley_coordinate_Y);
  Serial.print(F(" W"));
  Serial.print(last_home_white_steps);
  Serial.print(F(" B"));
  Serial.print(last_home_black_steps);
  Serial.println();
}

void runHostHeadMove(const char *square) {
  if (sequence != main_menu || remote_mode) {
    sendHostError(F("BUSY"));
    return;
  }
  if (motion_fault) {
    sendHostError(F("FAULT"));
    return;
  }
  if (!trolley_homed) {
    sendHostError(F("CALIBRATE"));
    return;
  }

  byte target_file = square[0] - 'a' + 1;
  byte target_rank = square[1] - '0';
  setMagnet(false);
  sequence = host_manual_motion;
  Serial.print(F("MOVING HEAD "));
  Serial.println(square);
  boolean moved = moveTrolleyStraightTo(target_file, target_rank, SPEED_FAST);
  finishHostManualMotion(moved);
  if (!moved || motion_fault) return;
  Serial.print(F("MOVED HEAD "));
  Serial.println(square);
}

void runHostPieceMove(const char *move) {
  if (sequence != main_menu || remote_mode) {
    sendHostError(F("BUSY"));
    return;
  }
  if (motion_fault) {
    sendHostError(F("FAULT"));
    return;
  }
  if (!trolley_homed) {
    sendHostError(F("CALIBRATE"));
    return;
  }

  byte from_file = move[0] - 'a' + 1;
  byte from_rank = move[1] - '0';
  byte to_file = move[2] - 'a' + 1;
  byte to_rank = move[3] - '0';
  if (from_file == to_file && from_rank == to_rank) {
    sendHostError(F("SAME SQUARE"));
    return;
  }

  scanSensors();
  if (!sensorSquareOccupied(from_file, from_rank)) {
    sendHostError(F("SOURCE EMPTY"));
    return;
  }
  if (sensorSquareOccupied(to_file, to_rank)) {
    sendHostError(F("TARGET FULL"));
    return;
  }

  sequence = host_manual_motion;
  Serial.print(F("MOVING PIECE "));
  Serial.println(move);
  setMagnet(false);
  boolean moved = moveTrolleyStraightTo(from_file, from_rank, SPEED_FAST);
  if (moved) {
    setMagnet(true);
    moved = magnet_state && moveHeldPieceSmooth(from_file, from_rank, to_file, to_rank);
  }
  setMagnet(false);
  finishHostManualMotion(moved);
  if (!moved || motion_fault) return;

  scanSensors();
  boolean sensors_agree = !sensorSquareOccupied(from_file, from_rank) &&
                          sensorSquareOccupied(to_file, to_rank);
  syncSensorState();
  if (!sensors_agree) {
    trolley_homed = false;
    motion_fault = true;
    sequence = fault_screen;
    showMotionFault();
    sendHostError(F("SENSORS"));
    return;
  }
  Serial.print(F("MOVED PIECE "));
  Serial.println(move);
}

// Developer-only, magnet-free execution of the production piece path. This
// replaces the fixed 200-ply on-device endurance routine with a configurable
// host tool while retaining the same straight and knight-curve planners.
void runHostPathTest(const char *move) {
  if (sequence != main_menu || remote_mode) {
    sendHostError(F("BUSY"));
    return;
  }
  if (motion_fault) {
    sendHostError(F("FAULT"));
    return;
  }
  if (!trolley_homed) {
    sendHostError(F("CALIBRATE"));
    return;
  }

  byte from_file = move[0] - 'a' + 1;
  byte from_rank = move[1] - '0';
  byte to_file = move[2] - 'a' + 1;
  byte to_rank = move[3] - '0';
  if (from_file == to_file && from_rank == to_rank) {
    sendHostError(F("SAME SQUARE"));
    return;
  }

  setMagnet(false);
  sequence = host_manual_motion;
  Serial.print(F("MOVING PATH "));
  Serial.println(move);
  boolean moved = moveTrolleyStraightTo(from_file, from_rank, SPEED_FAST);
  if (moved) {
    moved = moveHeldPieceSmooth(from_file, from_rank, to_file, to_rank);
  }
  finishHostManualMotion(moved);
  if (!moved || motion_fault) return;
  Serial.print(F("MOVED PATH "));
  Serial.println(move);
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
  if (strcmp(line, "CALIBRATE") == 0) {
    runHostCalibration();
    return;
  }
  if (strncmp(line, "HEAD ", 5) == 0 && validSquareText(line + 5)) {
    runHostHeadMove(line + 5);
    return;
  }
  if (strncmp(line, "PIECE ", 6) == 0 && line[10] == 0 &&
      validMoveText(line + 6)) {
    runHostPieceMove(line + 6);
    return;
  }
  if (strncmp(line, "PATH ", 5) == 0 && line[9] == 0 &&
      validMoveText(line + 5)) {
    runHostPathTest(line + 5);
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

void processHostStream(Stream &input, HostInputBuffer &buffer) {
  while (input.available()) {
    char value = input.read();
    if (value == '!') {
      buffer.length = 0;
      buffer.overflowed = false;
      tripRemoteEmergencyStop();
      continue;
    }
    if (value == '\r' || value == '\n') {
      if (!buffer.overflowed && buffer.length) {
        buffer.data[buffer.length] = 0;
        processHostCommand(buffer.data);
      }
      buffer.length = 0;
      buffer.overflowed = false;
    }
    else if (value >= 32 && value <= 126 && !buffer.overflowed) {
      if (buffer.length < HOST_INPUT_SIZE - 1) {
        buffer.data[buffer.length++] = value;
      }
      else {
        buffer.length = 0;
        buffer.overflowed = true;
        sendHostError(F("LINE LONG"));
      }
    }
  }
}

void processHostSerial() {
  processHostStream(Serial, usb_host_input);
  processHostStream(bluetoothInput, bluetooth_host_input);
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
