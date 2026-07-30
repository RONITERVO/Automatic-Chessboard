"""Run non-motion USB protocol checks against an attached Nano."""

import argparse
import time

import serial


def read_for(connection: serial.Serial, seconds: float) -> str:
    deadline = time.monotonic() + seconds
    data = bytearray()
    while time.monotonic() < deadline:
        data.extend(connection.read(connection.in_waiting or 1))
    return data.decode("ascii", errors="replace")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", default="COM7")
    args = parser.parse_args()
    with serial.Serial(args.port, 9600, timeout=0.1) as connection:
        print("BOOT:", repr(read_for(connection, 5.0)))
        for command in ("PING", "INFO", "STATUS", "TELEM", "SENSORMAP", "BOARD", "BTTEST"):
            connection.write((command + "\n").encode("ascii"))
            connection.flush()
            reply = read_for(connection, 1.0)
            print(f"{command}:", repr(reply))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
