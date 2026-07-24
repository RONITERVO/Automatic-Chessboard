#ifndef AUTOMATIC_CHESSBOARD_GLOBAL_H
#define AUTOMATIC_CHESSBOARD_GLOBAL_H

// Reed sensors: LOW means a magnet-equipped chess piece is present.
byte reed_sensor_status[8][8];
byte reed_sensor_record[8][8];
byte turn_start_status[8][8];

const byte NO_SQUARE = 255;
byte lifted_squares[2] = {NO_SQUARE, NO_SQUARE};
byte lifted_count = 0;
byte move_from = NO_SQUARE;
byte move_to = NO_SQUARE;
byte last_sensor_square = NO_SQUARE;
boolean last_sensor_occupied = false;
boolean human_move_ready = false;
boolean sensor_tracking_error = false;
boolean pending_move_displayed = false;

const float TROLLEY_START_POSITION_X = 0.78;
const float TROLLEY_START_POSITION_Y = 4.65;
byte trolley_coordinate_X = 5;
byte trolley_coordinate_Y = 7;
boolean trolley_homed = false;
boolean motion_fault = false;
boolean magnet_state = false;

char mov[5] = {0, 0, 0, 0, 0};

// User interface states.
enum {
  start_up,
  main_menu,
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
  service_move_rank
};
byte sequence = start_up;
byte after_calibration = setup_check;

enum {T_B, B_T, L_R, R_L, LR_BT, RL_TB, LR_TB, RL_BT};
// T=Top, B=Bottom, L=Left, R=Right.

enum {
  SERVICE_CALIBRATE,
  SERVICE_SENSORS,
  SERVICE_MOVE,
  SERVICE_MAGNET,
  SERVICE_STEP_LOSS,
  SERVICE_EXIT,
  SERVICE_COUNT
};
byte service_item = SERVICE_CALIBRATE;
byte service_file = 1;
byte service_rank = 1;

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
const unsigned int FULL_STEPS_PER_SQUARE = 195;
const unsigned int SQUARE_SIZE = FULL_STEPS_PER_SQUARE * MOTOR_MICROSTEPS;

// Half-period delays. Slow carrying moves cruise at 200 full steps/second but
// start at the former 167-step/second rate to protect the high-friction drive.
// Dividing by the microstep setting preserves the physical carriage speed if
// microstepping is enabled in future hardware.
const unsigned int MOTOR_START_DELAY = 3000U / MOTOR_MICROSTEPS;
const unsigned int SPEED_SLOW = 2500U / MOTOR_MICROSTEPS;
const unsigned int SPEED_FAST = 1000U / MOTOR_MICROSTEPS;
const unsigned int MOTOR_STEP_PULSE_US = 4;
const unsigned int MOTOR_RAMP_STEPS = 48U * MOTOR_MICROSTEPS;
const unsigned int HOME_MAX_STEPS = SQUARE_SIZE * 9U;
const unsigned int STEP_TEST_CYCLES = 50;
const unsigned int STEP_TEST_TOLERANCE = 4U * MOTOR_MICROSTEPS;
const unsigned int STEP_TEST_LIMIT_RELEASE_STEPS = 16U * MOTOR_MICROSTEPS;

// Reed-sensor multiplexers.
const byte MUX_ADDR[4] = {A3, A2, A1, A0};
const byte MUX_SELECT[4] = {13, 9, 8, 7};
const byte MUX_OUTPUT = 12;

// Button A shares its input with one limit switch; Button B shares the other.
const byte BUTTON_A_LIMIT_WHITE = 11;
const byte BUTTON_B_LIMIT_BLACK = 10;

extern char lastM[];

#endif
