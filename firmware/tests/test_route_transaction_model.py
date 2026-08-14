import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from route_transaction_model import (
    MotionlessRouteExecutor,
    RouteProtocolError,
    square_index,
    square_name,
)


def assert_error(test: unittest.TestCase, code: str, action) -> None:
    with test.assertRaises(RouteProtocolError) as caught:
        action()
    test.assertEqual(code, caught.exception.code)


class RouteTransactionModelTests(unittest.TestCase):
    def test_exhaustive_clear_straight_drags_commit(self):
        checked = 0
        for source in range(64):
            for target in range(64):
                same_file = source % 8 == target % 8
                same_rank = source // 8 == target // 8
                if source == target or not (same_file or same_rank):
                    continue
                model = MotionlessRouteExecutor({source})
                uci = square_name(source) + square_name(target)
                self.assertEqual("PLAN READY", model.begin(f"PLAN {uci}---"))
                self.assertEqual(f"MOVED PIECE {uci}", model.drag(f"DRAG {uci}"))
                self.assertEqual(f"DONE {uci}", model.commit())
                self.assertEqual(frozenset({target}), model.expected)
                checked += 1
        self.assertEqual(896, checked)

    def test_exhaustive_diagonal_drags_are_rejected(self):
        checked = 0
        for source in range(64):
            for target in range(64):
                if source == target or source % 8 == target % 8 or source // 8 == target // 8:
                    continue
                uci = square_name(source) + square_name(target)
                model = MotionlessRouteExecutor({source})
                model.begin(f"PLAN {uci}---")
                assert_error(self, "BAD ROUTE", lambda: model.drag(f"DRAG {uci}"))
                checked += 1
        self.assertEqual(3136, checked)

    def test_every_intermediate_corridor_square_blocks_a_drag(self):
        checked = 0
        for source in range(64):
            for target in range(64):
                if source == target:
                    continue
                if source % 8 == target % 8:
                    step = 8 if target > source else -8
                elif source // 8 == target // 8:
                    step = 1 if target > source else -1
                else:
                    continue
                intermediates = tuple(range(source + step, target, step))
                for blocker in intermediates:
                    uci = square_name(source) + square_name(target)
                    model = MotionlessRouteExecutor({source, blocker})
                    model.begin(f"PLAN {uci}---")
                    assert_error(self, "ROUTE BLOCKED", lambda: model.drag(f"DRAG {uci}"))
                    checked += 1
        self.assertEqual(1792, checked)

    def test_plan_a_style_evacuation_main_move_and_restore(self):
        initial = {square_index("a1"), square_index("a4")}
        model = MotionlessRouteExecutor(initial)
        model.begin("PLAN a1a8---")
        model.drag("DRAG a4b4")
        model.drag("DRAG a1a8")
        model.drag("DRAG b4a4")
        self.assertEqual("DONE a1a8", model.commit())

    def test_capture_en_passant_promotion_and_standard_castling(self):
        capture = MotionlessRouteExecutor({square_index("e4"), square_index("d5")})
        capture.begin("PLAN e4d5-d5")
        capture.remove_capture()
        self.assertNotIn(square_index("d5"), capture.observed)
        capture.drag("DRAG e4e5")
        capture.drag("DRAG e5d5")
        self.assertEqual("DONE e4d5", capture.commit())

        en_passant = MotionlessRouteExecutor({square_index("e5"), square_index("d5")})
        en_passant.begin("PLAN e5d6-d5")
        en_passant.remove_capture()
        en_passant.drag("DRAG e5e6")
        en_passant.drag("DRAG e6d6")
        self.assertEqual("DONE e5d6", en_passant.commit())

        promotion = MotionlessRouteExecutor({square_index("a7")})
        promotion.begin("PLAN a7a8q--")
        promotion.drag("DRAG a7a8")
        self.assertEqual("DONE a7a8", promotion.commit())

        for rank in ("1", "8"):
            king_side = MotionlessRouteExecutor({square_index(f"e{rank}"), square_index(f"h{rank}")})
            king_side.begin(f"PLAN e{rank}g{rank}k--")
            king_side.drag(f"DRAG e{rank}g{rank}")
            other_rank = "2" if rank == "1" else "7"
            king_side.drag(f"DRAG h{rank}h{other_rank}")
            king_side.drag(f"DRAG h{other_rank}f{other_rank}")
            king_side.drag(f"DRAG f{other_rank}f{rank}")
            self.assertEqual(f"DONE e{rank}g{rank}", king_side.commit())

            queen_side = MotionlessRouteExecutor({square_index(f"e{rank}"), square_index(f"a{rank}")})
            queen_side.begin(f"PLAN e{rank}c{rank}c--")
            queen_side.drag(f"DRAG e{rank}c{rank}")
            queen_side.drag(f"DRAG a{rank}a{other_rank}")
            queen_side.drag(f"DRAG a{other_rank}d{other_rank}")
            queen_side.drag(f"DRAG d{other_rank}d{rank}")
            self.assertEqual(f"DONE e{rank}c{rank}", queen_side.commit())

    def test_capture_removal_waits_until_an_orthogonal_exit_path_exists(self):
        model = MotionlessRouteExecutor({
            square_index("c6"), square_index("e5"), square_index("d5"),
            square_index("f5"), square_index("e4"), square_index("e6"),
        })
        self.assertEqual("PLAN READY", model.begin("PLAN c6e5-e5"))
        assert_error(self, "CAPTURE", model.remove_capture)
        model.drag("DRAG d5d4")
        self.assertEqual("REMOVED", model.remove_capture())
        self.assertNotIn(square_index("e5"), model.observed)

    def test_capture_can_be_dragged_to_any_a_file_exit_before_removal(self):
        model = MotionlessRouteExecutor({
            square_index("c6"), square_index("e5"), square_index("a4"),
            square_index("d5"), square_index("e6"),
        })
        self.assertEqual("PLAN READY", model.begin("PLAN c6e5-e5"))
        model.drag("DRAG e5e3")
        model.drag("DRAG e3a3")
        self.assertEqual("REMOVED", model.remove_capture())
        self.assertNotIn(square_index("a3"), model.observed)
        self.assertIn(square_index("a4"), model.observed)

    def test_exact_commit_cancellation_and_incomplete_plan(self):
        source, target = square_index("a1"), square_index("a2")
        cancelled = MotionlessRouteExecutor({source})
        cancelled.begin("PLAN a1a2---")
        self.assertEqual("PLAN CANCELLED", cancelled.commit())

        incomplete = MotionlessRouteExecutor({source})
        incomplete.begin("PLAN a1a2---")
        incomplete.drag("DRAG a1b1")
        assert_error(self, "PLAN INCOMPLETE", incomplete.commit)
        self.assertTrue(incomplete.active)
        self.assertEqual("STOPPED", incomplete.stop())
        self.assertFalse(incomplete.active)
        self.assertEqual(frozenset({square_index('b1')}), incomplete.expected)
        self.assertNotEqual(frozenset({target}), incomplete.expected)

    def test_pre_plan_pre_drag_and_post_drag_sensor_faults(self):
        stale = MotionlessRouteExecutor({square_index("a1")})
        stale.set_observed({square_index("a2")})
        assert_error(self, "PLAN STATE", lambda: stale.begin("PLAN a1a2---"))

        pre_drag = MotionlessRouteExecutor({square_index("a1")})
        pre_drag.begin("PLAN a1a2---")
        pre_drag.set_observed(set())
        assert_error(self, "PLAN STATE", lambda: pre_drag.drag("DRAG a1a2"))
        self.assertTrue(pre_drag.active)

        post_drag = MotionlessRouteExecutor({square_index("a1")})
        post_drag.begin("PLAN a1a2---")
        assert_error(
            self,
            "SENSORS",
            lambda: post_drag.drag("DRAG a1a2", observed_after={square_index("a1")}),
        )
        self.assertTrue(post_drag.fault)
        self.assertFalse(post_drag.active)

        capture_fault = MotionlessRouteExecutor({square_index("e4"), square_index("d5")})
        capture_fault.begin("PLAN e4d5-d5")
        assert_error(
            self,
            "SENSORS",
            lambda: capture_fault.remove_capture(
                observed_after={square_index("e4"), square_index("d5")},
            ),
        )
        self.assertTrue(capture_fault.fault)

    def test_source_target_and_malformed_requests_are_rejected(self):
        source_empty = MotionlessRouteExecutor(set())
        source_empty.begin("PLAN a1a2---")
        assert_error(self, "SOURCE EMPTY", lambda: source_empty.drag("DRAG a1a2"))

        target_full = MotionlessRouteExecutor({square_index("a1"), square_index("a2")})
        target_full.begin("PLAN a1a3---")
        assert_error(self, "TARGET FULL", lambda: target_full.drag("DRAG a1a2"))

        for command in (
            "PLAN a1a1---",
            "PLAN z1a2---",
            "PLAN a1a2x--",
            "PLAN e1c1k--",
            "PLAN e1g1c--",
            "PLAN a1a2--",
        ):
            assert_error(self, "BAD PLAN", lambda command=command: MotionlessRouteExecutor({0}).begin(command))

        missing_capture = MotionlessRouteExecutor({square_index("e4")})
        assert_error(self, "PLAN STATE", lambda: missing_capture.begin("PLAN e4d5-d5"))

    def test_emergency_halt_clears_plan_and_latches_fault(self):
        model = MotionlessRouteExecutor({square_index("a1")})
        model.begin("PLAN a1a2---")
        self.assertEqual("ESTOP REMOTE", model.emergency_halt())
        self.assertFalse(model.active)
        self.assertTrue(model.fault)
        assert_error(self, "NOT READY", lambda: model.begin("PLAN a1a2---"))

    def test_no_plan_and_final_sensor_branches(self):
        idle = MotionlessRouteExecutor({square_index("a1")})
        assert_error(self, "NO PLAN", lambda: idle.drag("DRAG a1a2"))
        assert_error(self, "NO PLAN", idle.commit)

        final = MotionlessRouteExecutor({square_index("a1")})
        final.begin("PLAN a1a2---")
        final.drag("DRAG a1a2")
        final.set_observed(set())
        assert_error(self, "FINAL SENSORS", final.commit)


if __name__ == "__main__":
    unittest.main()
