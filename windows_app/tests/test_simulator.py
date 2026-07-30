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

    def test_sensor_map_profile_can_be_changed(self):
        lines = []
        received = threading.Event()

        def on_line(line):
            lines.append(line)
            if line == "SENSORMAP ACB1 GLUED_TILES":
                received.set()

        transport = SimulatorTransport(on_line, lambda _status: None)
        transport.start()
        transport.send("SENSORMAP SET GLUED_TILES")
        self.assertTrue(received.wait(1.0))
        self.assertIn("SENSORMAP ACB1 GLUED_TILES", lines)
        transport.close()


if __name__ == "__main__":
    unittest.main()
