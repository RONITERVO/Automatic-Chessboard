import unittest

import chess

from routing import (
    MotionPlan,
    PieceTask,
    PlannerConfig,
    PlanningError,
    PlanningProblem,
    RearrangementPlanner,
    parse_square,
    planning_problem_from_chess,
)


def sq(name: str) -> int:
    return parse_square(name)


class RearrangementPlannerTests(unittest.TestCase):
    def planner(self, **overrides) -> RearrangementPlanner:
        values = dict(
            time_limit_s=5.0,
            max_nodes=150_000,
            max_temporary_pieces=4,
            parking_candidates=8,
            corridor_candidates=6,
            dependency_depth=5,
        )
        values.update(overrides)
        return RearrangementPlanner(PlannerConfig(**values))

    def assert_valid(self, plan: MotionPlan) -> None:
        plan.validate()
        for move in plan.relocations:
            self.assertEqual(move.path[0], move.source)
            self.assertEqual(move.path[-1], move.target)

    def test_direct_route_uses_one_pickup(self):
        problem = PlanningProblem(
            (PieceTask("main", sq("a1"), sq("a3"), primary=True),),
            move_uci="a1a3",
        )
        plan = self.planner().plan(problem)
        self.assert_valid(plan)
        self.assertEqual(len(plan.relocations), 1)
        self.assertEqual(plan.drag_count, 1)
        self.assertEqual(plan.pickup_count, 1)
        self.assertEqual(plan.temporary_piece_count, 0)

    def test_turning_route_counts_and_emits_each_physical_pickup(self):
        problem = PlanningProblem(
            (PieceTask("main", sq("a1"), sq("b2"), primary=True),),
            move_uci="a1b2",
        )
        plan = self.planner().plan(problem)
        self.assert_valid(plan)
        self.assertEqual(plan.drag_count, 2)
        self.assertEqual(plan.pickup_count, 2)
        drags = [command for command in plan.protocol_commands()
                 if command.startswith("DRAG ")]
        self.assertEqual(len(drags), 2)
        self.assertTrue(drags[0].startswith("DRAG a1"))
        self.assertTrue(drags[1].endswith("b2"))

    def test_plan_a_evacuates_one_barrier_piece_and_restores_it(self):
        pieces = [PieceTask("main", sq("a1"), sq("a8"), primary=True)]
        pieces.extend(
            PieceTask(f"barrier-{file_name}", sq(file_name + "4"), sq(file_name + "4"))
            for file_name in "abcdefgh"
        )
        plan = self.planner(max_temporary_pieces=2).plan(
            PlanningProblem(tuple(pieces), move_uci="a1a8")
        )
        self.assert_valid(plan)
        self.assertEqual(plan.temporary_piece_count, 1)
        purposes = [move.purpose for move in plan.relocations]
        self.assertIn("evacuate", purposes)
        self.assertIn("restore", purposes)
        self.assertEqual(plan.relocations[-1].target, plan.problem.pieces[
            next(index for index, piece in enumerate(plan.problem.pieces)
                 if piece.key == plan.relocations[-1].piece_key)
        ].goal)

    def test_plan_b_stages_primary_while_restoring_a_blocker(self):
        # This is the critical mid-plan topology: a3 is the only gate back to
        # the blocker's home. The primary at a3 must leave, B must pass, and the
        # primary must then return. R supplies the actual logical move task.
        problem = PlanningProblem(
            (
                PieceTask("main", sq("a3"), sq("a3"), primary=True),
                PieceTask("blocker", sq("a4"), sq("a2")),
                PieceTask("wall-b1", sq("b1"), sq("b1")),
                PieceTask("wall-b2", sq("b2"), sq("b2")),
                PieceTask("other-primary", sq("h1"), sq("h2"), primary=True),
            ),
            move_uci="h1h2",
        )
        plan = self.planner(max_temporary_pieces=1).plan(problem)
        self.assert_valid(plan)
        stage_indexes = [
            index for index, move in enumerate(plan.relocations)
            if move.piece_key == "main" and move.purpose == "stage"
        ]
        self.assertTrue(stage_indexes)
        blocker_restore = next(
            index for index, move in enumerate(plan.relocations)
            if move.piece_key == "blocker" and move.target == sq("a2")
        )
        main_return = max(
            index for index, move in enumerate(plan.relocations)
            if move.piece_key == "main" and move.target == sq("a3")
        )
        self.assertLess(stage_indexes[0], blocker_restore)
        self.assertLess(blocker_restore, main_return)

    def test_plan_c_recursively_frees_a_trapped_piece(self):
        # B starts in the a1 corner with both exits occupied. The planner must
        # first evacuate C or D, then route B, then restore the secondary piece.
        problem = PlanningProblem(
            (
                PieceTask("trapped", sq("a1"), sq("a3")),
                PieceTask("secondary-c", sq("a2"), sq("a2")),
                PieceTask("secondary-d", sq("b1"), sq("b1")),
                PieceTask("primary", sq("h1"), sq("h2"), primary=True),
            ),
            move_uci="h1h2",
        )
        plan = self.planner(max_temporary_pieces=2).plan(problem)
        self.assert_valid(plan)
        trapped_index = next(
            index for index, move in enumerate(plan.relocations)
            if move.piece_key == "trapped"
        )
        self.assertGreater(trapped_index, 0)
        self.assertIn(plan.relocations[trapped_index - 1].piece_key,
                      {"secondary-c", "secondary-d"})
        self.assertEqual(plan.temporary_piece_count, 2)

    def test_protocol_sequence_is_transactional(self):
        problem = PlanningProblem(
            (PieceTask("main", sq("a1"), sq("a2"), primary=True),),
            move_uci="a1a2",
        )
        commands = self.planner().plan(problem).protocol_commands()
        self.assertEqual(commands[0], "PLAN a1a2---")
        self.assertEqual(commands[1], "BOARD")
        self.assertTrue(any(command.startswith("DRAG ") for command in commands))
        self.assertEqual(commands[-2], "BOARD")
        self.assertEqual(commands[-1], "COMMIT")

    def test_physical_snapshot_mismatch_fails_before_search(self):
        problem = PlanningProblem(
            (PieceTask("main", sq("a1"), sq("a2"), primary=True),),
            move_uci="a1a2",
            initial_physical_occupancy=frozenset({sq("h8")}),
        )
        with self.assertRaises(PlanningError):
            self.planner().plan(problem)

    def test_deferred_capture_clears_bin_lane_before_removal(self):
        board = chess.Board()
        for uci in ("e2e4", "d7d5", "e4e5", "e7e6", "a2a4", "b8c6", "f2f4"):
            board.push_uci(uci)
        move = chess.Move.from_uci("c6e5")
        problem = planning_problem_from_chess(
            board,
            move,
            physical_occupancy=frozenset(board.piece_map()),
            deferred_capture=True,
        )

        plan = self.planner(max_nodes=500_000).plan(problem)

        self.assert_valid(plan)
        commands = plan.protocol_commands()
        remove_index = commands.index("REMOVE")
        self.assertTrue(any(command.startswith("DRAG ") for command in commands[2:remove_index]))
        self.assertEqual(commands[remove_index + 1], "BOARD")
        self.assertEqual(plan.capture_removal_index, 1)


class ChessAdapterTests(unittest.TestCase):
    def test_capture_square_is_removed_from_the_rearrangement_problem(self):
        board = chess.Board()
        board.push_uci("e2e4")
        board.push_uci("d7d5")
        move = chess.Move.from_uci("e4d5")

        problem = planning_problem_from_chess(board, move)

        self.assertEqual(problem.captured_square, chess.D5)
        self.assertNotIn(chess.D5, {piece.start for piece in problem.pieces})
        main = next(piece for piece in problem.pieces if piece.start == chess.E4)
        self.assertEqual(main.goal, chess.D5)
        self.assertTrue(main.primary)

    def test_en_passant_uses_the_captured_pawn_square(self):
        board = chess.Board()
        for uci in ("e2e4", "a7a6", "e4e5", "d7d5"):
            board.push_uci(uci)
        move = chess.Move.from_uci("e5d6")

        problem = planning_problem_from_chess(board, move)

        self.assertTrue(board.is_en_passant(move))
        self.assertEqual(problem.captured_square, chess.D5)
        self.assertNotIn(chess.D5, {piece.start for piece in problem.pieces})

    def test_promotion_mode_is_encoded_in_transaction_header(self):
        board = chess.Board("8/P7/8/8/8/8/8/4K2k w - - 0 1")
        move = chess.Move.from_uci("a7a8q")
        problem = planning_problem_from_chess(board, move)
        plan = RearrangementPlanner().plan(problem)

        self.assertEqual(plan.protocol_commands()[0], "PLAN a7a8q--")

    def test_standard_castling_routes_both_king_and_rook(self):
        board = chess.Board("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1")
        move = chess.Move.from_uci("e1g1")

        problem = planning_problem_from_chess(board, move)

        self.assertEqual(problem.castling_side, "kingside")
        primary_goals = {
            piece.start: piece.goal for piece in problem.pieces if piece.primary
        }
        self.assertEqual(primary_goals, {chess.E1: chess.G1, chess.H1: chess.F1})
        plan = RearrangementPlanner().plan(problem)
        self.assertEqual(plan.protocol_commands()[0], "PLAN e1g1k--")

    def test_chess960_is_rejected_explicitly(self):
        board = chess.Board()
        board.chess960 = True
        move = chess.Move.from_uci("e2e4")

        with self.assertRaisesRegex(PlanningError, "standard castling"):
            planning_problem_from_chess(board, move)


if __name__ == "__main__":
    unittest.main()
