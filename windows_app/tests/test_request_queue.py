import unittest
from collections import deque
import time

from app import AutomaticChessboardApp


class _ConnectedTransport:
    is_connected = True


class SafeRequestQueueTests(unittest.TestCase):
    def setUp(self):
        self.app = AutomaticChessboardApp.__new__(AutomaticChessboardApp)
        self.app.safe_request_queue = deque()
        self.app.safe_request_pending = None
        self.app.motion_expected = False
        self.app.transport = _ConnectedTransport()
        self.sent = []
        self.app._send = lambda command, quiet=False: self.sent.append(command) or True

    def test_only_one_request_is_in_flight(self):
        self.app._queue_safe_requests("PING", "INFO", "TELEM", "BOARD")
        self.assertEqual(self.sent, ["PING"])
        self.assertEqual(self.app.safe_request_pending[:2], ("PONG", "PING"))

        self.assertTrue(self.app._complete_safe_request("PONG"))
        self.app._dispatch_safe_request()
        self.assertEqual(self.sent, ["PING", "INFO"])

    def test_duplicate_and_motion_blocking_are_safe(self):
        self.app.motion_expected = True
        self.app._queue_safe_requests("TELEM", "TELEM", "START W")
        self.assertEqual(self.sent, [])
        self.assertEqual(list(self.app.safe_request_queue), ["TELEM"])

        self.app.motion_expected = False
        self.app._dispatch_safe_request()
        self.assertEqual(self.sent, ["TELEM"])

    def test_unrelated_event_does_not_release_request(self):
        self.app._queue_safe_requests("BOARD", "INFO")
        self.assertFalse(self.app._complete_safe_request("TELEM"))
        self.app._dispatch_safe_request()
        self.assertEqual(self.sent, ["BOARD"])

    def test_diagnostics_wait_for_the_serialized_batch(self):
        callbacks = []
        evaluated = []
        self.app.root = type("Root", (), {"after": lambda _self, _delay, callback: callbacks.append(callback)})()
        self.app.response_counts = {"PONG": 1, "INFO": 0, "TELEM": 0, "BOARD": 0}
        self.app.diagnostic_batch = ({"PONG": 0, "INFO": 0, "TELEM": 0, "BOARD": 0}, time.monotonic() + 10)
        self.app.safe_request_pending = ("INFO", "INFO", time.monotonic())
        self.app.safe_request_queue = deque(("TELEM", "BOARD"))
        self.app._evaluate_diagnostics = lambda: evaluated.append(True)

        self.app._check_diagnostic_batch()
        self.assertEqual(evaluated, [])
        self.assertEqual(len(callbacks), 1)

        self.app.response_counts.update({"INFO": 1, "TELEM": 1, "BOARD": 1})
        callbacks.pop()()
        self.assertEqual(evaluated, [True])


if __name__ == "__main__":
    unittest.main()
