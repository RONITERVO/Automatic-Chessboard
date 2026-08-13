/* Persistent board offset and bounded visual-alignment motion. */

const int PROFILE_ADDRESS = 320;
const byte PROFILE_COMMIT = 0xA7;
signed char visual_nudge_x = 0;
signed char visual_nudge_y = 0;

byte profileChecksum(unsigned int black, unsigned int white) {
  return 0x59 ^ lowByte(black) ^ highByte(black) ^
         lowByte(white) ^ highByte(white);
}

boolean profileValuesValid(unsigned int black, unsigned int white) {
  return black >= 200U * MOTOR_MICROSTEPS &&
         black <= 600U * MOTOR_MICROSTEPS &&
         white >= 650U * MOTOR_MICROSTEPS &&
         white <= 1000U * MOTOR_MICROSTEPS;
}

void loadCalibrationProfile() {
  if (EEPROM.read(PROFILE_ADDRESS + 5) != PROFILE_COMMIT) return;
  unsigned int black = EEPROM.read(PROFILE_ADDRESS) |
      ((unsigned int)EEPROM.read(PROFILE_ADDRESS + 1) << 8);
  unsigned int white = EEPROM.read(PROFILE_ADDRESS + 2) |
      ((unsigned int)EEPROM.read(PROFILE_ADDRESS + 3) << 8);
  if (EEPROM.read(PROFILE_ADDRESS + 4) != profileChecksum(black, white) ||
      !profileValuesValid(black, white)) return;
  calibration_park_black_steps = black;
  calibration_park_white_steps = white;
}

void saveCalibrationProfile(unsigned int black, unsigned int white) {
  EEPROM.update(PROFILE_ADDRESS + 5, 0);
  EEPROM.update(PROFILE_ADDRESS, lowByte(black));
  EEPROM.update(PROFILE_ADDRESS + 1, highByte(black));
  EEPROM.update(PROFILE_ADDRESS + 2, lowByte(white));
  EEPROM.update(PROFILE_ADDRESS + 3, highByte(white));
  EEPROM.update(PROFILE_ADDRESS + 4, profileChecksum(black, white));
  EEPROM.update(PROFILE_ADDRESS + 5, PROFILE_COMMIT);
  calibration_park_black_steps = black;
  calibration_park_white_steps = white;
}

void sendCalibrationProfile() {
  Serial.print(F("CALPROFILE "));
  Serial.print(CALIBRATION_PARK_BLACK_STEPS);
  Serial.print(' ');
  Serial.println(CALIBRATION_PARK_WHITE_STEPS);
}

void applyCalibrationProfile(unsigned int black, unsigned int white) {
  if (sequence != main_menu || remote_mode || motion_fault || magnet_state ||
      visualAlignmentActive() || !profileValuesValid(black, white)) {
    sendHostError(F("PROFILE"));
    return;
  }
  saveCalibrationProfile(black, white);
  visual_nudge_x = 0;
  visual_nudge_y = 0;
  trolley_homed = false;
  markTrolleyPositionUnknown();
  sendCalibrationProfile();
}

void saveVisualAlignment() {
  int black = (int)CALIBRATION_PARK_BLACK_STEPS - visual_nudge_y;
  int white = (int)CALIBRATION_PARK_WHITE_STEPS + visual_nudge_x;
  if (!profileValuesValid(black, white)) {
    sendHostError(F("PROFILE"));
    return;
  }
  visual_nudge_x = 0;
  visual_nudge_y = 0;
  applyCalibrationProfile(black, white);
}

boolean visualAlignmentActive() {
  return visual_nudge_x || visual_nudge_y;
}

void cancelVisualAlignment() {
  if (!visualAlignmentActive()) {
    Serial.println(F("CALCANCELLED"));
    return;
  }
  boolean moved = pulseCoreXYLine(-visual_nudge_x, -visual_nudge_y,
                                  SPEED_FAST, true);
  if (!moved || motion_fault) {
    hostMotionFault(F("MOTION"));
    return;
  }
  visual_nudge_x = 0;
  visual_nudge_y = 0;
  rememberTrolleyPosition();
  Serial.println(F("CALCANCELLED"));
}

void nudgeVisualAlignment(char axis, char sign, byte steps) {
  signed char *offset = axis == 'X' ? &visual_nudge_x : &visual_nudge_y;
  int delta = sign == '+' ? steps : -steps;
  int proposed = *offset + delta;
  if (sequence != main_menu || remote_mode || motion_fault || !trolley_homed ||
      trolley_coordinate_X != CALIBRATION_PARK_FILE ||
      trolley_coordinate_Y != CALIBRATION_PARK_RANK ||
      (steps != 1 && steps != 5) || proposed < -60 || proposed > 60) {
    sendHostError(F("NUDGE"));
    return;
  }
  setMagnet(true);
  boolean moved = pulseCoreXYLine(axis == 'X' ? delta : 0,
                                  axis == 'Y' ? delta : 0,
                                  SPEED_FAST, true);
  setMagnet(false);
  if (!moved || motion_fault) {
    hostMotionFault(F("MOTION"));
    return;
  }
  *offset = proposed;
  trolley_homed = true;
  Serial.print(F("NUDGED "));
  Serial.print((int)visual_nudge_x);
  Serial.print(' ');
  Serial.println((int)visual_nudge_y);
}
