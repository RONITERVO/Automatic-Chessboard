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

#if defined(ACB_PROFILE_MKS_GEN_L_V1)
static inline int readControlPinRaw(byte pin) {
  return readControlPin(pin) == HIGH ? 1023 : 0;
}
#endif

void sendHostInfo() {
  Serial.print(F("INFO ACB2 "));
  Serial.print(F(FIRMWARE_VERSION));
#if defined(ACB_PROFILE_MKS_GEN_L_V1)
  Serial.print(F(" BOARD,TELEM,REMOTE,ESTOP,BTTEST,SWTEST,CALIBRATE,MANUAL,SENSORFRAME,PLANROUTE,REMOVE,EDGEEXIT,APPBOARD,DEVPATH,DEVJOG,ALIGN"));
  Serial.print(',');
  Serial.println((const __FlashStringHelper *)HARDWARE_PROFILE_NAME);
#else
  Serial.println(F(" BOARD,TELEM,REMOTE,ESTOP,BTTEST,SWTEST,CALIBRATE,MANUAL,SENSORFRAME,PLANROUTE,REMOVE,EDGEEXIT,APPBOARD,DEVPATH,DEVJOG,ALIGN"));
#endif
}

void sendTelemetry() {
#if defined(ACB_PROFILE_MKS_GEN_L_V1)
  int black_raw = readControlPinRaw(BUTTON_B_LIMIT_BLACK);
#else
  int black_raw = analogRead(BUTTON_B_LIMIT_BLACK);
#endif
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
  // In app-authoritative play the physical switches are deliberately ignored.
  // Mirror the Nano's independently tracked virtual occupancy so both ends can
  // still prove every route command against the same board state.
  if (remote_mode == 2) copySensorTable(reed_sensor_status, reed_sensor_record);
  else scanSensors();
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

boolean waitForExclusiveSwitch(byte target, byte other, byte target_state) {
  unsigned long started = millis();
  while (millis() - started <= 30000UL) {
    if (readControlPin(other) == LOW) return false;
    if (readControlPin(target) == target_state) return true;
    delay(5);
  }
  return false;
}

void runHostSwitchTest() {
  if (sequence != main_menu || remote_mode || magnet_state) {
    sendHostError(F("BUSY"));
    return;
  }
  setMagnet(false);
  const byte pins[2] = {BUTTON_A_LIMIT_WHITE, BUTTON_B_LIMIT_BLACK};
  for (byte index = 0; index < 2; index++) {
    byte other = pins[1 - index];
    char label = index == 0 ? 'A' : 'B';
    if (readControlPin(pins[index]) == LOW || readControlPin(other) == LOW) {
      sendHostError(F("SWTEST"));
      return;
    }
    Serial.print(F("SWTEST PRESS "));
    Serial.println(label);
    if (!waitForExclusiveSwitch(pins[index], other, LOW)) {
      sendHostError(F("SWTEST"));
      return;
    }
    if (index == 1) {
      Serial.print(F("SWTEST B RAW "));
#if defined(ACB_PROFILE_MKS_GEN_L_V1)
      Serial.println(readControlPinRaw(BUTTON_B_LIMIT_BLACK));
#else
      Serial.println(analogRead(BUTTON_B_LIMIT_BLACK));
#endif
    }
    Serial.print(F("SWTEST RELEASE "));
    Serial.println(label);
    if (!waitForExclusiveSwitch(pins[index], other, HIGH)) {
      sendHostError(F("SWTEST"));
      return;
    }
  }
  Serial.println(F("SWTEST PASS"));
}

void runHostMotorJog(char motor, char sign) {
  if (sequence != main_menu || remote_mode || motion_fault || magnet_state) {
    sendHostError(F("BUSY"));
    return;
  }

  byte direction;
  if (motor == 'W') direction = sign == '+' ? RL_TB : LR_BT;
  else direction = sign == '+' ? RL_BT : LR_TB;

  setMagnet(false);
  trolley_homed = false;
  sequence = host_manual_motion;
  boolean moved = pulseMotor(direction, SPEED_SLOW,
                             20U * MOTOR_MICROSTEPS, true);
  finishHostManualMotion(moved);
  if (!moved || motion_fault) return;
  Serial.print(F("MOVED JOG "));
  Serial.print(motor);
  Serial.println(sign);
}

void startRemoteSession(boolean human_white, boolean app_board) {
  if (motion_fault) {
    sendHostError(F("FAULT"));
    return;
  }
  if (sequence != main_menu) {
    sendHostError(F("BUSY"));
    return;
  }

  remote_mode = app_board ? 2 : 1;
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

boolean remoteBoardMatchesExpected() {
  if (remote_mode == 2) {
    copySensorTable(reed_sensor_status, reed_sensor_record);
    return true;
  }
  return physicalSensorsMatchExpected();
}

void hostMotionFault(const __FlashStringHelper *message) {
  setMagnet(false);
  motion_fault = true;
  trolley_homed = false;
  sequence = fault_screen;
  showMotionFault();
  sendHostError(message);
}

void finishHostManualMotion(boolean succeeded) {
  setMagnet(false);
  if (!succeeded || motion_fault) {
    hostMotionFault(F("MOTION"));
    return;
  }
  sequence = main_menu;
  showMainMenu();
}

void sendHostGeometry() {
  Serial.print(F("GEOMETRY ACB1 "));
  Serial.print(FILE_PITCH_STEPS);
  Serial.print(' ');
  Serial.print(RANK_PITCH_STEPS);
  Serial.print(' ');
  Serial.print(CALIBRATION_PARK_BLACK_STEPS);
  Serial.print(' ');
  Serial.print(CALIBRATION_PARK_WHITE_STEPS);
  Serial.print(' ');
  Serial.println(MOTOR_MICROSTEPS);
}

void sendHostAlignment(const __FlashStringHelper *status, boolean details) {
  Serial.print(F("ALIGN "));
  Serial.print(status);
  if (details) {
    Serial.print(' ');
    printHostSquare(trolley_coordinate_X, trolley_coordinate_Y);
    Serial.print(' ');
    Serial.print(lifted_count ? 'M' : 'H');
    Serial.print(' ');
    Serial.print((signed char)lifted_squares[0]);
    Serial.print(' ');
    Serial.print((signed char)lifted_squares[1]);
  }
  Serial.println();
}

void runHostAlignmentBegin(const char *square, boolean magnetic_marker) {
  if (sequence != main_menu || remote_mode || motion_fault ||
      magnet_state || !trolley_homed) {
    sendHostError(F("ALIGN"));
    return;
  }

  sequence = host_manual_motion;
  Serial.print(F("ALIGNING "));
  Serial.println(square);
  boolean moved = moveTrolleyStraightTo(square[0] - 'a' + 1,
                                        square[1] - '0', SPEED_FAST);
  if (!moved || motion_fault) {
    finishHostManualMotion(false);
    return;
  }

  lifted_squares[0] = 0;
  lifted_squares[1] = 0;
  lifted_count = magnetic_marker ? 1 : 0;
  // Alignment intentionally invalidates the persisted square before any
  // offset is possible. A disconnect or power loss can therefore never make
  // an offset head look calibrated on the next boot.
  markTrolleyPositionUnknown();
  trolley_homed = false;
  sequence = host_alignment;
  lcd.clear();
  lcd.print(F("ALIGN "));
  printHostSquare(trolley_coordinate_X, trolley_coordinate_Y);
  lcd.setCursor(0, 1);
  lcd.print(F("APP NUDGE / END"));
  sendHostAlignment(F("READY"), true);
}

void runHostAlignmentNudge(char axis, char sign) {
  if (sequence != host_alignment || remote_mode || motion_fault || magnet_state) {
    sendHostError(F("ALIGN"));
    return;
  }
  byte index = axis == 'X' ? 0 : 1;
  int delta = sign == '+' ? 1 : -1;
  int proposed = (signed char)lifted_squares[index] + delta;
  if (proposed < -60 || proposed > 60) {
    sendHostError(F("LIMIT"));
    return;
  }

  setMagnet(lifted_count != 0);
  boolean moved = pulseCoreXYLine(index ? 0 : delta,
                                  index ? delta : 0, SPEED_FAST, true);
  setMagnet(false);
  if (!moved || motion_fault) {
    hostMotionFault(F("MOTION"));
    return;
  }
  lifted_squares[index] = (byte)(signed char)proposed;
  sendHostAlignment(F("ACTIVE"), true);
}

void runHostAlignmentEnd() {
  if (sequence != host_alignment || remote_mode || motion_fault || magnet_state) {
    sendHostError(F("ALIGN"));
    return;
  }
  sequence = host_manual_motion;
  boolean moved = pulseCoreXYLine(-(signed char)lifted_squares[0],
                                  -(signed char)lifted_squares[1],
                                  SPEED_FAST, true);
  setMagnet(false);
  if (!moved || motion_fault) {
    hostMotionFault(F("MOTION"));
    return;
  }
  trolley_homed = false;
  markTrolleyPositionUnknown();
  resetMoveTracker();
  sequence = main_menu;
  showMainMenu();
  sendHostAlignment(F("ENDED"), false);
}

void sendHostAlignmentStatus() {
  if (sequence == host_alignment)
    sendHostAlignment(F("ACTIVE"), true);
  else
    sendHostAlignment(F("IDLE"), false);
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


void runHostPieceMove(const char *move, boolean routed) {
  if ((routed && (!remote_mode || sequence != remote_route_plan)) ||
      (!routed && (sequence != main_menu || remote_mode))) {
    sendHostError(routed ? F("NO PLAN") : F("BUSY"));
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
  if (!queenAlignedSquares(from_file, from_rank, to_file, to_rank)) {
    sendHostError(F("BAD ROUTE"));
    return;
  }

  if (remote_mode == 2) copySensorTable(reed_sensor_status, reed_sensor_record);
  else scanSensors();
  if (routed && memcmp(reed_sensor_record.rows, reed_sensor_status.rows, 8)) {
    sendHostError(F("PLAN STATE"));
    return;
  }
  if (!sensorSquareOccupied(from_file, from_rank)) {
    sendHostError(F("SOURCE EMPTY"));
    return;
  }
  if (sensorSquareOccupied(to_file, to_rank)) {
    sendHostError(F("TARGET FULL"));
    return;
  }
  if (routed) {
    byte source = (from_rank - 1) * 8 + from_file - 1;
    byte target = (to_rank - 1) * 8 + to_file - 1;
    int step;
    if (from_file == to_file) step = target > source ? 8 : -8;
    else if (from_rank == to_rank) step = target > source ? 1 : -1;
    else {
      sendHostError(F("BAD ROUTE"));
      return;
    }
    for (int square = (int)source + step; square != target; square += step) {
      if (boardSquareOccupied(reed_sensor_record, 7 - (square >> 3), square & 7)) {
        sendHostError(F("ROUTE BLOCKED"));
        return;
      }
    }
  }

  sequence = host_manual_motion;
  Serial.print(F("MOVING PIECE "));
  Serial.println(move);
  setMagnet(false);
  boolean moved = moveTrolleyStraightTo(from_file, from_rank, SPEED_FAST);
  if (moved) {
    setMagnet(true);
    moved = magnet_state && moveHeldPieceSafely(from_file, from_rank, to_file, to_rank);
  }
  setMagnet(false);
  if (routed) {
    if (!moved || motion_fault) {
      hostMotionFault(F("MOTION"));
      return;
    }
    setBoardSquare(reed_sensor_status, 8 - from_rank, from_file - 1, false);
    setBoardSquare(reed_sensor_status, 8 - to_rank, to_file - 1, true);
    if (!remoteBoardMatchesExpected()) {
      hostMotionFault(F("SENSORS"));
      return;
    }
    byte source = (from_rank - 1) * 8 + from_file - 1;
    if (move_to == source)
      move_to = (to_rank - 1) * 8 + to_file - 1;
    sequence = remote_route_plan;
  }
  else {
    finishHostManualMotion(moved);
    if (!moved || motion_fault) return;
    scanSensors();
    if (sensorSquareOccupied(from_file, from_rank) ||
        !sensorSquareOccupied(to_file, to_rank)) {
      hostMotionFault(F("SENSORS"));
      return;
    }
    syncSensorState();
  }
  Serial.print(F("MOVED PIECE "));
  Serial.println(move);
}

// Developer-only, magnet-free execution of the production piece path. This
// replaces the fixed 200-ply on-device endurance routine with a configurable
// host tool while exercising only the production queen-aligned carry planner.
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
  if (!queenAlignedSquares(from_file, from_rank, to_file, to_rank)) {
    sendHostError(F("BAD ROUTE"));
    return;
  }

  setMagnet(false);
  sequence = host_manual_motion;
  boolean moved = moveTrolleyStraightTo(from_file, from_rank, SPEED_FAST);
  if (moved) {
    moved = moveHeldPieceSafely(from_file, from_rank, to_file, to_rank);
  }
  finishHostManualMotion(moved);
  if (!moved || motion_fault) return;
  Serial.println(F("OK PATH"));
}

// ---------------- Transactional collision-safe route execution ------------

void setRouteSquare(BoardState &board, byte square, boolean occupied) {
  setBoardSquare(board, 7 - (square >> 3), square & 7, occupied);
}

boolean routedFinalStateMatches() {
  BoardState expected;
  copySensorTable(turn_start_status, expected);
  byte source = (lastM[1] - '1') * 8 + lastM[0] - 'a';
  byte target = (lastM[3] - '1') * 8 + lastM[2] - 'a';
  setRouteSquare(expected, source, false);
  if (move_from != NO_SQUARE) setRouteSquare(expected, move_from, false);
  setRouteSquare(expected, target, true);

  // The PLAN mode carries standard-castling identity because reed switches
  // report occupancy, not piece type. Connected host planners intentionally
  // support only the standard castling rook squares.
  if (remote_promotion_piece == 'k' || remote_promotion_piece == 'c') {
    byte base = (lastM[1] - '1') * 8;
    byte rook_source = base + (remote_promotion_piece == 'k' ? 7 : 0);
    byte rook_target = base + (remote_promotion_piece == 'k' ? 5 : 3);
    setRouteSquare(expected, rook_source, false);
    setRouteSquare(expected, rook_target, true);
  }
  return memcmp(reed_sensor_record.rows, expected.rows, 8) == 0;
}

void beginRemoteRoutePlan(char *arguments) {
  if (!remote_mode || sequence != remote_wait_host || remote_human_move_pending ||
      motion_fault || !trolley_homed) {
    sendHostError(F("NOT READY"));
    return;
  }

  // Fixed seven-byte payload: <from><to><mode><capture-square-or-->. Mode is
  // '-', a promotion piece q/r/b/n, or k/c for standard king/queen-side
  // castling. The Nano independently rejects a stale physical sensor frame.
  char mode = arguments[4];
  char *capture_text = arguments + 5;
  boolean no_capture = capture_text[0] == '-' && capture_text[1] == '-';
  boolean castle_text = arguments[0] == 'e' &&
      (arguments[1] == '1' || arguments[1] == '8') &&
      arguments[3] == arguments[1];
  if (!arguments[6] || arguments[7] || !validMoveText(arguments) ||
      (arguments[0] == arguments[2] && arguments[1] == arguments[3]) ||
      (mode != '-' && mode != 'q' && mode != 'r' && mode != 'b' &&
       mode != 'n' && mode != 'k' && mode != 'c') ||
      (!no_capture && !validSquareText(capture_text)) ||
      (mode == 'k' && (!castle_text || arguments[2] != 'g')) ||
      (mode == 'c' && (!castle_text || arguments[2] != 'c'))) {
    sendHostError(F("BAD PLAN"));
    return;
  }
  if (!remoteBoardMatchesExpected()) {
    sendHostError(F("PLAN STATE"));
    return;
  }

  copySensorTable(reed_sensor_status, turn_start_status);
  resetMoveTracker();
  for (byte index = 0; index < 4; index++) lastM[index] = arguments[index];
  lastM[4] = 0;
  remote_promotion_piece = mode == '-' ? 0 : mode;
  remote_move_flags = (mode == 'q' || mode == 'r' || mode == 'b' || mode == 'n')
      ? 'P' : 0;
  sequence = remote_route_plan;

  if (!no_capture) {
    byte file = capture_text[0] - 'a' + 1;
    byte rank = capture_text[1] - '0';
    if (!sensorSquareOccupied(file, rank)) {
      sequence = remote_wait_host;
      sendHostError(F("PLAN STATE"));
      return;
    }
    move_from = (rank - 1) * 8 + file - 1;  // Capture square for final-frame proof.
    move_to = move_from;  // Reuse the move tracker as the pending-removal marker.
  }
  Serial.println(F("PLAN READY"));
}

void __attribute__((noinline)) runRemoteCaptureRemoval() {
  if (sequence == remote_route_plan && move_to != NO_SQUARE) {
    byte file = (move_to & 7) + 1;
    byte rank = (move_to >> 3) + 1;
    if (remoteBoardMatchesExpected() && captureRouteClear(file, rank)) {
      setBoardSquare(reed_sensor_status, 8 - rank, file - 1, false);
      if (!removeCapturedPiece(file, rank) || !remoteBoardMatchesExpected()) {
        hostMotionFault(F("SENSORS"));
        return;
      }
      move_to = NO_SQUARE;
      Serial.println(F("REMOVED"));
      return;
    }
  }
  sendHostError(F("CAPTURE"));
}

void commitRemoteRoutePlan() {
  if (sequence != remote_route_plan) {
    sendHostError(F("NO PLAN"));
    return;
  }
  if (!remoteBoardMatchesExpected()) {
    sendHostError(F("FINAL SENSORS"));
    return;
  }
  if (recordMatchesTurnStart()) {
    syncSensorState();
    sequence = remote_wait_host;
    Serial.println(F("PLAN CANCELLED"));
    return;
  }
  if (move_to != NO_SQUARE || !routedFinalStateMatches()) {
    sendHostError(F("PLAN INCOMPLETE"));
    return;
  }
  syncSensorState();
  finishRemoteComputerTurn();
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
  if (strcmp_P(line, PSTR("GEOMETRY")) == 0) {
    sendHostGeometry();
    return;
  }
  if (strcmp_P(line, PSTR("ALIGN STATUS")) == 0) {
    sendHostAlignmentStatus();
    return;
  }
  if (strcmp(line, "BTTEST") == 0) {
    if (sequence == main_menu) testBluetoothModule();
    else sendHostError(F("BUSY"));
    return;
  }
  if (strcmp(line, "SWTEST") == 0) {
    runHostSwitchTest();
    return;
  }
  if (strncmp(line, "JOG ", 4) == 0 &&
      (line[4] == 'W' || line[4] == 'B') &&
      (line[5] == '+' || line[5] == '-') && line[6] == 0) {
    runHostMotorJog(line[4], line[5]);
    return;
  }
  if (strncmp_P(line, PSTR("ALIGN "), 6) == 0 &&
      line[6] >= 'a' && line[6] <= 'h' &&
      line[7] >= '1' && line[7] <= '8' && line[8] == ' ' &&
      (line[9] == 'H' || line[9] == 'M') && line[10] == 0) {
    runHostAlignmentBegin(line + 6, line[9] == 'M');
    return;
  }
  if (strncmp_P(line, PSTR("NUDGE "), 6) == 0 &&
      (line[6] == 'X' || line[6] == 'Y') &&
      (line[7] == '+' || line[7] == '-') && line[8] == 0) {
    runHostAlignmentNudge(line[6], line[7]);
    return;
  }
  if (strcmp_P(line, PSTR("ALIGN END")) == 0) {
    runHostAlignmentEnd();
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
    runHostPieceMove(line + 6, false);
    return;
  }
  if (strncmp(line, "PATH ", 5) == 0 && line[9] == 0 &&
      validMoveText(line + 5)) {
    runHostPathTest(line + 5);
    return;
  }
  if (strncmp(line, "START ", 6) == 0 &&
      (line[6] == 'W' || line[6] == 'B') &&
      (line[7] == 0 || (line[7] == ' ' && line[8] == 'A' &&
                        line[9] == 'P' && line[10] == 'P' && line[11] == 0))) {
    startRemoteSession(line[6] == 'W', line[7] != 0);
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
  if (strncmp(line, "PLAN ", 5) == 0) {
    beginRemoteRoutePlan(line + 5);
    return;
  }
  if (strncmp(line, "DRAG ", 5) == 0 && line[9] == 0 &&
      validMoveText(line + 5)) {
    runHostPieceMove(line + 5, true);
    return;
  }
  if (strcmp_P(line, PSTR("REMOVE")) == 0) {
    runRemoteCaptureRemoval();
    return;
  }
  if (strcmp(line, "COMMIT") == 0) {
    commitRemoteRoutePlan();
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
  if (remote_mode == 2) {
    for (byte row = 0; row < 8; row++)
      reed_sensor_status.rows[row] = (row < 2 || row > 5) ? 0xFF : 0;
    copySensorTable(reed_sensor_status, reed_sensor_record);
    copySensorTable(reed_sensor_status, turn_start_status);
  }
  Serial.print(F("SESSION "));
  Serial.println(remote_human_white ? 'W' : 'B');
  if (remote_mode == 2) {
    waitForRemoteApp();
    Serial.println(remote_human_white ? F("TURN HUMAN") : F("TURN COMPUTER"));
    return;
  }
  if (remote_human_white) beginRemoteHumanTurn();
  else {
    waitForRemoteApp();
    Serial.println(F("TURN COMPUTER"));
  }
}

void waitForRemoteApp() {
  sequence = remote_wait_host;
  lcd.clear();
  lcd.print(F("HOST MOVE"));
  lcd.setCursor(0, 1);
  lcd.print(F("B=STOP"));
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
  if (!remoteBoardMatchesExpected()) {
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
  else if (remote_mode == 2) waitForRemoteApp();
  else beginRemoteHumanTurn();
}
