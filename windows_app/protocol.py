"""Dependency-free helpers for the versioned Automatic Chessboard protocol."""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum


@dataclass(frozen=True)
class BoardEvent:
    kind: str
    args: tuple[str, ...]
    raw: str


@dataclass(frozen=True)
class FirmwareInfo:
    protocol: str
    firmware: str
    capabilities: frozenset[str]


@dataclass(frozen=True)
class Telemetry:
    protocol: str
    sequence: int
    homed: bool
    remote_mode: bool
    motion_fault: bool
    magnet_on: bool
    trolley_x: int
    trolley_y: int
    button_a_released: bool
    button_b_released: bool
    button_b_raw: int
    free_ram: int
    uptime_seconds: int


class CommandRisk(Enum):
    READ_ONLY = "Read-only"
    CONTROL = "Changes board state"
    MOTION = "Can start physical movement"
    EMERGENCY = "Emergency halt"
    UNKNOWN = "Unknown command"


READ_ONLY_COMMANDS = frozenset({"PING", "HELLO", "INFO", "STATUS", "TELEM", "BOARD", "BTTEST"})
CONTROL_COMMANDS = frozenset({"STOP", "REJECT", "GAMEOVER"})
# ACCEPT can cause the companion to request the following engine move, so it is
# guarded with commands that move directly rather than treated as harmless state.
MOTION_COMMANDS = frozenset({"START", "PLAY", "ACCEPT", "CALIBRATE", "HEAD", "PIECE"})


class LineBuffer:
    """Turn arbitrary USB/BLE byte chunks into stripped ASCII lines."""

    def __init__(self, maximum: int = 256) -> None:
        self._data = bytearray()
        self._maximum = maximum
        self._overflowed = False

    def feed(self, data: bytes) -> list[str]:
        lines: list[str] = []
        for value in data:
            if value in (10, 13):
                if self._data and not self._overflowed:
                    lines.append(self._data.decode("ascii", errors="replace").strip())
                self._data.clear()
                self._overflowed = False
            elif 32 <= value <= 126 and not self._overflowed:
                if len(self._data) < self._maximum:
                    self._data.append(value)
                else:
                    self._data.clear()
                    self._overflowed = True
        return [line for line in lines if line]


def parse_event(line: str) -> BoardEvent:
    fields = line.strip().split()
    if not fields:
        return BoardEvent("EMPTY", (), line)
    return BoardEvent(fields[0].upper(), tuple(fields[1:]), line)


def parse_info(event: BoardEvent) -> FirmwareInfo:
    if event.kind != "INFO" or len(event.args) < 2:
        raise ValueError(f"Not an INFO event: {event.raw!r}")
    capabilities = frozenset(
        item.strip().upper() for item in " ".join(event.args[2:]).split(",") if item.strip()
    )
    return FirmwareInfo(event.args[0], event.args[1], capabilities)


def parse_telemetry(event: BoardEvent) -> Telemetry:
    if event.kind != "TELEM" or len(event.args) != 13:
        raise ValueError(f"Malformed TELEM event: {event.raw!r}")
    values = [int(value) for value in event.args[1:]]
    return Telemetry(
        protocol=event.args[0],
        sequence=values[0],
        homed=bool(values[1]),
        remote_mode=bool(values[2]),
        motion_fault=bool(values[3]),
        magnet_on=bool(values[4]),
        trolley_x=values[5],
        trolley_y=values[6],
        button_a_released=bool(values[7]),
        button_b_released=bool(values[8]),
        button_b_raw=values[9],
        free_ram=values[10],
        uptime_seconds=values[11],
    )


def parse_board_hex(value: str) -> frozenset[int]:
    """Return occupied python-chess square indexes from 16 firmware hex digits."""
    compact = value.strip().upper()
    if len(compact) != 16 or any(character not in "0123456789ABCDEF" for character in compact):
        raise ValueError(f"Invalid board snapshot: {value!r}")
    occupied: set[int] = set()
    for row in range(8):
        bits = int(compact[row * 2:row * 2 + 2], 16)
        for file_index in range(8):
            if bits & (1 << file_index):
                occupied.add((7 - row) * 8 + file_index)
    return frozenset(occupied)


def board_hex_from_squares(squares: set[int] | frozenset[int]) -> str:
    rows: list[str] = []
    for row in range(8):
        rank_index = 7 - row
        bits = sum(1 << file_index for file_index in range(8)
                   if rank_index * 8 + file_index in squares)
        rows.append(f"{bits:02X}")
    return "".join(rows)


def classify_command(line: str) -> CommandRisk:
    stripped = line.strip()
    if stripped.startswith("!"):
        return CommandRisk.EMERGENCY
    command = stripped.split(maxsplit=1)[0].upper() if stripped else ""
    if command in READ_ONLY_COMMANDS:
        return CommandRisk.READ_ONLY
    if command in CONTROL_COMMANDS:
        return CommandRisk.CONTROL
    if command in MOTION_COMMANDS:
        return CommandRisk.MOTION
    return CommandRisk.UNKNOWN


def play_command(uci: str, *, castling: bool = False,
                 en_passant: bool = False) -> str:
    if len(uci) not in (4, 5):
        raise ValueError(f"Invalid UCI move: {uci!r}")
    flag = "C" if castling else "E" if en_passant else ""
    return f"PLAY {uci}{' ' + flag if flag else ''}"


def head_command(square: str) -> str:
    if len(square) != 2 or square[0] not in "abcdefgh" or square[1] not in "12345678":
        raise ValueError(f"Invalid square: {square!r}")
    return f"HEAD {square}"


def piece_command(source: str, target: str) -> str:
    head_command(source)
    head_command(target)
    if source == target:
        raise ValueError("Source and target must differ")
    return f"PIECE {source}{target}"
