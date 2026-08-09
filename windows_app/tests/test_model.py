import unittest
from time import monotonic

import chess

from model import (
    ManualSelection,
    MonitorModel,
    calibration_matches,
    expected_occupancy,
    piece_move_matches,
)
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

    def test_manual_selection_and_calibration_verification(self):
        occupied = frozenset({chess.E2, chess.A7})
        selection, _ = ManualSelection("piece").choose(chess.E2, occupied)
        selection, _ = selection.choose(chess.E4, occupied)
        self.assertEqual(selection.command(), "PIECE e2e4")
        telemetry = Telemetry("ACB2", 1, True, False, False, False,
                              5, 6, True, True, 1023, 700, 1)
        self.assertTrue(calibration_matches("e6", telemetry))
        self.assertTrue(piece_move_matches(chess.E2, chess.E4, frozenset({chess.E4})))

    def test_telemetry_freshness_is_independent_of_other_board_messages(self):
        model = MonitorModel(connected=True)
        model.mark_seen()
        self.assertIsNone(model.telemetry_age_seconds())
        model.telemetry_updated = monotonic() - 6.0
        self.assertGreater(model.telemetry_age_seconds(), 5.0)


if __name__ == "__main__":
    unittest.main()
