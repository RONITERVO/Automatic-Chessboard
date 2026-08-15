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
