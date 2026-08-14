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

    def test_knight_never_auto_resumes(self):
        self.assertFalse(carried_path_clear({(2, 1)}, (2, 1), (3, 3)))


if __name__ == "__main__":
    unittest.main()
