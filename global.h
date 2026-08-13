/*
 * Configuration for the modified Automatic Chessboard firmware.
 * Based on "Automated Chessboard" by Greg06, CC BY-NC-SA 4.0.
 * See ATTRIBUTION.md and LICENSE.md.
 */

#ifndef AUTOMATIC_CHESSBOARD_GLOBAL_H
#define AUTOMATIC_CHESSBOARD_GLOBAL_H

// Reed occupancy is stored as one byte per rank: bit 0=file a, bit 7=file h.
// A set bit means a magnet-equipped piece is present. Three packed snapshots
// use 24 bytes instead of 192, leaving substantially more SRAM for Micro-Max.
struct BoardState {
  byte rows[8];
};
extern BoardState reed_sensor_status;
extern BoardState reed_sensor_record;
extern BoardState turn_start_status;
const byte NO_SQUARE = 255;
extern byte lifted_squares[2];
extern byte lifted_count;
extern byte move_from;
extern byte move_to;
extern byte last_sensor_square;
extern boolean last_sensor_occupied;
extern boolean human_move_ready;
extern boolean sensor_tracking_error;
extern boolean pending_move_displayed;

// Every successful reference pass parks at e6. Rank 6 is deliberately clear
// of the second (black) calibration switch lane, so the next calibration can
// seek the first (white) switch without travelling toward the corner switch.
const byte CALIBRATION_PARK_FILE = 5;
const byte CALIBRATION_PARK_RANK = 6;
extern byte trolley_coordinate_X;
extern byte trolley_coordinate_Y;
extern boolean trolley_homed;
extern boolean motion_fault;
extern boolean magnet_state;

extern char mov[5];

// User interface states.
enum {
  start_up,
  main_menu,
  position_recovery,
  calibration,
  setup_check,
  player_white,
  player_black,
  undo_required,
  ai_sensor_check,
  game_over_screen,
  fault_screen,
  service_menu,
  service_geometry_nudge,
  remote_setup_check,
  remote_human,
  remote_wait_host,
  remote_undo_required,
  remote_sensor_check,
  remote_promotion_wait,
  host_manual_motion,
  remote_route_plan
};
extern byte sequence;
extern byte after_calibration;

enum {T_B, B_T, L_R, R_L, LR_BT, RL_TB, LR_TB, RL_BT};
// T=Top, B=Bottom, L=Left, R=Right.

enum {
  SERVICE_CALIBRATE,
  SERVICE_GEOMETRY,
  SERVICE_EXIT,
  SERVICE_COUNT
};
extern byte service_item;
extern byte service_file;

// Hardware profiles. The classic Nano build remains the default. Select the
// MKS profile only through firmware/build.ps1 -HardwareProfile mks-gen-l-v1;
// making a Mega target implicit would be unsafe because generic Mega wiring
// does not match the integrated MKS driver and MOSFET connectors.
#if defined(ACB_PROFILE_MKS_GEN_L_V1)
const char HARDWARE_PROFILE_NAME[] PROGMEM = "MKS_GEN_L_V1";

// MKS Gen L V1.0 HE0 is the D10 low-side MOSFET output.
const byte MAGNET = 10;

// Integrated X and Y StepStick sockets.
const byte MOTOR_WHITE_STEP = 54;  // X STEP / A0
const byte MOTOR_WHITE_DIR = 55;   // X DIR / A1
const byte MOTOR_WHITE_ENABLE = 38;
const byte MOTOR_BLACK_STEP = 60;  // Y STEP / A6
const byte MOTOR_BLACK_DIR = 61;   // Y DIR / A7
const byte MOTOR_BLACK_ENABLE = 56;
const boolean MOTOR_ENABLE_ACTIVE_LOW = true;

// AUX-2 carries all eight control lines. These are Arduino pin numbers;
// A9/A5/A11/A10/A12 are also D63/D59/D65/D64/D66 respectively.
const byte MUX_ADDR[4] = {A9, A5, 40, 42};
const byte MUX_SELECT[4] = {A11, A10, 44, A12};
const byte MUX_OUTPUT = 4;  // SERVOS2 D4 signal pin (unfiltered digital input)

// Use the signal and GND contacts of the X- and Y- endstop connectors.
const byte BUTTON_A_LIMIT_WHITE = 3;
const byte BUTTON_B_LIMIT_BLACK = 14;

// SERVOS1 provides D11=SDA and D6=SCL for the software-I2C LCD bus.
const byte LCD_SOFTWARE_SDA = 11;
const byte LCD_SOFTWARE_SCL = 6;
#else
// Electromagnet.
const byte MAGNET = 6;

// CoreXY motors.
const byte MOTOR_WHITE_DIR = 2;
const byte MOTOR_WHITE_STEP = 3;
const byte MOTOR_BLACK_DIR = 4;
const byte MOTOR_BLACK_STEP = 5;
#endif
// This value must match the MS1/MS2/MS3 wiring on both STEP/DIR drivers.
// Supported A4988 values are 1, 2, 4, 8, and 16.
const byte MOTOR_MICROSTEPS = 1;
// ------------------------ Builder geometry ------------------------------
// These four compile-time values are the complete board registration. They
// consume no global SRAM and are never written to EEPROM. Service > GEOMETRY
// reports signed X/Y corrections at any chosen square. Record the reports,
// apply the formulas below, edit these values, upload, calibrate, and verify.
//
// FILE/RANK_PITCH_STEPS are center-to-center travel along the printed grid.
// Keep them separate: nominally square tiles can still need different step
// counts because of pulley, belt, printer, or mechanism tolerances.
const unsigned int FILE_PITCH_STEPS = 188U * MOTOR_MICROSTEPS;
const unsigned int RANK_PITCH_STEPS = 188U * MOTOR_MICROSTEPS;
// The park values are raw motor steps from the repeatable two-switch corner to
// the logical e6 center. Service > GEOMETRY reports their corrected values.
const unsigned int CALIBRATION_PARK_BLACK_STEPS = 354U * MOTOR_MICROSTEPS;
const unsigned int CALIBRATION_PARK_WHITE_STEPS = 871U * MOTOR_MICROSTEPS;
// With two reports A and B, choose different files to measure file pitch and
// different ranks to measure rank pitch (far-apart points reduce visual error):
//   new FILE = old FILE + (XB-XA)/(fileB-fileA)
//   new RANK = old RANK + (YB-YA)/(rankB-rankA)
// Round each result to the nearest whole step. Then translate either report A
// to the e6 park using those pitch changes:
//   e6X = XA-(fileA-5)*(new FILE-old FILE)
//   e6Y = YA-(rankA-6)*(new RANK-old RANK)
//   new WHITE = old WHITE+e6X; new BLACK = old BLACK-e6Y
const unsigned int CAPTURE_SIDE_X_STEPS =
    (FILE_PITCH_STEPS * 12UL + 12UL) / 25UL;

// Hardware-validated half-period delay for the current full-step drive. Using
// the same value for start, carrying, and unloaded travel intentionally
// disables the ramp and avoids the mechanism's strong low-speed resonance.
// Dividing by the microstep setting preserves physical speed if it changes.
const unsigned int MOTOR_START_DELAY = 1000U / MOTOR_MICROSTEPS;
const unsigned int SPEED_SLOW = 1000U / MOTOR_MICROSTEPS;
const unsigned int SPEED_FAST = 1000U / MOTOR_MICROSTEPS;
const unsigned int MOTOR_STEP_PULSE_US = 4;
const unsigned int HOME_MAX_STEPS =
    (FILE_PITCH_STEPS > RANK_PITCH_STEPS ? FILE_PITCH_STEPS : RANK_PITCH_STEPS) * 9U;
const unsigned int CALIBRATION_LANE_CLEARANCE_STEPS = RANK_PITCH_STEPS;
const unsigned long MAGNET_MAX_ON_MS = 30000UL;

// Reed-sensor multiplexers (classic Nano profile).
#if !defined(ACB_PROFILE_MKS_GEN_L_V1)
const byte MUX_ADDR[4] = {A3, A2, A1, A0};
const byte MUX_SELECT[4] = {13, 9, 8, 7};
const byte MUX_OUTPUT = 12;

// Button A shares its input with one limit switch; Button B shares the other.
// A6 is analog-input-only, so Button B/black limit needs an external 10 kOhm
// pull-up to 5 V. D10 is reserved for the HC-08 receive-only software UART.
const byte BUTTON_A_LIMIT_WHITE = 11;
const byte BUTTON_B_LIMIT_BLACK = A6;
const byte BLUETOOTH_RX = 10;
#endif

#endif
