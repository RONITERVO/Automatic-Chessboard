import time
import unittest

from transports import SimulatorTransport


class SimulatorTests(unittest.TestCase):
    def wait_for(self, lines, predicate, timeout=3.0):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            snapshot = list(lines)
            if predicate(snapshot):
                return
            time.sleep(0.01)
        self.fail(f"Timed out waiting for simulator output: {lines}")

    def test_safe_monitor_commands(self):
        lines = []

        def on_line(line):
            lines.append(line)

        transport = SimulatorTransport(on_line, lambda _status: None)
        transport.start()
        transport.send("INFO")
        transport.send("TELEM")
        transport.send("BOARD")
        self.wait_for(lines, lambda values: all(
            any(value.startswith(prefix) for value in values)
            for prefix in ("INFO ACB2", "TELEM ACB2", "BOARD ")
        ))
        info = next(value for value in lines if value.startswith("INFO ACB2"))
        self.assertIn("SENSORFRAME", info)
        self.assertIn("PLANROUTE", info)
        self.assertIn("REMOVE", info)
        self.assertIn("EDGEEXIT", info)
        self.assertIn("APPBOARD", info)
        transport.close()

    def test_app_board_mode_routes_both_sides_without_reed_events(self):
        from protocol import commit_plan_command, drag_command, parse_board_hex, plan_command

        lines = []
        transport = SimulatorTransport(lines.append, lambda _status: None)
        transport.start()
        transport.send("START W APP")
        self.wait_for(lines, lambda values: "SESSION W" in values and "TURN HUMAN" in values)
        self.assertNotIn("SETUP PRESS A", lines)

        for uci, path in (("e2e4", (12, 20, 28)), ("e7e5", (52, 44, 36))):
            transport.send(plan_command(uci))
            self.wait_for(lines, lambda values: values.count("PLAN READY") >= (1 if uci == "e2e4" else 2))
            transport.send(drag_command(path))
            self.wait_for(lines, lambda values: f"MOVED PIECE {uci}" in values)
            transport.send(commit_plan_command())
            self.wait_for(lines, lambda values: f"DONE {uci}" in values)

        transport.send("BOARD")
        self.wait_for(lines, lambda values: any(value.startswith("BOARD ") for value in values))
        occupied = parse_board_hex(next(value for value in reversed(lines) if value.startswith("BOARD ")).split()[1])
        self.assertNotIn(12, occupied)
        self.assertIn(28, occupied)
        self.assertNotIn(52, occupied)
        self.assertIn(36, occupied)
        transport.close()

    def test_emergency_halt(self):
        lines = []

        def on_line(line):
            lines.append(line)

        transport = SimulatorTransport(on_line, lambda _status: None)
        transport.start()
        transport.send("!")
        self.wait_for(lines, lambda values: "ESTOP REMOTE" in values)
        self.assertIn("ESTOP REMOTE", lines)
        transport.send("CALIBRATE")
        transport.send("HEAD e4")
        transport.send("PIECE e2e4")
        transport.send("TELEM")
        self.wait_for(lines, lambda values: values.count("ERR FAULT") >= 3 and
                      any(value.startswith("TELEM ACB2") for value in values))
        self.assertGreaterEqual(lines.count("ERR FAULT"), 3)
        telemetry = next(value for value in reversed(lines) if value.startswith("TELEM ACB2"))
        fields = telemetry.split()
        self.assertEqual(fields[2:9], ["10", "0", "0", "1", "0", "0", "0"])
        transport.close()

    def test_transactional_planroute_updates_physical_board_before_commit(self):
        from protocol import commit_plan_command, drag_command, parse_board_hex, plan_command

        lines = []
        transport = SimulatorTransport(lines.append, lambda _status: None)
        transport.start()
        transport.send("START B")
        self.wait_for(lines, lambda values: "SESSION B" in values and "TURN COMPUTER" in values)
        transport.send(plan_command("e2e4"))
        self.wait_for(lines, lambda values: "PLAN READY" in values)
        self.assertIn("PLAN READY", lines)
        transport.send(drag_command((12, 20, 28)))
        self.wait_for(lines, lambda values: "MOVED PIECE e2e4" in values)
        self.assertIn("MOVED PIECE e2e4", lines)
        transport.send("BOARD")
        self.wait_for(lines, lambda values: any(value.startswith("BOARD ") for value in values))
        board_line = next(value for value in reversed(lines) if value.startswith("BOARD "))
        occupied = parse_board_hex(board_line.split()[1])
        self.assertNotIn(12, occupied)
        self.assertIn(28, occupied)
        transport.send(commit_plan_command())
        self.wait_for(lines, lambda values: "DONE e2e4" in values)
        self.assertIn("DONE e2e4", lines)
        transport.close()

    def test_clean_transaction_commit_is_cancellation(self):
        from protocol import commit_plan_command, plan_command

        lines = []
        transport = SimulatorTransport(lines.append, lambda _status: None)
        transport.start()
        transport.send("START B")
        self.wait_for(lines, lambda values: "SESSION B" in values and "TURN COMPUTER" in values)
        transport.send(plan_command("e2e4"))
        self.wait_for(lines, lambda values: "PLAN READY" in values)
        transport.send(commit_plan_command())
        self.wait_for(lines, lambda values: "PLAN CANCELLED" in values)
        self.assertIn("PLAN CANCELLED", lines)
        transport.close()

    def test_incomplete_dirty_transaction_is_rejected_but_stop_remains_available(self):
        from protocol import commit_plan_command, drag_command, plan_command

        lines = []
        transport = SimulatorTransport(lines.append, lambda _status: None)
        transport.start()
        transport.send("START B")
        self.wait_for(lines, lambda values: "SESSION B" in values and "TURN COMPUTER" in values)
        transport.send(plan_command("e2e4"))
        self.wait_for(lines, lambda values: "PLAN READY" in values)
        transport.send(drag_command((12, 20)))
        self.wait_for(lines, lambda values: "MOVED PIECE e2e3" in values)
        transport.send(commit_plan_command())
        self.wait_for(lines, lambda values: "ERR PLAN INCOMPLETE" in values)
        self.assertIn("ERR PLAN INCOMPLETE", lines)
        transport.send("STOP")
        self.wait_for(lines, lambda values: "STOPPED" in values)
        self.assertIn("STOPPED", lines)
        transport.close()

    def test_capture_cannot_be_committed_before_main_piece_arrives(self):
        import chess
        from protocol import commit_plan_command, plan_command, remove_command

        lines = []
        transport = SimulatorTransport(lines.append, lambda _status: None)
        transport.start()
        # White: e2-e4; black: d7-d5. It is then White's legal e4xd5 capture.
        transport._board.push_uci("e2e4")
        transport._board.push_uci("d7d5")
        transport._physical_squares = set(transport._board.piece_map())
        transport._sequence = 15
        transport._remote_mode = True
        transport._homed = True
        move = chess.Move.from_uci("e4d5")
        self.assertIn(move, transport._board.legal_moves)
        transport.send(plan_command("e4d5", chess.D5))
        self.wait_for(lines, lambda values: "PLAN READY" in values)
        self.assertIn("PLAN READY", lines)
        transport.send(remove_command(chess.D5))
        self.wait_for(lines, lambda values: "REMOVED" in values)
        transport.send(commit_plan_command())
        self.wait_for(lines, lambda values: "ERR PLAN INCOMPLETE" in values)
        self.assertIn("ERR PLAN INCOMPLETE", lines)
        transport.close()

    def test_manual_calibration_head_and_piece_commands(self):
        lines = []
        transport = SimulatorTransport(lines.append, lambda _status: None)
        transport.start()
        transport.send("CALIBRATE")
        transport.send("HEAD e4")
        transport.send("PIECE e2e4")
        self.wait_for(lines, lambda values: all(expected in values for expected in (
            "CALIBRATED e6", "MOVED HEAD e4", "MOVED PIECE e2e4",
        )))
        self.assertIn("CALIBRATED e6", lines)
        self.assertIn("MOVED HEAD e4", lines)
        self.assertIn("MOVED PIECE e2e4", lines)
        transport.send("PIECE b1c3")
        self.wait_for(lines, lambda values: "ERR BAD ROUTE" in values)
        self.assertIn("ERR BAD ROUTE", lines)
        transport.close()

    def test_remote_state_numbers_match_firmware_45(self):
        from protocol import parse_event, parse_telemetry

        lines = []
        transport = SimulatorTransport(lines.append, lambda _status: None)
        transport.start()
        transport.send("START B")
        self.wait_for(lines, lambda values: "TURN COMPUTER" in values)
        transport.send("TELEM")
        self.wait_for(lines, lambda values: any(value.startswith("TELEM ACB2") for value in values))
        active = parse_telemetry(parse_event(
            next(value for value in reversed(lines) if value.startswith("TELEM ACB2"))
        ))
        self.assertEqual(active.sequence, 15)
        self.assertTrue(active.remote_mode)
        transport.send("STOP")
        self.wait_for(lines, lambda values: "STOPPED" in values)
        transport.send("TELEM")
        self.wait_for(lines, lambda values: sum(value.startswith("TELEM ACB2") for value in values) >= 2)
        idle = parse_telemetry(parse_event(
            next(value for value in reversed(lines) if value.startswith("TELEM ACB2"))
        ))
        self.assertEqual(idle.sequence, 1)
        self.assertFalse(idle.remote_mode)
        transport.close()

    def test_alignment_session_is_recoverable_and_leaves_position_unknown(self):
        from protocol import parse_alignment, parse_event, parse_geometry

        lines = []
        transport = SimulatorTransport(lines.append, lambda _status: None)
        transport.start()
        transport.send("CALIBRATE")
        self.wait_for(lines, lambda values: "CALIBRATED e6" in values)
        transport.send("GEOMETRY")
        transport.send("ALIGN a2 H")
        self.wait_for(lines, lambda values: "ALIGN READY a2 H 0 0" in values)
        geometry_line = next(value for value in lines if value.startswith("GEOMETRY "))
        self.assertEqual(parse_geometry(parse_event(geometry_line)).file_pitch, 188)
        transport.send("NUDGE X+")
        transport.send("NUDGE Y-")
        self.wait_for(lines, lambda values: "ALIGN ACTIVE a2 H 1 -1" in values)
        active = parse_alignment(parse_event("ALIGN ACTIVE a2 H 1 -1"))
        self.assertEqual((active.offset_x, active.offset_y), (1, -1))
        transport.send("ALIGN STATUS")
        self.wait_for(lines, lambda values: values.count("ALIGN ACTIVE a2 H 1 -1") >= 2)
        transport.send("ALIGN END")
        self.wait_for(lines, lambda values: "ALIGN ENDED" in values)
        transport.send("TELEM")
        self.wait_for(lines, lambda values: any(value.startswith("TELEM ACB2") for value in values))
        telemetry = next(value for value in reversed(lines) if value.startswith("TELEM ACB2"))
        self.assertEqual(telemetry.split()[3], "0")
        transport.close()

    def test_planroute_requires_a_homed_computer_turn(self):
        from protocol import plan_command

        lines = []
        transport = SimulatorTransport(lines.append, lambda _status: None)
        transport.start()
        transport._sequence = 15
        transport._remote_mode = True
        transport.send(plan_command("e2e4"))
        self.wait_for(lines, lambda values: "ERR NOT READY" in values)
        self.assertIn("ERR NOT READY", lines)
        transport.close()

    def test_planner_protocol_and_simulator_complete_special_moves(self):
        import chess
        from routing import PlannerConfig, RearrangementPlanner, planning_problem_from_chess

        positions = (
            ("8/8/8/3p4/4P3/8/8/4K2k w - - 0 1", "e4d5", "DONE e4d5"),
            ("8/P7/8/8/8/8/8/4K2k w - - 0 1", "a7a8q", "DONE a7a8"),
            ("4k3/8/8/8/8/8/8/4K2R w K - 0 1", "e1g1", "DONE e1g1"),
        )
        planner = RearrangementPlanner(PlannerConfig(time_limit_s=5.0, max_nodes=150_000))
        for fen, uci, acknowledgement in positions:
            with self.subTest(uci=uci):
                board = chess.Board(fen)
                move = chess.Move.from_uci(uci)
                self.assertIn(move, board.legal_moves)
                plan = planner.plan(planning_problem_from_chess(
                    board, move, deferred_capture=True,
                ))
                lines = []
                transport = SimulatorTransport(lines.append, lambda _status: None)
                transport.start()
                transport._board = board.copy(stack=False)
                transport._physical_squares = set(board.piece_map())
                transport._sequence = 15
                transport._remote_mode = True
                transport._homed = True
                for command in plan.protocol_commands():
                    baseline = len(lines)
                    transport.send(command)
                    expected = (
                        "PLAN READY" if command.startswith("PLAN ") else
                        f"MOVED PIECE {command.split()[1]}" if command.startswith("DRAG ") else
                        "REMOVED" if command == "REMOVE" else
                        acknowledgement if command == "COMMIT" else
                        "BOARD "
                    )
                    self.wait_for(
                        lines,
                        lambda values, expected=expected: any(
                            value == expected or value.startswith(expected)
                            for value in values[baseline:]
                        ),
                    )
                self.assertFalse(any(line.startswith("ERR ") for line in lines), lines)
                self.assertIn(acknowledgement, lines)
                if move.promotion:
                    self.wait_for(lines, lambda values: "PROMOTE q" in values)
                    self.assertIn("PROMOTE q", lines)
                self.assertEqual(set(transport._board.piece_map()), transport._physical_squares)
                transport.close()

    def test_blocked_knight_capture_is_cleared_removed_and_completed(self):
        import chess
        from routing import PlannerConfig, RearrangementPlanner, planning_problem_from_chess

        board = chess.Board()
        for uci in ("e2e4", "d7d5", "e4e5", "e7e6", "a2a4", "b8c6", "f2f4"):
            board.push_uci(uci)
        move = chess.Move.from_uci("c6e5")
        plan = RearrangementPlanner(
            PlannerConfig(time_limit_s=5.0, max_nodes=500_000)
        ).plan(planning_problem_from_chess(
            board, move, deferred_capture=True, edge_capture_exit=True,
        ))

        self.assertEqual(plan.temporary_piece_count, 0)
        self.assertNotIn("DRAG a4a5", plan.protocol_commands())

        lines = []
        transport = SimulatorTransport(lines.append, lambda _status: None)
        transport.start()
        transport._board = board.copy(stack=False)
        transport._physical_squares = set(board.piece_map())
        transport._sequence = 15
        transport._remote_mode = True
        transport._homed = True
        for command in plan.protocol_commands():
            baseline = len(lines)
            transport.send(command)
            expected = (
                "PLAN READY" if command.startswith("PLAN ") else
                f"MOVED PIECE {command.split()[1]}" if command.startswith("DRAG ") else
                "REMOVED" if command == "REMOVE" else
                "DONE c6e5" if command == "COMMIT" else "BOARD "
            )
            self.wait_for(lines, lambda values, expected=expected, baseline=baseline: any(
                value == expected or value.startswith(expected) for value in values[baseline:]
            ))

        self.assertFalse(any(line.startswith("ERR ") for line in lines), lines)
        self.assertEqual(set(transport._board.piece_map()), transport._physical_squares)
        transport.close()


if __name__ == "__main__":
    unittest.main()
