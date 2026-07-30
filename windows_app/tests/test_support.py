import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from support import EventRecorder, create_support_bundle, redact_text, sanitized_settings


class SupportTests(unittest.TestCase):
    def test_credentials_are_redacted(self):
        settings = sanitized_settings({
            "camera_source": "rtsp://user:password@camera.local/live",
            "engine": r"C:\private\stockfish.exe",
        })
        self.assertEqual(settings["camera_source"], "<network-camera-url-redacted>")
        self.assertEqual(settings["engine"], "stockfish.exe")

    def test_log_identifiers_are_redacted(self):
        value = redact_text(
            r"BLE connected 50:65:83:8D:6A:5C C:\Users\alice\board "
            "rtsp://user:secret@camera/live"
        )
        self.assertNotIn("50:65", value)
        self.assertNotIn("alice", value)
        self.assertNotIn("user:secret", value)

    def test_bundle_contains_only_expected_diagnostics(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            recorder = EventRecorder(root / "logs")
            recorder.record("test", "hello")
            output = create_support_bundle(
                root / "support.zip", recorder,
                {"camera_source": "rtsp://secret@camera/live"}, {"health": "Ready"},
            )
            with zipfile.ZipFile(output) as archive:
                self.assertEqual(
                    set(archive.namelist()),
                    {"system.json", "settings-sanitized.json", "board-snapshot.json", "session.jsonl"},
                )
                settings = json.loads(archive.read("settings-sanitized.json"))
                self.assertNotIn("secret", settings["camera_source"])


if __name__ == "__main__":
    unittest.main()
