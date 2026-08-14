import unittest

from protocol import (
    CommandRisk,
    LineBuffer,
    board_hex_from_squares,
    classify_command,
    commit_plan_command,
    drag_command,
    head_command,
    parse_board_hex,
    parse_drag_command,
    parse_event,
    parse_info,
    parse_plan_command,
    parse_telemetry,
    piece_command,
    plan_command,
    play_command,
    queen_aligned,
    split_route_runs,
)


class ProtocolTests(unittest.TestCase):
    def test_ble_chunks_are_reassembled(self):
        lines = LineBuffer()
        self.assertEqual(lines.feed(b"MOVE e2"), [])
        self.assertEqual(lines.feed(b"e4\r\nTURN HU"), ["MOVE e2e4"])
        self.assertEqual(lines.feed(b"MAN\n"), ["TURN HUMAN"])

    def test_overflowed_line_tail_is_discarded(self):
        lines = LineBuffer(maximum=8)
        self.assertEqual(lines.feed(b"123456789HEAD e4"), [])
        self.assertEqual(lines.feed(b"\nPING\n"), ["PING"])

    def test_event_fields(self):
        event = parse_event("STATUS ACB1 17 1 1")
        self.assertEqual(event.kind, "STATUS")
        self.assertEqual(event.args, ("ACB1", "17", "1", "1"))

    def test_special_move_commands(self):
        self.assertEqual(play_command("e1g1", castling=True), "PLAY e1g1 C")
        self.assertEqual(play_command("e5d6", en_passant=True), "PLAY e5d6 E")
        self.assertEqual(play_command("e7e8q"), "PLAY e7e8q")

    def test_versioned_info_and_telemetry(self):
        info = parse_info(parse_event("INFO ACB2 3.29 BOARD,TELEM,REMOTE,ESTOP,BTTEST"))
        self.assertEqual(info.firmware, "3.29")
        self.assertIn("ESTOP", info.capabilities)
        telemetry = parse_telemetry(
            parse_event("TELEM ACB2 17 1 1 0 0 5 6 1 1 1023 847 65")
        )
        self.assertEqual(telemetry.sequence, 17)
        self.assertTrue(telemetry.homed)
        self.assertEqual(telemetry.button_b_raw, 1023)
        self.assertEqual(telemetry.free_ram, 847)

    def test_board_snapshot_round_trip(self):
        squares = frozenset({0, 7, 8, 55, 56, 63})
        encoded = board_hex_from_squares(squares)
        self.assertEqual(parse_board_hex(encoded), squares)
        with self.assertRaises(ValueError):
            parse_board_hex("not-a-board")

    def test_planroute_round_trip(self):
        path = (0, 1, 2, 10, 18, 17, 16)
        runs = split_route_runs(path)
        self.assertEqual(runs, ((0, 1, 2), (2, 10, 18), (18, 17, 16)))
        commands = [drag_command(run) for run in runs]
        self.assertEqual(commands, ["DRAG a1c1", "DRAG c1c3", "DRAG c3a3"])
        parsed = parse_drag_command(commands[1])
        self.assertEqual(parsed.path, runs[1])
        self.assertEqual(parsed.source, 2)
        self.assertEqual(parsed.target, 18)

        normal = plan_command("e2e4")
        self.assertEqual(normal, "PLAN e2e4---")
        self.assertEqual(parse_plan_command(normal).uci, "e2e4")

        capture = plan_command("e4d5", 35)
        capture_request = parse_plan_command(capture)
        self.assertEqual(capture, "PLAN e4d5-d5")
        self.assertEqual(capture_request.capture_square, 35)

        promotion_capture = plan_command("e7f8q", 61)
        promotion_request = parse_plan_command(promotion_capture)
        self.assertEqual(promotion_capture, "PLAN e7f8qf8")
        self.assertEqual(promotion_request.uci, "e7f8q")
        self.assertEqual(promotion_request.mode, "q")

        king_castle = plan_command("e1g1", castling_side="kingside")
        queen_castle = plan_command("e8c8", castling_side="queenside")
        self.assertEqual(king_castle, "PLAN e1g1k--")
        self.assertEqual(queen_castle, "PLAN e8c8c--")
        self.assertEqual(parse_plan_command(king_castle).castling_side, "kingside")
        self.assertEqual(commit_plan_command(), "COMMIT")

    def test_planroute_rejects_wrap_bad_endpoint_and_malformed_header(self):
        with self.assertRaises(ValueError):
            split_route_runs((7, 8))
        with self.assertRaises(ValueError):
            drag_command((0, 1, 9))
        with self.assertRaises(ValueError):
            parse_drag_command("DRAG a1b2")
        for value in (
            "PLAN e2e4--",
            "PLAN e2e4x--",
            "PLAN e2e4k--",
            "PLAN e1g1k-a",
        ):
            with self.assertRaises(ValueError, msg=value):
                parse_plan_command(value)
        with self.assertRaises(ValueError):
            plan_command("e1g1", castling_side="queenside")

    def test_command_risk(self):
        self.assertEqual(classify_command("TELEM"), CommandRisk.READ_ONLY)
        self.assertEqual(classify_command("SWTEST"), CommandRisk.READ_ONLY)
        self.assertEqual(classify_command("PLAY e2e4"), CommandRisk.MOTION)
        self.assertEqual(classify_command("ACCEPT"), CommandRisk.MOTION)
        self.assertEqual(classify_command("!"), CommandRisk.EMERGENCY)
        self.assertEqual(classify_command("CALIBRATE"), CommandRisk.MOTION)
        self.assertEqual(classify_command("HEAD e4"), CommandRisk.MOTION)
        self.assertEqual(classify_command("PIECE e2e4"), CommandRisk.MOTION)
        self.assertEqual(classify_command("PATH e2e4"), CommandRisk.MOTION)
        # JOG remains conservatively classified as motion for older firmware.
        self.assertEqual(classify_command("JOG W+"), CommandRisk.MOTION)
        self.assertEqual(classify_command("PLAN e2e4---"), CommandRisk.MOTION)
        self.assertEqual(classify_command("DRAG e2e4"), CommandRisk.MOTION)
        self.assertEqual(classify_command("COMMIT"), CommandRisk.MOTION)
        self.assertEqual(head_command("e6"), "HEAD e6")
        self.assertEqual(piece_command("e2", "e4"), "PIECE e2e4")

    def test_direct_carries_are_queen_aligned(self):
        self.assertTrue(queen_aligned(0, 7))
        self.assertTrue(queen_aligned(0, 56))
        self.assertTrue(queen_aligned(0, 63))
        self.assertFalse(queen_aligned(1, 18))
        self.assertFalse(queen_aligned(0, 0))


if __name__ == "__main__":
    unittest.main()
