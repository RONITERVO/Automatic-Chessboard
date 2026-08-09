import unittest
from collections import deque

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


if __name__ == "__main__":
    unittest.main()
