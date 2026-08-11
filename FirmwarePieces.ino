/* Physical captures, castling, and computer-controlled piece movement. */

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
  if (!pulseCoreXYCorridor(0, end_y, end_x, 0, 0, 0,
                           SPEED_SLOW, true)) return false;
  // The corridor completes before release. setMagnet(false) keeps the head
  // stationary for the existing pre-release delay. The extra dwell after
  // power goes low lets the piece fall clear before capture-triggered homing.
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

  // Capture recovery uses the same two-axis reference routine as ordinary
  // calibration, so both paths restore the identical e6 coordinate.
  unsigned int ignored_white = 0;
  unsigned int ignored_black = 0;
  return measureHomeReference(ignored_white, ignored_black);
}

boolean removeCapturedPiece(byte file, byte rank) {
  return removeCapturedPiecePath(file, rank, true);
}

boolean moveCastlingPieces(byte from_x, byte rank, byte to_x,
                           boolean use_magnet) {
  if (use_magnet) setMagnet(true);
  if (!moveHeldPieceSafely(from_x, rank, to_x, rank)) return false;
  setMagnet(false);

  byte rook_from = to_x > from_x ? 8 : 1;
  byte rook_to = to_x > from_x ? 6 : 4;
  if (!moveTrolleyStraightTo(rook_from, rank, SPEED_FAST)) return false;
  if (use_magnet) setMagnet(true);

  int dx = ((int)rook_to - rook_from) * (int)SQUARE_SIZE;
  int clearance = rank <= 4 ? (int)SQUARE_SIZE / 2 : -(int)SQUARE_SIZE / 2;
  if (!pulseCoreXYCorridor(0, clearance, dx, 0, 0, -clearance,
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
  boolean destination_occupied = boardSquareOccupied(
      reed_sensor_status, arrival_row, arrival_column);

  // In remote mode the Windows rules engine marks en passant explicitly. The
  // second clause preserves Micro-Max's black-only standalone behavior.
  boolean en_passant = move_flags == 'E' ||
                       (!destination_occupied && departure_y == 4 && arrival_y == 3 &&
                        displacement_x == 1 && displacement_y == 1 &&
                        boardSquareOccupied(reed_sensor_status,
                                            8 - departure_y,
                                            arrival_column));

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
    if (!moveHeldPieceSafely(departure_x, departure_y, arrival_x, arrival_y))
      return false;
  }

  setMagnet(false);
  if (motion_fault) return false;
  trolley_coordinate_X = arrival_x;
  trolley_coordinate_Y = arrival_y;
  rememberTrolleyPosition();

  byte departure_row = 8 - departure_y;
  byte departure_column = departure_x - 1;
  setBoardSquare(reed_sensor_status, departure_row, departure_column, false);
  setBoardSquare(reed_sensor_status, arrival_row, arrival_column, true);
  if (en_passant) {
    setBoardSquare(reed_sensor_status, 8 - departure_y,
                   arrival_column, false);
  }

  if (castling) {
    byte castling_row = 8 - departure_y;
    if (arrival_x == 7) {
      setBoardSquare(reed_sensor_status, castling_row, 7, false);
      setBoardSquare(reed_sensor_status, castling_row, 5, true);
    }
    else if (arrival_x == 3) {
      setBoardSquare(reed_sensor_status, castling_row, 0, false);
      setBoardSquare(reed_sensor_status, castling_row, 3, true);
    }
  }
  return true;
}

boolean blackPlayerMovement() {
  return computerPlayerMovement(lastM, 0);
}
