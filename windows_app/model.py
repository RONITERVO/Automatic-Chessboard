"""State and presentation helpers shared by the GUI, simulator, and tests."""

from __future__ import annotations

from dataclasses import dataclass, field
from time import monotonic

import chess

from protocol import FirmwareInfo, Telemetry, queen_aligned


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
    11: "Reserved",
    12: "Board alignment",
    13: "Remote setup check",
    14: "Remote human turn",
    15: "Waiting for computer move",
    16: "Remote move must be undone",
    17: "Checking remote computer move",
    18: "Waiting for promotion piece",
    19: "Direct app movement",
    20: "Verified route transaction",
}

SEQUENCE_GUIDANCE = {
    1: "Board is idle and ready for a safe diagnostic or new game.",
    2: "The saved carriage position is uncertain. Recalibrate locally before movement.",
    3: "Calibration is moving the carriage. Keep the board clear.",
    4: "Arrange all pieces in their starting squares and follow the LCD prompt.",
    7: "Restore the previous physical position, then confirm on the board.",
    8: "The board is checking the piece moved by the carriage.",
    10: "Motion was stopped. Inspect the mechanism locally before clearing the fault.",
    12: "The companion is measuring board alignment. Keep hands clear and follow its prompts.",
    13: "Arrange starting pieces and press physical Button A.",
    14: "Make the human move, then press physical Button A.",
    15: "Windows may send the next legal computer move.",
    16: "The reported move was invalid. Restore the pieces physically.",
    17: "The board is checking the completed automatic move.",
    18: "Replace the promoted pawn, then press physical Button A.",
    19: "The companion requested direct movement. Keep hands clear.",
    20: "The companion is executing a sensor-verified route. Keep hands clear.",
}


def expected_occupancy(board: chess.Board) -> frozenset[int]:
    return frozenset(board.piece_map())


def square_name(square: int) -> str:
    if square not in range(64):
        raise ValueError("Square is outside the board")
    return chess.square_name(square)


@dataclass(frozen=True)
class ManualSelection:
    mode: str = "head"
    source: int | None = None
    target: int | None = None

    @property
    def highlighted(self) -> frozenset[int]:
        return frozenset(value for value in (self.source, self.target) if value is not None)

    def with_mode(self, mode: str) -> "ManualSelection":
        if mode not in ("head", "piece"):
            raise ValueError(f"Unknown manual mode: {mode}")
        return self if mode == self.mode else ManualSelection(mode)

    def choose(self, square: int, occupied: frozenset[int] | None) -> tuple["ManualSelection", str]:
        square_name(square)
        if self.mode == "head":
            return ManualSelection("head", target=square), f"Head target {square_name(square)}"
        if self.source is None:
            if occupied is None:
                return self, "Refresh board sensors before choosing a piece"
            if square not in occupied:
                return self, "Choose a square that currently contains a piece"
            return ManualSelection("piece", source=square), (
                f"Piece selected at {square_name(square)}; choose an empty destination"
            )
        if square == self.source:
            return ManualSelection("piece"), "Source cleared; choose a piece"
        if occupied is not None and square in occupied:
            return self, f"Destination {square_name(square)} is occupied"
        if not queen_aligned(self.source, square):
            return self, "Choose a destination on the same file, rank, or diagonal"
        return ManualSelection("piece", self.source, square), (
            f"Move {square_name(self.source)} to {square_name(square)}"
        )

    def command(self) -> str | None:
        if self.mode == "head":
            return None if self.target is None else f"HEAD {square_name(self.target)}"
        if self.source is None or self.target is None:
            return None
        if not queen_aligned(self.source, self.target):
            return None
        return f"PIECE {square_name(self.source)}{square_name(self.target)}"


def calibration_matches(reported_square: str | None, telemetry: Telemetry | None) -> bool:
    return bool(
        reported_square and reported_square.lower() == "e6" and telemetry and
        telemetry.homed and not telemetry.motion_fault and not telemetry.magnet_on and
        telemetry.trolley_x == 5 and telemetry.trolley_y == 6
    )


def head_move_matches(target: int, telemetry: Telemetry | None) -> bool:
    return bool(
        telemetry and telemetry.homed and not telemetry.motion_fault and not telemetry.magnet_on and
        telemetry.trolley_x == chess.square_file(target) + 1 and
        telemetry.trolley_y == chess.square_rank(target) + 1
    )


def piece_move_matches(source: int, target: int, sensors: frozenset[int] | None) -> bool:
    return sensors is not None and source not in sensors and target in sensors


@dataclass
class MonitorModel:
    connected: bool = False
    connection_text: str = "Disconnected"
    last_seen: float | None = None
    firmware: FirmwareInfo | None = None
    telemetry: Telemetry | None = None
    telemetry_updated: float | None = None
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

    def telemetry_age_seconds(self) -> float | None:
        return None if self.telemetry_updated is None else max(0.0, monotonic() - self.telemetry_updated)

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
