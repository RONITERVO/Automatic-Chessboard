"""Board-offset calibration values shared by UI, simulator, and tests."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class BoardOffsetProfile:
    black_steps: int
    white_steps: int

    def __post_init__(self) -> None:
        if not 200 <= self.black_steps <= 600 or not 650 <= self.white_steps <= 1000:
            raise ValueError("Board offset is outside the firmware's safe range")

    @classmethod
    def from_event_args(cls, args: tuple[str, ...] | list[str]) -> "BoardOffsetProfile":
        if len(args) != 2:
            raise ValueError("Malformed CALPROFILE response")
        return cls(*(int(value) for value in args))

    @property
    def answer(self) -> str:
        return f"Black offset {self.black_steps}; white offset {self.white_steps}"

    @property
    def command(self) -> str:
        return f"CALSET {self.black_steps} {self.white_steps}"


def nudge_command(axis: str, positive: bool, coarse: bool) -> str:
    normalized = axis.upper()
    if normalized not in {"X", "Y"}:
        raise ValueError("Axis must be X or Y")
    return f"NUDGE {normalized}{'+' if positive else '-'} {5 if coarse else 1}"
