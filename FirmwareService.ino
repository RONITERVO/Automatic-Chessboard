/* Two-button local diagnostics and guarded manual controls. */

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
      requestCalibration(service_menu);
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
      boolean recorded = boardSquareOccupied(reed_sensor_record, row, column);
      if (recorded != boardSquareOccupied(reed_sensor_status, row, column)) {
        last_sensor_square = row * 8 + column;
        last_sensor_occupied = recorded;
        setBoardSquare(reed_sensor_status, row, column, recorded);
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
