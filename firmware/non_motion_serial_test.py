"""Read-only Nano/USB protocol probe that cannot request physical motion.

Only the exact release handshake, INFO, TELEM, and BOARD are permitted by the command gate.
BOARD payload shape is checked, but its occupancy is intentionally not judged:
this test remains useful when reed switches or chess-piece magnets are absent.
Opening a Nano port can reset some boards even with DTR held inactive; firmware
startup must therefore continue to keep the magnet and step pins low.
"""

from __future__ import annotations

import argparse
import re
import time
from dataclasses import dataclass
from typing import Protocol

SOFTWARE_VERSION = "5.0.1"
HELLO_COMMAND = f"HELLO {SOFTWARE_VERSION}"
SAFE_COMMANDS = frozenset({HELLO_COMMAND, "INFO", "TELEM", "BOARD"})
INFO_PATTERN = re.compile(r"^INFO ACB3 (\S+) (NANO|MKS_GEN_L_V1)$")
BOARD_PATTERN = re.compile(r"^BOARD ([0-9A-Fa-f]{16})$")


class SerialLike(Protocol):
    def reset_input_buffer(self) -> None: ...
    def write(self, data: bytes) -> int: ...
    def flush(self) -> None: ...
    def readline(self) -> bytes: ...
    def close(self) -> None: ...


@dataclass(frozen=True)
class ProbeResult:
    firmware: str
    hardware: str
    samples: int
    board_frames: frozenset[str]
    minimum_free_ram: int


def parse_info(line: str) -> tuple[str, str]:
    match = INFO_PATTERN.fullmatch(line.strip())
    if not match:
        raise ValueError(f"Malformed INFO response: {line!r}")
    return match.group(1), match.group(2)


def parse_board(line: str) -> str:
    match = BOARD_PATTERN.fullmatch(line.strip())
    if not match:
        raise ValueError(f"Malformed BOARD response: {line!r}")
    return match.group(1).upper()


def parse_telemetry(line: str) -> tuple[int, int]:
    fields = line.strip().split()
    if len(fields) != 14 or fields[:2] != ["TELEM", "ACB3"]:
        raise ValueError(f"Malformed TELEM response: {line!r}")
    try:
        numbers = [int(value) for value in fields[2:]]
    except ValueError as error:
        raise ValueError(f"Malformed TELEM response: {line!r}") from error
    magnet_on = numbers[4]
    free_ram = numbers[10]
    if magnet_on not in (0, 1) or free_ram < 0:
        raise ValueError(f"Out-of-range TELEM response: {line!r}")
    return magnet_on, free_ram


class ReadOnlyNanoProbe:
    def __init__(self, connection: SerialLike, timeout_seconds: float = 2.0) -> None:
        self.connection = connection
        self.timeout_seconds = timeout_seconds

    def command(self, command: str, prefix: str) -> str:
        if command not in SAFE_COMMANDS:
            raise ValueError(f"unsafe command refused by non-motion probe: {command!r}")
        self.connection.reset_input_buffer()
        self.connection.write((command + "\n").encode("ascii"))
        self.connection.flush()
        deadline = time.monotonic() + self.timeout_seconds
        while time.monotonic() < deadline:
            raw = self.connection.readline()
            if not raw:
                continue
            line = raw.decode("ascii", errors="replace").strip()
            if line.startswith("ERR ") or line.startswith("ESTOP "):
                raise RuntimeError(line)
            if line.startswith(prefix):
                return line
        raise TimeoutError(f"Timed out waiting for {prefix!r} after {command!r}")

    def run(self, samples: int = 20) -> ProbeResult:
        if samples < 1:
            raise ValueError("samples must be positive")
        hello = self.command(HELLO_COMMAND, "HELLO ")
        if hello != HELLO_COMMAND:
            raise RuntimeError(
                f"Unexpected HELLO response {hello!r}; expected {HELLO_COMMAND!r}"
            )
        firmware, hardware = parse_info(self.command("INFO", "INFO "))
        if firmware != SOFTWARE_VERSION:
            raise RuntimeError(
                f"Probe {SOFTWARE_VERSION} and firmware {firmware} do not match"
            )

        frames: set[str] = set()
        minimum_free_ram: int | None = None
        for _ in range(samples):
            magnet_on, free_ram = parse_telemetry(self.command("TELEM", "TELEM "))
            if magnet_on:
                raise RuntimeError("Firmware reports the magnet ON; use the physical cutoff")
            frames.add(parse_board(self.command("BOARD", "BOARD ")))
            minimum_free_ram = free_ram if minimum_free_ram is None else min(minimum_free_ram, free_ram)
        return ProbeResult(
            firmware=firmware,
            hardware=hardware,
            samples=samples,
            board_frames=frozenset(frames),
            minimum_free_ram=minimum_free_ram or 0,
        )


def open_serial(port: str, allow_reset: bool = False) -> SerialLike:
    try:
        import serial
    except ImportError as error:
        raise RuntimeError("pyserial is required: python -m pip install pyserial") from error
    connection = serial.Serial()
    connection.port = port
    connection.baudrate = 9600
    connection.timeout = 0.1
    connection.write_timeout = 1.0
    if not allow_reset:
        connection.dtr = False
        connection.rts = False
    connection.open()
    return connection


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", required=True, help="Nano USB serial port, for example COM7")
    parser.add_argument("--samples", type=int, default=20)
    parser.add_argument("--settle-seconds", type=float, default=1.0)
    parser.add_argument(
        "--allow-reset",
        action="store_true",
        help="allow the USB serial adapter's ordinary DTR reset; still sends only read-only commands",
    )
    args = parser.parse_args()
    if args.settle_seconds < 0:
        parser.error("--settle-seconds cannot be negative")

    connection = open_serial(args.port, allow_reset=args.allow_reset)
    try:
        time.sleep(args.settle_seconds)
        result = ReadOnlyNanoProbe(connection).run(args.samples)
    finally:
        connection.close()
    print(
        f"PASS: firmware {result.firmware}; {result.samples} motionless TELEM/BOARD samples; "
        f"{len(result.board_frames)} observed board frame(s); minimum free RAM "
        f"{result.minimum_free_ram} bytes."
    )
    print("No calibration, motor, magnet, move, plan, drag, commit, or stop command was sent.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
