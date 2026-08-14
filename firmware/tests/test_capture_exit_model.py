import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from route_transaction_model import capture_exit_path, empty_orthogonal_path, square_index

def indexes(*names):
    return {square_index(name) for name in names}


def manual_capture_states(occupied, source, target, captured):
    after_removal = occupied - {captured}
    after_placement = (after_removal - {source}) | {target}
    return after_removal, after_placement


def carried_path_clear(occupied, source, target, ignored=None):
    file_delta = target[0] - source[0]
    rank_delta = target[1] - source[1]
    if file_delta and rank_delta and abs(file_delta) != abs(rank_delta):
        return False
    file_step = (file_delta > 0) - (file_delta < 0)
    rank_step = (rank_delta > 0) - (rank_delta < 0)
    file, rank = source
    while (file, rank) != target:
        next_file = file + file_step
        next_rank = rank + rank_step
        touched = [(next_file, next_rank)]
        if file_step and rank_step:
            touched.extend(((next_file, rank), (file, next_rank)))
        if any(square != ignored and square in occupied for square in touched):
            return False
        file, rank = next_file, next_rank
    return source != target


class CaptureExitModelTests(unittest.TestCase):
    def test_uses_direct_rank_when_clear(self):
        path = capture_exit_path(indexes("e4"), square_index("e4"))
        self.assertEqual(path, tuple(map(square_index, ("e4", "d4", "c4", "b4", "a4"))))

    def test_finds_shortest_route_around_unrelated_pieces(self):
        occupied = indexes("e5", "d5", "c5", "b5", "d4", "c4", "b4")
        path = capture_exit_path(occupied, square_index("e5"))
        self.assertIsNotNone(path)
        self.assertEqual(path[0], square_index("e5"))
        self.assertEqual(path[-1] % 8, 0)
        self.assertTrue(all(square not in occupied - {square_index("e5")} for square in path))
        self.assertTrue(all(abs(second - first) in (1, 8) for first, second in zip(path, path[1:])))

    def test_uses_any_free_a_file_exit(self):
        occupied = indexes("e5", "a1", "a2", "a3", "a4", "a5", "a7", "a8")
        path = capture_exit_path(occupied, square_index("e5"))
        self.assertEqual(path[-1], square_index("a6"))

    def test_returns_no_route_only_when_capture_is_trapped(self):
        occupied = indexes("e5", "d5", "f5", "e4", "e6")
        self.assertIsNone(capture_exit_path(occupied, square_index("e5")))

    def test_a_file_capture_is_already_at_bin_exit(self):
        self.assertEqual(
            capture_exit_path(indexes("a4"), square_index("a4")),
            (square_index("a4"),),
        )

    def test_manual_ordinary_capture_has_an_empty_intermediate_target(self):
        removal, placement = manual_capture_states(
            {("b", 6), ("c", 4)}, ("b", 6), ("c", 4), ("c", 4)
        )
        self.assertEqual(removal, {("b", 6)})
        self.assertEqual(placement, {("c", 4)})

    def test_manual_en_passant_verifies_all_three_changed_squares(self):
        removal, placement = manual_capture_states(
            {("b", 4), ("c", 4)}, ("b", 4), ("c", 3), ("c", 4)
        )
        self.assertEqual(removal, {("b", 4)})
        self.assertEqual(placement, {("c", 3)})

    def test_auto_resume_accepts_clear_orthogonal_and_diagonal_carries(self):
        self.assertTrue(carried_path_clear({(5, 4)}, (5, 4), (5, 2)))
        self.assertTrue(carried_path_clear({(3, 3)}, (3, 3), (5, 5)))

    def test_auto_resume_rejects_a_blocked_straight_corridor(self):
        self.assertFalse(
            carried_path_clear({(1, 1), (3, 1)}, (1, 1), (4, 1))
        )

    def test_auto_resume_requires_both_diagonal_corners_clear(self):
        self.assertFalse(
            carried_path_clear({(3, 3), (4, 3)}, (3, 3), (4, 4))
        )
        self.assertFalse(
            carried_path_clear({(3, 3), (3, 4)}, (3, 3), (4, 4))
        )

    def test_capture_square_can_be_ignored_during_preflight(self):
        occupied = {(2, 4), (3, 4)}
        self.assertTrue(carried_path_clear(occupied, (2, 4), (3, 3), (3, 4)))

    def test_knight_uses_shortest_empty_orthogonal_route(self):
        occupied = indexes("f3", "e3")
        path = empty_orthogonal_path(
            occupied, square_index("f3"), square_index("d4")
        )
        self.assertEqual(
            path, tuple(map(square_index, ("f3", "f4", "e4", "d4")))
        )

    def test_knight_routes_around_unrelated_pieces(self):
        occupied = indexes("f3", "e3", "f4", "e4")
        path = empty_orthogonal_path(
            occupied, square_index("f3"), square_index("d4")
        )
        self.assertIsNotNone(path)
        self.assertEqual(path[0], square_index("f3"))
        self.assertEqual(path[-1], square_index("d4"))
        self.assertTrue(all(square not in occupied - {square_index("f3")} for square in path))

    def test_knight_falls_back_only_when_source_is_trapped(self):
        occupied = indexes("f3", "e3", "g3", "f2", "f4")
        self.assertIsNone(
            empty_orthogonal_path(occupied, square_index("f3"), square_index("d4"))
        )

    def test_capture_target_can_be_ignored_during_knight_preflight(self):
        occupied = indexes("f3", "e3", "d4")
        path = empty_orthogonal_path(
            occupied, square_index("f3"), square_index("d4"), square_index("d4")
        )
        self.assertIsNotNone(path)
        self.assertEqual(path[-1], square_index("d4"))


if __name__ == "__main__":
    unittest.main()
