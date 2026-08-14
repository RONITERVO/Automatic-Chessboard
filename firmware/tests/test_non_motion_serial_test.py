import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from non_motion_serial_test import (
    HELLO_COMMAND,
    ReadOnlyNanoProbe,
    parse_board,
    parse_info,
    parse_telemetry,
)


class FakeSerial:
    def __init__(self, responses):
        self.responses = {command: list(values) for command, values in responses.items()}
        self.current = []
        self.commands = []

    def reset_input_buffer(self):
        self.current = []

    def write(self, data):
        command = data.decode("ascii").strip()
        self.commands.append(command)
        self.current = list(self.responses[command])
        return len(data)

    def flush(self):
        pass

    def readline(self):
        if not self.current:
            return b""
        return (self.current.pop(0) + "\n").encode("ascii")

    def close(self):
        pass


class NonMotionSerialTestTests(unittest.TestCase):
    def test_parsers_accept_current_protocol(self):
        firmware, hardware = parse_info("INFO ACB3 5.0.0 NANO")
        self.assertEqual("5.0.0", firmware)
        self.assertEqual("NANO", hardware)
        self.assertEqual("8180000000000181", parse_board("BOARD 8180000000000181"))
        self.assertEqual((0, 847), parse_telemetry(
            "TELEM ACB3 17 1 1 0 0 5 6 1 1 1023 847 65"
        ))

    def test_probe_sends_only_allowlisted_read_only_commands(self):
        fake = FakeSerial({
            HELLO_COMMAND: [HELLO_COMMAND],
            "INFO": ["INFO ACB3 5.0.0 NANO"],
            "TELEM": ["TELEM ACB3 1 0 0 0 0 5 6 1 1 1023 900 10"],
            "BOARD": ["BOARD FFFF00000000FFFF"],
        })
        result = ReadOnlyNanoProbe(fake, timeout_seconds=0.1).run(samples=3)
        self.assertEqual(3, result.samples)
        self.assertEqual(900, result.minimum_free_ram)
        self.assertEqual(
            [HELLO_COMMAND, "INFO", "TELEM", "BOARD", "TELEM", "BOARD", "TELEM", "BOARD"],
            fake.commands,
        )
        self.assertTrue(set(fake.commands) <= {HELLO_COMMAND, "INFO", "TELEM", "BOARD"})

    def test_probe_refuses_motion_or_control_commands(self):
        probe = ReadOnlyNanoProbe(FakeSerial({}), timeout_seconds=0.1)
        for command in ("CALIBRATE", "HEAD e4", "PLAN e2e4---", "DRAG e2e4", "COMMIT", "STOP", "!"):
            with self.assertRaisesRegex(ValueError, "unsafe command refused"):
                probe.command(command, "")

    def test_version_mismatch_cannot_pass_release_probe(self):
        responses = {
            HELLO_COMMAND: [HELLO_COMMAND],
            "INFO": ["INFO ACB3 4.8.0 NANO"],
        }
        with self.assertRaisesRegex(RuntimeError, "do not match"):
            ReadOnlyNanoProbe(FakeSerial(responses), timeout_seconds=0.1).run(samples=1)

    def test_malformed_or_unsafe_responses_fail(self):
        for value in ("BOARD 123", "BOARD GGGG000000000000", "BOARD 00000000000000000"):
            with self.assertRaises(ValueError):
                parse_board(value)
        with self.assertRaises(ValueError):
            parse_telemetry("TELEM ACB3 bad")
        with self.assertRaises(ValueError):
            parse_info("INFO ACB3 bad")

        responses = {
            HELLO_COMMAND: [HELLO_COMMAND],
            "INFO": ["INFO ACB3 5.0.0 NANO"],
            "TELEM": ["TELEM ACB3 1 0 0 0 1 5 6 1 1 1023 900 10"],
            "BOARD": ["BOARD FFFF00000000FFFF"],
        }
        with self.assertRaisesRegex(RuntimeError, "magnet ON"):
            ReadOnlyNanoProbe(FakeSerial(responses), timeout_seconds=0.1).run(samples=1)


if __name__ == "__main__":
    unittest.main()
