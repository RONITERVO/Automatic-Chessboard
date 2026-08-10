/* CoreXY stepping, calibration, persistence transitions, and path planning. */

// ---------------------------- Calibration/motion -------------------------

void configureMotorDirection(byte direction) {
  digitalWrite(MOTOR_WHITE_DIR,
               (direction == R_L || direction == T_B || direction == RL_TB) ? HIGH : LOW);
  digitalWrite(MOTOR_BLACK_DIR,
               (direction == B_T || direction == R_L || direction == RL_BT) ? HIGH : LOW);
}

unsigned int motorStepDelay(unsigned int cruise_delay, unsigned int step_index,
                            unsigned int total_steps) {
  if (cruise_delay >= MOTOR_START_DELAY || total_steps < 2) return cruise_delay;

  unsigned int ramp_steps = min(MOTOR_RAMP_STEPS, total_steps / 2U);
  if (ramp_steps == 0) return cruise_delay;

  unsigned int steps_from_end = total_steps - step_index - 1U;
  unsigned int edge_distance = min(step_index, steps_from_end);
  if (edge_distance >= ramp_steps) return cruise_delay;

  unsigned long delay_range = MOTOR_START_DELAY - cruise_delay;
  return MOTOR_START_DELAY - (unsigned int)(delay_range * edge_distance / ramp_steps);
}

void tripRemoteEmergencyStop() {
  if (remote_stop_requested) return;
  remote_stop_requested = true;
  motion_fault = true;
  trolley_homed = false;
  setMagnet(false);
  sequence = fault_screen;
  showMotionFault();
  Serial.println(F("ESTOP REMOTE"));
}

boolean drainForEmergencyStop(Stream &input, HostInputBuffer &buffer) {
  boolean requested = false;
  while (input.available()) {
    char value = input.read();
    if (value == '!') requested = true;
    if (value == '\r' || value == '\n') buffer.overflowed = false;
    else if (value >= 32 && value <= 126 && value != '!') buffer.overflowed = true;
    buffer.length = 0;
  }
  return requested;
}

boolean pollRemoteEmergencyStop() {
  if (enforceMagnetTimeout()) return true;
  if (remote_stop_requested) return true;
  boolean requested = drainForEmergencyStop(Serial, usb_host_input);
  if (drainForEmergencyStop(bluetoothInput, bluetooth_host_input)) requested = true;
  if (!requested) return false;
  tripRemoteEmergencyStop();
  return true;
}

boolean pulseMotor(byte direction, unsigned int speed_delay, unsigned int steps, boolean monitor_stops) {
  if (monitor_stops && motion_fault) return false;
  if (steps == 0) return true;
  markTrolleyPositionUnknown();
  configureMotorDirection(direction);
  delayMicroseconds(2);

  for (unsigned int step_count = 0; step_count < steps; step_count++) {
    if ((step_count & 7) == 0 && pollRemoteEmergencyStop()) return false;
    if (monitor_stops &&
        (readControlPin(BUTTON_A_LIMIT_WHITE) == LOW ||
         readControlPin(BUTTON_B_LIMIT_BLACK) == LOW)) {
      motion_fault = true;
      trolley_homed = false;
      setMagnet(false);
      return false;
    }

    unsigned int current_delay = motorStepDelay(speed_delay, step_count, steps);
    unsigned int low_time = current_delay * 2U - MOTOR_STEP_PULSE_US;

    digitalWrite(MOTOR_WHITE_STEP,
                 (direction == LR_TB || direction == RL_BT) ? LOW : HIGH);
    digitalWrite(MOTOR_BLACK_STEP,
                 (direction == LR_BT || direction == RL_TB) ? LOW : HIGH);
    delayMicroseconds(MOTOR_STEP_PULSE_US);
    digitalWrite(MOTOR_WHITE_STEP, LOW);
    digitalWrite(MOTOR_BLACK_STEP, LOW);
    delayMicroseconds(low_time);
  }
  return true;
}

boolean pulseCoreXYLine(int delta_x_steps, int delta_y_steps,
                        unsigned int speed_delay, boolean monitor_stops) {
  if (monitor_stops && motion_fault) return false;

  // CoreXY transform for the existing motor polarity:
  // white = +X -Y, black = -X -Y.
  long white_delta = (long)delta_x_steps - delta_y_steps;
  long black_delta = -(long)delta_x_steps - delta_y_steps;
  unsigned int white_steps =
      (unsigned int)(white_delta < 0 ? -white_delta : white_delta);
  unsigned int black_steps =
      (unsigned int)(black_delta < 0 ? -black_delta : black_delta);
  unsigned int event_count = max(white_steps, black_steps);
  if (event_count == 0) return true;

  markTrolleyPositionUnknown();
  digitalWrite(MOTOR_WHITE_DIR, white_delta >= 0 ? HIGH : LOW);
  digitalWrite(MOTOR_BLACK_DIR, black_delta >= 0 ? HIGH : LOW);
  delayMicroseconds(2);

  unsigned long white_accumulator = 0;
  unsigned long black_accumulator = 0;
  for (unsigned int event = 0; event < event_count; event++) {
    if ((event & 7) == 0 && pollRemoteEmergencyStop()) return false;
    if (monitor_stops &&
        (readControlPin(BUTTON_A_LIMIT_WHITE) == LOW ||
         readControlPin(BUTTON_B_LIMIT_BLACK) == LOW)) {
      motion_fault = true;
      trolley_homed = false;
      setMagnet(false);
      return false;
    }

    white_accumulator += white_steps;
    black_accumulator += black_steps;
    boolean step_white = white_accumulator >= event_count;
    boolean step_black = black_accumulator >= event_count;
    if (step_white) white_accumulator -= event_count;
    if (step_black) black_accumulator -= event_count;

    unsigned int current_delay = motorStepDelay(speed_delay, event, event_count);
    unsigned int low_time = current_delay * 2U - MOTOR_STEP_PULSE_US;
    digitalWrite(MOTOR_WHITE_STEP, step_white ? HIGH : LOW);
    digitalWrite(MOTOR_BLACK_STEP, step_black ? HIGH : LOW);
    delayMicroseconds(MOTOR_STEP_PULSE_US);
    digitalWrite(MOTOR_WHITE_STEP, LOW);
    digitalWrite(MOTOR_BLACK_STEP, LOW);
    delayMicroseconds(low_time);
  }
  return true;
}

int roundedDivide(long value, long divisor) {
  return (int)(value >= 0 ? (value + divisor / 2) / divisor
                          : (value - divisor / 2) / divisor);
}

boolean pulseCoreXYCurve(int end_x, int end_y, int control1_x, int control1_y,
                         int control2_x, int control2_y,
                         unsigned int speed_delay, boolean monitor_stops) {
  const byte segments = 12;
  const long denominator = (long)segments * segments * segments;
  int current_x = 0;
  int current_y = 0;

  for (byte i = 1; i <= segments; i++) {
    long u = segments - i;
    long x = 3L * u * u * i * control1_x +
             3L * u * i * i * control2_x + (long)i * i * i * end_x;
    long y = 3L * u * u * i * control1_y +
             3L * u * i * i * control2_y + (long)i * i * i * end_y;
    int target_x = roundedDivide(x, denominator);
    int target_y = roundedDivide(y, denominator);
    if (!pulseCoreXYLine(target_x - current_x, target_y - current_y,
                         speed_delay, monitor_stops)) return false;
    current_x = target_x;
    current_y = target_y;
  }
  return current_x == end_x && current_y == end_y;
}

boolean moveTrolleyStraightTo(byte target_x, byte target_y,
                              unsigned int speed_delay) {
  if (!trolley_homed || target_x < 1 || target_x > 8 ||
      target_y < 1 || target_y > 8) {
    motion_fault = true;
    setMagnet(false);
    return false;
  }

  int delta_x = ((int)target_x - trolley_coordinate_X) * (int)SQUARE_SIZE;
  int delta_y = ((int)target_y - trolley_coordinate_Y) * (int)SQUARE_SIZE;
  if (!pulseCoreXYLine(delta_x, delta_y, speed_delay, true)) return false;
  trolley_coordinate_X = target_x;
  trolley_coordinate_Y = target_y;
  rememberTrolleyPosition();
  return true;
}

// A legal sliding move has a clear direct corridor, so the shortest straight
// path gives the weakest magnet the least lateral acceleration. Knights use a
// smooth S-curve through the center of their L-shaped clearance corridor.
boolean moveHeldPieceSmooth(byte from_x, byte from_y, byte to_x, byte to_y) {
  int dx = ((int)to_x - from_x) * (int)SQUARE_SIZE;
  int dy = ((int)to_y - from_y) * (int)SQUARE_SIZE;
  byte squares_x = abs((int)to_x - from_x);
  byte squares_y = abs((int)to_y - from_y);
  boolean ok;

  if (squares_x == 1 && squares_y == 2) {
    ok = pulseCoreXYCurve(dx, dy, dx / 2, 0, dx / 2, dy,
                          SPEED_SLOW, true);
  }
  else if (squares_x == 2 && squares_y == 1) {
    ok = pulseCoreXYCurve(dx, dy, 0, dy / 2, dx, dy / 2,
                          SPEED_SLOW, true);
  }
  else {
    ok = pulseCoreXYLine(dx, dy, SPEED_SLOW, true);
  }

  if (!ok) return false;
  trolley_coordinate_X = to_x;
  trolley_coordinate_Y = to_y;
  rememberTrolleyPosition();
  return true;
}

boolean prepareFirstCalibrationApproach() {
  // Never seek the first (white) switch while the head is in the lane that
  // leads to the second (black) switch at the calibration corner.
  if (readControlPin(BUTTON_B_LIMIT_BLACK) == LOW) {
    calibration_lane_confirmed = false;
    trolley_homed = false;
    if (!pulseMotor(R_L, SPEED_SLOW,
                    CALIBRATION_LANE_CLEARANCE_STEPS, false)) return false;
    return readControlPin(BUTTON_B_LIMIT_BLACK) == HIGH;
  }

  // A coordinate restored from EEPROM is enough for this one safe staging
  // move, but it does not mark the trolley homed for normal chess movement.
  // Only move away from the corner; never pre-stage toward the second switch.
  if (trolley_position_known &&
      trolley_coordinate_Y > CALIBRATION_PARK_RANK) {
    int delta_y = ((int)CALIBRATION_PARK_RANK - trolley_coordinate_Y) *
                  (int)SQUARE_SIZE;
    if (!pulseCoreXYLine(0, delta_y, SPEED_FAST, false)) return false;
    trolley_coordinate_Y = CALIBRATION_PARK_RANK;
    rememberTrolleyPosition();
  }
  if (trolley_position_known) {
    calibration_lane_confirmed = false;
    return true;
  }
  if (!calibration_lane_confirmed) return false;
  calibration_lane_confirmed = false;
  return true;
}

boolean homeAxisMeasured(byte direction, byte limit_pin,
                         unsigned int &measured_steps) {
  measured_steps = 0;
  while (readControlPin(limit_pin) == HIGH && measured_steps < HOME_MAX_STEPS) {
    if (!pulseMotor(direction, SPEED_SLOW, 1, false)) return false;
    measured_steps++;
  }
  return readControlPin(limit_pin) == LOW;
}

boolean moveCalibrationCornerToPark() {
  // Both switches stay pressed until the corner is fully measured. Move
  // directly from that repeatable corner to the exact e6 parking offset.
  if (!pulseMotor(R_L, SPEED_FAST, CALIBRATION_PARK_BLACK_STEPS, false))
    return false;
  if (!pulseMotor(T_B, SPEED_FAST, CALIBRATION_PARK_WHITE_STEPS, false))
    return false;

  return readControlPin(BUTTON_A_LIMIT_WHITE) == HIGH &&
         readControlPin(BUTTON_B_LIMIT_BLACK) == HIGH;
}

boolean restoreCalibrationPark() {
  if (!moveCalibrationCornerToPark()) return false;
  trolley_coordinate_X = CALIBRATION_PARK_FILE;
  trolley_coordinate_Y = CALIBRATION_PARK_RANK;
  trolley_homed = true;
  rememberTrolleyPosition();
  return true;
}

boolean measureHomeReference(unsigned int &white_steps,
                             unsigned int &black_steps) {
  if (!prepareFirstCalibrationApproach()) {
    trolley_homed = false;
    return false;
  }
  trolley_homed = false;

  if (!homeAxisMeasured(B_T, BUTTON_A_LIMIT_WHITE, white_steps)) return false;

  // Keep the first switch pressed while finding the second switch. Calibration
  // buttons are endstops only here; use the board power switch for emergency stop.
  if (!homeAxisMeasured(L_R, BUTTON_B_LIMIT_BLACK, black_steps)) return false;

  return restoreCalibrationPark();
}

boolean calibrateBoard() {
  setMagnet(false);
  motion_fault = false;
  remote_stop_requested = false;

  if (!prepareFirstCalibrationApproach()) {
    motion_fault = true;
    trolley_homed = false;
    return false;
  }
  trolley_homed = false;

  last_home_white_steps = 0;
  last_home_black_steps = 0;
  if (!homeAxisMeasured(B_T, BUTTON_A_LIMIT_WHITE,
                        last_home_white_steps)) {
    motion_fault = true;
    return false;
  }
  if (!homeAxisMeasured(L_R, BUTTON_B_LIMIT_BLACK,
                        last_home_black_steps)) {
    motion_fault = true;
    return false;
  }

  // Keep both switches pressed until the corner is established, then move
  // directly to e6 without a separate switch-release/backoff stage.
  if (!moveCalibrationCornerToPark()) {
    motion_fault = true;
    return false;
  }
  delay(300);

  trolley_coordinate_X = CALIBRATION_PARK_FILE;
  trolley_coordinate_Y = CALIBRATION_PARK_RANK;
  trolley_homed = true;
  rememberTrolleyPosition();
  return true;
}

boolean moveTrolleyTo(byte target_x, byte target_y, unsigned int speed_delay) {
  return moveTrolleyStraightTo(target_x, target_y, speed_delay);
}

boolean enforceMagnetTimeout() {
  if (!magnet_state || millis() - magnet_on_since <= MAGNET_MAX_ON_MS)
    return false;

  digitalWrite(MAGNET, LOW);
  magnet_state = false;
  motion_fault = true;
  trolley_homed = false;
  Serial.println(F("ERR MAGNET TIMEOUT"));
  return true;
}

void setMagnet(boolean enabled) {
  if (enabled && motion_fault) return;
  if (enabled == magnet_state) return;

  if (enabled) {
    digitalWrite(MAGNET, HIGH);
    magnet_state = true;
    magnet_on_since = millis();
    delay(600);
  }
  else {
    digitalWrite(MAGNET, LOW);
    magnet_state = false;
    magnet_on_since = 0;
    delay(600);
  }
}
