/* Packed reed-matrix scanning and physical move tracking. */

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
      byte raw_row = 7 - column;
      setBoardSquare(reed_sensor_record, logicalSensorRow(raw_row),
                     logicalSensorColumn(row), high_votes < 2);
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

void copySensorTable(const BoardState &source, BoardState &destination) {
  memcpy(destination.rows, source.rows, sizeof(destination.rows));
}

byte countOccupied(const BoardState &table) {
  byte occupied = 0;
  for (byte row = 0; row < 8; row++) {
    byte bits = table.rows[row];
    while (bits) {
      bits &= bits - 1;
      occupied++;
    }
  }
  return occupied;
}

boolean startingPositionIsValid() {
  scanSensors();
  for (byte row = 0; row < 8; row++) {
    byte expected = (row < 2 || row > 5) ? 0xFF : 0;
    if (reed_sensor_record.rows[row] != expected) return false;
  }
  syncSensorState();
  return true;
}

boolean physicalSensorsMatchExpected() {
  scanSensors();
  return memcmp(reed_sensor_record.rows, reed_sensor_status.rows,
                sizeof(reed_sensor_record.rows)) == 0;
}

boolean recordMatchesTurnStart() {
  return memcmp(reed_sensor_record.rows, turn_start_status.rows,
                sizeof(reed_sensor_record.rows)) == 0;
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
      boolean old_occupied = boardSquareOccupied(reed_sensor_status,
                                                  row, column);
      boolean new_occupied = boardSquareOccupied(reed_sensor_record,
                                                  row, column);
      if (old_occupied == new_occupied) continue;

      byte square = row * 8 + column;
      last_sensor_square = square;
      last_sensor_occupied = new_occupied;

      if (!human_move_ready) {
        if (old_occupied && !new_occupied) recordLift(square);
        else if (!old_occupied && new_occupied) recordPlacement(square);
      }
      setBoardSquare(reed_sensor_status, row, column, new_occupied);
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
  // Captures are performed by lifting the moving piece first. Prefer that
  // first lift so en-passant (whose captured pawn is not on the destination)
  // is reported correctly; fall back for a lift-and-replace gesture.
  for (byte i = 0; i < lifted_count; i++) {
    byte candidate = lifted_squares[i];
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
