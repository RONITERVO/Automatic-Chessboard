"""Board-registration measurements and firmware-source answers."""

from __future__ import annotations

from dataclasses import dataclass

from protocol import GeometrySettings


@dataclass(frozen=True)
class AlignmentPoint:
    square: str
    offset_x: int
    offset_y: int

    @property
    def file(self) -> int:
        return ord(self.square[0].lower()) - ord("a") + 1

    @property
    def rank(self) -> int:
        return int(self.square[1])


@dataclass(frozen=True)
class GeometrySourceValues:
    file_pitch: int
    rank_pitch: int
    black_park: int
    white_park: int

    def firmware_lines(self) -> str:
        return "\n".join((
            f"FILE_PITCH_STEPS = {self.file_pitch}U * MOTOR_MICROSTEPS",
            f"RANK_PITCH_STEPS = {self.rank_pitch}U * MOTOR_MICROSTEPS",
            f"CALIBRATION_PARK_BLACK_STEPS = {self.black_park}U * MOTOR_MICROSTEPS",
            f"CALIBRATION_PARK_WHITE_STEPS = {self.white_park}U * MOTOR_MICROSTEPS",
        ))


def rounded_divide(numerator: int, denominator: int) -> int:
    if denominator == 0:
        raise ValueError("measurement coordinates must differ")
    sign = -1 if numerator * denominator < 0 else 1
    return sign * ((abs(numerator) + abs(denominator) // 2) // abs(denominator))


def calculate_geometry(
    first: AlignmentPoint,
    second: AlignmentPoint,
    current: GeometrySettings,
) -> GeometrySourceValues:
    if first.file == second.file or first.rank == second.rank:
        raise ValueError("Choose points with different files and different ranks")
    file_change = rounded_divide(
        second.offset_x - first.offset_x, second.file - first.file,
    )
    rank_change = rounded_divide(
        second.offset_y - first.offset_y, second.rank - first.rank,
    )
    origin_x = first.offset_x - (first.file - 5) * file_change
    origin_y = first.offset_y - (first.rank - 6) * rank_change
    microsteps = current.microsteps
    return GeometrySourceValues(
        rounded_divide(current.file_pitch + file_change, microsteps),
        rounded_divide(current.rank_pitch + rank_change, microsteps),
        rounded_divide(current.black_park - origin_y, microsteps),
        rounded_divide(current.white_park + origin_x, microsteps),
    )
