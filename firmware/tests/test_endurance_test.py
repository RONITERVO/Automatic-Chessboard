import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from endurance_test import HomeReference, parse_home_reference, reference_delta


class EnduranceProtocolTests(unittest.TestCase):
    def test_parses_version_4_calibration_measurements(self):
        self.assertEqual(
            parse_home_reference("CALIBRATED e6 W907 B347"),
            HomeReference("e6", 907, 347),
        )

    def test_rejects_legacy_or_malformed_results(self):
        for value in ("CALIBRATED e6", "CALIBRATED e5 W1 B2", "ERR MOTION"):
            with self.assertRaises(ValueError):
                parse_home_reference(value)

    def test_calculates_signed_reference_deltas(self):
        baseline = HomeReference("e6", 907, 347)
        current = HomeReference("e6", 904, 352)
        self.assertEqual(reference_delta(baseline, current), (-3, 5))


if __name__ == "__main__":
    unittest.main()
