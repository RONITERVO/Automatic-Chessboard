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

void seedStartingPosition() {
  for (byte row = 0; row < 8; row++)
    reed_sensor_status.rows[row] = (row < 2 || row > 5) ? 0xFF : 0;
  copySensorTable(reed_sensor_status, reed_sensor_record);
  copySensorTable(reed_sensor_status, turn_start_status);
  resetMoveTracker();
}

boolean recordMatchesTurnStart() {
  return memcmp(reed_sensor_record.rows, turn_start_status.rows,
                sizeof(reed_sensor_record.rows)) == 0;
}

void resetMoveTracker() {
  move_edit_stage = 0;
  move_from = NO_SQUARE;
  move_to = NO_SQUARE;
  human_move_ready = false;
}

// Only called by the human's first confirm press. Noise never changes the
// authoritative board. Rank plausible moves by source/destination evidence;
// unrelated missing/extra reeds do not veto a move or end a game.
void detectHumanMove() {
  scanSensors();
  resetMoveTracker();
  signed char best = -1;
  for (byte from = 0; from < 64; from++) {
    for (byte to = 0; to < 64; to++) {
      if (!AI_movePossible(from, to, !remote_mode || remote_human_white)) continue;
      boolean source_empty = !boardSquareOccupied(reed_sensor_record, from >> 3, from & 7);
      boolean target_full = boardSquareOccupied(reed_sensor_record, to >> 3, to & 7);
      boolean target_was_full = boardSquareOccupied(turn_start_status, to >> 3, to & 7);
      signed char score = source_empty * 4 + target_full * (target_was_full ? 1 : 3);
      if (score > best) {
        best = score;
        move_from = from;
        move_to = to;
      }
    }
  }
  human_move_ready = move_from != NO_SQUARE;
}

// B enters correction, then cycles source and destination; A chooses each
// square and returns to the explicit move preview. No reed reading is needed
// for correction, so even a completely missed switch remains recoverable.
void editHumanMove() {
  if (!human_move_ready) {
    move_from = 63;
    move_to = 0;
    move_edit_stage = 1;
    human_move_ready = true;
  }
  if (!move_edit_stage) move_edit_stage = 1;
  else if (move_edit_stage == 1) {
    do { move_from = (move_from + 1) & 63; }
    while (!(AI_pieceAt(move_from) & ((!remote_mode || remote_human_white) ? 8 : 16)));
  }
  else {
    do { move_to = (move_to + 1) & 63; }
    while (move_to == move_from);
  }
  showPendingMove();
}

boolean confirmHumanMove() {
  if (!human_move_ready) {
    detectHumanMove();
    showPendingMove();
    return false;
  }
  if (move_edit_stage) {
    move_edit_stage = move_edit_stage == 1 ? 2 : 0;
    showPendingMove();
    return false;
  }
  return true;
}
