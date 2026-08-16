import hashlib
import random
import unittest

import chess

from routing import (
    CAPTURE_EDGE_EXITS,
    MotionPlan,
    NO_SQUARE,
    PieceTask,
    PlannerConfig,
    PlanningError,
    PlanningProblem,
    RearrangementPlanner,
    analyze_feasibility,
    parse_square,
    planning_problem_from_chess,
    relaxed_corridors,
)


def sq(name: str) -> int:
    return parse_square(name)


def physical_fen_problem(fen: str, uci: str) -> PlanningProblem:
    """Build a hardware endpoint task without asserting chess legality."""

    board = chess.Board(fen)
    move = chess.Move.from_uci(uci)
    pieces = []
    for square, piece in sorted(board.piece_map().items()):
        if square == move.to_square:
            continue
        pieces.append(
            PieceTask(
                f"{piece.symbol()}-{chess.square_name(square)}",
                square,
                move.to_square if square == move.from_square else square,
                square == move.from_square,
            )
        )
    captured = board.piece_at(move.to_square) is not None
    return PlanningProblem(
        tuple(pieces),
        move_uci=uci,
        captured_square=move.to_square if captured else None,
        initial_physical_occupancy=frozenset(board.piece_map()),
        deferred_capture=captured,
        edge_capture_exit=captured,
    )


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

    def test_dense_challenge_geometries_have_constructive_plans(self):
        cases = (
            ("8/2pppp2/1pbqkbp1/1prpprp1/1PPnnPP1/1PBBKQP1/2PNNP2/2R2R2 w - - 0 1", "d3e4"),
            ("4k3/2pppp2/1prqnbp1/1pNBBpr1/1PRRNPP1/1PBBQNP1/2PPKP2/8 b - - 0 1", "d5c4"),
            ("2k5/2pppp2/1pbbnrp1/1prppqp1/1PPpnPP1/1PBBNNP1/2PRQP2/2K3R1 w - - 0 1", "e3f5"),
            ("4k3/2pppp2/1pnbqrp1/1pnrbrp1/1PRBNPP1/1PBQNRP1/2PPKP2/8 b - - 0 1", "c6d4"),
            ("4k3/2pppp2/1pbbqnp1/2prnpr1/1PPPNPP1/2PRQBP1/2PRNP2/4K3 w - - 0 1", "e4d5"),
            ("4k3/2pppp2/1pbbqrp1/1pnRnRp1/1PRQNPP1/1PBBNRP1/2PPKP2/8 w - - 0 1", "d4e5"),
            ("4k3/2pppp2/1pnbqrp1/1pnrbrp1/1PBRNPP1/1PBQNRP1/2PPKP2/8 w - - 0 1", "d4d5"),
            ("4k3/2pppp2/1pnbqrp1/1pnrprb1/1PRKNPP1/1PBQNRP1/2PPBP2/8 w - - 0 1", "d4e5"),
            ("4k3/8/8/rnbq4/pppp4/PP1P4/PPPP4/RNBQ1K2 w - - 0 1", "b1c3"),
            ("4k3/8/8/8/8/n1nnnnnn/P1PPPPPP/RNBQKBNR w - - 0 1", "c1a3"),
        )
        planner = self.planner(
            time_limit_s=1.0,
            max_nodes=1,
            max_temporary_pieces=10,
            parking_candidates=12,
            corridor_candidates=8,
            dependency_depth=8,
        )
        for fen, uci in cases:
            with self.subTest(move=uci, fen=fen):
                problem = physical_fen_problem(fen, uci)
                self.assertEqual(analyze_feasibility(problem).status, "proven_solvable")
                plan = planner.plan(problem)
                self.assert_valid(plan)
                self.assertLessEqual(plan.temporary_piece_count, 10)
                if problem.captured_square is not None:
                    self.assertEqual(plan.capture_path[-1] % 8, 0)

    def test_deterministic_constructive_stress_plans_replay(self):
        rng = random.Random(20260816)
        planner = self.planner(
            time_limit_s=1.0,
            max_nodes=1,
            max_temporary_pieces=63,
            corridor_candidates=8,
            parking_candidates=12,
            dependency_depth=8,
            broad_candidates_per_piece=3,
        )
        for capture in (False, True):
            for trial in range(500):
                piece_count = rng.randint(2, 32)
                occupied = set(rng.sample(range(64), piece_count))
                source = rng.choice(sorted(occupied))
                if capture:
                    target = rng.choice(sorted(occupied - {source}))
                    active = occupied - {target}
                else:
                    target = rng.choice(sorted(set(range(64)) - occupied))
                    active = occupied
                problem = PlanningProblem(
                    tuple(
                        PieceTask(
                            f"piece-{square}",
                            square,
                            target if square == source else square,
                            square == source,
                        )
                        for square in sorted(active)
                    ),
                    captured_square=target if capture else None,
                    deferred_capture=capture,
                    edge_capture_exit=capture,
                )
                with self.subTest(capture=capture, trial=trial):
                    self.assertEqual(analyze_feasibility(problem).status, "proven_solvable")
                    plan = planner.plan(problem)
                    self.assert_valid(plan)
                    self.assertEqual(plan.statistics.search_mode, "constructive-fallback")

    def test_capture_corridor_limit_never_discards_a_bin_exit(self):
        start = 7
        occupied = {
            1, 2, 3, 5, 9, 10, 14, 15, 16, 17, 24, 25, 31, 32, 34, 36,
            37, 39, 44, 45, 48, 49, 52, 53, 56, 58, 59, 60, 61, 62, 63,
        }
        occupant = {start: NO_SQUARE}
        occupant.update({square: index for index, square in enumerate(occupied)})

        corridors = relaxed_corridors(
            start, CAPTURE_EDGE_EXITS, occupant, NO_SQUARE, limit=4
        )

        self.assertEqual({path[-1] for path in corridors}, set(CAPTURE_EDGE_EXITS))

    def test_random_legal_chess_corpus_matches_shared_parity_digest(self):
        class XorShift64:
            def __init__(self, seed: int) -> None:
                self.state = seed

            def next_index(self, bound: int) -> int:
                mask = (1 << 64) - 1
                self.state ^= (self.state << 13) & mask
                self.state ^= self.state >> 7
                self.state ^= (self.state << 17) & mask
                self.state &= mask
                return self.state % bound

        selector = XorShift64(20260816)
        digest = hashlib.sha256()
        board = chess.Board()
        captures = 0
        promotions = 0
        planner = self.planner(
            time_limit_s=1.0,
            max_nodes=1,
            max_temporary_pieces=31,
            corridor_candidates=8,
            parking_candidates=12,
            dependency_depth=8,
            broad_candidates_per_piece=3,
        )
        for case in range(1000):
            if case and case % 80 == 0:
                board = chess.Board()
                digest.update(b"RESET\n")
            legal = sorted(move.uci() for move in board.legal_moves)
            if not legal:
                board = chess.Board()
                digest.update(b"RESET\n")
                legal = sorted(move.uci() for move in board.legal_moves)
            uci = legal[selector.next_index(len(legal))]
            digest.update(f"{uci}\n".encode())
            move = chess.Move.from_uci(uci)
            captures += int(board.is_capture(move))
            promotions += int(move.promotion is not None)
            problem = planning_problem_from_chess(
                board,
                move,
                physical_occupancy=frozenset(board.piece_map()),
                deferred_capture=True,
                edge_capture_exit=True,
            )
            plan = planner.plan(problem)
            self.assert_valid(plan)
            self.assertEqual(plan.statistics.search_mode, "constructive-fallback")
            commands = plan.protocol_commands()
            self.assertTrue(commands[0].startswith("PLAN "))
            self.assertEqual(commands[-1], "COMMIT")
            for command in commands:
                self.assertLess(len(command), 32)
                if command.startswith("DRAG "):
                    move_text = command[5:]
                    self.assertTrue(
                        move_text[0] == move_text[2] or move_text[1] == move_text[3]
                    )
            board.push(move)

        self.assertEqual(captures, 124)
        self.assertEqual(promotions, 2)
        self.assertEqual(
            digest.hexdigest().upper(),
            "0E2822D7D5A4A2500587ABCC85799E8417A5AAEE6C3EBF9FCD2AD2164120757A",
        )

    def test_capture_blocker_can_stay_parked_until_after_primary_move(self):
        occupied = {sq(name) for name in (
            "a1", "c1", "a2", "b2", "d2", "a3", "b3", "c3", "a4"
        )}
        source, captured = sq("a4"), sq("b2")
        pieces = tuple(
            PieceTask(
                f"p-{square}",
                square,
                captured if square == source else square,
                square == source,
            )
            for square in sorted(occupied - {captured})
        )
        problem = PlanningProblem(
            pieces,
            captured_square=captured,
            deferred_capture=True,
            edge_capture_exit=True,
        )

        plan = self.planner(
            time_limit_s=1.0,
            max_nodes=1,
            max_temporary_pieces=1,
            corridor_candidates=8,
            parking_candidates=12,
            dependency_depth=8,
        ).plan(problem)

        self.assert_valid(plan)
        self.assertEqual(plan.temporary_piece_count, 1)
        self.assertEqual(plan.capture_removal_index, 1)
        self.assertEqual(plan.relocations[0].piece_key, f"p-{sq('b3')}")
        self.assertEqual(plan.relocations[-1].target, sq("b3"))

    def test_constructive_capture_seeds_every_a_file_exit(self):
        occupied = {
            1, 6, 8, 9, 11, 18, 23, 25, 27, 29, 30, 31, 32, 33, 34, 35,
            37, 39, 40, 43, 44, 46, 48, 49, 50, 52, 53, 55, 56, 59, 61, 62,
        }
        source, captured = 61, 43
        pieces = tuple(
            PieceTask(
                f"p-{square}",
                square,
                captured if square == source else square,
                square == source,
            )
            for square in sorted(occupied - {captured})
        )
        problem = PlanningProblem(
            pieces,
            captured_square=captured,
            deferred_capture=True,
            edge_capture_exit=True,
        )

        plan = self.planner(
            time_limit_s=1.0,
            max_nodes=1,
            max_temporary_pieces=10,
            corridor_candidates=8,
            parking_candidates=12,
            dependency_depth=8,
        ).plan(problem)

        self.assert_valid(plan)
        self.assertLessEqual(plan.temporary_piece_count, 10)
        self.assertEqual(plan.capture_path[-1] % 8, 0)

    def test_full_board_non_edge_capture_is_proven_impossible(self):
        captured = sq("b1")
        pieces = []
        for square in range(64):
            if square == captured:
                continue
            pieces.append(
                PieceTask(
                    f"p-{square}",
                    square,
                    captured if square == sq("a1") else square,
                    square == sq("a1"),
                )
            )
        problem = PlanningProblem(
            tuple(pieces),
            captured_square=captured,
            deferred_capture=True,
            edge_capture_exit=True,
            initial_physical_occupancy=frozenset(range(64)),
        )
        analysis = analyze_feasibility(problem)
        self.assertEqual(analysis.status, "proven_impossible")
        with self.assertRaisesRegex(PlanningError, "Proven physically impossible"):
            self.planner().plan(problem)

    def test_one_hole_parity_obstruction_is_proven_impossible(self):
        # Swap two same-color labels while the only blank remains fixed.  The
        # desired permutation is odd but the blank-color change is even.
        blank = sq("h8")
        first, second = sq("a1"), sq("c1")
        pieces = []
        for square in range(64):
            if square == blank:
                continue
            goal = second if square == first else first if square == second else square
            pieces.append(
                PieceTask(f"p-{square}", square, goal, square == first)
            )
        problem = PlanningProblem(tuple(pieces))
        analysis = analyze_feasibility(problem)
        self.assertEqual(analysis.status, "proven_impossible")
        self.assertIn("parity", analysis.reason)

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

        plan = self.planner(time_limit_s=15.0, max_nodes=500_000).plan(problem)

        self.assert_valid(plan)
        commands = plan.protocol_commands()
        remove_index = commands.index("REMOVE")
        self.assertTrue(any(command.startswith("DRAG ") for command in commands[2:remove_index]))
        self.assertEqual(commands[remove_index + 1], "BOARD")
        self.assertEqual(plan.capture_removal_index, 1)

    def test_edge_exit_routes_capture_without_moving_unrelated_a4(self):
        board = chess.Board()
        for uci in ("e2e4", "d7d5", "e4e5", "e7e6", "a2a4", "b8c6", "f2f4"):
            board.push_uci(uci)
        move = chess.Move.from_uci("c6e5")
        problem = planning_problem_from_chess(
            board,
            move,
            physical_occupancy=frozenset(board.piece_map()),
            deferred_capture=True,
            edge_capture_exit=True,
        )

        plan = self.planner(time_limit_s=15.0, max_nodes=500_000).plan(problem)

        self.assert_valid(plan)
        self.assertEqual(plan.temporary_piece_count, 0)
        self.assertEqual(plan.capture_removal_index, 0)
        self.assertEqual(plan.capture_path[0], sq("e5"))
        self.assertEqual(plan.capture_path[-1], sq("a3"))
        commands = plan.protocol_commands()
        self.assertNotIn("DRAG a4a5", commands)
        self.assertLess(commands.index("DRAG e3a3"), commands.index("REMOVE"))

    def test_edge_exit_uses_a_winding_empty_route_before_disturbing_pieces(self):
        fixed = (
            "a1", "a2", "a3", "a4", "a5", "a7", "a8",
            "b1", "b2", "b3", "b4", "b7", "b8",
        )
        pieces = [PieceTask("main", sq("h8"), sq("h7"), primary=True)]
        pieces.extend(PieceTask(f"fixed-{name}", sq(name), sq(name)) for name in fixed)
        problem = PlanningProblem(
            tuple(pieces),
            move_uci="h8h7",
            captured_square=sq("e5"),
            deferred_capture=True,
            edge_capture_exit=True,
        )

        plan = self.planner(max_temporary_pieces=0).plan(problem)

        self.assert_valid(plan)
        self.assertEqual(plan.temporary_piece_count, 0)
        self.assertEqual(
            tuple(map(sq, ("e5", "e6", "d6", "c6", "b6", "a6"))),
            plan.capture_path,
        )

    def test_fewer_pickups_outrank_shorter_carried_distance(self):
        # The old local pathfinder returned a 6-step route with three turns.
        # Because each turn is another pickup, the 8-step/two-turn route is
        # lexicographically better under the hardware objective.
        problem = PlanningProblem(
            (
                PieceTask("main", sq("a1"), sq("a5"), primary=True),
                PieceTask("wall-b2", sq("b2"), sq("b2")),
                PieceTask("wall-a4", sq("a4"), sq("a4")),
            ),
            move_uci="a1a5",
        )

        plan = self.planner(max_temporary_pieces=0).plan(problem)

        self.assert_valid(plan)
        self.assertEqual((plan.pickup_count, plan.carried_steps), (3, 8))
        self.assertEqual(sum(move.turns for move in plan.relocations), 2)

    def test_fewer_capture_pickups_outrank_shorter_bin_route(self):
        problem = PlanningProblem(
            (
                PieceTask("main", sq("h8"), sq("h7"), primary=True),
                PieceTask("fixed-a1", sq("a1"), sq("a1")),
                PieceTask("fixed-f2", sq("f2"), sq("f2")),
            ),
            move_uci="h8h7",
            captured_square=sq("h1"),
            deferred_capture=True,
            edge_capture_exit=True,
        )

        plan = self.planner(max_temporary_pieces=0).plan(problem)

        self.assert_valid(plan)
        self.assertEqual((plan.pickup_count, plan.carried_steps), (4, 10))
        self.assertEqual(plan.capture_path[-1], sq("a3"))

    def test_unweighted_default_avoids_weighted_first_goal_turn_loss(self):
        problem = PlanningProblem(
            (
                PieceTask("main", sq("a1"), sq("a8"), primary=True),
                PieceTask("secondary", sq("a2"), sq("b3")),
                PieceTask("wall", sq("b1"), sq("b1")),
            )
        )

        plan = self.planner(max_temporary_pieces=1).plan(problem)

        self.assert_valid(plan)
        objective = (
            plan.temporary_piece_count,
            plan.pickup_count,
            plan.carried_steps,
            sum(move.turns for move in plan.relocations),
        )
        self.assertEqual(objective, (1, 3, 9, 0))

    def test_exact_macro_search_certifies_small_case(self):
        problem = PlanningProblem(
            (
                PieceTask("main", sq("a1"), sq("a5"), primary=True),
                PieceTask("wall-b2", sq("b2"), sq("b2")),
                PieceTask("wall-a4", sq("a4"), sq("a4")),
            )
        )

        plan = self.planner(
            max_temporary_pieces=0,
            exact_search=True,
            heuristic_weight=1.0,
        ).plan(problem)

        self.assert_valid(plan)
        self.assertTrue(plan.statistics.optimal)
        self.assertEqual(plan.statistics.search_mode, "exhaustive")
        self.assertEqual((plan.pickup_count, plan.carried_steps), (3, 8))

    def test_constructive_incumbent_reserves_half_the_deadline_for_search(self):
        class SearchStarted(RuntimeError):
            pass

        class DeadlineProbePlanner(RearrangementPlanner):
            started = 0.0
            constructive_deadline = 0.0
            search_deadline = 0.0

            def _constructive_plan(self, problem, started, **_kwargs):
                self.started = started
                self.constructive_deadline = self._deadline
                return None

            def _search(self, *_args, **_kwargs):
                self.search_deadline = self._deadline
                raise SearchStarted

        planner = DeadlineProbePlanner(PlannerConfig(time_limit_s=2.0))
        problem = PlanningProblem(
            (PieceTask("main", sq("a1"), sq("a2"), primary=True),)
        )

        with self.assertRaises(SearchStarted):
            planner.plan(problem)

        self.assertAlmostEqual(
            planner.constructive_deadline - planner.started, 1.0, places=4
        )
        self.assertAlmostEqual(
            planner.search_deadline - planner.started, 2.0, places=4
        )

    def test_exact_macro_search_improves_a_focused_first_goal(self):
        problem = PlanningProblem(
            (
                PieceTask("main", sq("d6"), sq("c8"), primary=True),
                PieceTask("secondary", sq("e3"), sq("d1")),
            )
        )

        focused = self.planner(max_temporary_pieces=1).plan(problem)
        exact = self.planner(
            max_temporary_pieces=1,
            exact_search=True,
            heuristic_weight=1.0,
        ).plan(problem)

        self.assert_valid(focused)
        self.assert_valid(exact)
        focused_turns = sum(move.turns for move in focused.relocations)
        exact_turns = sum(move.turns for move in exact.relocations)
        self.assertEqual(
            (focused.pickup_count, focused.carried_steps, focused_turns),
            (4, 6, 2),
        )
        self.assertEqual(
            (exact.pickup_count, exact.carried_steps, exact_turns),
            (4, 6, 0),
        )
        self.assertTrue(exact.statistics.optimal)

    def test_duplicate_piece_keys_fail_at_problem_construction(self):
        with self.assertRaisesRegex(ValueError, "unique"):
            PlanningProblem(
                (
                    PieceTask("duplicate", sq("a1"), sq("a2"), primary=True),
                    PieceTask("duplicate", sq("h8"), sq("h8")),
                )
            )

    def test_invalid_branch_controls_are_rejected(self):
        with self.assertRaises(ValueError):
            PlannerConfig(dependency_depth=-1)
        with self.assertRaises(ValueError):
            PlannerConfig(broad_candidates_per_piece=0)
        with self.assertRaisesRegex(ValueError, "heuristic_weight=1.0"):
            PlannerConfig(exact_search=True, heuristic_weight=1.25)


class ChessAdapterTests(unittest.TestCase):
    def test_invalid_fen_is_rejected_even_if_move_generator_lists_the_move(self):
        board = chess.Board(
            "8/2pppp2/1pbqkbp1/1prpprp1/1PPnnPP1/1PBBKQP1/2PNNP2/2R2R2 w - - 0 1"
        )
        move = chess.Move.from_uci("d3e4")
        self.assertIn(move, board.legal_moves)
        status = board.status()
        self.assertTrue(status & chess.STATUS_TOO_MANY_BLACK_PAWNS)
        self.assertTrue(status & chess.STATUS_TOO_MANY_BLACK_PIECES)
        self.assertFalse(board.is_valid())

        with self.assertRaisesRegex(PlanningError, "Invalid chess position"):
            planning_problem_from_chess(board, move)

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
