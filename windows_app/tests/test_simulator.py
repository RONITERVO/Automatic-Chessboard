import threading
import unittest

from transports import SimulatorTransport


class SimulatorTests(unittest.TestCase):
    def test_safe_monitor_commands(self):
        lines = []
        received = threading.Event()

        def on_line(line):
            lines.append(line)
            if all(any(value.startswith(prefix) for value in lines)
                   for prefix in ("INFO ACB2", "TELEM ACB2", "BOARD ")):
                received.set()

        transport = SimulatorTransport(on_line, lambda _status: None)
        transport.start()
        transport.send("INFO")
        transport.send("TELEM")
        transport.send("BOARD")
        self.assertTrue(received.wait(1.0))
        self.assertTrue(any(value.startswith("INFO ACB2") for value in lines))
        transport.close()

    def test_emergency_halt(self):
        lines = []
        received = threading.Event()

        def on_line(line):
            lines.append(line)
            if line == "ESTOP REMOTE":
                received.set()

        transport = SimulatorTransport(on_line, lambda _status: None)
        transport.start()
        transport.send("!")
        self.assertTrue(received.wait(1.0))
        self.assertIn("ESTOP REMOTE", lines)
        transport.send("CALIBRATE")
        transport.send("HEAD e4")
        transport.send("PIECE e2e4")
        transport.send("TELEM")
        threading.Event().wait(0.2)
        self.assertGreaterEqual(lines.count("ERR FAULT"), 3)
        telemetry = next(value for value in reversed(lines) if value.startswith("TELEM ACB2"))
        fields = telemetry.split()
        self.assertEqual(fields[2:9], ["10", "0", "0", "1", "0", "0", "0"])
        transport.close()

    def test_manual_calibration_head_and_piece_commands(self):
        lines = []
        transport = SimulatorTransport(lines.append, lambda _status: None)
        transport.start()
        transport.send("CALIBRATE")
        transport.send("HEAD e4")
        transport.send("PIECE e2e4")
        threading.Event().wait(0.5)
        self.assertIn("CALIBRATED e6", lines)
        self.assertIn("MOVED HEAD e4", lines)
        self.assertIn("MOVED PIECE e2e4", lines)
        transport.close()


if __name__ == "__main__":
    unittest.main()
