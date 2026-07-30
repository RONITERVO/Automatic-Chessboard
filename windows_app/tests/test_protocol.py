import unittest

from protocol import (
    CommandRisk,
    LineBuffer,
    board_hex_from_squares,
    classify_command,
    parse_board_hex,
    parse_event,
    parse_info,
    parse_telemetry,
    play_command,
)


class ProtocolTests(unittest.TestCase):
    def test_ble_chunks_are_reassembled(self):
        lines = LineBuffer()
        self.assertEqual(lines.feed(b"MOVE e2"), [])
        self.assertEqual(lines.feed(b"e4\r\nTURN HU"), ["MOVE e2e4"])
        self.assertEqual(lines.feed(b"MAN\n"), ["TURN HUMAN"])

    def test_event_fields(self):
        event = parse_event("STATUS ACB1 17 1 1")
        self.assertEqual(event.kind, "STATUS")
        self.assertEqual(event.args, ("ACB1", "17", "1", "1"))

    def test_special_move_commands(self):
        self.assertEqual(play_command("e1g1", castling=True), "PLAY e1g1 C")
        self.assertEqual(play_command("e5d6", en_passant=True), "PLAY e5d6 E")
        self.assertEqual(play_command("e7e8q"), "PLAY e7e8q")

    def test_versioned_info_and_telemetry(self):
        info = parse_info(parse_event("INFO ACB2 3.28 BOARD,TELEM,REMOTE,ESTOP"))
        self.assertEqual(info.firmware, "3.28")
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

    def test_command_risk(self):
        self.assertEqual(classify_command("TELEM"), CommandRisk.READ_ONLY)
        self.assertEqual(classify_command("PLAY e2e4"), CommandRisk.MOTION)
        self.assertEqual(classify_command("ACCEPT"), CommandRisk.MOTION)
        self.assertEqual(classify_command("!"), CommandRisk.EMERGENCY)


if __name__ == "__main__":
    unittest.main()
