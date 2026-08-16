import unittest
from types import SimpleNamespace
from unittest.mock import patch

import chess

from app import AutomaticChessboardApp


class _Value:
    def __init__(self):
        self.value = ""

    def set(self, value):
        self.value = value


class GameSessionGenerationTests(unittest.TestCase):
    @staticmethod
    def _pending_start_app(generation=3):
        app = AutomaticChessboardApp.__new__(AutomaticChessboardApp)
        app.session_generation = generation
        app.start_pending_generation = generation
        app.session_active = True
        app.motion_expected = True
        app.engine_thinking = False
        app.pending_move_generation = None
        app.sensorless_confirmation_generation = None
        app.sensorless_waiting_visual = False
        app.sensorless_ready_for_move = False
        app.sensorless_selected_source = None
        app.awaiting_promotion_confirmation = False
        app.sensorless_stop_reason = None
        app.game_status = _Value()
        app._reset_route_orchestration = lambda clear_pending: None
        app._render = lambda: None
        return app

    def test_start_acknowledgement_clears_only_the_current_generation(self):
        app = self._pending_start_app()

        self.assertTrue(app._acknowledge_start())
        self.assertIsNone(app.start_pending_generation)

        app.start_pending_generation = app.session_generation - 1
        self.assertFalse(app._acknowledge_start())

    def test_rejected_start_clears_the_optimistic_windows_session(self):
        app = self._pending_start_app()

        app._fail_pending_start("Could not start game: board rejected START (BUSY).")

        self.assertFalse(app.session_active)
        self.assertFalse(app.motion_expected)
        self.assertIsNone(app.start_pending_generation)
        self.assertIn("BUSY", app.game_status.value)

    def test_unacknowledged_start_requests_stop_and_invalidates_session(self):
        app = self._pending_start_app()
        app.transport = SimpleNamespace(is_connected=True)
        sent = []
        app._send = lambda command, quiet=False: sent.append((command, quiet)) or True

        app._handle_start_timeout(app.session_generation)

        self.assertEqual(sent, [("STOP", True)])
        self.assertFalse(app.session_active)
        self.assertIsNone(app.start_pending_generation)
        self.assertIn("STOP was requested", app.game_status.value)

    def test_stale_engine_event_cannot_start_a_replacement_game_move(self):
        app = AutomaticChessboardApp.__new__(AutomaticChessboardApp)
        app.session_generation = 2
        app.session_active = True
        app.engine_thinking = True
        app.board = chess.Board()
        app.game_status = _Value()
        requested = []
        app._request_physical_move = lambda move, **kwargs: requested.append((move, kwargs))

        app._send_engine_move((1, "e2e4"))

        self.assertEqual(requested, [])
        self.assertTrue(app.engine_thinking)

    def test_visual_confirmation_rechecks_session_move_and_generation(self):
        app = AutomaticChessboardApp.__new__(AutomaticChessboardApp)
        app.session_generation = 4
        app.session_active = True
        app.engine_thinking = False
        app.pending_engine_move = chess.Move.from_uci("e2e4")
        app.pending_move_is_human = True
        app.pending_move_generation = 4
        app.sensorless_waiting_visual = False
        app.sensorless_confirmation_generation = None
        app.board = chess.Board()
        app.human_color = chess.WHITE
        app.root = None
        app.game_status = _Value()
        original_fen = app.board.fen()

        def replace_session(*_args, **_kwargs):
            app._invalidate_game_session()
            app.session_active = False
            app.pending_engine_move = None
            return True

        with patch("app.messagebox.askyesno", side_effect=replace_session):
            app._request_sensorless_visual_confirmation()

        self.assertEqual(app.board.fen(), original_fen)
        self.assertFalse(app.sensorless_waiting_visual)

    def test_route_snapshot_waits_for_older_monitor_response(self):
        app = AutomaticChessboardApp.__new__(AutomaticChessboardApp)
        app.route_snapshot_pending = True
        app.route_snapshot_request_sent = False
        app.safe_request_pending = ("BOARD", "BOARD", 1.0)
        app.transport = SimpleNamespace(is_connected=True)
        app.route_deadline = 0.0
        sent = []
        app._send = lambda command, quiet=False: sent.append(command) or True

        app._dispatch_route_snapshot()
        self.assertEqual(sent, [])

        app.safe_request_pending = None
        app._dispatch_route_snapshot()
        self.assertEqual(sent, ["BOARD"])
        self.assertTrue(app.route_snapshot_request_sent)
        self.assertGreater(app.route_deadline, 0.0)

    def test_emergency_halt_reports_a_failed_write(self):
        app = AutomaticChessboardApp.__new__(AutomaticChessboardApp)
        app.transport = SimpleNamespace(is_connected=True)
        app._send = lambda command, quiet=False: False
        app._invalidate_game_session = lambda: None
        app._reset_route_orchestration = lambda clear_pending: None
        app.motion_expected = True
        app.session_active = True
        app.game_status = _Value()
        recorded = []
        app.recorder = SimpleNamespace(record=lambda *args: recorded.append(args))

        app._emergency_halt()

        self.assertIn("NOT DELIVERED", app.game_status.value)
        self.assertEqual([("safety", "remote_halt_failed")], recorded)
        self.assertFalse(app.motion_expected)
        self.assertFalse(app.session_active)


if __name__ == "__main__":
    unittest.main()
