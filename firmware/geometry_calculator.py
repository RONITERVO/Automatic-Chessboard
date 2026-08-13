#!/usr/bin/env python3
"""Calculate global.h geometry constants from two local service reports."""

from __future__ import annotations

import argparse
import re


REPORT = re.compile(r"(?:GEOMETRY\s+)?([a-h][1-8])\s+X([+-]?\d+)\s+Y([+-]?\d+)", re.I)


def parse_report(text: str) -> tuple[int, int, int, int]:
    match = REPORT.fullmatch(text.strip())
    if not match:
        raise argparse.ArgumentTypeError("expected: GEOMETRY a1 X+0 Y-3")
    square, x_text, y_text = match.groups()
    return ord(square[0].lower()) - ord("a") + 1, int(square[1]), int(x_text), int(y_text)


def rounded_divide(numerator: int, denominator: int) -> int:
    if denominator == 0:
        raise ValueError("measurement coordinates must differ")
    sign = -1 if numerator * denominator < 0 else 1
    return sign * ((abs(numerator) + abs(denominator) // 2) // abs(denominator))


def calculate(
    first: tuple[int, int, int, int],
    second: tuple[int, int, int, int],
    file_pitch: int,
    rank_pitch: int,
    black_park: int,
    white_park: int,
) -> tuple[int, int, int, int]:
    file_a, rank_a, x_a, y_a = first
    file_b, rank_b, x_b, y_b = second
    if file_a == file_b or rank_a == rank_b:
        raise ValueError("choose points with different files and different ranks")
    file_change = rounded_divide(x_b - x_a, file_b - file_a)
    rank_change = rounded_divide(y_b - y_a, rank_b - rank_a)
    origin_x = x_a - (file_a - 5) * file_change
    origin_y = y_a - (rank_a - 6) * rank_change
    return (
        file_pitch + file_change,
        rank_pitch + rank_change,
        black_park - origin_y,
        white_park + origin_x,
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("first", type=parse_report, help='for example "GEOMETRY a2 X+3 Y-1"')
    parser.add_argument("second", type=parse_report, help='for example "GEOMETRY h7 X+10 Y-6"')
    parser.add_argument("--file-pitch", type=int, default=188)
    parser.add_argument("--rank-pitch", type=int, default=188)
    parser.add_argument("--black-park", type=int, default=354)
    parser.add_argument("--white-park", type=int, default=871)
    args = parser.parse_args()
    try:
        values = calculate(
            args.first, args.second, args.file_pitch, args.rank_pitch,
            args.black_park, args.white_park,
        )
    except ValueError as error:
        parser.error(str(error))
    names = (
        "FILE_PITCH_STEPS", "RANK_PITCH_STEPS",
        "CALIBRATION_PARK_BLACK_STEPS", "CALIBRATION_PARK_WHITE_STEPS",
    )
    for name, value in zip(names, values):
        print(f"{name} = {value}")
    print("Upload, calibrate, then verify at least four widely separated squares.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
