import unittest

import chess

from model import MonitorModel, expected_occupancy
from protocol import Telemetry


class MonitorModelTests(unittest.TestCase):
    def test_disconnected_guidance_marks_values_as_stale(self):
        model = MonitorModel(connected=False)
        model.telemetry = Telemetry("ACB2", 1, True, False, False, False,
                                    5, 6, True, True, 1023, 800, 10)
        self.assertIn("last known state", model.guidance())

    def test_physical_logical_mismatch(self):
        board = chess.Board()
        expected = expected_occupancy(board)
        removed = chess.E2
        extra = chess.E4
        sensors = frozenset((expected - {removed}) | {extra})
        model = MonitorModel(connected=True, expected_squares=expected,
                             sensor_squares=sensors)
        model.mark_seen()
        self.assertEqual(model.missing_squares(), frozenset({removed}))
        self.assertEqual(model.unexpected_squares(), frozenset({extra}))
        self.assertEqual(model.overall_health(), ("Physical/logical position differs", "warn"))
        self.assertIn("differs from the logical game", model.guidance())

    def test_fault_has_priority(self):
        model = MonitorModel(connected=True)
        model.mark_seen()
        model.telemetry = Telemetry("ACB2", 10, False, True, True, False,
                                    5, 6, True, True, 1023, 800, 10)
        self.assertEqual(model.overall_health(), ("Motion fault", "bad"))
        self.assertIn("switch off physical motor power", model.guidance())


if __name__ == "__main__":
    unittest.main()
