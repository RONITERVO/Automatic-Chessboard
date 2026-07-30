"""Optional camera worker for USB webcams and RTSP/HTTP streams.

Camera dependencies are deliberately optional so board diagnostics continue to
work on machines where OpenCV is unavailable.
"""

from __future__ import annotations

import threading
import time
from collections.abc import Callable
from typing import Any


FrameCallback = Callable[[Any], None]
StatusCallback = Callable[[str], None]


class CameraWorker:
    def __init__(self, source: str, on_frame: FrameCallback,
                 on_status: StatusCallback) -> None:
        self.source = source.strip() or "0"
        self.on_frame = on_frame
        self.on_status = on_status
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    @staticmethod
    def dependencies_available() -> bool:
        try:
            import cv2  # noqa: F401
            from PIL import Image  # noqa: F401
            return True
        except ImportError:
            return False

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def _run(self) -> None:
        try:
            import cv2
            from PIL import Image
        except ImportError:
            self.on_status("Camera support is not installed. Run setup-camera.ps1.")
            return

        source: int | str = int(self.source) if self.source.isdigit() else self.source
        display_source = "network camera" if "://" in self.source else self.source
        delay = 1.0
        while not self._stop.is_set():
            capture = cv2.VideoCapture(source)
            if not capture.isOpened():
                self.on_status(f"Cannot open camera {display_source!r}; retrying...")
                capture.release()
                self._stop.wait(delay)
                delay = min(delay * 2, 10.0)
                continue
            delay = 1.0
            self.on_status(f"Camera connected: {display_source}")
            last_frame = 0.0
            while not self._stop.is_set() and capture.isOpened():
                ok, frame = capture.read()
                if not ok:
                    break
                now = time.monotonic()
                if now - last_frame < 0.1:
                    continue
                last_frame = now
                rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
                self.on_frame(Image.fromarray(rgb))
            capture.release()
            if not self._stop.is_set():
                self.on_status("Camera stream interrupted; reconnecting...")
        self.on_status("Camera stopped")

    def stop(self) -> None:
        self._stop.set()
