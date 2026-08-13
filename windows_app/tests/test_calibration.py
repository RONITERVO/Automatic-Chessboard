import unittest

from calibration import BoardOffsetProfile, nudge_command


class BoardOffsetCalibrationTests(unittest.TestCase):
    def test_profile_is_copyable_and_reinstallable(self):
        profile = BoardOffsetProfile.from_event_args(("354", "871"))
        self.assertEqual(profile.answer, "Black offset 354; white offset 871")
        self.assertEqual(profile.command, "CALSET 354 871")

    def test_safe_ranges_and_nudges(self):
        with self.assertRaises(ValueError):
            BoardOffsetProfile(199, 871)
        self.assertEqual(nudge_command("x", True, False), "NUDGE X+ 1")
        self.assertEqual(nudge_command("Y", False, True), "NUDGE Y- 5")
