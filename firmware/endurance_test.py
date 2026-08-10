"""Configurable USB motion-repeatability test for firmware 4.0+.

The magnet is never enabled. The tool requires an explicit motion confirmation,
uses the production PATH planner, returns to e6, and compares measured homing
steps with a baseline. A physical power cutoff remains the real emergency stop.
"""

from __future__ import annotations

import argparse
import re
import sys
import time
from dataclasses import dataclass

CALIBRATED = re.compile(r"^CALIBRATED ([a-h][1-8]) W(\d+) B(\d+)$")
ROUTE = ("e6a1", "a1c2", "c2h7", "h7f8", "f8e6")


@dataclass(frozen=True)
class HomeReference:
    square: str
    white_steps: int
    black_steps: int


def parse_home_reference(line: str) -> HomeReference:
    match = CALIBRATED.fullmatch(line.strip())
    if not match:
        raise ValueError(f"Malformed calibration result: {line!r}")
    reference = HomeReference(match.group(1), int(match.group(2)), int(match.group(3)))
    if reference.square != "e6":
        raise ValueError(f"Calibration did not finish at e6: {line!r}")
    return reference


def reference_delta(baseline: HomeReference, current: HomeReference) -> tuple[int, int]:
    if baseline.square != "e6" or current.square != "e6":
        raise ValueError("Both calibration results must finish at e6")
    return (current.white_steps - baseline.white_steps,
            current.black_steps - baseline.black_steps)


class BoardLink:
    def __init__(self, port: str) -> None:
        try:
            import serial
        except ImportError as error:
            raise RuntimeError(
                "pyserial is required for hardware tests; install it with "
                "'python -m pip install pyserial'"
            ) from error
        self.connection = serial.Serial(port, 9600, timeout=0.1)

    def close(self) -> None:
        self.connection.close()

    def emergency_stop(self) -> None:
        self.connection.write(b"!")
        self.connection.flush()

    def command(self, command: str, success_prefix: str,
                timeout: float = 120.0) -> str:
        self.connection.reset_input_buffer()
        self.connection.write((command + "\n").encode("ascii"))
        self.connection.flush()
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            raw = self.connection.readline()
            if not raw:
                continue
            line = raw.decode("ascii", errors="replace").strip()
            if not line:
                continue
            print(f"< {line}")
            if line.startswith("ERR ") or line.startswith("ESTOP "):
                raise RuntimeError(line)
            if line.startswith(success_prefix):
                return line
        raise TimeoutError(f"Timed out waiting for {success_prefix!r} after {command!r}")


def calibrate(link: BoardLink) -> HomeReference:
    print("> CALIBRATE")
    return parse_home_reference(link.command("CALIBRATE", "CALIBRATED "))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", required=True, help="Nano USB serial port, for example COM7")
    parser.add_argument("--cycles", type=int, default=25)
    parser.add_argument("--reference-every", type=int, default=5)
    parser.add_argument("--tolerance", type=int, default=4,
                        help="maximum allowed measured-step delta per motor")
    parser.add_argument("--confirm-motion", action="store_true",
                        help="required acknowledgement that motor power and cutoff are ready")
    args = parser.parse_args()
    if not args.confirm_motion:
        parser.error("--confirm-motion is required; this test moves the mechanism")
    if args.cycles < 1 or args.reference_every < 1 or args.tolerance < 0:
        parser.error("cycles/reference-every must be positive and tolerance non-negative")

    link = BoardLink(args.port)
    try:
        time.sleep(2.5)  # Opening a Nano serial port commonly resets it.
        info = link.command("INFO", "INFO ", timeout=3.0)
        if "DEVPATH" not in info:
            raise RuntimeError("Firmware does not advertise DEVPATH (4.0+ required)")

        calibrate(link)  # Establish e6 from the current known position.
        baseline = calibrate(link)  # Repeat from e6 for a comparable baseline.
        print(f"Baseline: W{baseline.white_steps} B{baseline.black_steps}")

        for cycle in range(1, args.cycles + 1):
            for move in ROUTE:
                print(f"> PATH {move}")
                link.command(f"PATH {move}", f"MOVED PATH {move}")
            if cycle % args.reference_every and cycle != args.cycles:
                continue

            current = calibrate(link)
            white_delta, black_delta = reference_delta(baseline, current)
            print(f"Reference {cycle}: W{white_delta:+d} B{black_delta:+d}")
            if abs(white_delta) > args.tolerance or abs(black_delta) > args.tolerance:
                raise RuntimeError(
                    f"Step tolerance exceeded at cycle {cycle}: "
                    f"W{white_delta:+d} B{black_delta:+d}"
                )

        print(f"PASS: {args.cycles * len(ROUTE)} production path moves stayed within tolerance.")
        return 0
    except KeyboardInterrupt:
        print("Interrupted; sending best-effort remote halt.", file=sys.stderr)
        link.emergency_stop()
        return 130
    except Exception as error:
        print(f"FAIL: {error}", file=sys.stderr)
        link.emergency_stop()
        return 1
    finally:
        link.close()


if __name__ == "__main__":
    raise SystemExit(main())
