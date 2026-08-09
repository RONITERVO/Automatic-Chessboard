"""Structured session logging and privacy-conscious support bundles."""

from __future__ import annotations

import json
import os
import platform
import re
import sys
import threading
import uuid
import zipfile
from datetime import UTC, datetime
from pathlib import Path
from typing import Any


APP_NAME = "OpenAutomaticChessboard"


def user_data_dir() -> Path:
    base = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData" / "Local"))
    path = base / APP_NAME
    path.mkdir(parents=True, exist_ok=True)
    return path


class EventRecorder:
    def __init__(self, log_dir: Path | None = None, maximum_sessions: int = 20) -> None:
        self.session_id = uuid.uuid4().hex[:12]
        self.started = datetime.now(UTC)
        directory = log_dir or user_data_dir() / "logs"
        directory.mkdir(parents=True, exist_ok=True)
        self.path = directory / f"session-{self.started:%Y%m%d-%H%M%S}-{self.session_id}.jsonl"
        self._lock = threading.Lock()
        self.record("app", "session_started")
        self._prune_old_sessions(max(1, maximum_sessions))

    def _prune_old_sessions(self, maximum_sessions: int) -> None:
        """Keep diagnostics useful without allowing logs to grow forever."""
        try:
            sessions = sorted(
                self.path.parent.glob("session-*.jsonl"),
                key=lambda path: (path == self.path, path.stat().st_mtime_ns),
                reverse=True,
            )
            for stale in sessions[maximum_sessions:]:
                stale.unlink(missing_ok=True)
        except OSError:
            # Logging must never prevent the control application from starting.
            pass

    def record(self, category: str, message: str, **fields: Any) -> None:
        row = {
            "time": datetime.now(UTC).isoformat(timespec="milliseconds"),
            "session": self.session_id,
            "category": category,
            "message": message,
            **fields,
        }
        encoded = json.dumps(row, ensure_ascii=False, separators=(",", ":"))
        with self._lock:
            with self.path.open("a", encoding="utf-8") as handle:
                handle.write(encoded + "\n")


def write_json_atomic(path: Path, payload: dict[str, Any]) -> None:
    """Replace a JSON file atomically so an interrupted save cannot truncate it."""
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    try:
        temporary.write_text(json.dumps(payload, indent=2), encoding="utf-8")
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def sanitized_settings(settings: dict[str, Any]) -> dict[str, Any]:
    clean = dict(settings)
    camera = str(clean.get("camera_source", ""))
    if "://" in camera:
        clean["camera_source"] = "<network-camera-url-redacted>"
    engine = str(clean.get("engine", ""))
    if engine:
        clean["engine"] = Path(engine).name
    ble_name = str(clean.get("ble_name", ""))
    clean["ble_name"] = re.sub(
        r"(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}",
        "<bluetooth-address-redacted>", ble_name,
    )
    return clean


def redact_text(value: str) -> str:
    value = re.sub(r"(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}",
                   "<bluetooth-address-redacted>", value)
    value = re.sub(r"(?i)[A-Z]:\\Users\\[^\\\"\s]+",
                   r"<user-profile>", value)
    value = re.sub(r"(?i)([a-z][a-z0-9+.-]*://)[^/@\s]+@",
                   r"\1<credentials-redacted>@", value)
    return value


def create_support_bundle(
    destination: Path,
    recorder: EventRecorder,
    settings: dict[str, Any],
    snapshot: dict[str, Any],
    project_files: list[Path] | None = None,
) -> Path:
    """Create a ZIP without camera frames, PGNs, credentials, or home paths."""
    destination.parent.mkdir(parents=True, exist_ok=True)
    system = {
        "created_utc": datetime.now(UTC).isoformat(),
        "platform": platform.platform(),
        "python": sys.version,
        "machine": platform.machine(),
        "app": APP_NAME,
    }
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("system.json", json.dumps(system, indent=2))
        archive.writestr("settings-sanitized.json", json.dumps(sanitized_settings(settings), indent=2))
        archive.writestr("board-snapshot.json", json.dumps(snapshot, indent=2, default=str))
        if recorder.path.is_file():
            archive.writestr("session.jsonl",
                             redact_text(recorder.path.read_text(encoding="utf-8")))
        for source in project_files or []:
            if source.is_file():
                archive.write(source, f"project/{source.name}")
    return destination
