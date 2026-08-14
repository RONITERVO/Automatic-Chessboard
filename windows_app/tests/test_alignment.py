import unittest

from alignment import AlignmentPoint, calculate_geometry, rounded_divide
from protocol import GeometrySettings


class AlignmentTests(unittest.TestCase):
    def test_calculates_pitch_and_e6_origin(self):
        current = GeometrySettings("ACB1", 188, 188, 354, 871, 1)
        # Corrections follow X = 9 + (file-5)*2 and Y = -4 + (rank-6)*-1.
        values = calculate_geometry(
            AlignmentPoint("a2", 1, 0), AlignmentPoint("h7", 15, -5), current,
        )
        self.assertEqual(
            (values.file_pitch, values.rank_pitch, values.black_park, values.white_park),
            (190, 187, 358, 880),
        )
        self.assertIn("FILE_PITCH_STEPS = 190U * MOTOR_MICROSTEPS", values.firmware_lines())

    def test_reports_source_values_for_microstepping(self):
        current = GeometrySettings("ACB1", 376, 376, 708, 1742, 2)
        values = calculate_geometry(
            AlignmentPoint("a2", 2, 0), AlignmentPoint("h7", 30, -10), current,
        )
        self.assertEqual(
            (values.file_pitch, values.rank_pitch, values.black_park, values.white_park),
            (190, 187, 358, 880),
        )

    def test_rejects_measurements_without_two_axes(self):
        current = GeometrySettings("ACB1", 188, 188, 354, 871, 1)
        with self.assertRaises(ValueError):
            calculate_geometry(
                AlignmentPoint("a2", 0, 0), AlignmentPoint("h2", 0, 0), current,
            )

    def test_rounds_signed_values(self):
        self.assertEqual(rounded_divide(7, 3), 2)
        self.assertEqual(rounded_divide(-7, 3), -2)
        self.assertEqual(rounded_divide(7, -3), -2)


if __name__ == "__main__":
    unittest.main()
