"""Dependency-free helpers for the versioned Automatic Chessboard protocol."""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum

SOFTWARE_VERSION = "5.0.1"
PROTOCOL_VERSION = "ACB3"
CURRENT_CAPABILITIES = frozenset({
    "BOARD", "TELEM", "REMOTE", "ESTOP", "BTTEST", "SWTEST", "CALIBRATE",
    "MANUAL", "SENSORFRAME", "PLANROUTE", "REMOVE", "EDGEEXIT", "APPBOARD",
    "DEVPATH", "DEVJOG", "ALIGN",
})


@dataclass(frozen=True)
class BoardEvent:
    kind: str
    args: tuple[str, ...]
    raw: str


@dataclass(frozen=True)
class FirmwareInfo:
    protocol: str
    firmware: str
    hardware: str
    capabilities: frozenset[str]

    @property
    def compatible(self) -> bool:
        return self.protocol == PROTOCOL_VERSION and self.firmware == SOFTWARE_VERSION


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


@dataclass(frozen=True)
class GeometrySettings:
    protocol: str
    file_pitch: int
    rank_pitch: int
    black_park: int
    white_park: int
    microsteps: int


@dataclass(frozen=True)
class AlignmentStatus:
    state: str
    square: str | None = None
    magnetic_marker: bool = False
    offset_x: int = 0
    offset_y: int = 0


class CommandRisk(Enum):
    READ_ONLY = "Read-only"
    CONTROL = "Changes board state"
    MOTION = "Can start physical movement"
    EMERGENCY = "Emergency halt"
    UNKNOWN = "Unknown command"


READ_ONLY_COMMANDS = frozenset({
    "HELLO", "INFO", "TELEM", "BOARD", "BTTEST", "SWTEST", "GEOMETRY",
})
CONTROL_COMMANDS = frozenset({"STOP", "REJECT", "GAMEOVER"})
# ACCEPT can cause the companion to request the following engine move, so it is
# guarded with commands that move directly rather than treated as harmless state.
MOTION_COMMANDS = frozenset({
    "START", "ACCEPT", "CALIBRATE", "HEAD", "PIECE", "PATH", "JOG",
    "PLAN", "DRAG", "REMOVE", "COMMIT", "ALIGN", "NUDGE",
})


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
    if event.kind != "INFO" or len(event.args) != 3:
        raise ValueError(f"Not an INFO event: {event.raw!r}")
    if event.args[2] not in {"NANO", "MKS_GEN_L_V1", "SIM"}:
        raise ValueError(f"Unknown INFO hardware profile: {event.raw!r}")
    return FirmwareInfo(
        event.args[0], event.args[1], event.args[2], CURRENT_CAPABILITIES
    )


def hello_command() -> str:
    return f"HELLO {SOFTWARE_VERSION}"


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


def parse_geometry(event: BoardEvent) -> GeometrySettings:
    if event.kind != "GEOMETRY" or len(event.args) != 6:
        raise ValueError(f"Malformed GEOMETRY event: {event.raw!r}")
    try:
        values = tuple(int(value) for value in event.args[1:])
    except ValueError as error:
        raise ValueError(f"Malformed GEOMETRY event: {event.raw!r}") from error
    if any(value <= 0 for value in values):
        raise ValueError(f"Invalid GEOMETRY values: {event.raw!r}")
    return GeometrySettings(event.args[0], *values)


def parse_alignment(event: BoardEvent) -> AlignmentStatus:
    if event.kind != "ALIGN" or not event.args:
        raise ValueError(f"Malformed ALIGN event: {event.raw!r}")
    state = event.args[0].upper()
    if state in {"IDLE", "ENDED"} and len(event.args) == 1:
        return AlignmentStatus(state)
    if state not in {"READY", "ACTIVE"} or len(event.args) != 5:
        raise ValueError(f"Malformed ALIGN event: {event.raw!r}")
    square, mode = event.args[1].lower(), event.args[2].upper()
    head_command(square)
    if mode not in {"H", "M"}:
        raise ValueError(f"Malformed ALIGN mode: {event.raw!r}")
    try:
        offset_x, offset_y = int(event.args[3]), int(event.args[4])
    except ValueError as error:
        raise ValueError(f"Malformed ALIGN offsets: {event.raw!r}") from error
    if not -60 <= offset_x <= 60 or not -60 <= offset_y <= 60:
        raise ValueError(f"ALIGN offsets outside firmware limits: {event.raw!r}")
    return AlignmentStatus(state, square, mode == "M", offset_x, offset_y)


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
    if stripped.upper() == "ALIGN STATUS":
        return CommandRisk.READ_ONLY
    command = stripped.split(maxsplit=1)[0].upper() if stripped else ""
    if command in READ_ONLY_COMMANDS:
        return CommandRisk.READ_ONLY
    if command in CONTROL_COMMANDS:
        return CommandRisk.CONTROL
    if command in MOTION_COMMANDS:
        return CommandRisk.MOTION
    return CommandRisk.UNKNOWN


def _normalize_uci(uci: str) -> str:
    text = uci.strip().lower()
    if len(text) not in (4, 5):
        raise ValueError(f"Invalid UCI move: {uci!r}")
    source = _square_index(text[:2])
    target = _square_index(text[2:4])
    if source == target or (len(text) == 5 and text[4] not in "qrbn"):
        raise ValueError(f"Invalid UCI move: {uci!r}")
    return text


def start_game_command(human_white: bool, *, app_board: bool = False) -> str:
    return f"START {'W' if human_white else 'B'}{' APP' if app_board else ''}"


def head_command(square: str) -> str:
    if len(square) != 2 or square[0] not in "abcdefgh" or square[1] not in "12345678":
        raise ValueError(f"Invalid square: {square!r}")
    return f"HEAD {square}"


def alignment_command(square: str, *, magnetic_marker: bool = False) -> str:
    normalized = square.lower()
    head_command(normalized)
    return f"ALIGN {normalized} {'M' if magnetic_marker else 'H'}"


def nudge_command(axis: str, sign: str) -> str:
    normalized_axis = axis.strip().upper()
    if normalized_axis not in {"X", "Y"} or sign not in {"+", "-"}:
        raise ValueError(f"Invalid alignment nudge: {axis!r}{sign!r}")
    return f"NUDGE {normalized_axis}{sign}"


def piece_command(source: str, target: str) -> str:
    head_command(source)
    head_command(target)
    if source == target:
        raise ValueError("Source and target must differ")
    return f"PIECE {source}{target}"


# PLANROUTE executes one straight orthogonal run per DRAG command. A complete
# planner path is split at corners, deliberately releasing and reacquiring at
# square centers so the Nano can reuse its hardware-validated straight mover.


@dataclass(frozen=True)
class DragRoute:
    source: int
    target: int
    path: tuple[int, ...]
    step_count: int


def _square_index(name: str) -> int:
    square = name.strip().lower()
    if len(square) != 2 or square[0] not in "abcdefgh" or square[1] not in "12345678":
        raise ValueError(f"Invalid square: {name!r}")
    return ord(square[0]) - ord("a") + (int(square[1]) - 1) * 8


def _square_text(square: int) -> str:
    if square not in range(64):
        raise ValueError(f"Square is outside the board: {square!r}")
    return f"{chr(ord('a') + square % 8)}{square // 8 + 1}"


def queen_aligned(source: int, target: int) -> bool:
    """Return whether one direct carry is horizontal, vertical, or 45 degrees."""

    _square_text(source)
    _square_text(target)
    file_delta = abs(source % 8 - target % 8)
    rank_delta = abs(source // 8 - target // 8)
    return source != target and (
        file_delta == 0 or rank_delta == 0 or file_delta == rank_delta
    )


def _route_step(first: int, second: int) -> int:
    delta = second - first
    if delta in (-8, 8):
        return delta
    if delta in (-1, 1) and first // 8 == second // 8:
        return delta
    raise ValueError(
        f"Route contains a non-orthogonal step {_square_text(first)}->{_square_text(second)}"
    )


def split_route_runs(path: tuple[int, ...] | list[int]) -> tuple[tuple[int, ...], ...]:
    """Split a valid orthogonal path into maximal straight square-center runs."""

    if len(path) < 2:
        raise ValueError("A drag route needs source and destination")
    steps = [_route_step(first, second) for first, second in zip(path, path[1:])]
    runs: list[tuple[int, ...]] = []
    run_start = 0
    for index in range(1, len(steps)):
        if steps[index] != steps[index - 1]:
            runs.append(tuple(path[run_start:index + 1]))
            run_start = index
    runs.append(tuple(path[run_start:]))
    return tuple(runs)


def drag_command(path: tuple[int, ...] | list[int]) -> str:
    runs = split_route_runs(path)
    if len(runs) != 1:
        raise ValueError("One DRAG command must be a single straight run")
    run = runs[0]
    return f"DRAG {_square_text(run[0])}{_square_text(run[-1])}"


def remove_command(capture_square: int | None) -> str:
    if capture_square is None:
        raise ValueError("REMOVE requires a captured square")
    _square_text(capture_square)
    return "REMOVE"


def parse_drag_command(line: str) -> DragRoute:
    fields = line.strip().split()
    if len(fields) != 2 or fields[0].upper() != "DRAG" or len(fields[1]) != 4:
        raise ValueError(f"Malformed DRAG command: {line!r}")
    source = _square_index(fields[1][:2])
    target = _square_index(fields[1][2:])
    if source == target:
        raise ValueError("DRAG endpoints must differ")
    source_file, source_rank = source % 8, source // 8
    target_file, target_rank = target % 8, target // 8
    if source_file != target_file and source_rank != target_rank:
        raise ValueError("DRAG must be orthogonally straight")
    step = 8 if source_file == target_file and target > source else (
        -8 if source_file == target_file else (1 if target > source else -1)
    )
    path = tuple(range(source, target + step, step))
    return DragRoute(source, target, path, len(path) - 1)


@dataclass(frozen=True)
class PlanRouteRequest:
    """Decoded fixed-width PLANROUTE transaction header."""

    uci: str
    mode: str
    capture_square: int | None

    @property
    def castling_side(self) -> str | None:
        return "kingside" if self.mode == "k" else "queenside" if self.mode == "c" else None


def _normalize_castling_side(value: str | None) -> str | None:
    if value is None:
        return None
    normalized = value.strip().lower().replace("_", "").replace("-", "")
    if normalized in {"k", "king", "kingside"}:
        return "k"
    if normalized in {"c", "q", "queen", "queenside"}:
        return "c"
    raise ValueError(f"Invalid castling side: {value!r}")


def plan_command(
    uci: str,
    capture_square: int | str | None = None,
    *,
    castling_side: str | None = None,
) -> str:
    """Open a compact sensor-verified route transaction.

    The payload is exactly seven characters: ``from``, ``to``, one mode byte,
    and a capture square or ``--``. The mode is ``-`` for a normal move,
    ``q/r/b/n`` for promotion, or ``k/c`` for standard king/queen-side
    castling. The Nano independently compares its current 64-square sensor
    frame with its authoritative pre-move frame before accepting the command.
    """

    normalized = _normalize_uci(uci)
    castle = _normalize_castling_side(castling_side)
    if castle is not None:
        if len(normalized) != 4:
            raise ValueError("Castling cannot also be a promotion")
        expected = {"k": {"e1g1", "e8g8"}, "c": {"e1c1", "e8c8"}}[castle]
        if normalized not in expected:
            raise ValueError(f"UCI move does not match {castling_side} castling: {uci!r}")
        mode = castle
    else:
        mode = normalized[4] if len(normalized) == 5 else "-"

    if capture_square is None:
        capture = "--"
    elif isinstance(capture_square, int):
        capture = _square_text(capture_square)
    else:
        capture = capture_square.strip().lower()
        _square_index(capture)

    return f"PLAN {normalized[:4]}{mode}{capture}"


def parse_plan_command(line: str) -> PlanRouteRequest:
    fields = line.strip().split()
    if len(fields) != 2 or fields[0].upper() != "PLAN" or len(fields[1]) != 7:
        raise ValueError(f"Malformed PLAN command: {line!r}")
    payload = fields[1].lower()
    base_uci = payload[:4]
    _normalize_uci(base_uci)
    mode = payload[4]
    if mode not in "-qrbnkc":
        raise ValueError(f"Invalid PLAN mode: {mode!r}")
    if mode == "k" and base_uci not in {"e1g1", "e8g8"}:
        raise ValueError("King-side PLAN mode does not match its UCI endpoints")
    if mode == "c" and base_uci not in {"e1c1", "e8c8"}:
        raise ValueError("Queen-side PLAN mode does not match its UCI endpoints")
    uci = base_uci + (mode if mode in "qrbn" else "")
    capture_text = payload[5:]
    capture_square = None if capture_text == "--" else _square_index(capture_text)
    return PlanRouteRequest(uci=uci, mode=mode, capture_square=capture_square)


def commit_plan_command() -> str:
    """Ask the Nano to prove the derived chess end frame and commit it."""

    return "COMMIT"
