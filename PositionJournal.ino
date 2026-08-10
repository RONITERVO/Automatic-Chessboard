/* Wear-levelled, power-loss-tolerant trolley position journal. */

byte positionRecordChecksum(unsigned int record_sequence, byte state,
                            byte coordinate_x, byte coordinate_y) {
  return 0x6D ^ lowByte(record_sequence) ^ highByte(record_sequence) ^
         POSITION_RECORD_VERSION ^ state ^ coordinate_x ^ coordinate_y;
}

boolean positionSequenceIsNewer(unsigned int candidate, unsigned int current) {
  return (int)((signed int)(candidate - current)) > 0;
}

boolean readPositionRecord(byte slot, unsigned int &record_sequence,
                           byte &state, byte &coordinate_x,
                           byte &coordinate_y) {
  int address = (int)slot * POSITION_RECORD_SIZE;
  if (EEPROM.read(address + 7) != POSITION_RECORD_COMMIT) return false;

  record_sequence = EEPROM.read(address) |
                    ((unsigned int)EEPROM.read(address + 1) << 8);
  byte version = EEPROM.read(address + 2);
  state = EEPROM.read(address + 3);
  coordinate_x = EEPROM.read(address + 4);
  coordinate_y = EEPROM.read(address + 5);
  byte checksum = EEPROM.read(address + 6);

  if (version != POSITION_RECORD_VERSION ||
      (state != POSITION_STATE_KNOWN && state != POSITION_STATE_UNKNOWN) ||
      checksum != positionRecordChecksum(record_sequence, state,
                                         coordinate_x, coordinate_y)) return false;
  if (state == POSITION_STATE_KNOWN &&
      (coordinate_x < 1 || coordinate_x > 8 ||
       coordinate_y < 1 || coordinate_y > 8)) return false;
  return true;
}

void loadPersistedTrolleyPosition() {
  boolean found = false;
  byte latest_state = POSITION_STATE_UNKNOWN;
  byte latest_x = 0;
  byte latest_y = 0;

  for (byte slot = 0; slot < POSITION_RECORD_SLOTS; slot++) {
    unsigned int record_sequence;
    byte state;
    byte coordinate_x;
    byte coordinate_y;
    if (!readPositionRecord(slot, record_sequence, state,
                            coordinate_x, coordinate_y)) continue;
    if (!found || positionSequenceIsNewer(record_sequence,
                                          position_record_sequence)) {
      found = true;
      position_record_slot = slot;
      position_record_sequence = record_sequence;
      latest_state = state;
      latest_x = coordinate_x;
      latest_y = coordinate_y;
    }
  }

  trolley_position_known = found && latest_state == POSITION_STATE_KNOWN;
  trolley_homed = false;
  if (trolley_position_known) {
    trolley_coordinate_X = latest_x;
    trolley_coordinate_Y = latest_y;
  }
}

void appendPositionRecord(byte state, byte coordinate_x, byte coordinate_y) {
  byte next_slot = position_record_slot < 0 ? 0 :
                   (position_record_slot + 1) % POSITION_RECORD_SLOTS;
  unsigned int next_sequence = position_record_slot < 0 ? 0 :
                               position_record_sequence + 1U;
  int address = (int)next_slot * POSITION_RECORD_SIZE;
  byte checksum = positionRecordChecksum(next_sequence, state,
                                         coordinate_x, coordinate_y);

  // Invalidate the destination first and commit it last. If power disappears
  // during this write, the previous complete journal entry remains newest.
  EEPROM.update(address + 7, 0);
  EEPROM.update(address, lowByte(next_sequence));
  EEPROM.update(address + 1, highByte(next_sequence));
  EEPROM.update(address + 2, POSITION_RECORD_VERSION);
  EEPROM.update(address + 3, state);
  EEPROM.update(address + 4, coordinate_x);
  EEPROM.update(address + 5, coordinate_y);
  EEPROM.update(address + 6, checksum);
  EEPROM.update(address + 7, POSITION_RECORD_COMMIT);

  position_record_slot = next_slot;
  position_record_sequence = next_sequence;
}

void markTrolleyPositionUnknown() {
  if (!trolley_position_known) return;
  appendPositionRecord(POSITION_STATE_UNKNOWN, 0, 0);
  trolley_position_known = false;
}

void rememberTrolleyPosition() {
  if (trolley_position_known) return;
  appendPositionRecord(POSITION_STATE_KNOWN,
                       trolley_coordinate_X, trolley_coordinate_Y);
  trolley_position_known = true;
}

void showPersistedTrolleyPosition() {
  lcd.clear();
  lcd.setCursor(0, 0);
  lcd.print(F("LAST HEAD: "));
  if (trolley_position_known) {
    lcd.print((char)('a' + trolley_coordinate_X - 1));
    lcd.print(trolley_coordinate_Y);
    lcd.setCursor(0, 1);
    lcd.print(F("CAL BEFORE USE"));
  }
  else {
    lcd.print(F("??"));
    lcd.setCursor(0, 1);
    lcd.print(F("POS UNKNOWN"));
  }
  delay(2500);
}

void showPositionRecovery() {
  lcd.clear();
  lcd.setCursor(0, 0);
  lcd.print(F("SET HEAD <= R6"));
  lcd.setCursor(0, 1);
  lcd.print(F("A=READY B=MENU"));
}
