/* Two-button local diagnostics and compile-time geometry measurement. */

const byte GEOMETRY_SAVE = 4;
const byte GEOMETRY_PLACE_MARKER = 5;
#define GEOMETRY_OFFSET(axis) ((signed char)lifted_squares[axis])

void leaveGeometryMeasurement() {
  setMagnet(false);
  boolean moved = pulseCoreXYLine(-GEOMETRY_OFFSET(0), -GEOMETRY_OFFSET(1),
                                  SPEED_FAST, true);
  if (!moved || motion_fault) {
    sequence = fault_screen;
    showMotionFault();
    return;
  }
  trolley_homed = false;
  markTrolleyPositionUnknown();
  resetMoveTracker();
  showServiceMenu();
}

// Geometry mode reuses move-tracker fields only while no game/remote operation
// is active: lifted_squares store signed X/Y corrections, lifted_count stores
// the nudge menu item, and service_file stores the selected 0..63 square.

void showServiceMenu() {
  sequence = service_menu;
  lcd.clear();
  lcd.setCursor(0, 0);
  if (service_item == SERVICE_GEOMETRY) {
    lcd.print('F');
    lcd.print(FILE_PITCH_STEPS);
    lcd.print(F(" R"));
    lcd.print(RANK_PITCH_STEPS);
    lcd.print(F(" B"));
    lcd.print(CALIBRATION_PARK_BLACK_STEPS);
    lcd.setCursor(0, 1);
    lcd.print('W');
    lcd.print(CALIBRATION_PARK_WHITE_STEPS);
    lcd.print(F(" A=RUN B=N"));
    return;
  }
  lcd.print(F("TEST: "));
  switch (service_item) {
    case SERVICE_CALIBRATE: lcd.print(F("CALIBRATE")); break;
    case SERVICE_GEOMETRY:  break;
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

    case SERVICE_GEOMETRY:
      if (!trolley_homed) {
        lcd.clear();
        lcd.print(F("CALIBRATE FIRST"));
        delay(1200);
        showServiceMenu();
      }
      else {
        lifted_squares[0] = 0;
        lifted_squares[1] = 0;
        service_file = (8 - trolley_coordinate_Y) * 8 + trolley_coordinate_X - 1;
        lifted_count = GEOMETRY_PLACE_MARKER;
        showGeometryNudge();
      }
      break;

    case SERVICE_EXIT:
      returnToMainMenu();
      break;
  }
}

void showGeometryNudge() {
  sequence = service_geometry_nudge;
  lcd.clear();
  lcd.print(F("GEO "));
  if (lifted_count == GEOMETRY_PLACE_MARKER) {
    printSquare(service_file);
    lcd.setCursor(0, 1);
    lcd.print(F("B=NEXT A=GO"));
    return;
  }

  if (lifted_count < GEOMETRY_SAVE) {
    byte axis = (lifted_count & 2) ? 1 : 0;
    boolean positive = !(lifted_count & 1);
    lcd.print(axis == 0 ? 'X' : 'Y');
    lcd.print(positive ? '+' : '-');
    lcd.print('1');
    lcd.print('=');
    int offset = GEOMETRY_OFFSET(axis);
    if (offset >= 0) lcd.print('+');
    lcd.print(offset);
  }
  else lcd.print(F("SAVE"));
  lcd.setCursor(0, 1);
  lcd.print(F("A=DO B=NEXT"));
}

void storeGeometryPoint() {
  int dx = GEOMETRY_OFFSET(0);
  int dy = GEOMETRY_OFFSET(1);
  lcd.clear();
  lcd.print((char)('a' + trolley_coordinate_X - 1));
  lcd.print(trolley_coordinate_Y);
  lcd.print(F(" X"));
  if (dx >= 0) lcd.print('+');
  lcd.print(dx);
  lcd.print(F(" Y"));
  if (dy >= 0) lcd.print('+');
  lcd.print(dy);
  lcd.setCursor(0, 1);
  lcd.print(F("RECORD"));
  delay(2600);
  leaveGeometryMeasurement();
}

boolean moveGeometryHead() {
  byte file = (service_file & 7) + 1;
  byte rank = 8 - (service_file >> 3);
  if (!moveTrolleyStraightTo(file, rank, SPEED_FAST)) return false;
  lcd.clear();
  lcd.print(F("PLACE "));
  printSquare(service_file);
  lcd.setCursor(0, 1);
  lcd.print(F("A=ALIGN B=EXIT"));
  lifted_count = GEOMETRY_SAVE + 2;
  return true;
}

void serviceGeometryNudgeLoop() {
  if (lifted_count == GEOMETRY_PLACE_MARKER) {
    if (buttonPressed(BUTTON_B_LIMIT_BLACK)) {
      service_file = service_file == 63 ? 0 : service_file + 1;
      showGeometryNudge();
    }
    else if (buttonPressed(BUTTON_A_LIMIT_WHITE) && !moveGeometryHead()) {
      sequence = fault_screen;
      showMotionFault();
    }
    return;
  }
  if (lifted_count == GEOMETRY_SAVE + 2) {
    if (buttonPressed(BUTTON_B_LIMIT_BLACK)) leaveGeometryMeasurement();
    else if (buttonPressed(BUTTON_A_LIMIT_WHITE)) {
      lifted_count = 0;
      showGeometryNudge();
    }
    return;
  }
  if (buttonPressed(BUTTON_B_LIMIT_BLACK)) {
    lifted_count = (lifted_count + 1) % (GEOMETRY_SAVE + 1);
    showGeometryNudge();
    return;
  }
  if (!buttonPressed(BUTTON_A_LIMIT_WHITE)) return;
  if (lifted_count == GEOMETRY_SAVE) storeGeometryPoint();
  else {

    byte axis = (lifted_count & 2) ? 1 : 0;
    int delta = 1;
    if (lifted_count & 1) delta = -delta;
    int proposed = GEOMETRY_OFFSET(axis) + delta;
    if (proposed < -60 || proposed > 60) {
      lcd.clear();
      lcd.print(F("LIMIT +/-60"));
      delay(800);
      showGeometryNudge();
      return;
    }
    setMagnet(true);
    boolean moved = pulseCoreXYLine(axis == 0 ? delta : 0,
                                    axis == 1 ? delta : 0,
                                    SPEED_FAST, true);
    setMagnet(false);
    if (!moved || motion_fault) {
      sequence = fault_screen;
      showMotionFault();
      return;
    }
    lifted_squares[axis] = (byte)(signed char)proposed;
    showGeometryNudge();
  }
}
