"""State and presentation helpers shared by the GUI, simulator, and tests."""

from __future__ import annotations

from dataclasses import dataclass, field
from time import monotonic

import chess

from protocol import FirmwareInfo, Telemetry


SEQUENCE_NAMES = {
    0: "Starting",
    1: "Main menu / idle",
    2: "Position recovery",
    3: "Calibrating",
    4: "Checking starting position",
    5: "Human playing White",
    6: "Human playing Black",
    7: "Waiting for move undo",
    8: "Checking computer move",
    9: "Game over",
    10: "Motion fault",
    11: "Service menu",
    12: "Sensor service",
    13: "Service move: select file",
    14: "Service move: select rank",
    15: "Remote setup check",
    16: "Remote human turn",
    17: "Waiting for computer move",
    18: "Remote move must be undone",
    19: "Checking remote computer move",
    20: "Waiting for promotion piece",
}

SEQUENCE_GUIDANCE = {
    1: "Board is idle and ready for a safe diagnostic or new game.",
    2: "The saved carriage position is uncertain. Recalibrate locally before movement.",
    3: "Calibration is moving the carriage. Keep the board clear.",
    4: "Arrange all pieces in their starting squares and follow the LCD prompt.",
    7: "Restore the previous physical position, then confirm on the board.",
    8: "The board is checking the piece moved by the carriage.",
    10: "Motion was stopped. Inspect the mechanism locally before clearing the fault.",
    15: "Arrange starting pieces and press physical Button A.",
    16: "Make the human move, then press physical Button A.",
    17: "Windows may send the next legal computer move.",
    18: "The reported move was invalid. Restore the pieces physically.",
    19: "The board is checking the completed automatic move.",
    20: "Replace the promoted pawn, then press physical Button A.",
}


def expected_occupancy(board: chess.Board) -> frozenset[int]:
    return frozenset(board.piece_map())


@dataclass
class MonitorModel:
    connected: bool = False
    connection_text: str = "Disconnected"
    last_seen: float | None = None
    firmware: FirmwareInfo | None = None
    telemetry: Telemetry | None = None
    sensor_squares: frozenset[int] | None = None
    sensor_hex: str = ""
    sensor_updated: float | None = None
    expected_squares: frozenset[int] = field(default_factory=frozenset)
    last_error: str = ""

    def mark_seen(self) -> None:
        self.last_seen = monotonic()
        self.connected = True

    def age_seconds(self) -> float | None:
        return None if self.last_seen is None else max(0.0, monotonic() - self.last_seen)

    def missing_squares(self) -> frozenset[int]:
        if self.sensor_squares is None:
            return frozenset()
        return self.expected_squares - self.sensor_squares

    def unexpected_squares(self) -> frozenset[int]:
        if self.sensor_squares is None:
            return frozenset()
        return self.sensor_squares - self.expected_squares

    def sequence_name(self) -> str:
        if not self.telemetry:
            return "Unknown"
        return SEQUENCE_NAMES.get(self.telemetry.sequence, f"Unknown state {self.telemetry.sequence}")

    def guidance(self) -> str:
        if not self.connected:
            return "Reconnect to refresh the board. Values below are the last known state, not live data."
        if self.telemetry and self.telemetry.motion_fault:
            return "Keep hands clear, switch off physical motor power, and inspect the mechanism locally."
        if self.telemetry and (not self.telemetry.button_a_released or
                               not self.telemetry.button_b_released):
            return "A limit or button is active. Inspect it locally before calibration or movement."
        if self.sensor_squares is not None and (self.missing_squares() or self.unexpected_squares()):
            return ("The sensor pattern differs from the logical game. Use the coloured squares to correct "
                    "or synchronize the position before starting play.")
        if not self.telemetry:
            return "Connect and run a safe refresh to read the board state."
        return SEQUENCE_GUIDANCE.get(
            self.telemetry.sequence,
            "Follow the physical LCD and keep the mechanism in view before using motion controls.",
        )

    def overall_health(self) -> tuple[str, str]:
        age = self.age_seconds()
        if not self.connected:
            return "Disconnected", "bad"
        if age is None or age > 12:
            return "Connection stale", "bad"
        if self.telemetry and self.telemetry.motion_fault:
            return "Motion fault", "bad"
        if self.telemetry and (not self.telemetry.button_a_released or
                               not self.telemetry.button_b_released):
            return "A limit/button is active", "warn"
        if self.sensor_squares is not None and (self.missing_squares() or self.unexpected_squares()):
            return "Physical/logical position differs", "warn"
        if self.firmware is None:
            return "Connected — firmware identity unavailable", "warn"
        if "TELEM" in self.firmware.capabilities and self.telemetry is None:
            return "Waiting for live telemetry", "warn"
        if "BOARD" in self.firmware.capabilities and self.sensor_squares is None:
            return "Waiting for sensor snapshot", "warn"
        if age > 5:
            return "Updates delayed", "warn"
        return "Ready", "good"
