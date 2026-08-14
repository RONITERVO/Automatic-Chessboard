"""Motionless reference model for the Nano PLAN/DRAG/COMMIT executor.

This model deliberately knows nothing about motors, magnets, or chess strategy.
It independently reproduces the occupancy and transaction invariants enforced
by ``FirmwareHost.ino`` so exhaustive host tests can exercise the safety
contract without energising hardware.  It is test infrastructure, not code
compiled into the Nano firmware.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable


class RouteProtocolError(RuntimeError):
    """A firmware-style route error with a stable protocol code."""

    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(code)


def square_index(value: str) -> int:
    if len(value) != 2 or value[0] not in "abcdefgh" or value[1] not in "12345678":
        raise RouteProtocolError("BAD SQUARE")
    return (ord(value[0]) - ord("a")) + (ord(value[1]) - ord("1")) * 8


def square_name(square: int) -> str:
    if square not in range(64):
        raise ValueError(f"square outside board: {square}")
    return f"{chr(ord('a') + square % 8)}{square // 8 + 1}"


def capture_exit_clear(occupied: Iterable[int], capture: int, exit_rank: int) -> bool:
    squares = frozenset(occupied)
    file = capture % 8 + 1
    source_rank = capture // 8 + 1
    for rank in range(min(source_rank, exit_rank), max(source_rank, exit_rank) + 1):
        if rank != source_rank and (rank - 1) * 8 + file - 1 in squares:
            return False
    for column in range(1, file):
        if (exit_rank - 1) * 8 + column - 1 in squares:
            return False
    return True


def capture_has_exit(occupied: Iterable[int], capture: int) -> bool:
    source_rank = capture // 8 + 1
    ranks = (*range(source_rank, 0, -1), *range(source_rank + 1, 9))
    return any(capture_exit_clear(occupied, capture, rank) for rank in ranks)


@dataclass(frozen=True)
class PlanRequest:
    source: int
    target: int
    mode: str
    capture: int | None
    uci: str

    @classmethod
    def parse(cls, command: str) -> "PlanRequest":
        if not command.startswith("PLAN ") or len(command) != 12:
            raise RouteProtocolError("BAD PLAN")
        payload = command[5:]
        try:
            source = square_index(payload[:2])
            target = square_index(payload[2:4])
        except RouteProtocolError as error:
            raise RouteProtocolError("BAD PLAN") from error
        mode = payload[4]
        capture_text = payload[5:7]
        if source == target or mode not in "-qrbnkc":
            raise RouteProtocolError("BAD PLAN")
        if capture_text == "--":
            capture = None
        else:
            try:
                capture = square_index(capture_text)
            except RouteProtocolError as error:
                raise RouteProtocolError("BAD PLAN") from error

        source_name = payload[:2]
        target_name = payload[2:4]
        castle_rank = source_name[1] in "18" and target_name[1] == source_name[1]
        if mode == "k" and not (source_name[0] == "e" and target_name[0] == "g" and castle_rank):
            raise RouteProtocolError("BAD PLAN")
        if mode == "c" and not (source_name[0] == "e" and target_name[0] == "c" and castle_rank):
            raise RouteProtocolError("BAD PLAN")
        # The Nano acknowledges the four-coordinate move in DONE and reports
        # promotion identity separately with PROMOTE <piece>.
        return cls(source, target, mode, capture, payload[:4])

    def final_occupancy(self, initial: Iterable[int]) -> frozenset[int]:
        expected = set(initial)
        expected.discard(self.source)
        if self.capture is not None:
            expected.discard(self.capture)
        expected.add(self.target)
        if self.mode in "kc":
            rank_base = self.source // 8 * 8
            rook_source = rank_base + (7 if self.mode == "k" else 0)
            rook_target = rank_base + (5 if self.mode == "k" else 3)
            expected.discard(rook_source)
            expected.add(rook_target)
        return frozenset(expected)


@dataclass(frozen=True)
class DragRequest:
    source: int
    target: int
    step: int
    path: tuple[int, ...]

    @classmethod
    def parse(cls, command: str) -> "DragRequest":
        if not command.startswith("DRAG ") or len(command) != 9:
            raise RouteProtocolError("BAD ROUTE")
        try:
            source = square_index(command[5:7])
            target = square_index(command[7:9])
        except RouteProtocolError as error:
            raise RouteProtocolError("BAD ROUTE") from error
        if source == target:
            raise RouteProtocolError("SAME SQUARE")
        source_file, source_rank = source % 8, source // 8
        target_file, target_rank = target % 8, target // 8
        if source_file == target_file:
            step = 8 if target > source else -8
        elif source_rank == target_rank:
            step = 1 if target > source else -1
        else:
            raise RouteProtocolError("BAD ROUTE")
        path = tuple(range(source, target + step, step))
        return cls(source, target, step, path)


class MotionlessRouteExecutor:
    """Occupancy-only digital twin with injectable observed sensor frames."""

    def __init__(self, occupied: Iterable[int]) -> None:
        initial = frozenset(occupied)
        if any(square not in range(64) for square in initial):
            raise ValueError("occupancy contains a square outside the board")
        self.expected = initial
        self.observed = initial
        self.turn_start: frozenset[int] | None = None
        self.final_expected: frozenset[int] | None = None
        self.plan: PlanRequest | None = None
        self.capture_pending = False
        self.capture_square: int | None = None
        self.fault = False

    @property
    def active(self) -> bool:
        return self.plan is not None

    def set_observed(self, occupied: Iterable[int]) -> None:
        self.observed = frozenset(occupied)

    def begin(self, command: str) -> str:
        if self.active or self.fault:
            raise RouteProtocolError("NOT READY")
        if self.observed != self.expected:
            raise RouteProtocolError("PLAN STATE")
        request = PlanRequest.parse(command)
        if request.capture is not None and request.capture not in self.observed:
            raise RouteProtocolError("PLAN STATE")

        self.plan = request
        self.turn_start = self.observed
        self.final_expected = request.final_occupancy(self.turn_start)
        self.capture_pending = request.capture is not None
        self.capture_square = request.capture
        return "PLAN READY"

    def remove_capture(self, observed_after: Iterable[int] | None = None) -> str:
        capture = self.capture_square
        if not self.capture_pending or capture is None:
            raise RouteProtocolError("CAPTURE")
        if self.observed != self.expected or not capture_has_exit(self.observed, capture):
            raise RouteProtocolError("CAPTURE")
        next_expected = set(self.expected)
        next_expected.remove(capture)
        self.expected = frozenset(next_expected)
        self.observed = self.expected if observed_after is None else frozenset(observed_after)
        if self.observed != self.expected:
            self._latch_sensor_fault()
            raise RouteProtocolError("SENSORS")
        self.capture_pending = False
        self.capture_square = None
        return "REMOVED"

    def drag(self, command: str, observed_after: Iterable[int] | None = None) -> str:
        if not self.active:
            raise RouteProtocolError("NO PLAN")
        if self.observed != self.expected:
            raise RouteProtocolError("PLAN STATE")

        request = DragRequest.parse(command)
        if request.source not in self.observed:
            raise RouteProtocolError("SOURCE EMPTY")
        if request.target in self.observed:
            raise RouteProtocolError("TARGET FULL")
        if any(square in self.expected for square in request.path[1:-1]):
            raise RouteProtocolError("ROUTE BLOCKED")

        next_expected = set(self.expected)
        next_expected.remove(request.source)
        next_expected.add(request.target)
        self.expected = frozenset(next_expected)
        self.observed = self.expected if observed_after is None else frozenset(observed_after)
        if self.observed != self.expected:
            self._latch_sensor_fault()
            raise RouteProtocolError("SENSORS")
        if request.source == self.capture_square:
            self.capture_square = request.target
        return f"MOVED PIECE {square_name(request.source)}{square_name(request.target)}"

    def commit(self) -> str:
        if not self.active:
            raise RouteProtocolError("NO PLAN")
        if self.observed != self.expected:
            raise RouteProtocolError("FINAL SENSORS")
        if self.observed == self.turn_start:
            self._clear_plan()
            return "PLAN CANCELLED"
        if self.capture_pending or self.observed != self.final_expected:
            raise RouteProtocolError("PLAN INCOMPLETE")
        uci = self.plan.uci
        self._clear_plan()
        return f"DONE {uci}"

    def stop(self) -> str:
        self._clear_plan()
        return "STOPPED"

    def emergency_halt(self) -> str:
        self.fault = True
        self._clear_plan()
        return "ESTOP REMOTE"

    def _clear_plan(self) -> None:
        self.plan = None
        self.turn_start = None
        self.final_expected = None
        self.capture_pending = False
        self.capture_square = None

    def _latch_sensor_fault(self) -> None:
        self.fault = True
        self._clear_plan()
