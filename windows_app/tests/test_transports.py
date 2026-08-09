import unittest

from transports import rank_ble_devices


class BleDiscoveryTests(unittest.TestCase):
    def test_likely_board_is_ranked_before_unrelated_stronger_device(self):
        devices = [
            ("Living Room TV", "AA:AA:AA:AA:AA:AA", -35),
            ("HC-08", "BB:BB:BB:BB:BB:BB", -72),
            ("Headphones", "CC:CC:CC:CC:CC:CC", -48),
        ]
        self.assertEqual(rank_ble_devices(devices)[0][0], "HC-08")

    def test_signal_strength_breaks_ties_between_board_modules(self):
        devices = [
            ("HC-08", "AA:AA:AA:AA:AA:AA", -80),
            ("HMSoft", "BB:BB:BB:BB:BB:BB", -55),
        ]
        self.assertEqual(rank_ble_devices(devices)[0][0], "HMSoft")


if __name__ == "__main__":
    unittest.main()
