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
  service_sensors,
  service_move_file,
  service_move_rank,
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
  SERVICE_SENSORS,
  SERVICE_MOVE,
  SERVICE_MAGNET,
  SERVICE_EXIT,
  SERVICE_COUNT
};
extern byte service_item;
extern byte service_file;
extern byte service_rank;

// Electromagnet.
const byte MAGNET = 6;

// CoreXY motors.
const byte MOTOR_WHITE_DIR = 2;
const byte MOTOR_WHITE_STEP = 3;
const byte MOTOR_BLACK_DIR = 4;
const byte MOTOR_BLACK_STEP = 5;
// This value must match the MS1/MS2/MS3 wiring on both STEP/DIR drivers.
// Supported A4988 values are 1, 2, 4, 8, and 16.
const byte MOTOR_MICROSTEPS = 1;
// Repeated collision-free camera registration measured about 188 steps per
// square. This keeps every outer square inside the proven physical envelope.
const unsigned int SQUARE_SIZE = 188U * MOTOR_MICROSTEPS;
// Physical corner-to-e6 offsets are deliberately independent of logical
// square pitch. These values register the e6 park to a camera-observed physical
// calibration sheet; keep them separate so future board revisions can change
// the origin without changing the measured square pitch.
const unsigned int DEFAULT_PARK_BLACK_STEPS = 354U * MOTOR_MICROSTEPS;
const unsigned int DEFAULT_PARK_WHITE_STEPS = 871U * MOTOR_MICROSTEPS;
extern unsigned int calibration_park_black_steps;
extern unsigned int calibration_park_white_steps;
#define CALIBRATION_PARK_BLACK_STEPS calibration_park_black_steps
#define CALIBRATION_PARK_WHITE_STEPS calibration_park_white_steps
const unsigned int CAPTURE_SIDE_X_STEPS =
    (SQUARE_SIZE * 12UL + 12UL) / 25UL;

// Hardware-validated half-period delay for the current full-step drive. Using
// the same value for start, carrying, and unloaded travel intentionally
// disables the ramp and avoids the mechanism's strong low-speed resonance.
// Dividing by the microstep setting preserves physical speed if it changes.
const unsigned int MOTOR_START_DELAY = 1000U / MOTOR_MICROSTEPS;
const unsigned int SPEED_SLOW = 1000U / MOTOR_MICROSTEPS;
const unsigned int SPEED_FAST = 1000U / MOTOR_MICROSTEPS;
const unsigned int MOTOR_STEP_PULSE_US = 4;
const unsigned int MOTOR_RAMP_STEPS = 48U * MOTOR_MICROSTEPS;
const unsigned int HOME_MAX_STEPS = SQUARE_SIZE * 9U;
const unsigned int CALIBRATION_LANE_CLEARANCE_STEPS = SQUARE_SIZE;
const unsigned long MAGNET_MAX_ON_MS = 30000UL;

// Reed-sensor multiplexers.
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
