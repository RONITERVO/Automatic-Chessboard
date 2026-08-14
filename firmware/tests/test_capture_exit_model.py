import unittest


def exit_clear(occupied, file, source_rank, exit_rank):
    first_rank, last_rank = sorted((source_rank, exit_rank))
    for rank in range(first_rank, last_rank + 1):
        if rank != source_rank and (file, rank) in occupied:
            return False

    for column in range(1, file + 1):
        if column < file and (column, exit_rank) in occupied:
            return False
        if (
            exit_rank > 1
            and (column, exit_rank - 1) != (file, source_rank)
            and (column, exit_rank - 1) in occupied
        ):
            return False
    return True


def find_exit_rank(occupied, file, source_rank):
    for rank in range(source_rank, 0, -1):
        if exit_clear(occupied, file, source_rank, rank):
            return rank
    for rank in range(source_rank + 1, 9):
        if exit_clear(occupied, file, source_rank, rank):
            return rank
    return 0


class CaptureExitModelTests(unittest.TestCase):
    def test_uses_current_lane_when_clear(self):
        self.assertEqual(find_exit_rank({(4, 4)}, 4, 4), 4)

    def test_moves_down_to_bypass_a_blocked_lane(self):
        occupied = {(4, 4), (2, 4)}
        self.assertEqual(find_exit_rank(occupied, 4, 4), 3)

    def test_checks_both_rows_touching_the_boundary_lane(self):
        occupied = {(4, 4), (2, 3)}
        self.assertEqual(find_exit_rank(occupied, 4, 4), 2)

    def test_moves_up_only_when_lower_routes_are_blocked(self):
        occupied = {(4, 4), (2, 4), (4, 3)}
        self.assertEqual(find_exit_rank(occupied, 4, 4), 6)

    def test_source_is_ignored_after_vertical_departure(self):
        occupied = {(4, 4), (4, 3)}
        self.assertEqual(find_exit_rank(occupied, 4, 4), 5)

    def test_returns_no_route_when_both_vertical_directions_are_blocked(self):
        occupied = {(4, 4), (2, 4), (4, 3), (4, 5)}
        self.assertEqual(find_exit_rank(occupied, 4, 4), 0)

    def test_rank_one_uses_only_the_validated_outer_white_lane(self):
        occupied = {(5, 1), (1, 2), (2, 2), (3, 2), (4, 2)}
        self.assertEqual(find_exit_rank(occupied, 5, 1), 1)


if __name__ == "__main__":
    unittest.main()
