"""Visual Windows control, monitoring, and development app for the chessboard."""

from __future__ import annotations

import json
import queue
import sys
import threading
import time
from collections import deque
from datetime import datetime
from pathlib import Path
import tkinter as tk
from tkinter import filedialog, messagebox, simpledialog, ttk

import chess
import chess.engine
import chess.pgn

from camera_source import CameraWorker
from model import (
    ManualSelection,
    MonitorModel,
    calibration_matches,
    expected_occupancy,
    head_move_matches,
    piece_move_matches,
    square_name,
)
from protocol import (
    CommandRisk,
    classify_command,
    parse_board_hex,
    parse_drag_command,
    parse_event,
    parse_info,
    parse_telemetry,
    play_command,
)
from routing import MotionPlan, PlannerConfig, PlanningError, plan_chess_move
from support import EventRecorder, create_support_bundle, user_data_dir, write_json_atomic
from transports import (
    Hc08BleTransport,
    SimulatorTransport,
    UsbSerialTransport,
    discover_ble_devices,
    serial_ports,
)

APP_VERSION = "1.2.0"
ROUTE_CONTROL_TIMEOUT_S = 8.0
ROUTE_MOTION_TIMEOUT_S = 75.0
APP_DIR = Path(__file__).resolve().parent
RUNTIME_DIR = Path(sys.executable).resolve().parent if getattr(sys, "frozen", False) else APP_DIR
DATA_DIR = user_data_dir()
SETTINGS_PATH = DATA_DIR / "settings.json"
LEGACY_SETTINGS_PATH = APP_DIR / "settings.json"
DEFAULT_STOCKFISH = RUNTIME_DIR / "stockfish" / "stockfish.exe"

PIECE_TEXT = {
    "P": "♙", "N": "♘", "B": "♗", "R": "♖", "Q": "♕", "K": "♔",
    "p": "♟", "n": "♞", "b": "♝", "r": "♜", "q": "♛", "k": "♚",
}

COLORS = {
    "good": "#207a3c",
    "warn": "#a15c00",
    "bad": "#ad2532",
    "muted": "#637083",
    "accent": "#2067b2",
    "panel": "#f4f6f8",
}


class ChessboardCanvas(tk.Canvas):
    """Logical pieces plus physical reed occupancy and carriage position."""

    def __init__(self, master, size: int = 560):
        super().__init__(master, width=size, height=size, highlightthickness=1,
                         highlightbackground="#9ba5b1", background="#ffffff")
        self.board = chess.Board()
        self.sensors: frozenset[int] | None = None
        self.flipped = False
        self.last_move: chess.Move | None = None
        self.carriage: tuple[int, int] | None = None
        self.selected_squares: frozenset[int] = frozenset()
        self.on_square_clicked = None
        self.bind("<Configure>", lambda _event: self.redraw())
        self.bind("<Button-1>", self._clicked)

    def set_state(self, board: chess.Board, sensors: frozenset[int] | None,
                  flipped: bool, carriage: tuple[int, int] | None = None) -> None:
        self.board = board.copy(stack=False)
        self.sensors = sensors
        self.flipped = flipped
        self.last_move = board.peek() if board.move_stack else None
        self.carriage = carriage
        self.redraw()

    def set_interaction(self, selected: frozenset[int], callback) -> None:
        changed = selected != self.selected_squares or callback != self.on_square_clicked
        self.selected_squares = selected
        self.on_square_clicked = callback
        self.configure(cursor="hand2" if callback else "")
        if changed:
            self.redraw()

    def _clicked(self, event) -> None:
        if not self.on_square_clicked:
            return
        width, height = max(self.winfo_width(), 160), max(self.winfo_height(), 160)
        size = min(width, height)
        offset_x, offset_y = (width - size) / 2, (height - size) / 2
        if not (offset_x <= event.x < offset_x + size and offset_y <= event.y < offset_y + size):
            return
        display_file = min(7, int((event.x - offset_x) / (size / 8)))
        display_rank = min(7, int((event.y - offset_y) / (size / 8)))
        file_index = 7 - display_file if self.flipped else display_file
        rank_index = display_rank if self.flipped else 7 - display_rank
        self.on_square_clicked(chess.square(file_index, rank_index))

    def _display_coordinates(self, square: int) -> tuple[int, int]:
        file_index = chess.square_file(square)
        rank_index = chess.square_rank(square)
        if self.flipped:
            return 7 - file_index, rank_index
        return file_index, 7 - rank_index

    def redraw(self) -> None:
        self.delete("all")
        width = max(self.winfo_width(), 160)
        height = max(self.winfo_height(), 160)
        size = min(width, height)
        offset_x = (width - size) / 2
        offset_y = (height - size) / 2
        square_size = size / 8
        expected = expected_occupancy(self.board)
        missing = expected - self.sensors if self.sensors is not None else frozenset()
        unexpected = self.sensors - expected if self.sensors is not None else frozenset()

        for display_rank in range(8):
            for display_file in range(8):
                file_index = 7 - display_file if self.flipped else display_file
                rank_index = display_rank if self.flipped else 7 - display_rank
                square = chess.square(file_index, rank_index)
                x0 = offset_x + display_file * square_size
                y0 = offset_y + display_rank * square_size
                fill = "#f0d9b5" if (file_index + rank_index) % 2 else "#b58863"
                if self.last_move and square in (self.last_move.from_square, self.last_move.to_square):
                    fill = "#d7c75b" if fill == "#f0d9b5" else "#aa9b3d"
                self.create_rectangle(x0, y0, x0 + square_size, y0 + square_size,
                                      fill=fill, outline=fill)
                if square in self.selected_squares:
                    inset = max(2, square_size * 0.04)
                    self.create_rectangle(
                        x0 + inset, y0 + inset, x0 + square_size - inset,
                        y0 + square_size - inset, outline="#f4c542",
                        width=max(3, int(square_size / 14)),
                    )

                piece = self.board.piece_at(square)
                if piece:
                    self.create_text(
                        x0 + square_size / 2, y0 + square_size / 2,
                        text=PIECE_TEXT[piece.symbol()],
                        font=("Segoe UI Symbol", max(12, int(square_size * 0.66))),
                    )

                if self.sensors is not None:
                    if square in missing:
                        inset = max(3, square_size * 0.06)
                        self.create_rectangle(
                            x0 + inset, y0 + inset, x0 + square_size - inset,
                            y0 + square_size - inset, outline="#d1242f", width=max(2, int(square_size / 20)),
                        )
                    elif square in unexpected:
                        radius = max(5, square_size * 0.13)
                        cx, cy = x0 + square_size * 0.78, y0 + square_size * 0.22
                        self.create_oval(cx - radius, cy - radius, cx + radius, cy + radius,
                                         fill="#ffb000", outline="#7a4b00", width=2)
                    elif square in self.sensors:
                        radius = max(3, square_size * 0.07)
                        cx, cy = x0 + square_size * 0.84, y0 + square_size * 0.16
                        self.create_oval(cx - radius, cy - radius, cx + radius, cy + radius,
                                         fill="#2ca44f", outline="#145c2a")

                if display_file == 0:
                    self.create_text(x0 + 4, y0 + 3, anchor="nw", text=str(rank_index + 1),
                                     fill="#303030", font=("Segoe UI", 8, "bold"))
                if display_rank == 7:
                    self.create_text(x0 + square_size - 3, y0 + square_size - 2,
                                     anchor="se", text=chr(ord("a") + file_index),
                                     fill="#303030", font=("Segoe UI", 8, "bold"))

        if self.carriage and all(1 <= value <= 8 for value in self.carriage):
            square = chess.square(self.carriage[0] - 1, self.carriage[1] - 1)
            display_file, display_rank = self._display_coordinates(square)
            cx = offset_x + (display_file + 0.5) * square_size
            cy = offset_y + (display_rank + 0.5) * square_size
            radius = square_size * 0.35
            self.create_oval(cx - radius, cy - radius, cx + radius, cy + radius,
                             outline="#00a6d6", width=max(2, int(square_size / 18)))
            self.create_line(cx - radius, cy, cx + radius, cy, fill="#00a6d6", width=2)
            self.create_line(cx, cy - radius, cx, cy + radius, fill="#00a6d6", width=2)


class MechanismCanvas(tk.Canvas):
    def __init__(self, master):
        super().__init__(master, height=95, background="#f7f9fb", highlightthickness=0)
        self.telemetry = None
        self.bind("<Configure>", lambda _event: self.redraw())

    def set_telemetry(self, telemetry) -> None:
        self.telemetry = telemetry
        self.redraw()

    def redraw(self) -> None:
        self.delete("all")
        width, height = max(self.winfo_width(), 260), max(self.winfo_height(), 120)
        margin = 18
        self.create_rectangle(margin, margin, width - margin, height - margin,
                              fill="#eef1f4", outline="#6d7782", width=2)
        for index in range(1, 8):
            x = margin + index * (width - 2 * margin) / 8
            y = margin + index * (height - 2 * margin) / 8
            self.create_line(x, margin, x, height - margin, fill="#d6dce2")
            self.create_line(margin, y, width - margin, y, fill="#d6dce2")
        if not self.telemetry:
            self.create_text(width / 2, height / 2, text="No mechanism telemetry yet",
                             fill=COLORS["muted"], font=("Segoe UI", 10))
            return
        x = margin + (self.telemetry.trolley_x - 0.5) * (width - 2 * margin) / 8
        y = height - margin - (self.telemetry.trolley_y - 0.5) * (height - 2 * margin) / 8
        color = COLORS["bad"] if self.telemetry.motion_fault else "#00a6d6"
        self.create_line(x - 14, y, x + 14, y, fill=color, width=3)
        self.create_line(x, y - 14, x, y + 14, fill=color, width=3)
        self.create_oval(x - 8, y - 8, x + 8, y + 8,
                         fill="#8b46c5" if self.telemetry.magnet_on else "#ffffff",
                         outline=color, width=2)


class AutomaticChessboardApp:
    def __init__(self, root: tk.Tk) -> None:
        self.root = root
        self.root.title(f"Open Automatic Chessboard — Monitor {APP_VERSION}")
        self.root.geometry("1220x790")
        self.root.minsize(1040, 680)
        self.events: queue.Queue[tuple[str, object]] = queue.Queue()
        self.transport = None
        self.engine: chess.engine.SimpleEngine | None = None
        self.camera: CameraWorker | None = None
        self.latest_camera_image = None
        self.camera_photo = None
        self.board = chess.Board()
        self.human_color = chess.WHITE
        self.pending_engine_move: chess.Move | None = None
        self.route_snapshot_pending = False
        self.route_planning = False
        self.active_route_plan: MotionPlan | None = None
        self.route_commands: deque[str] = deque()
        self.route_waiting_for = ""
        self.route_current_command = ""
        self.route_deadline = 0.0
        self.route_firmware_open = False
        self.route_motion_command_sent = False
        self.route_expected_occupancy: frozenset[int] = frozenset()
        self.route_generation = 0
        self.awaiting_promotion_confirmation = False
        self.engine_thinking = False
        self.session_active = False
        self.motion_expected = False
        self.safe_request_queue: deque[str] = deque()
        self.safe_request_pending: tuple[str, str, float] | None = None
        self.response_counts: dict[str, int] = {}
        self.diagnostic_batch: tuple[dict[str, int], float] | None = None
        self.last_poll_monotonic = 0.0
        self.poll_board_next = False
        self.model = MonitorModel(expected_squares=expected_occupancy(self.board))
        self.settings = self._load_settings()
        self.recorder = EventRecorder()
        self.diag_results: dict[str, tuple[str, str]] = {}
        self.manual_selection = ManualSelection()
        self.manual_calibration_verified = False
        self.manual_pending = ""
        self.manual_pending_selection: ManualSelection | None = None
        self.calibration_reported_square: str | None = None

        self._configure_style()
        self._build_menu()
        self._build_ui()
        self._refresh_ports()
        self._render()
        self._refresh_visual_state()
        self.root.after(50, self._poll_events)
        self.root.after(1000, self._monitor_tick)
        self.root.protocol("WM_DELETE_WINDOW", self._on_close)

    def _configure_style(self) -> None:
        style = ttk.Style(self.root)
        try:
            style.theme_use("clam")
        except tk.TclError:
            pass
        style.configure("Title.TLabel", font=("Segoe UI", 16, "bold"))
        style.configure("Heading.TLabel", font=("Segoe UI", 11, "bold"))
        style.configure("CardValue.TLabel", font=("Segoe UI", 11, "bold"))
        style.configure("Quiet.TLabel", foreground=COLORS["muted"])
        style.configure("Danger.TButton", foreground="#9f1020")

    def _build_menu(self) -> None:
        menu = tk.Menu(self.root)
        file_menu = tk.Menu(menu, tearoff=False)
        file_menu.add_command(label="Create support bundle...", command=self._create_support_bundle)
        file_menu.add_separator()
        file_menu.add_command(label="Exit", command=self._on_close)
        menu.add_cascade(label="File", menu=file_menu)
        help_menu = tk.Menu(menu, tearoff=False)
        help_menu.add_command(label="Remote safety", command=self._show_remote_safety)
        help_menu.add_command(label="About", command=self._show_about)
        menu.add_cascade(label="Help", menu=help_menu)
        self.root.configure(menu=menu)

    def _build_ui(self) -> None:
        top = ttk.Frame(self.root, padding=(12, 9))
        top.pack(fill="x")
        ttk.Label(top, text="Open Automatic Chessboard", style="Title.TLabel").pack(side="left")

        self.connection_badge = tk.Label(top, text="DISCONNECTED", bg=COLORS["bad"], fg="white",
                                         padx=10, pady=5, font=("Segoe UI", 9, "bold"))
        self.connection_badge.pack(side="left", padx=14)
        self.last_seen_label = ttk.Label(top, text="No data received", style="Quiet.TLabel")
        self.last_seen_label.pack(side="left")
        tk.Button(top, text="EMERGENCY HALT", command=self._emergency_halt,
                  bg="#c72032", fg="white", activebackground="#981625",
                  activeforeground="white", font=("Segoe UI", 10, "bold"),
                  padx=14, pady=5, relief="raised").pack(side="right")

        connection = ttk.LabelFrame(self.root, text="Connection", padding=8)
        connection.pack(fill="x", padx=12, pady=(0, 8))
        self.transport_kind = tk.StringVar(value=self.settings.get("transport", "BLE"))
        self.transport_box = ttk.Combobox(connection, textvariable=self.transport_kind,
                                          values=("BLE", "USB", "Simulator"), state="readonly", width=10)
        self.transport_box.grid(row=0, column=0, padx=(0, 6))
        self.transport_box.bind("<<ComboboxSelected>>", lambda _event: self._update_connection_fields())
        ttk.Label(connection, text="USB port").grid(row=0, column=1, padx=(8, 3))
        self.port = tk.StringVar(value=self.settings.get("port", "COM7"))
        self.port_box = ttk.Combobox(connection, textvariable=self.port, width=10)
        self.port_box.grid(row=0, column=2)
        ttk.Button(connection, text="Refresh", command=self._refresh_ports).grid(row=0, column=3, padx=4)
        ttk.Label(connection, text="BLE device").grid(row=0, column=4, padx=(12, 3))
        self.ble_name = tk.StringVar(value=self.settings.get("ble_name", "HC-08"))
        self.ble_box = ttk.Combobox(connection, textvariable=self.ble_name, width=29)
        self.ble_box.grid(row=0, column=5, sticky="ew")
        ttk.Button(connection, text="Scan", command=self._scan_ble).grid(row=0, column=6, padx=4)
        self.connect_button = ttk.Button(connection, text="Connect", command=self._toggle_connection)
        self.connect_button.grid(row=0, column=7, padx=(10, 0))
        self.auto_reconnect = tk.BooleanVar(value=bool(self.settings.get("auto_reconnect", True)))
        ttk.Checkbutton(connection, text="Reconnect automatically",
                        variable=self.auto_reconnect).grid(row=0, column=8, padx=(10, 0))
        connection.columnconfigure(5, weight=1)
        self._update_connection_fields()

        self.notebook = ttk.Notebook(self.root)
        self.notebook.pack(fill="both", expand=True, padx=12, pady=(0, 12))
        self.monitor_tab = ttk.Frame(self.notebook, padding=8)
        self.play_tab = ttk.Frame(self.notebook, padding=10)
        self.manual_tab = ttk.Frame(self.notebook, padding=8)
        self.diagnostics_tab = ttk.Frame(self.notebook, padding=10)
        self.camera_tab = ttk.Frame(self.notebook, padding=10)
        self.developer_tab = ttk.Frame(self.notebook, padding=10)
        self.notebook.add(self.monitor_tab, text="Monitor")
        self.notebook.add(self.play_tab, text="Play")
        self.notebook.add(self.manual_tab, text="Move head / piece")
        self.notebook.add(self.diagnostics_tab, text="Diagnostics")
        self.notebook.add(self.camera_tab, text="Camera")
        self.notebook.add(self.developer_tab, text="Developer")

        self._build_monitor_tab()
        self._build_play_tab()
        self._build_manual_tab()
        self._build_diagnostics_tab()
        self._build_camera_tab()
        self._build_developer_tab()

    def _build_monitor_tab(self) -> None:
        self.monitor_tab.columnconfigure(0, weight=3)
        self.monitor_tab.columnconfigure(1, weight=2)
        self.monitor_tab.rowconfigure(0, weight=1)
        left = ttk.Frame(self.monitor_tab)
        left.grid(row=0, column=0, sticky="nsew", padx=(0, 10))
        left.rowconfigure(0, weight=1)
        left.columnconfigure(0, weight=1)
        self.board_canvas = ChessboardCanvas(left)
        self.board_canvas.grid(row=0, column=0, sticky="nsew")
        legend = ttk.Frame(left, padding=(4, 6))
        legend.grid(row=1, column=0, sticky="ew")
        ttk.Label(legend, text="●", foreground="#2ca44f").pack(side="left")
        ttk.Label(legend, text=" sensor agrees   ").pack(side="left")
        ttk.Label(legend, text="●", foreground="#ff9800").pack(side="left")
        ttk.Label(legend, text=" unexpected piece   ").pack(side="left")
        ttk.Label(legend, text="□", foreground="#d1242f").pack(side="left")
        ttk.Label(legend, text=" expected piece missing   ").pack(side="left")
        ttk.Label(legend, text="◎", foreground="#00a6d6").pack(side="left")
        ttk.Label(legend, text=" carriage").pack(side="left")

        right = ttk.Frame(self.monitor_tab)
        right.grid(row=0, column=1, sticky="nsew")
        right.columnconfigure(0, weight=1)
        self.health_banner = tk.Label(right, text="Disconnected", bg=COLORS["bad"], fg="white",
                                      anchor="w", padx=12, pady=9, font=("Segoe UI", 13, "bold"))
        self.health_banner.grid(row=0, column=0, sticky="ew")
        self.guidance = tk.StringVar(value="Connect to read the board.")
        ttk.Label(right, textvariable=self.guidance, wraplength=430, padding=(4, 8)).grid(
            row=1, column=0, sticky="ew")

        cards = ttk.LabelFrame(right, text="Live state", padding=8)
        cards.grid(row=3, column=0, sticky="ew", pady=(0, 8))
        cards.columnconfigure(1, weight=1)
        self.state_values: dict[str, tk.StringVar] = {}
        card_rows = (
            ("Controller", "controller"), ("Firmware", "firmware"),
            ("Carriage", "carriage"), ("Magnet", "magnet"),
            ("Button / limit A", "limit_a"), ("Button / limit B", "limit_b"),
            ("Sensors", "sensors"), ("Free Nano RAM", "ram"),
            ("Uptime", "uptime"),
        )
        for row, (label, key) in enumerate(card_rows):
            ttk.Label(cards, text=label).grid(row=row, column=0, sticky="w", padx=(0, 10), pady=2)
            variable = tk.StringVar(value="Unknown")
            self.state_values[key] = variable
            ttk.Label(cards, textvariable=variable, style="CardValue.TLabel").grid(
                row=row, column=1, sticky="e", pady=2)

        mechanism = ttk.LabelFrame(right, text="Mechanism position", padding=4)
        mechanism.grid(row=4, column=0, sticky="ew", pady=(0, 8))
        self.mechanism_canvas = MechanismCanvas(mechanism)
        self.mechanism_canvas.pack(fill="x", expand=True)

        controls = ttk.Frame(right)
        controls.grid(row=5, column=0, sticky="ew")
        ttk.Button(controls, text="Refresh safely", command=self._safe_refresh).pack(side="left")
        self.auto_monitor = tk.BooleanVar(value=bool(self.settings.get("auto_monitor", True)))
        ttk.Checkbutton(controls, text="Live monitoring", variable=self.auto_monitor).pack(
            side="left", padx=10)
        self.poll_seconds = tk.DoubleVar(value=float(self.settings.get("poll_seconds", 2.0)))
        ttk.Label(controls, text="every").pack(side="left")
        ttk.Spinbox(controls, from_=1.0, to=10.0, increment=0.5,
                    textvariable=self.poll_seconds, width=5).pack(side="left", padx=3)
        ttk.Label(controls, text="seconds").pack(side="left")
        ttk.Label(
            right,
            text="Remote halt is best-effort over radio. Keep a local physical power cutoff available.",
            foreground=COLORS["bad"], wraplength=430,
        ).grid(row=2, column=0, sticky="ew", pady=(0, 8))

    def _build_play_tab(self) -> None:
        self.play_tab.columnconfigure(0, weight=1)
        self.play_tab.columnconfigure(1, weight=1)
        engine_frame = ttk.LabelFrame(self.play_tab, text="Stockfish", padding=10)
        engine_frame.grid(row=0, column=0, sticky="new", padx=(0, 8))
        engine_frame.columnconfigure(1, weight=1)
        ttk.Label(engine_frame, text="Executable").grid(row=0, column=0, sticky="w")
        self.engine_path = tk.StringVar(value=self.settings.get("engine", str(DEFAULT_STOCKFISH)))
        ttk.Entry(engine_frame, textvariable=self.engine_path).grid(row=0, column=1, sticky="ew", padx=5)
        ttk.Button(engine_frame, text="Browse...", command=self._choose_engine).grid(row=0, column=2)
        ttk.Label(engine_frame, text="Strength (Elo)").grid(row=1, column=0, sticky="w", pady=(8, 0))
        self.elo = tk.IntVar(value=int(self.settings.get("elo", 2000)))
        ttk.Spinbox(engine_frame, from_=1320, to=3190, increment=50,
                    textvariable=self.elo, width=9).grid(row=1, column=1, sticky="w", pady=(8, 0))
        ttk.Label(engine_frame, text="Think time (seconds)").grid(row=2, column=0, sticky="w", pady=(8, 0))
        self.think_seconds = tk.DoubleVar(value=float(self.settings.get("think_seconds", 0.8)))
        ttk.Spinbox(engine_frame, from_=0.1, to=300.0, increment=0.1,
                    textvariable=self.think_seconds, width=9).grid(row=2, column=1, sticky="w", pady=(8, 0))
        ttk.Label(engine_frame, text="Route search limit (seconds)").grid(
            row=3, column=0, sticky="w", pady=(8, 0))
        self.route_seconds = tk.DoubleVar(value=float(self.settings.get("route_seconds", 8.0)))
        ttk.Spinbox(engine_frame, from_=0.5, to=120.0, increment=0.5,
                    textvariable=self.route_seconds, width=9).grid(
                        row=3, column=1, sticky="w", pady=(8, 0))
        ttk.Label(engine_frame, text="Maximum temporary pieces").grid(
            row=4, column=0, sticky="w", pady=(8, 0))
        self.route_temporary_pieces = tk.IntVar(
            value=int(self.settings.get("route_temporary_pieces", 10)))
        ttk.Spinbox(engine_frame, from_=0, to=30, increment=1,
                    textvariable=self.route_temporary_pieces, width=9).grid(
                        row=4, column=1, sticky="w", pady=(8, 0))
        ttk.Label(
            engine_frame,
            text=("Stockfish and collision-safe rearrangement planning run on Windows. "
                  "Firmware 4.1 executes one sensor-verified drag transaction at a time."),
            wraplength=470, style="Quiet.TLabel",
        ).grid(row=5, column=0, columnspan=3, sticky="w", pady=(8, 0))

        game = ttk.LabelFrame(self.play_tab, text="Game controls", padding=10)
        game.grid(row=0, column=1, sticky="new")
        self.human_side = tk.StringVar(value=self.settings.get("human_side", "White"))
        ttk.Radiobutton(game, text="Human plays White", variable=self.human_side,
                        value="White").grid(row=0, column=0, sticky="w")
        ttk.Radiobutton(game, text="Human plays Black", variable=self.human_side,
                        value="Black").grid(row=0, column=1, sticky="w")
        ttk.Button(game, text="Start game and calibrate", command=self._start_game).grid(
            row=1, column=0, columnspan=2, sticky="ew", pady=(10, 4))
        ttk.Button(game, text="Stop game session", command=lambda: self._send("STOP")).grid(
            row=2, column=0, sticky="ew", padx=(0, 3))
        ttk.Button(game, text="Save PGN...", command=self._save_pgn).grid(
            row=2, column=1, sticky="ew", padx=(3, 0))
        self.game_status = tk.StringVar(value="No game in progress")
        ttk.Label(game, textvariable=self.game_status, wraplength=470,
                  style="Heading.TLabel").grid(row=3, column=0, columnspan=2, sticky="ew", pady=(12, 0))

        history = ttk.LabelFrame(self.play_tab, text="Move history", padding=8)
        history.grid(row=1, column=0, columnspan=2, sticky="nsew", pady=(10, 0))
        self.play_tab.rowconfigure(1, weight=1)
        self.move_history = tk.Text(history, height=15, state="disabled", font=("Consolas", 10))
        self.move_history.pack(fill="both", expand=True)

    def _build_manual_tab(self) -> None:
        self.manual_tab.columnconfigure(0, weight=3)
        self.manual_tab.columnconfigure(1, weight=2)
        self.manual_tab.rowconfigure(0, weight=1)

        self.manual_board = ChessboardCanvas(self.manual_tab)
        self.manual_board.grid(row=0, column=0, sticky="nsew", padx=(0, 10))
        self.manual_board.set_interaction(frozenset(), self._manual_square_clicked)

        controls = ttk.Frame(self.manual_tab, padding=8)
        controls.grid(row=0, column=1, sticky="nsew")
        controls.columnconfigure(0, weight=1)
        controls.rowconfigure(5, weight=1)
        ttk.Label(controls, text="Direct board control", style="Heading.TLabel").grid(
            row=0, column=0, sticky="w"
        )
        ttk.Label(
            controls,
            text=("Calibrate here before using manual movement. Head only never energizes the magnet. "
                  "Move piece requires an occupied source and empty destination."),
            wraplength=430,
        ).grid(row=1, column=0, sticky="ew", pady=(5, 10))

        self.manual_mode = tk.StringVar(value="head")
        modes = ttk.LabelFrame(controls, text="Operation", padding=8)
        modes.grid(row=2, column=0, sticky="ew")
        ttk.Radiobutton(modes, text="Head only (magnet off)", variable=self.manual_mode,
                        value="head", command=self._manual_mode_changed).pack(anchor="w")
        ttk.Radiobutton(modes, text="Move a sensed piece", variable=self.manual_mode,
                        value="piece", command=self._manual_mode_changed).pack(anchor="w")

        self.manual_status = tk.StringVar(value="Calibrate from this page before moving the head.")
        self.manual_selection_text = tk.StringVar(value="Tap a target square")
        ttk.Label(controls, textvariable=self.manual_status, wraplength=430,
                  style="Heading.TLabel").grid(row=3, column=0, sticky="ew", pady=(12, 5))
        ttk.Label(controls, textvariable=self.manual_selection_text, wraplength=430,
                  style="Quiet.TLabel").grid(row=4, column=0, sticky="new")

        actions = ttk.Frame(controls)
        actions.grid(row=6, column=0, sticky="ew", pady=(12, 0))
        for column in range(3):
            actions.columnconfigure(column, weight=1)
        ttk.Button(actions, text="Calibrate", command=self._manual_calibrate).grid(
            row=0, column=0, sticky="ew", padx=(0, 3)
        )
        ttk.Button(actions, text="Clear selection", command=self._manual_clear).grid(
            row=0, column=1, sticky="ew", padx=3
        )
        ttk.Button(actions, text="Move", command=self._manual_move).grid(
            row=0, column=2, sticky="ew", padx=(3, 0)
        )

    def _build_diagnostics_tab(self) -> None:
        self.diagnostics_tab.columnconfigure(0, weight=1)
        self.diagnostics_tab.rowconfigure(1, weight=1)
        intro = ttk.Frame(self.diagnostics_tab)
        intro.grid(row=0, column=0, sticky="ew", pady=(0, 8))
        ttk.Label(intro, text="Guided safe diagnostics", style="Heading.TLabel").pack(anchor="w")
        ttk.Label(
            intro,
            text="These checks read state only. They never calibrate, energize the magnet, or move the carriage.",
            wraplength=900,
        ).pack(anchor="w")
        self.diag_tree = ttk.Treeview(self.diagnostics_tab,
                                      columns=("result", "details"), show="tree headings", height=10)
        self.diag_tree.heading("#0", text="Check")
        self.diag_tree.heading("result", text="Result")
        self.diag_tree.heading("details", text="What it means")
        self.diag_tree.column("#0", width=220, stretch=False)
        self.diag_tree.column("result", width=110, stretch=False)
        self.diag_tree.column("details", width=650)
        self.diag_tree.grid(row=1, column=0, sticky="nsew")
        self.diag_tree.tag_configure("pass", foreground=COLORS["good"])
        self.diag_tree.tag_configure("warn", foreground=COLORS["warn"])
        self.diag_tree.tag_configure("fail", foreground=COLORS["bad"])
        checks = (
            ("connection", "Board connection"), ("firmware", "Firmware identity"),
            ("telemetry", "Live telemetry"), ("sensors", "64-square sensors"),
            ("controls", "Buttons / limit inputs"), ("engine", "Stockfish engine"),
            ("camera", "Optional camera"),
        )
        for key, label in checks:
            self.diag_tree.insert("", "end", iid=key, text=label,
                                  values=("Not run", ""), tags=("warn",))
        actions = ttk.Frame(self.diagnostics_tab, padding=(0, 10))
        actions.grid(row=2, column=0, sticky="ew")
        ttk.Button(actions, text="Run safe diagnostics", command=self._run_diagnostics).pack(side="left")
        ttk.Button(actions, text="Create support bundle...",
                   command=self._create_support_bundle).pack(side="left", padx=8)
        ttk.Button(actions, text="Copy summary", command=self._copy_diagnostic_summary).pack(side="left")

    def _build_camera_tab(self) -> None:
        self.camera_tab.columnconfigure(0, weight=1)
        self.camera_tab.rowconfigure(1, weight=1)
        controls = ttk.Frame(self.camera_tab)
        controls.grid(row=0, column=0, sticky="ew", pady=(0, 8))
        ttk.Label(controls, text="Camera source").pack(side="left")
        self.camera_source = tk.StringVar(value=self.settings.get("camera_source", "0"))
        ttk.Entry(controls, textvariable=self.camera_source, width=55).pack(side="left", padx=6, fill="x", expand=True)
        ttk.Button(controls, text="Start", command=self._start_camera).pack(side="left")
        ttk.Button(controls, text="Stop", command=self._stop_camera).pack(side="left", padx=4)
        ttk.Button(controls, text="Save snapshot...", command=self._save_camera_snapshot).pack(side="left")
        self.camera_view = ttk.Label(self.camera_tab, text="Camera is off", anchor="center",
                                     background="#15181c", foreground="white")
        self.camera_view.grid(row=1, column=0, sticky="nsew")
        self.camera_status = tk.StringVar(
            value="Use 0 for the first USB webcam, or an RTSP/HTTP URL. Video stays local and is not logged."
        )
        ttk.Label(self.camera_tab, textvariable=self.camera_status, wraplength=1000).grid(
            row=2, column=0, sticky="ew", pady=(8, 0))

    def _build_developer_tab(self) -> None:
        self.developer_tab.columnconfigure(0, weight=1)
        self.developer_tab.rowconfigure(0, weight=1)
        pane = ttk.Panedwindow(self.developer_tab, orient="vertical")
        pane.grid(row=0, column=0, sticky="nsew")
        timeline_frame = ttk.LabelFrame(pane, text="Structured event timeline", padding=5)
        raw_frame = ttk.LabelFrame(pane, text="Raw protocol", padding=5)
        pane.add(timeline_frame, weight=2)
        pane.add(raw_frame, weight=1)
        self.event_tree = ttk.Treeview(timeline_frame, columns=("time", "direction", "event", "detail"),
                                       show="headings", height=12)
        for key, title, width in (("time", "Time", 90), ("direction", "Direction", 80),
                                  ("event", "Event", 150), ("detail", "Details", 650)):
            self.event_tree.heading(key, text=title)
            self.event_tree.column(key, width=width, stretch=key == "detail")
        self.event_tree.pack(fill="both", expand=True)
        self.raw_log = tk.Text(raw_frame, height=8, state="disabled", font=("Consolas", 9))
        self.raw_log.pack(fill="both", expand=True)

        command_frame = ttk.LabelFrame(self.developer_tab, text="Command laboratory", padding=8)
        command_frame.grid(row=1, column=0, sticky="ew", pady=(8, 0))
        command_frame.columnconfigure(1, weight=1)
        ttk.Label(command_frame, text="Command").grid(row=0, column=0, sticky="w")
        self.developer_command = tk.StringVar(value="TELEM")
        entry = ttk.Entry(command_frame, textvariable=self.developer_command)
        entry.grid(row=0, column=1, sticky="ew", padx=6)
        entry.bind("<KeyRelease>", lambda _event: self._update_command_risk())
        entry.bind("<Return>", lambda _event: self._send_developer_command())
        ttk.Button(command_frame, text="Send", command=self._send_developer_command).grid(row=0, column=2)
        self.command_risk = tk.StringVar()
        ttk.Label(command_frame, textvariable=self.command_risk).grid(row=1, column=1, sticky="w")
        self.unlock_motion = tk.BooleanVar(value=False)
        ttk.Checkbutton(command_frame, text="Unlock commands that can move hardware",
                        variable=self.unlock_motion).grid(row=2, column=1, sticky="w", pady=(5, 0))
        ttk.Label(command_frame,
                  text="Simulator helper: SIMMOVE e2e4 reports a physical human move without hardware.",
                  style="Quiet.TLabel").grid(row=3, column=1, sticky="w", pady=(5, 0))
        self._update_command_risk()

    def _load_settings(self) -> dict:
        for path in (SETTINGS_PATH, LEGACY_SETTINGS_PATH):
            try:
                return json.loads(path.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                continue
        return {}

    def _settings_dict(self) -> dict:
        source = self.camera_source.get() if hasattr(self, "camera_source") else self.settings.get("camera_source", "0")
        if "://" in source and "@" in source:
            source = ""
        return {
            "transport": self.transport_kind.get(), "port": self.port.get(),
            "ble_name": self.ble_name.get(), "auto_reconnect": self.auto_reconnect.get(),
            "auto_monitor": self.auto_monitor.get(), "poll_seconds": self.poll_seconds.get(),
            "engine": self.engine_path.get(), "elo": self.elo.get(),
            "think_seconds": self.think_seconds.get(), "human_side": self.human_side.get(),
            "route_seconds": self.route_seconds.get(),
            "route_temporary_pieces": self.route_temporary_pieces.get(),
            "camera_source": source,
        }

    def _save_settings(self) -> None:
        write_json_atomic(SETTINGS_PATH, self._settings_dict())

    def _update_connection_fields(self) -> None:
        if not hasattr(self, "transport_kind"):
            return
        kind = self.transport_kind.get()
        self.port_box.configure(state="normal" if kind == "USB" else "disabled")
        self.ble_box.configure(state="normal" if kind == "BLE" else "disabled")

    def _refresh_ports(self) -> None:
        try:
            ports = serial_ports()
            self.port_box["values"] = ports
            if ports and self.port.get() not in ports:
                self.port.set(ports[0])
            elif not ports:
                self.port.set("")
        except Exception as error:
            self._append_log("system", "Port scan failed", str(error))

    def _scan_ble(self) -> None:
        self._set_connection_text("Scanning for Bluetooth boards...")

        def worker() -> None:
            try:
                self.events.put(("ble_devices", discover_ble_devices()))
            except Exception as error:
                self.events.put(("transport_status", f"BLE scan failed: {error}"))

        threading.Thread(target=worker, daemon=True).start()

    def _toggle_connection(self) -> None:
        if self.transport:
            self.transport.close()
            self.transport = None
            self.model.connected = False
            self.connect_button.configure(text="Connect")
            self._refresh_visual_state()
            return
        try:
            kind = self.transport_kind.get()
            if kind == "USB":
                transport = UsbSerialTransport(self.port.get(), self._queue_line,
                                               self._queue_status, reconnect=self.auto_reconnect.get())
            elif kind == "BLE":
                identifier = self.ble_name.get().strip()
                if "[" in identifier and identifier.endswith("]"):
                    identifier = identifier.rsplit("[", 1)[1][:-1]
                transport = Hc08BleTransport(identifier, self._queue_line,
                                             self._queue_status, reconnect=self.auto_reconnect.get())
            else:
                transport = SimulatorTransport(self._queue_line, self._queue_status)
            self.transport = transport
            transport.start()
            self.connect_button.configure(text="Disconnect")
            self._save_settings()
        except Exception as error:
            self.transport = None
            messagebox.showerror("Connection failed", str(error), parent=self.root)

    def _queue_line(self, line: str) -> None:
        self.events.put(("line", line))

    def _queue_status(self, status: str) -> None:
        self.events.put(("transport_status", status))

    def _send(self, line: str, quiet: bool = False) -> bool:
        if not self.transport:
            if not quiet:
                messagebox.showwarning("Not connected", "Connect to the board first.", parent=self.root)
            return False
        try:
            self.transport.send(line)
            self.recorder.record("protocol_tx", line)
            self._append_log("TX", line.split(maxsplit=1)[0], line)
            return True
        except Exception as error:
            self.recorder.record("error", "send_failed", command=line, error=str(error))
            self._append_log("error", "Send failed", str(error))
            if not quiet:
                messagebox.showerror("Send failed", str(error), parent=self.root)
            return False

    def _safe_refresh(self) -> None:
        if not self.transport or not self.transport.is_connected:
            messagebox.showwarning("Not connected", "Connect to the board before refreshing.", parent=self.root)
            return
        self._queue_safe_requests("INFO", "TELEM", "BOARD")

    @staticmethod
    def _expected_response(command: str) -> str:
        verb = command.split(maxsplit=1)[0].upper()
        if verb in ("PING", "HELLO"):
            return "PONG"
        if verb == "BTTEST":
            return "BT"
        return verb

    def _queue_safe_requests(self, *commands: str) -> None:
        """Serialize read-only protocol traffic across every feature."""
        pending_command = self.safe_request_pending[1] if self.safe_request_pending else None
        for command in commands:
            value = command.strip()
            if not value or classify_command(value) != CommandRisk.READ_ONLY:
                continue
            if value != pending_command and value not in self.safe_request_queue:
                self.safe_request_queue.append(value)
        self._dispatch_safe_request()

    def _dispatch_safe_request(self) -> None:
        if (self.safe_request_pending or not self.safe_request_queue or self.motion_expected or
                not self.transport or not self.transport.is_connected):
            return
        command = self.safe_request_queue.popleft()
        expected = self._expected_response(command)
        if self._send(command, quiet=True):
            self.safe_request_pending = (expected, command, time.monotonic())

    def _complete_safe_request(self, response_kind: str) -> bool:
        if not self.safe_request_pending or response_kind != self.safe_request_pending[0]:
            return False
        self.safe_request_pending = None
        return True

    def _monitor_tick(self) -> None:
        try:
            connected = bool(self.transport and self.transport.is_connected)
            self.model.connected = connected
            now = time.monotonic()
            if ((self.active_route_plan is not None or self.route_snapshot_pending) and
                    self.route_waiting_for and
                    self.route_deadline and now >= self.route_deadline):
                command = self.route_current_command.split(maxsplit=1)[0] or "route"
                self._route_plan_failed(
                    PlanningError(f"Timed out waiting for {command} acknowledgement")
                )
            if self.safe_request_pending and now - self.safe_request_pending[2] > 4.0:
                expected, command, _started = self.safe_request_pending
                self.recorder.record("monitor", "request_timeout",
                                     command=command, expected=expected)
                self.safe_request_pending = None
            self._dispatch_safe_request()
            if (connected and self.auto_monitor.get() and not self.motion_expected and
                    not self.safe_request_pending and not self.safe_request_queue):
                interval = max(1.0, float(self.poll_seconds.get()))
                if now - self.last_poll_monotonic >= interval:
                    command = "BOARD" if self.poll_board_next else "TELEM"
                    self.poll_board_next = not self.poll_board_next
                    self.last_poll_monotonic = now
                    self._queue_safe_requests(command)
            self._refresh_visual_state()
        except (tk.TclError, ValueError):
            pass
        self.root.after(1000, self._monitor_tick)

    def _poll_events(self) -> None:
        try:
            while True:
                kind, payload = self.events.get_nowait()
                if kind == "line":
                    self._handle_line(str(payload))
                elif kind == "transport_status":
                    self._handle_transport_status(str(payload))
                elif kind == "engine_move":
                    self._send_engine_move(str(payload))
                elif kind == "engine_error":
                    self.engine_thinking = False
                    self.game_status.set(f"Engine error: {payload}")
                    self._append_log("error", "Stockfish", str(payload))
                elif kind == "route_plan_ready":
                    self._begin_route_execution(payload)
                elif kind == "route_plan_error":
                    self._route_plan_failed(payload)
                elif kind == "engine_test":
                    ok, detail = payload
                    self._set_diag("engine", "Pass" if ok else "Fail", detail,
                                   "pass" if ok else "fail")
                elif kind == "ble_devices":
                    rows = list(payload)
                    values = [f"{name} [{address}]" for name, address, _rssi in rows]
                    self.ble_box["values"] = values
                    if rows:
                        name, address, rssi = rows[0]
                        self.ble_name.set(f"{name} [{address}]")
                        self._set_connection_text(
                            f"Found {len(rows)} devices; best board match is {name} ({rssi} dBm)."
                        )
                    else:
                        self._set_connection_text("No Bluetooth devices found.")
                elif kind == "camera_frame":
                    self.latest_camera_image = payload
                    self._render_camera_frame()
                elif kind == "camera_status":
                    self.camera_status.set(str(payload))
                    self._append_log("camera", "Camera", str(payload))
        except queue.Empty:
            pass
        self.root.after(50, self._poll_events)

    def _handle_transport_status(self, status: str) -> None:
        self.model.connection_text = status
        lower = status.lower()
        if "connected" in lower and "disconnected" not in lower:
            self.model.connected = True
            if not self.manual_calibration_verified:
                self.manual_status.set("Connected; calibrate from this page before moving.")
            self._queue_safe_requests("PING", "INFO", "TELEM", "BOARD")
        elif any(word in lower for word in ("disconnected", "interrupted", "stopped", "reconnecting")):
            self.model.connected = False
            self.safe_request_queue.clear()
            self.safe_request_pending = None
            self.manual_calibration_verified = False
            self.manual_pending = ""
            self.manual_pending_selection = None
            self.model.telemetry_updated = None
            self.manual_selection = ManualSelection(self.manual_selection.mode)
            self.manual_status.set("Connection lost; calibrate again after reconnecting.")
            self._reset_route_orchestration(clear_pending=True)
        self.recorder.record("transport", status)
        self._append_log("transport", "Connection", status)
        self._refresh_visual_state()

    def _handle_line(self, line: str) -> None:
        self.model.mark_seen()
        self.recorder.record("protocol_rx", line)
        event = parse_event(line)
        self.response_counts[event.kind] = self.response_counts.get(event.kind, 0) + 1
        self._append_log("RX", event.kind, " ".join(event.args))
        self._complete_safe_request(event.kind)
        if event.kind == "INFO":
            try:
                self.model.firmware = parse_info(event)
            except ValueError as error:
                self.model.last_error = str(error)
        elif event.kind == "TELEM":
            try:
                self.model.telemetry = parse_telemetry(event)
                self.model.telemetry_updated = time.monotonic()
                self.motion_expected = (
                    self.model.telemetry.sequence in (3, 8, 19, 21, 22)
                    or self.route_snapshot_pending or self.route_planning
                    or self.active_route_plan is not None
                )
            except ValueError as error:
                self.model.last_error = str(error)
            else:
                self._verify_manual_telemetry()
        elif event.kind == "BOARD" and event.args:
            try:
                self.model.sensor_hex = event.args[0]
                self.model.sensor_squares = parse_board_hex(event.args[0])
                self.model.sensor_updated = time.monotonic()
            except ValueError as error:
                self.model.last_error = str(error)
                if self.active_route_plan is not None and self.route_waiting_for == "BOARD":
                    self._route_plan_failed(error)
            else:
                if self.active_route_plan is not None and self.route_waiting_for == "BOARD":
                    if self.model.sensor_squares != self.route_expected_occupancy:
                        missing = self.route_expected_occupancy - self.model.sensor_squares
                        unexpected = self.model.sensor_squares - self.route_expected_occupancy
                        detail = (
                            f"routed sensor proof failed (missing "
                            f"{', '.join(map(square_name, sorted(missing))) or 'none'}; extra "
                            f"{', '.join(map(square_name, sorted(unexpected))) or 'none'})"
                        )
                        self._route_plan_failed(PlanningError(detail))
                    else:
                        self.route_deadline = 0.0
                        self._advance_route_execution()
                else:
                    self._verify_manual_sensors()
                    self._maybe_start_route_planning()
        elif event.kind in ("READY", "PONG"):
            self._set_connection_text("Board connected and responding")
        elif event.kind == "SETUP":
            self.motion_expected = False
            self.game_status.set("Set all starting pieces, then press physical Button A.")
        elif event.kind == "SESSION":
            self.game_status.set("Remote game started")
        elif event.kind == "TURN" and event.args:
            if event.args[0] == "COMPUTER" and self.board.turn != self.human_color:
                self._start_engine_think()
            elif event.args[0] == "HUMAN":
                self.game_status.set("Your move. Press Button A on the board when complete.")
        elif event.kind == "MOVE" and event.args:
            self._accept_human_move(event.args[0])
        elif event.kind == "CALIBRATING":
            self.motion_expected = True
            self.manual_status.set("Calibrating; keep the mechanism clear.")
        elif event.kind == "CALIBRATED":
            self.motion_expected = False
            if self.manual_pending == "calibration":
                self.calibration_reported_square = event.args[0] if event.args else None
                self.manual_status.set(
                    f"Calibration ended at {self.calibration_reported_square or 'unknown'}; checking fresh telemetry."
                )
                self._queue_safe_requests("TELEM", "BOARD")
        elif event.kind == "PLAN" and event.args:
            if self.active_route_plan is not None and self.route_waiting_for == "PLAN":
                if event.args[0] == "READY":
                    self.route_deadline = 0.0
                    self.route_firmware_open = True
                    captured = self.active_route_plan.problem.captured_square
                    if captured is not None:
                        if captured not in self.route_expected_occupancy:
                            self._route_plan_failed(
                                PlanningError("Capture square was absent from the planned start frame")
                            )
                            return
                        self.route_expected_occupancy = frozenset(
                            square for square in self.route_expected_occupancy
                            if square != captured
                        )
                    self._advance_route_execution()
                else:
                    self._route_plan_failed(PlanningError("PLAN acknowledgement mismatch"))
        elif event.kind == "MOVING":
            self.motion_expected = True
            if event.args and event.args[0] in ("HEAD", "PIECE"):
                self.manual_status.set(f"{event.args[0].title()} movement in progress; keep hands clear.")
            else:
                self.game_status.set("The carriage is moving. Keep hands clear.")
        elif event.kind == "MOVED" and event.args:
            if self.active_route_plan is not None and self.route_waiting_for == "MOVED":
                try:
                    route = parse_drag_command(self.route_current_command)
                    expected_label = self.route_current_command.split()[1]
                    acknowledged = (
                        len(event.args) >= 2 and event.args[0] == "PIECE" and
                        event.args[1].lower() == expected_label.lower()
                    )
                    if not acknowledged:
                        raise PlanningError("MOVED acknowledgement mismatch")
                    if (route.source not in self.route_expected_occupancy or
                            route.target in self.route_expected_occupancy):
                        raise PlanningError("Route occupancy diverged before sensor proof")
                    updated = set(self.route_expected_occupancy)
                    updated.remove(route.source)
                    updated.add(route.target)
                    self.route_expected_occupancy = frozenset(updated)
                except (ValueError, PlanningError) as error:
                    self._route_plan_failed(error)
                else:
                    self.route_deadline = 0.0
                    self._advance_route_execution()
            else:
                self.motion_expected = False
                if event.args[0] == "HEAD" and self.manual_pending == "head":
                    self.manual_status.set("Head stopped; checking fresh telemetry.")
                    self._queue_safe_requests("TELEM")
                elif event.args[0] == "PIECE" and self.manual_pending == "piece":
                    self.manual_status.set("Piece movement finished; checking head and sensors.")
                    self._queue_safe_requests("TELEM", "BOARD")
        elif event.kind == "DONE" and event.args:
            if self.active_route_plan is not None:
                if self.route_waiting_for != "DONE" or self.route_current_command != "COMMIT":
                    self._route_plan_failed(PlanningError("Unexpected DONE acknowledgement"))
                else:
                    self.route_deadline = 0.0
                    self.motion_expected = False
                    self._complete_engine_move(event.args[0])
            else:
                self.motion_expected = False
                self._complete_engine_move(event.args[0])
        elif event.kind == "PROMOTE" and event.args:
            messagebox.showinfo("Replace promoted pawn",
                                f"Replace the pawn with {event.args[0].upper()}, then press Button A.",
                                parent=self.root)
        elif event.kind == "PROMOTION" and event.args and event.args[0] == "OK":
            self.awaiting_promotion_confirmation = False
            if self.board.is_game_over(claim_draw=True):
                self._finish_game()
        elif event.kind == "ESTOP":
            self.motion_expected = False
            self.session_active = False
            self.game_status.set("REMOTE HALT REQUESTED — inspect the board locally")
        elif event.kind == "ERR":
            self.model.last_error = " ".join(event.args)
            self.motion_expected = False
            if self.active_route_plan is not None or self.route_planning or self.route_snapshot_pending:
                detail = " ".join(event.args) or "unknown route error"
                self._route_plan_failed(PlanningError(detail))
            if self.manual_pending:
                self._clear_manual_pending()
                self.manual_status.set(f"Board rejected the operation: {' '.join(event.args)}")
        elif event.kind == "STOPPED":
            self.session_active = False
            self.motion_expected = False
            self._reset_route_orchestration(clear_pending=True)
            self.game_status.set("Remote game stopped; standalone mode remains available.")
        self._render()
        self._refresh_visual_state()
        self.root.after_idle(self._dispatch_safe_request)

    def _set_connection_text(self, text: str) -> None:
        self.model.connection_text = text

    def _refresh_visual_state(self) -> None:
        connected = bool(self.transport and self.transport.is_connected)
        self.model.connected = connected
        health, level = self.model.overall_health()
        color = COLORS[level]
        self.health_banner.configure(text=health, bg=color)
        self.connection_badge.configure(text="CONNECTED" if connected else "DISCONNECTED",
                                        bg=COLORS["good"] if connected else COLORS["bad"])
        age = self.model.age_seconds()
        self.last_seen_label.configure(
            text="No board data yet" if age is None else f"Last response {age:.1f} seconds ago"
        )
        self.guidance.set(self.model.guidance())
        telemetry = self.model.telemetry
        self.state_values["controller"].set(self.model.sequence_name())
        if self.model.firmware:
            self.state_values["firmware"].set(
                f"{self.model.firmware.firmware} / {self.model.firmware.protocol}"
            )
        else:
            self.state_values["firmware"].set("Legacy or not read")
        if telemetry:
            homed = "referenced" if telemetry.homed else "not referenced"
            self.state_values["carriage"].set(f"{chr(96 + telemetry.trolley_x)}{telemetry.trolley_y} · {homed}")
            self.state_values["magnet"].set("ON" if telemetry.magnet_on else "Off")
            self.state_values["limit_a"].set("Released" if telemetry.button_a_released else "ACTIVE")
            self.state_values["limit_b"].set(
                ("Released" if telemetry.button_b_released else "ACTIVE") + f" · ADC {telemetry.button_b_raw}"
            )
            self.state_values["ram"].set(f"{telemetry.free_ram} bytes")
            hours, remainder = divmod(telemetry.uptime_seconds, 3600)
            minutes, seconds = divmod(remainder, 60)
            self.state_values["uptime"].set(f"{hours:02d}:{minutes:02d}:{seconds:02d}")
        else:
            for key in ("carriage", "magnet", "limit_a", "limit_b", "ram", "uptime"):
                self.state_values[key].set("Unknown")
        if self.model.sensor_squares is None:
            self.state_values["sensors"].set("Not read")
        else:
            count = len(self.model.sensor_squares)
            missing = len(self.model.missing_squares())
            unexpected = len(self.model.unexpected_squares())
            self.state_values["sensors"].set(f"{count} occupied · {missing} missing · {unexpected} extra")
        self.mechanism_canvas.set_telemetry(telemetry)
        self._refresh_manual_control()

    def _refresh_manual_control(self) -> None:
        if not hasattr(self, "manual_board"):
            return
        telemetry = self.model.telemetry
        carriage = None if not telemetry else (telemetry.trolley_x, telemetry.trolley_y)
        self.manual_board.set_state(
            self.board, self.model.sensor_squares, self.human_color == chess.BLACK, carriage
        )
        callback = None if self.manual_pending else self._manual_square_clicked
        self.manual_board.set_interaction(self.manual_selection.highlighted, callback)
        calibration = "Calibration verified" if self.manual_calibration_verified else "Calibration required"
        prompt = "Tap a target square" if self.manual_selection.mode == "head" else "Tap occupied source, then empty target"
        self.manual_selection_text.set(
            f"{calibration}\n{self.manual_selection.command() or prompt}"
        )

    def _manual_mode_changed(self) -> None:
        if self.manual_pending:
            self.manual_mode.set(self.manual_selection.mode)
            self.manual_status.set("An operation is in progress; wait for verification.")
            self._refresh_manual_control()
            return
        self.manual_selection = self.manual_selection.with_mode(self.manual_mode.get())
        self.manual_status.set(
            "Tap one destination; the electromagnet will stay off."
            if self.manual_selection.mode == "head" else
            "Tap an occupied source, then an empty destination."
        )
        self._refresh_manual_control()

    def _manual_square_clicked(self, square: int) -> None:
        if self.manual_pending:
            self.manual_status.set("An operation is in progress; wait for verification.")
            return
        self.manual_selection, message = self.manual_selection.choose(square, self.model.sensor_squares)
        self.manual_status.set(message)
        self._refresh_manual_control()

    def _manual_clear(self) -> None:
        if self.manual_pending:
            self.manual_status.set("An operation is in progress; wait for verification.")
            return
        self.manual_selection = ManualSelection(self.manual_selection.mode)
        self.manual_status.set("Choose squares." if self.manual_calibration_verified else "Calibrate before moving.")
        self._refresh_manual_control()

    def _manual_capability_ready(self) -> bool:
        if not self.transport or not self.transport.is_connected:
            messagebox.showwarning("Not connected", "Connect to the board first.", parent=self.root)
            return False
        capabilities = self.model.firmware.capabilities if self.model.firmware else frozenset()
        if not {"CALIBRATE", "MANUAL"}.issubset(capabilities):
            messagebox.showerror(
                "Firmware update required",
                "Install firmware 3.31 or newer to use in-app calibration and square movement.",
                parent=self.root,
            )
            return False
        if not self._sensor_frame_ready():
            return False
        if self.session_active:
            messagebox.showwarning("Game active", "Stop the game before manual movement.", parent=self.root)
            return False
        telemetry = self.model.telemetry
        if telemetry and telemetry.motion_fault:
            messagebox.showerror(
                "Motion fault", "Inspect and recover the fault locally before remote movement.", parent=self.root
            )
            return False
        telemetry_age = self.model.telemetry_age_seconds()
        if telemetry is None or telemetry_age is None or telemetry_age > 5.0:
            self._queue_safe_requests("TELEM")
            messagebox.showwarning(
                "Fresh telemetry required",
                "Refresh live telemetry and try again before allowing calibration or movement.",
                parent=self.root,
            )
            return False
        return True

    def _sensor_frame_ready(self) -> bool:
        capabilities = self.model.firmware.capabilities if self.model.firmware else frozenset()
        if "SENSORFRAME" in capabilities:
            return True
        messagebox.showerror(
            "Firmware update required",
            "Install firmware 3.31 or newer so reed sensors and carriage coordinates agree.",
            parent=self.root,
        )
        return False

    def _manual_calibrate(self) -> None:
        if self.manual_pending:
            self.manual_status.set("An operation is already in progress; wait for verification.")
            return
        if not self._manual_capability_ready():
            return
        if not messagebox.askokcancel(
            "Calibrate carriage from Windows?",
            "Calibration moves to the limit references and parks at e6. Clear the mechanism, keep the "
            "physical power cutoff accessible, and watch the board throughout.",
            parent=self.root,
        ):
            return
        self.manual_calibration_verified = False
        self.manual_pending = "calibration"
        self.manual_pending_selection = None
        self.calibration_reported_square = None
        self.manual_status.set("Calibration command sent; keep hands clear.")
        if not self._send("CALIBRATE"):
            self._clear_manual_pending()

    def _manual_move(self) -> None:
        if self.manual_pending:
            self.manual_status.set("An operation is already in progress; wait for verification.")
            return
        if not self._manual_capability_ready():
            return
        if not self.manual_calibration_verified:
            messagebox.showwarning(
                "Calibrate first", "Calibrate here and wait for the e6 telemetry check to pass.", parent=self.root
            )
            return
        telemetry = self.model.telemetry
        if not telemetry or not telemetry.homed or telemetry.motion_fault or telemetry.magnet_on:
            self.manual_calibration_verified = False
            messagebox.showerror(
                "Board not ready", "Telemetry no longer confirms a homed, fault-free head with magnet off.",
                parent=self.root,
            )
            return
        command = self.manual_selection.command()
        if not command:
            messagebox.showinfo("Choose squares", "Select the required square or squares first.", parent=self.root)
            return
        if self.manual_selection.mode == "piece":
            if self.model.sensor_updated is None or time.monotonic() - self.model.sensor_updated > 5.0:
                self._queue_safe_requests("BOARD")
                self.manual_status.set("Refreshing sensors; verify the selection and click Move again.")
                return
            occupied = self.model.sensor_squares or frozenset()
            source = self.manual_selection.source
            target = self.manual_selection.target
            if source is None or target is None:
                messagebox.showinfo(
                    "Choose squares", "Select an occupied source and empty destination first.", parent=self.root
                )
                return
            if source not in occupied or target in occupied:
                messagebox.showerror(
                    "Sensor check changed", "The source must contain a piece and the destination must be empty.",
                    parent=self.root,
                )
                return
            detail = (
                f"Move the piece {square_name(source)} to "
                f"{square_name(target)}? The magnet energizes only after the head reaches "
                "the occupied source."
            )
        else:
            target = self.manual_selection.target
            if target is None:
                messagebox.showinfo("Choose square", "Select a destination first.", parent=self.root)
                return
            detail = f"Move the head to {square_name(target)} with the electromagnet OFF?"
        if not messagebox.askokcancel("Confirm physical movement", detail, parent=self.root):
            return
        requested_selection = self.manual_selection
        self.manual_pending = requested_selection.mode
        self.manual_pending_selection = requested_selection
        self.manual_status.set("Movement command sent; keep hands clear.")
        if not self._send(command):
            self._clear_manual_pending()

    def _verify_manual_telemetry(self) -> None:
        telemetry = self.model.telemetry
        if self.manual_pending == "calibration":
            self.manual_calibration_verified = calibration_matches(
                self.calibration_reported_square, telemetry
            )
            self.manual_status.set(
                "Calibration verified: board and Windows agree the head is at e6, homed, with magnet off."
                if self.manual_calibration_verified else
                "Calibration report disagrees with telemetry; do not move."
            )
            self._clear_manual_pending()
        elif self.manual_pending == "head":
            target = self.manual_pending_selection.target if self.manual_pending_selection else None
            if target is None:
                self.manual_status.set("The requested head destination was lost; recalibrate before another move.")
                self.manual_calibration_verified = False
                self._clear_manual_pending()
                return
            verified = head_move_matches(target, telemetry)
            self.manual_status.set(
                f"Head position verified at {square_name(target)}; magnet remained off."
                if verified else "Head position could not be verified; recalibrate before another move."
            )
            if not verified:
                self.manual_calibration_verified = False
            self._clear_manual_pending()
        elif self.manual_pending == "piece":
            target = self.manual_pending_selection.target if self.manual_pending_selection else None
            if target is None:
                self.manual_status.set("The requested piece destination was lost; inspect and recalibrate.")
                self.manual_calibration_verified = False
                self._clear_manual_pending()
                return
            if not head_move_matches(target, telemetry):
                self.manual_status.set(
                    "Head telemetry disagrees with the requested destination; inspect and recalibrate."
                )
                self.manual_calibration_verified = False
                self._clear_manual_pending()
            else:
                self.manual_status.set(
                    f"Head is at {square_name(target)}; checking piece sensors."
                )

    def _verify_manual_sensors(self) -> None:
        if self.manual_pending != "piece":
            return
        pending_selection = self.manual_pending_selection
        source = pending_selection.source if pending_selection else None
        target = pending_selection.target if pending_selection else None
        if source is None or target is None:
            self.manual_status.set("The requested piece squares were lost; inspect the board before continuing.")
            self.manual_calibration_verified = False
            self._clear_manual_pending()
            return
        verified = piece_move_matches(
            source, target, self.model.sensor_squares
        )
        self.manual_status.set(
            f"Piece verified at {square_name(target)}; source is clear."
            if verified else "Sensors do not confirm the piece move; inspect the board before continuing."
        )
        self._clear_manual_pending()
        if verified:
            self.manual_selection = ManualSelection(pending_selection.mode)

    def _clear_manual_pending(self) -> None:
        self.manual_pending = ""
        self.manual_pending_selection = None

    def _choose_engine(self) -> None:
        selected = filedialog.askopenfilename(
            title="Select Stockfish executable", filetypes=(("Executables", "*.exe"), ("All", "*.*"))
        )
        if selected:
            self.engine_path.set(selected)

    def _ensure_engine(self) -> bool:
        path = Path(self.engine_path.get())
        if not path.is_file():
            messagebox.showerror("Stockfish not found",
                                 "Run install-stockfish.ps1 (or setup.ps1 for source use), then select a "
                                 "Stockfish executable.", parent=self.root)
            return False
        if self.engine is None:
            try:
                self.engine = chess.engine.SimpleEngine.popen_uci(str(path))
            except Exception as error:
                messagebox.showerror("Stockfish failed", str(error), parent=self.root)
                return False
        if "UCI_LimitStrength" in self.engine.options:
            self.engine.configure({"UCI_LimitStrength": True})
        if "UCI_Elo" in self.engine.options:
            option = self.engine.options["UCI_Elo"]
            selected = max(option.min or 1320, min(option.max or 3190, self.elo.get()))
            self.engine.configure({"UCI_Elo": selected})
        return True

    def _start_game(self) -> None:
        if not self.transport or not self.transport.is_connected:
            messagebox.showwarning("Not connected", "Connect to the board first.", parent=self.root)
            return
        if not self._sensor_frame_ready():
            return
        if not messagebox.askyesno(
            "Calibration will move the carriage",
            "Confirm that the camera or local view is clear, no hands are near the mechanism, "
            "and a physical power cutoff is available.\n\nStart calibration now?",
            icon="warning", parent=self.root,
        ):
            return
        if not self._ensure_engine():
            return
        self.board.reset()
        self._reset_route_orchestration(clear_pending=True)
        self.awaiting_promotion_confirmation = False
        self.engine_thinking = False
        self.human_color = chess.WHITE if self.human_side.get() == "White" else chess.BLACK
        self.session_active = True
        self.motion_expected = True
        self._render()
        self._send("START W" if self.human_color == chess.WHITE else "START B")
        self.game_status.set("Calibration requested. Keep hands clear and watch the board.")

    def _emergency_halt(self) -> None:
        if not self.transport or not self.transport.is_connected:
            messagebox.showwarning(
                "No radio connection",
                "The halt command cannot be delivered. Use the board's physical power cutoff.",
                parent=self.root,
            )
            return
        self._send("!", quiet=True)
        self.motion_expected = False
        self.session_active = False
        self._reset_route_orchestration(clear_pending=True)
        self.game_status.set("REMOTE HALT SENT — verify locally; use physical power if motion continues")
        self.recorder.record("safety", "remote_halt_sent")

    def _resolve_human_move(self, text: str) -> chess.Move | None:
        candidates = [move for move in self.board.legal_moves if move.uci().startswith(text)]
        if len(candidates) == 1:
            return candidates[0]
        promotions = [move for move in candidates if move.promotion]
        if promotions:
            answer = simpledialog.askstring("Promotion", "Promote to Q, R, B, or N:",
                                            initialvalue="Q", parent=self.root)
            symbol = (answer or "Q").lower()[0]
            return next((move for move in promotions if move.uci().endswith(symbol)), None)
        return None

    def _accept_human_move(self, text: str) -> None:
        if not self.session_active or self.board.turn != self.human_color:
            self._send("REJECT")
            return
        move = self._resolve_human_move(text)
        if move is None:
            self._send("REJECT")
            self.game_status.set(f"Illegal move {text}; restore the pieces physically.")
            return
        self.board.push(move)
        self._render()
        self._send("ACCEPT")
        if self.board.is_game_over(claim_draw=True):
            self._finish_game()
        else:
            self._start_engine_think()

    def _start_engine_think(self) -> None:
        if self.engine_thinking or not self.engine or self.board.turn == self.human_color:
            return
        self.engine_thinking = True
        position = self.board.copy()
        limit = chess.engine.Limit(time=max(0.05, self.think_seconds.get()))
        self.game_status.set("Stockfish is thinking on Windows...")

        def worker() -> None:
            try:
                result = self.engine.play(position, limit)
                self.events.put(("engine_move", result.move.uci()))
            except Exception as error:
                self.events.put(("engine_error", error))

        threading.Thread(target=worker, daemon=True).start()

    def _send_engine_move(self, uci: str) -> None:
        self.engine_thinking = False
        try:
            move = chess.Move.from_uci(uci)
        except ValueError:
            self.game_status.set(f"Stockfish returned invalid move {uci}")
            return
        if move not in self.board.legal_moves:
            self.game_status.set(f"Stockfish returned illegal move {uci}")
            return

        self.pending_engine_move = move
        capabilities = self.model.firmware.capabilities if self.model.firmware else frozenset()
        if "PLANROUTE" in capabilities:
            self.route_snapshot_pending = True
            self.route_planning = False
            self.motion_expected = True
            self.route_waiting_for = "BOARD"
            self.route_current_command = "BOARD"
            self.route_deadline = time.monotonic() + ROUTE_CONTROL_TIMEOUT_S
            self.game_status.set("Reading all 64 sensors before collision-safe route planning...")
            if not self._send("BOARD", quiet=True):
                self._reset_route_orchestration(clear_pending=True)
                self.game_status.set("Could not request the physical board snapshot.")
            return

        # Backward compatibility for firmware 4.0 and earlier. Legal chess moves
        # retain the original direct/knight physical planner.
        command = play_command(
            uci,
            castling=self.board.is_castling(move),
            en_passant=self.board.is_en_passant(move),
        )
        if self._send(command):
            self.motion_expected = True
            self.game_status.set(f"Board is moving {uci}; keep hands clear.")

    def _maybe_start_route_planning(self) -> None:
        if not self.route_snapshot_pending or self.pending_engine_move is None:
            return
        self.route_snapshot_pending = False
        self.route_waiting_for = ""
        self.route_current_command = ""
        self.route_deadline = 0.0
        sensors = self.model.sensor_squares
        expected = expected_occupancy(self.board)
        if sensors is None or sensors != expected:
            missing = expected - (sensors or frozenset())
            unexpected = (sensors or frozenset()) - expected
            detail = (
                f"missing {', '.join(map(square_name, sorted(missing))) or 'none'}; "
                f"extra {', '.join(map(square_name, sorted(unexpected))) or 'none'}"
            )
            self._route_plan_failed(
                PlanningError(f"physical/logical mismatch ({detail})")
            )
            return

        position = self.board.copy(stack=False)
        move = self.pending_engine_move
        occupancy = frozenset(sensors)
        try:
            config = PlannerConfig(
                time_limit_s=max(0.5, float(self.route_seconds.get())),
                max_temporary_pieces=max(0, min(30, int(self.route_temporary_pieces.get()))),
            )
        except (tk.TclError, ValueError) as error:
            self._route_plan_failed(error)
            return

        self.route_planning = True
        self.motion_expected = True
        self.game_status.set(
            f"Planning collision-safe physical route for {move.uci()} on Windows..."
        )
        generation = self.route_generation

        def worker() -> None:
            try:
                plan = plan_chess_move(
                    position,
                    move,
                    physical_occupancy=occupancy,
                    config=config,
                )
                self.events.put(("route_plan_ready", (generation, plan)))
            except Exception as error:
                self.events.put(("route_plan_error", (generation, error)))

        threading.Thread(target=worker, daemon=True).start()

    def _begin_route_execution(self, payload: object) -> None:
        if (not isinstance(payload, tuple) or len(payload) != 2 or
                not isinstance(payload[0], int)):
            self._route_plan_failed(PlanningError("Planner returned an invalid event"))
            return
        generation, result = payload
        if generation != self.route_generation:
            return
        self.route_planning = False
        if not isinstance(result, MotionPlan):
            self._route_plan_failed(PlanningError("Planner returned an invalid result"))
            return
        payload = result
        move = self.pending_engine_move
        if move is None or payload.problem.move_uci != move.uci():
            self._route_plan_failed(PlanningError("Stale route plan was discarded"))
            return
        try:
            payload.validate()
            commands = payload.protocol_commands()
        except Exception as error:
            self._route_plan_failed(error)
            return

        self.active_route_plan = payload
        self.route_commands = deque(commands)
        self.route_waiting_for = ""
        self.route_current_command = ""
        self.route_deadline = 0.0
        self.route_firmware_open = False
        self.route_motion_command_sent = False
        self.route_expected_occupancy = (
            payload.problem.initial_physical_occupancy
            if payload.problem.initial_physical_occupancy is not None
            else payload.problem.initial_occupancy_before_capture
        )
        self.motion_expected = True
        self.recorder.record(
            "route",
            "plan_ready",
            move=move.uci(),
            relocations=len(payload.relocations),
            temporary_pieces=payload.temporary_piece_count,
            carried_steps=payload.carried_steps,
            expanded=payload.statistics.expanded_nodes,
            mode=payload.statistics.search_mode,
        )
        self.game_status.set(
            f"Route ready: {payload.describe()}. Executing verified drags; keep hands clear."
        )
        self._advance_route_execution()

    def _advance_route_execution(self) -> None:
        if self.active_route_plan is None:
            return
        if not self.route_commands:
            self._route_plan_failed(PlanningError("Route command sequence ended before COMMIT"))
            return
        command = self.route_commands.popleft()
        verb = command.split(maxsplit=1)[0].upper()
        self.route_waiting_for = (
            "PLAN" if verb == "PLAN" else
            "MOVED" if verb == "DRAG" else
            "BOARD" if verb == "BOARD" else
            "DONE" if verb == "COMMIT" else ""
        )
        self.route_current_command = command
        plan_has_capture = (
            verb == "PLAN" and self.active_route_plan.problem.captured_square is not None
        )
        timeout = (
            ROUTE_MOTION_TIMEOUT_S
            if verb == "DRAG" or plan_has_capture
            else ROUTE_CONTROL_TIMEOUT_S
        )
        self.route_deadline = time.monotonic() + timeout
        if verb == "DRAG" or plan_has_capture:
            # A capture is removed during PLAN. Once any physical command is
            # issued, a missing reply cannot prove the starting arrangement.
            self.route_motion_command_sent = True
        if not self.route_waiting_for or not self._send(command, quiet=True):
            self._route_plan_failed(PlanningError(f"Could not send route command {verb}"))
            return
        self.motion_expected = True

    def _route_plan_failed(self, error: object) -> None:
        if (isinstance(error, tuple) and len(error) == 2 and
                isinstance(error[0], int)):
            generation, actual_error = error
            if generation != self.route_generation:
                return
            error = actual_error
        detail = str(error) or error.__class__.__name__
        self.recorder.record("route", "plan_failed", error=detail)
        # A non-capture PLAN is reversible because no magnet motion was issued.
        # Never attempt automatic cancellation after capture/DRAG motion: a
        # lost acknowledgement leaves the physical arrangement uncertain.
        connected = self.transport is not None and self.transport.is_connected
        can_cancel = (
            connected and not self.route_motion_command_sent and
            (self.route_firmware_open or self.route_current_command.startswith("PLAN "))
        )
        if can_cancel:
            # A bare COMMIT is deliberately a clean cancellation while the full
            # starting sensor frame is still present.
            self._send("COMMIT", quiet=True)
        # STOP never moves a piece. It closes any remaining remote transaction
        # after the current firmware command reaches an idle point. Dirty boards
        # remain visibly mismatched and require explicit recovery.
        if connected:
            self._send("STOP", quiet=True)
        uncertain = self.route_motion_command_sent
        self._reset_route_orchestration(clear_pending=True)
        self.motion_expected = False
        self.session_active = False
        recovery = (
            "The last motion may have changed the board; inspect every square before recovery."
            if uncertain else
            "No routed magnet movement was acknowledged; verify the position before restarting."
        )
        self.game_status.set(f"Collision-safe route stopped: {detail}. {recovery}")
        self._append_log("error", "Route planner", detail)

    def _reset_route_orchestration(self, *, clear_pending: bool) -> None:
        self.route_generation += 1
        self.route_snapshot_pending = False
        self.route_planning = False
        self.active_route_plan = None
        self.route_commands.clear()
        self.route_waiting_for = ""
        self.route_current_command = ""
        self.route_deadline = 0.0
        self.route_firmware_open = False
        self.route_motion_command_sent = False
        self.route_expected_occupancy = frozenset()
        if clear_pending:
            self.pending_engine_move = None

    def _complete_engine_move(self, reported: str) -> None:
        move = self.pending_engine_move
        if move is None or not move.uci().startswith(reported):
            self.game_status.set(f"Unexpected motion completion: {reported}")
            return
        self.board.push(move)
        self.awaiting_promotion_confirmation = bool(move.promotion)
        self._reset_route_orchestration(clear_pending=True)
        self._render()
        if self.board.is_game_over(claim_draw=True) and not self.awaiting_promotion_confirmation:
            self._finish_game()
        else:
            self.game_status.set("Your move. Press Button A when complete.")

    def _finish_game(self) -> None:
        outcome = self.board.outcome(claim_draw=True)
        result = outcome.result() if outcome else "*"
        self._send(f"GAMEOVER {result}")
        self.game_status.set(f"Game over: {result}")
        self.session_active = False

    def _render(self) -> None:
        self.model.expected_squares = expected_occupancy(self.board)
        telemetry = self.model.telemetry
        carriage = (telemetry.trolley_x, telemetry.trolley_y) if telemetry else None
        if hasattr(self, "board_canvas"):
            self.board_canvas.set_state(self.board, self.model.sensor_squares,
                                        self.human_color == chess.BLACK, carriage)
        if hasattr(self, "move_history"):
            lines = []
            replay = chess.Board()
            for index, move in enumerate(self.board.move_stack):
                san = replay.san(move)
                if index % 2 == 0:
                    lines.append(f"{index // 2 + 1}. {san}")
                else:
                    lines[-1] += f"  {san}"
                replay.push(move)
            self.move_history.configure(state="normal")
            self.move_history.delete("1.0", "end")
            self.move_history.insert("1.0", "\n".join(lines) or "No moves yet")
            self.move_history.configure(state="disabled")

    def _game_pgn(self) -> chess.pgn.Game:
        game = chess.pgn.Game()
        game.headers["Event"] = "Open Automatic Chessboard"
        game.headers["Date"] = datetime.now().strftime("%Y.%m.%d")
        game.headers["White"] = "Human" if self.human_color == chess.WHITE else "Stockfish"
        game.headers["Black"] = "Human" if self.human_color == chess.BLACK else "Stockfish"
        node = game
        for move in self.board.move_stack:
            node = node.add_variation(move)
        game.headers["Result"] = self.board.result(claim_draw=True)
        return game

    def _save_pgn(self) -> None:
        default = f"automatic-chessboard-{datetime.now():%Y%m%d-%H%M}.pgn"
        path = filedialog.asksaveasfilename(defaultextension=".pgn", initialfile=default,
                                            filetypes=(("PGN", "*.pgn"), ("All", "*.*")))
        if path:
            Path(path).write_text(str(self._game_pgn()) + "\n", encoding="utf-8")
            self.game_status.set(f"Saved {path}")

    def _run_diagnostics(self) -> None:
        connected = bool(self.transport and self.transport.is_connected)
        self._set_diag("connection", "Pass" if connected else "Fail",
                       self.model.connection_text, "pass" if connected else "fail")
        expected = ("PONG", "INFO", "TELEM", "BOARD")
        baseline = {kind: self.response_counts.get(kind, 0) for kind in expected}
        self.diagnostic_batch = (baseline, time.monotonic() + (18.0 if connected else 0.0))
        if connected:
            self._queue_safe_requests("PING", "INFO", "TELEM", "BOARD")
        for key in ("firmware", "telemetry", "sensors", "controls"):
            self._set_diag(key, "Running", "Waiting for board response...", "warn")

        def engine_worker() -> None:
            path = Path(self.engine_path.get())
            if not path.is_file():
                self.events.put(("engine_test", (False, "Stockfish executable not found")))
                return
            try:
                engine = chess.engine.SimpleEngine.popen_uci(str(path))
                result = engine.play(chess.Board(), chess.engine.Limit(time=0.05))
                engine.quit()
                self.events.put(("engine_test", (True, f"Engine replied with {result.move.uci()}")))
            except Exception as error:
                self.events.put(("engine_test", (False, str(error))))

        threading.Thread(target=engine_worker, daemon=True).start()
        available = CameraWorker.dependencies_available()
        self._set_diag("camera", "Pass" if available else "Optional",
                       "Camera dependencies installed" if available else "Install only if video is needed",
                       "pass" if available else "warn")
        self.root.after(100, self._check_diagnostic_batch)

    def _check_diagnostic_batch(self) -> None:
        batch = self.diagnostic_batch
        if batch is None:
            return
        baseline, deadline = batch
        all_received = all(self.response_counts.get(kind, 0) > count
                           for kind, count in baseline.items())
        requests_finished = not self.safe_request_pending and not self.safe_request_queue
        if all_received or requests_finished or time.monotonic() >= deadline:
            self.diagnostic_batch = None
            self._evaluate_diagnostics(baseline)
        else:
            self.root.after(100, self._check_diagnostic_batch)

    def _evaluate_diagnostics(self, baseline: dict[str, int]) -> None:
        def fresh(kind: str) -> bool:
            return self.response_counts.get(kind, 0) > baseline.get(kind, 0)

        connected = bool(self.transport and self.transport.is_connected and fresh("PONG"))
        self._set_diag("connection", "Pass" if connected else "Fail",
                       self.model.connection_text if connected else "No current PONG response",
                       "pass" if connected else "fail")
        info = self.model.firmware if fresh("INFO") else None
        self._set_diag("firmware", "Pass" if info else "Fail",
                       f"Firmware {info.firmware}, protocol {info.protocol}" if info else "No INFO response",
                       "pass" if info else "fail")
        telemetry = self.model.telemetry if fresh("TELEM") else None
        self._set_diag("telemetry", "Pass" if telemetry else "Fail",
                       self.model.sequence_name() if telemetry else "No TELEM response",
                       "pass" if telemetry else "fail")
        sensors = self.model.sensor_squares if fresh("BOARD") else None
        self._set_diag("sensors", "Pass" if sensors is not None else "Fail",
                       f"Read all 64 squares; {len(sensors)} currently occupied" if sensors is not None else "No BOARD response",
                       "pass" if sensors is not None else "fail")
        if telemetry:
            controls_ok = telemetry.button_a_released and telemetry.button_b_released and telemetry.button_b_raw >= 700
            detail = (f"A released; B released; A6 ADC {telemetry.button_b_raw}"
                      if controls_ok else
                      f"Check locally: A released={telemetry.button_a_released}, "
                      f"B released={telemetry.button_b_released}, A6 ADC={telemetry.button_b_raw}")
            self._set_diag("controls", "Pass" if controls_ok else "Attention", detail,
                           "pass" if controls_ok else "warn")
        else:
            self._set_diag("controls", "Unknown", "Telemetry unavailable", "fail")

    def _set_diag(self, key: str, result: str, details: str, tag: str) -> None:
        self.diag_results[key] = (result, details)
        if self.diag_tree.exists(key):
            self.diag_tree.item(key, values=(result, details), tags=(tag,))

    def _copy_diagnostic_summary(self) -> None:
        lines = [f"Open Automatic Chessboard {APP_VERSION}"]
        for key in self.diag_tree.get_children():
            label = self.diag_tree.item(key, "text")
            result, detail = self.diag_results.get(key, ("Not run", ""))
            lines.append(f"{label}: {result} — {detail}")
        self.root.clipboard_clear()
        self.root.clipboard_append("\n".join(lines))

    def _support_snapshot(self) -> dict:
        telemetry = self.model.telemetry
        return {
            "app_version": APP_VERSION,
            "connection": self.model.connection_text,
            "firmware": self.model.firmware.__dict__ if self.model.firmware else None,
            "telemetry": telemetry.__dict__ if telemetry else None,
            "sensor_hex": self.model.sensor_hex,
            "sensor_occupied": len(self.model.sensor_squares or ()),
            "logical_fen": self.board.fen(),
            "health": self.model.overall_health()[0],
            "last_error": self.model.last_error,
            "diagnostics": self.diag_results,
        }

    def _create_support_bundle(self) -> None:
        default = f"chessboard-support-{datetime.now():%Y%m%d-%H%M}.zip"
        destination = filedialog.asksaveasfilename(
            defaultextension=".zip", initialfile=default,
            filetypes=(("ZIP support bundle", "*.zip"),), parent=self.root,
        )
        if not destination:
            return
        try:
            result = create_support_bundle(
                Path(destination), self.recorder, self._settings_dict(), self._support_snapshot(),
                [APP_DIR / "README.md", APP_DIR / "FIRMWARE_PROTOCOL.md"],
            )
            messagebox.showinfo(
                "Support bundle created",
                f"Saved {result}\n\nIt contains logs and state, but no camera images, PGNs, or camera credentials.",
                parent=self.root,
            )
        except Exception as error:
            messagebox.showerror("Support bundle failed", str(error), parent=self.root)

    def _start_camera(self) -> None:
        if self.camera:
            self.camera.stop()
        self.camera = CameraWorker(
            self.camera_source.get(),
            lambda frame: self.events.put(("camera_frame", frame)),
            lambda status: self.events.put(("camera_status", status)),
        )
        self.camera.start()

    def _stop_camera(self) -> None:
        if self.camera:
            self.camera.stop()
            self.camera = None
        self.camera_status.set("Camera stopped")

    def _render_camera_frame(self) -> None:
        if self.latest_camera_image is None:
            return
        try:
            from PIL import ImageTk

            width = max(self.camera_view.winfo_width() - 8, 320)
            height = max(self.camera_view.winfo_height() - 8, 240)
            image = self.latest_camera_image.copy()
            image.thumbnail((width, height))
            self.camera_photo = ImageTk.PhotoImage(image)
            self.camera_view.configure(image=self.camera_photo, text="")
        except Exception as error:
            self.camera_status.set(f"Camera display failed: {error}")

    def _save_camera_snapshot(self) -> None:
        if self.latest_camera_image is None:
            messagebox.showwarning("No camera frame", "Start the camera first.", parent=self.root)
            return
        path = filedialog.asksaveasfilename(
            defaultextension=".jpg", initialfile=f"chessboard-{datetime.now():%Y%m%d-%H%M%S}.jpg",
            filetypes=(("JPEG", "*.jpg"), ("PNG", "*.png")), parent=self.root,
        )
        if path:
            self.latest_camera_image.save(path)
            self.camera_status.set(f"Snapshot saved to {path}")

    def _update_command_risk(self) -> None:
        risk = classify_command(self.developer_command.get())
        descriptions = {
            CommandRisk.READ_ONLY: "Read-only: safe for remote diagnostics",
            CommandRisk.CONTROL: "Control command: changes session state",
            CommandRisk.MOTION: "MOTION RISK: may calibrate or move the carriage",
            CommandRisk.EMERGENCY: "Emergency halt request",
            CommandRisk.UNKNOWN: "Unknown command: blocked unless reviewed in firmware documentation",
        }
        self.command_risk.set(descriptions[risk])

    def _send_developer_command(self) -> None:
        command = self.developer_command.get().strip()
        verb = command.split(maxsplit=1)[0].upper() if command else ""
        if verb in {"PLAN", "DRAG", "COMMIT"}:
            messagebox.showwarning(
                "Verified route command reserved",
                "PLAN, DRAG, and COMMIT are owned by automatic route orchestration.",
                parent=self.root,
            )
            return
        if (self.route_snapshot_pending or self.route_planning or
                self.active_route_plan is not None):
            messagebox.showwarning(
                "Route transaction active",
                "Wait for the verified route to finish, or use the persistent emergency halt.",
                parent=self.root,
            )
            return
        risk = classify_command(command)
        if risk == CommandRisk.UNKNOWN and not command.upper().startswith("SIMMOVE "):
            messagebox.showwarning("Unknown command", "This command is not in the documented protocol.",
                                   parent=self.root)
            return
        if risk == CommandRisk.MOTION:
            if not self.unlock_motion.get():
                messagebox.showwarning("Motion commands locked",
                                       "Enable the motion-command checkbox first.", parent=self.root)
                return
            if not messagebox.askyesno("Send motion command?",
                                       "This command can move hardware. Confirm the board is clear.",
                                       icon="warning", parent=self.root):
                return
        if risk == CommandRisk.READ_ONLY:
            if self.transport and self.transport.is_connected:
                self._queue_safe_requests(command)
            else:
                messagebox.showwarning("Not connected", "Connect before sending a read-only request.",
                                       parent=self.root)
        else:
            self._send(command)

    def _append_log(self, direction: str, event: str, detail: str) -> None:
        if not hasattr(self, "event_tree"):
            return
        stamp = datetime.now().strftime("%H:%M:%S")
        self.event_tree.insert("", "end", values=(stamp, direction, event, detail))
        children = self.event_tree.get_children()
        if len(children) > 500:
            self.event_tree.delete(*children[:100])
        self.event_tree.yview_moveto(1.0)
        line = f"{stamp} {direction:<9} {event} {detail}".rstrip()
        self.raw_log.configure(state="normal")
        self.raw_log.insert("end", line + "\n")
        self.raw_log.see("end")
        self.raw_log.configure(state="disabled")

    def _show_remote_safety(self) -> None:
        messagebox.showwarning(
            "Remote-operation limits",
            "Bluetooth is a short-range local link, not an internet connection. Camera video does not prove "
            "that every obstruction is visible. A radio halt can be delayed or lost if Windows, Bluetooth, or "
            "the Nano stops responding. Keep a physical power cutoff accessible to someone near the board, "
            "and never begin calibration or movement from an uncertain camera view.",
            parent=self.root,
        )

    def _show_about(self) -> None:
        messagebox.showinfo(
            "About",
            f"Open Automatic Chessboard Monitor {APP_VERSION}\n\n"
            "Open-source Windows monitoring, diagnostics, Stockfish play, camera viewing, simulation, and "
            "support tooling for the Arduino Nano automatic chessboard.\n\n"
            "Licensed under GNU GPL version 3 or later.",
            parent=self.root,
        )

    def _on_close(self) -> None:
        self._save_settings()
        self.recorder.record("app", "session_closed")
        if self.camera:
            self.camera.stop()
        if self.transport:
            self.transport.close()
        if self.engine:
            try:
                self.engine.quit()
            except Exception:
                pass
        self.root.destroy()


def main() -> None:
    root = tk.Tk()
    AutomaticChessboardApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
