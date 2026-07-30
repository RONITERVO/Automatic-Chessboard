import threading
import unittest

from transports import SimulatorTransport


class SimulatorTests(unittest.TestCase):
    def test_safe_monitor_commands(self):
        lines = []
        received = threading.Event()

        def on_line(line):
            lines.append(line)
            if any(value.startswith("TELEM ACB2") for value in lines) and \
                    any(value.startswith("BOARD ") for value in lines):
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


if __name__ == "__main__":
    unittest.main()
