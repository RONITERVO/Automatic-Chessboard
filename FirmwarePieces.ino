/* Physical captures, castling, and computer-controlled piece movement. */

// ---------------------------- AI physical movement -----------------------

// A capture may leave on the boundary below any rank. The carried piece first
// travels vertically through empty square centres, then uses that boundary
// only when every square touching the lane to its left is empty. The source
// square is ignored because it becomes empty as the captured piece departs.
boolean captureExitClear(byte file, byte source_rank, byte exit_rank) {
  byte first_rank = min(source_rank, exit_rank);
  byte last_rank = max(source_rank, exit_rank);
  for (byte rank = first_rank; rank <= last_rank; rank++) {
    if (rank != source_rank &&
        boardSquareOccupied(reed_sensor_record, 8 - rank, file - 1))
      return false;
  }

  for (byte column = 0; column < file; column++) {
    if (column < file - 1 &&
        boardSquareOccupied(reed_sensor_record, 8 - exit_rank, column))
      return false;
    if (exit_rank > 1 &&
        !(exit_rank - 1 == source_rank && column == file - 1) &&
        boardSquareOccupied(reed_sensor_record, 9 - exit_rank, column))
      return false;
  }
  return true;
}

byte findCaptureExitRank(byte file, byte source_rank) {
  // Prefer the current or a lower rank: rank 1 uses the already validated
  // outside-white-edge lane. Search upward only when no lower route is clear.
  for (byte rank = source_rank; rank > 0; rank--)
    if (captureExitClear(file, source_rank, rank)) return rank;
  for (byte rank = source_rank + 1; rank <= 8; rank++)
    if (captureExitClear(file, source_rank, rank)) return rank;
  return 0;
}

boolean removeCapturedPiecePath(byte file, byte rank, boolean use_magnet) {
  byte exit_rank = findCaptureExitRank(file, rank);
  if (!exit_rank) return false;
  if (!moveTrolleyStraightTo(file, rank, SPEED_FAST)) return false;
  if (use_magnet) {
    lcd.clear();
    lcd.print(F("REMOVING CAPTURE"));
    lcd.setCursor(0, 1);
    lcd.print(F("TO LEFT BIN"));
    setMagnet(true);
  }
  if (exit_rank != rank &&
      !moveHeldPieceSafely(file, rank, file, exit_rank)) return false;

  int end_x = (int)CAPTURE_SIDE_X_STEPS -
              (int)file * (int)FILE_PITCH_STEPS;
  int end_y = -(int)RANK_PITCH_STEPS / 2;
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
  if (exit_rank > CALIBRATION_PARK_RANK) {
    int staging_steps = ((int)exit_rank - CALIBRATION_PARK_RANK) *
                        (int)RANK_PITCH_STEPS;
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

  int dx = ((int)rook_to - rook_from) * (int)FILE_PITCH_STEPS;
  int clearance = rank <= 4 ? (int)RANK_PITCH_STEPS / 2 : -(int)RANK_PITCH_STEPS / 2;
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
  boolean castling = move_flags == 'C' ||
                     (departure_x == 5 && departure_y == arrival_y &&
                      (departure_y == 1 || departure_y == 8) && displacement_x == 2);
  // 'L' is the local Micro-Max fallback: record an unsupported knight as the
  // expected position without moving, then let the normal sensor-check screen
  // guide and verify the player's manual placement.
  boolean manual_move = !castling &&
      !queenAlignedSquares(departure_x, departure_y, arrival_x, arrival_y);
  if (manual_move && move_flags != 'L') return false;

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

  byte capture_rank = destination_occupied ? arrival_y :
                      (en_passant ? departure_y : 0);
  if (!manual_move && capture_rank &&
      !findCaptureExitRank(arrival_x, capture_rank)) {
    if (move_flags != 'L') return false;
    manual_move = true;
  }
  if (manual_move && capture_rank) {
    // Verify a manual capture in two observable phases. The target must first
    // become empty; only then may the player place the AI piece there.
    move_from = (8 - capture_rank) * 8 + arrival_column;
    setBoardSquare(reed_sensor_status, 8 - capture_rank,
                   arrival_column, false);
    return true;
  }

  if (!manual_move) {
    if (destination_occupied) {
      if (!removeCapturedPiece(arrival_x, arrival_y)) return false;
    }
    else if (en_passant) {
      if (!removeCapturedPiece(arrival_x, departure_y)) return false;
    }

    if (!moveTrolleyStraightTo(departure_x, departure_y, SPEED_FAST))
      return false;
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
  }

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
  move_from = NO_SQUARE;
  return computerPlayerMovement(lastM, 'L');
}
