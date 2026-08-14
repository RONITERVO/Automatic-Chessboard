import argparse
import unittest

from geometry_calculator import calculate, parse_report, rounded_divide


class GeometryCalculatorTests(unittest.TestCase):
    def test_parses_lcd_or_serial_report(self):
        self.assertEqual(parse_report("GEOMETRY a2 X+3 Y-1"), (1, 2, 3, -1))
        self.assertEqual(parse_report("h7 X-2 Y+4"), (8, 7, -2, 4))

    def test_rejects_malformed_report(self):
        with self.assertRaises(argparse.ArgumentTypeError):
            parse_report("a9 x0 y0")

    def test_rounds_signed_changes(self):
        self.assertEqual(rounded_divide(7, 3), 2)
        self.assertEqual(rounded_divide(-7, 3), -2)
        self.assertEqual(rounded_divide(7, -3), -2)

    def test_calculates_pitch_and_e6_origin(self):
        # Corrections follow X = 9 + (file-5)*2 and Y = -4 + (rank-6)*-1.
        values = calculate((1, 2, 1, 0), (8, 7, 15, -5), 188, 188, 354, 871)
        self.assertEqual(values, (190, 187, 358, 880))

    def test_requires_both_coordinates_to_differ(self):
        with self.assertRaises(ValueError):
            calculate((1, 2, 0, 0), (8, 2, 0, 0), 188, 188, 354, 871)

    def test_converts_actual_steps_back_to_source_values(self):
        values = calculate(
            (1, 2, 2, 0), (8, 7, 30, -10),
            376, 376, 708, 1742, microsteps=2,
        )
        self.assertEqual(values, (190, 187, 358, 880))

    def test_rejects_invalid_microsteps(self):
        with self.assertRaises(ValueError):
            calculate((1, 2, 0, 0), (8, 7, 0, 0), 188, 188, 354, 871, microsteps=0)


if __name__ == "__main__":
    unittest.main()
