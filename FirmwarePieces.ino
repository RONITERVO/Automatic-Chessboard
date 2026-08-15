/* Physical captures, castling, and computer-controlled piece movement. */

// ---------------------------- AI physical movement -----------------------

// Find a shortest empty orthogonal route either to one target square or, when
// target is NO_SQUARE, to any a-file bin exit. The caller's path buffer doubles
// as the BFS queue; the only other workspace is one 64-byte parent table on the
// stack, so no global SRAM or EEPROM is consumed. West-first ordering makes
// equally short capture routes prefer the bin.
byte findEmptyPath(byte source, byte goal, byte ignored, byte *path) {
  byte parent[64];
  memset(parent, NO_SQUARE, sizeof(parent));
  // This is the deepest shared routing workspace: callers also hold a 64-byte
  // path buffer. Preserve that transient SRAM low-water mark for TELEM.
  freeRam();
  byte head = 0;
  byte tail = 1;
  path[0] = source;
  parent[source] = source;
  byte reached = NO_SQUARE;

  while (head < tail) {
    byte current = path[head++];
    if ((goal == NO_SQUARE && !(current & 7)) || current == goal) {
      reached = current;
      break;
    }
    byte file = current & 7;
    byte rank = current >> 3;
    for (byte direction = 0; direction < 4; direction++) {
      byte next;
      if (direction == 0) {
        if (!file) continue;
        next = current - 1;
      }
      else if (direction == 1) {
        if (!rank) continue;
        next = current - 8;
      }
      else if (direction == 2) {
        if (rank == 7) continue;
        next = current + 8;
      }
      else {
        if (file == 7) continue;
        next = current + 1;
      }
      if (parent[next] != NO_SQUARE ||
          (next != ignored &&
           boardSquareOccupied(reed_sensor_record, 7 - (next >> 3), next & 7)))
        continue;
      parent[next] = current;
      path[tail++] = next;
    }
  }
  if (reached == NO_SQUARE) return 0;

  byte length = 0;
  do {
    path[length++] = reached;
    reached = parent[reached];
  } while (path[length - 1] != source);
  for (byte left = 0, right = length - 1; left < right; left++, right--) {
    byte swap = path[left];
    path[left] = path[right];
    path[right] = swap;
  }
  return length;
}

byte findCapturePath(byte source, byte *path) {
  return findEmptyPath(source, NO_SQUARE, NO_SQUARE, path);
}

byte findCarriedPath(byte from_file, byte from_rank,
                     byte to_file, byte to_rank, byte ignored,
                     byte *path) {
  byte source = (from_rank - 1) * 8 + from_file - 1;
  byte target = (to_rank - 1) * 8 + to_file - 1;
  return findEmptyPath(source, target, ignored, path);
}

boolean captureRouteClear(byte file, byte rank) {
  byte path[64];
  return findCapturePath((rank - 1) * 8 + file - 1, path) != 0;
}

boolean carriedRouteClear(byte from_file, byte from_rank,
                          byte to_file, byte to_rank, byte ignored) {
  byte path[64];
  return findCarriedPath(from_file, from_rank, to_file, to_rank,
                         ignored, path) != 0;
}

// This preflight is used before ordinary motion and again after a manual
// capture removal. Keep one out-of-line copy on the flash-constrained Nano.
boolean __attribute__((noinline)) carriedPathClear(
    byte from_file, byte from_rank, byte to_file, byte to_rank,
    byte ignored_file, byte ignored_rank) {
  if (!queenAlignedSquares(from_file, from_rank, to_file, to_rank)) return false;
  signed char file_step = to_file == from_file ? 0 :
                          (to_file > from_file ? 1 : -1);
  signed char rank_step = to_rank == from_rank ? 0 :
                          (to_rank > from_rank ? 1 : -1);
  byte file = from_file;
  byte rank = from_rank;
  while (file != to_file || rank != to_rank) {
    byte next_file = file + file_step;
    byte next_rank = rank + rank_step;
    if (!(next_file == ignored_file && next_rank == ignored_rank) &&
        boardSquareOccupied(reed_sensor_record, 8 - next_rank,
                            next_file - 1)) return false;
    if (file_step && rank_step) {
      if (!(next_file == ignored_file && rank == ignored_rank) &&
          boardSquareOccupied(reed_sensor_record, 8 - rank,
                              next_file - 1)) return false;
      if (!(file == ignored_file && next_rank == ignored_rank) &&
          boardSquareOccupied(reed_sensor_record, 8 - next_rank,
                              file - 1)) return false;
    }
    file = next_file;
    rank = next_rank;
  }
  return true;
}

boolean followHeldPiecePath(byte *path, byte path_length) {
  if (!path_length) return false;
  byte file = (path[0] & 7) + 1;
  byte rank = (path[0] >> 3) + 1;
  byte segment_start = 0;
  while (segment_start + 1 < path_length) {
    int direction = (int)path[segment_start + 1] - path[segment_start];
    byte segment_end = segment_start + 1;
    while (segment_end + 1 < path_length &&
           (int)path[segment_end + 1] - path[segment_end] == direction)
      segment_end++;
    byte destination = path[segment_end];
    if (!moveHeldPieceSafely(file, rank, (destination & 7) + 1,
                             (destination >> 3) + 1)) return false;
    file = (destination & 7) + 1;
    rank = (destination >> 3) + 1;
    segment_start = segment_end;
  }
  return true;
}

boolean moveHeldPieceByEmptyRoute(byte from_file, byte from_rank,
                                  byte to_file, byte to_rank, byte ignored) {
  byte path[64];
  byte path_length = findCarriedPath(from_file, from_rank, to_file, to_rank,
                                     ignored, path);
  return followHeldPiecePath(path, path_length);
}

boolean removeCapturedPiecePath(byte file, byte rank, boolean use_magnet) {
  byte path[64];
  byte path_length = findCapturePath((rank - 1) * 8 + file - 1, path);
  if (!path_length) return false;
  if (!moveTrolleyStraightTo(file, rank, SPEED_FAST)) return false;
  if (use_magnet) {
    lcd.clear();
    lcd.print(F("REMOVING CAPTURE"));
    lcd.setCursor(0, 1);
    lcd.print(F("TO LEFT BIN"));
    setMagnet(true);
  }
  if (!followHeldPiecePath(path, path_length)) return false;
  byte destination = path[path_length - 1];
  file = (destination & 7) + 1;
  rank = (destination >> 3) + 1;
  int end_x = (int)CAPTURE_SIDE_X_STEPS - (int)FILE_PITCH_STEPS;
  if (!pulseCoreXYLine(end_x, 0, SPEED_SLOW, true)) return false;
  // The corridor completes before release. setMagnet(false) keeps the head
  // stationary for the existing pre-release delay. The extra dwell after
  // power goes low lets the piece fall clear before capture-triggered homing.
  setMagnet(false);
  if (use_magnet) delay(CAPTURE_DROP_SETTLE_MS);

  // The off-board bin coordinate cannot be stored as a board square. If this
  // capture left above the calibration park, move down outside the board before
  // starting the first calibration approach.
  if (rank > CALIBRATION_PARK_RANK) {
    int staging_steps = ((int)rank - CALIBRATION_PARK_RANK) *
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
  byte ignored_square = capture_rank ?
      (capture_rank - 1) * 8 + arrival_column : NO_SQUARE;
  boolean direct_path = !castling &&
      carriedPathClear(departure_x, departure_y, arrival_x, arrival_y,
                       capture_rank ? arrival_x : 0, capture_rank);
  boolean routed_path = !castling && !direct_path &&
      carriedRouteClear(departure_x, departure_y, arrival_x, arrival_y,
                        ignored_square);
  boolean manual_move = !castling && !direct_path && !routed_path;
  if (manual_move && move_flags != 'L') return false;
  if (!manual_move && capture_rank &&
      !captureRouteClear(arrival_x, capture_rank)) {
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
      if (direct_path) {
        if (!moveHeldPieceSafely(departure_x, departure_y,
                                 arrival_x, arrival_y)) return false;
      }
      else if (!moveHeldPieceByEmptyRoute(departure_x, departure_y,
                                          arrival_x, arrival_y,
                                          ignored_square)) return false;
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
