import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from non_motion_serial_test import (
    ReadOnlyNanoProbe,
    firmware_version_tuple,
    parse_board,
    parse_info,
    parse_status,
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
        firmware, capabilities = parse_info(
            "INFO ACB2 4.1.0 BOARD,TELEM,REMOTE,SENSORFRAME,PLANROUTE"
        )
        self.assertEqual("4.1.0", firmware)
        self.assertIn("PLANROUTE", capabilities)
        self.assertEqual("8180000000000181", parse_board("BOARD 8180000000000181"))
        self.assertEqual((0, 847), parse_telemetry(
            "TELEM ACB2 17 1 1 0 0 5 6 1 1 1023 847 65"
        ))
        self.assertIsNone(parse_status("STATUS ACB1 17 1 1"))
        self.assertEqual((4, 1, 0), firmware_version_tuple("4.1.0-SIM"))

    def test_probe_sends_only_allowlisted_read_only_commands(self):
        fake = FakeSerial({
            "PING": ["PONG ACB1"],
            "INFO": ["INFO ACB2 4.1.0 BOARD,TELEM,REMOTE,SENSORFRAME,PLANROUTE"],
            "STATUS": ["STATUS ACB1 1 0 0"],
            "TELEM": ["TELEM ACB2 1 0 0 0 0 5 6 1 1 1023 900 10"],
            "BOARD": ["BOARD FFFF00000000FFFF"],
        })
        result = ReadOnlyNanoProbe(fake, timeout_seconds=0.01).run(samples=3)
        self.assertEqual(3, result.samples)
        self.assertEqual(900, result.minimum_free_ram)
        self.assertEqual(
            ["PING", "INFO", "STATUS", "TELEM", "BOARD", "TELEM", "BOARD", "TELEM", "BOARD"],
            fake.commands,
        )
        self.assertTrue(set(fake.commands) <= {"PING", "INFO", "STATUS", "TELEM", "BOARD"})

    def test_probe_refuses_motion_or_control_commands(self):
        probe = ReadOnlyNanoProbe(FakeSerial({}), timeout_seconds=0.01)
        for command in ("CALIBRATE", "HEAD e4", "PLAN e2e4---", "DRAG e2e4", "COMMIT", "STOP", "!"):
            with self.assertRaisesRegex(ValueError, "unsafe command refused"):
                probe.command(command, "")

    def test_legacy_diagnostic_mode_does_not_weaken_release_mode(self):
        responses = {
            "PING": ["PONG ACB1"],
            "INFO": ["INFO ACB2 4.0.0 BOARD,TELEM,REMOTE,SENSORFRAME"],
            "STATUS": ["STATUS ACB1 1 0 0"],
            "TELEM": ["TELEM ACB2 1 0 0 0 0 5 6 1 1 1023 900 10"],
            "BOARD": ["BOARD FFFF00000000FFFF"],
        }
        with self.assertRaisesRegex(RuntimeError, "PLANROUTE"):
            ReadOnlyNanoProbe(FakeSerial(responses), timeout_seconds=0.01).run(samples=1)
        result = ReadOnlyNanoProbe(FakeSerial(responses), timeout_seconds=0.01).run(
            samples=2,
            require_planroute=False,
        )
        self.assertEqual("4.0.0", result.firmware)
        self.assertEqual(2, result.samples)

    def test_malformed_or_unsafe_responses_fail(self):
        for value in ("BOARD 123", "BOARD GGGG000000000000", "BOARD 00000000000000000"):
            with self.assertRaises(ValueError):
                parse_board(value)
        with self.assertRaises(ValueError):
            parse_telemetry("TELEM ACB2 bad")
        with self.assertRaises(ValueError):
            parse_status("STATUS ACB1 bad")


if __name__ == "__main__":
    unittest.main()
