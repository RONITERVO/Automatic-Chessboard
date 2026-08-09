"""Resilient USB, HC-08 BLE, and simulator transports for Windows."""

from __future__ import annotations

import asyncio
import threading
import time
from collections.abc import Callable

from protocol import LineBuffer, board_hex_from_squares

HC08_SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
HC08_CHARACTERISTIC_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"

LineCallback = Callable[[str], None]
StatusCallback = Callable[[str], None]


def rank_ble_devices(rows: list[tuple[str, str, int]]) -> list[tuple[str, str, int]]:
    """Prefer known board-module names, then signal strength and stable labels."""
    known_names = ("hc-08", "hc08", "hmsoft", "bt05", "bt-05")

    def rank(row: tuple[str, str, int]) -> tuple[int, int, str, str]:
        name, address, rssi = row
        normalized = name.casefold().replace("_", "-").replace(" ", "")
        likely_board = any(token.replace(" ", "") in normalized for token in known_names)
        return (0 if likely_board else 1, -rssi, name.casefold(), address.casefold())

    return sorted(rows, key=rank)


def serial_ports() -> list[str]:
    from serial.tools import list_ports

    # HC-08 uses BLE GATT in this application. Windows may also expose unrelated
    # Bluetooth RFCOMM pseudo-ports; hiding them prevents nontechnical users from
    # selecting a port that can never speak to the Nano bootloader or firmware.
    return [port.device for port in list_ports.comports()
            if "BTHENUM" not in (port.hwid or "").upper()]


def discover_ble_devices(timeout: float = 8.0) -> list[tuple[str, str, int]]:
    """Return (display name, address, RSSI) without connecting to devices."""
    from bleak import BleakScanner

    async def discover() -> list[tuple[str, str, int]]:
        found = await BleakScanner.discover(timeout=timeout, return_adv=True)
        rows = []
        for device, advertisement in found.values():
            name = advertisement.local_name or device.name or "(unnamed)"
            rows.append((name, device.address, advertisement.rssi))
        return rank_ble_devices(rows)

    return asyncio.run(discover())


class UsbSerialTransport:
    kind = "USB"

    def __init__(self, port: str, on_line: LineCallback,
                 on_status: StatusCallback, baud: int = 9600,
                 reconnect: bool = True) -> None:
        self.port = port
        self.baud = baud
        self.on_line = on_line
        self.on_status = on_status
        self.reconnect = reconnect
        self._serial = None
        self._thread: threading.Thread | None = None
        self._running = threading.Event()
        self._connected = threading.Event()
        self._send_lock = threading.Lock()
        self._lines = LineBuffer()

    @property
    def is_connected(self) -> bool:
        return self._connected.is_set()

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._running.set()
        self._thread = threading.Thread(target=self._read_loop, daemon=True)
        self._thread.start()

    def _read_loop(self) -> None:
        import serial

        while self._running.is_set():
            try:
                self.on_status(f"Opening USB {self.port}...")
                connection = serial.Serial(self.port, self.baud, timeout=0.2)
                self._serial = connection
                self._connected.set()
                self.on_status(f"USB connected: {self.port}")
                while self._running.is_set() and connection.is_open:
                    data = connection.read(connection.in_waiting or 1)
                    for line in self._lines.feed(data):
                        self.on_line(line)
            except Exception as error:
                if self._running.is_set():
                    self.on_status(f"USB interrupted: {error}")
            finally:
                self._connected.clear()
                connection = self._serial
                self._serial = None
                if connection:
                    try:
                        connection.close()
                    except Exception:
                        pass
            if not self._running.is_set() or not self.reconnect:
                break
            self.on_status("USB reconnecting in 2 seconds...")
            time.sleep(2.0)
        self._connected.clear()

    def send(self, line: str) -> None:
        with self._send_lock:
            connection = self._serial
            if not connection or not connection.is_open:
                raise RuntimeError("USB is not connected")
            connection.write((line.strip() + "\n").encode("ascii"))
            connection.flush()

    def close(self) -> None:
        self._running.clear()
        connection = self._serial
        if connection:
            try:
                connection.close()
            except Exception:
                pass
        self._connected.clear()
        self.on_status("USB disconnected")


class Hc08BleTransport:
    kind = "BLE"

    def __init__(self, device_name: str, on_line: LineCallback,
                 on_status: StatusCallback, reconnect: bool = True) -> None:
        self.device_name = device_name
        self.on_line = on_line
        self.on_status = on_status
        self.reconnect = reconnect
        self._thread: threading.Thread | None = None
        self._loop: asyncio.AbstractEventLoop | None = None
        self._client = None
        self._stop: asyncio.Event | None = None
        self._connected = threading.Event()
        self._lines = LineBuffer()

    @property
    def is_connected(self) -> bool:
        return self._connected.is_set()

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._thread = threading.Thread(target=self._thread_main, daemon=True)
        self._thread.start()

    def _thread_main(self) -> None:
        try:
            asyncio.run(self._run())
        except Exception as error:
            self.on_status(f"BLE stopped: {error}")
        finally:
            self._connected.clear()

    async def _run(self) -> None:
        from bleak import BleakClient, BleakScanner

        self._loop = asyncio.get_running_loop()
        self._stop = asyncio.Event()
        backoff = 1.0
        while not self._stop.is_set():
            identifier = self.device_name.lower()
            try:
                self.on_status(f"Scanning for {self.device_name}...")
                device = await BleakScanner.find_device_by_filter(
                    lambda candidate, advertisement:
                        candidate.address.lower() == identifier or
                        (advertisement.local_name or candidate.name or "").lower() == identifier,
                    timeout=15.0,
                )
                if device is None:
                    raise RuntimeError(f"BLE device {self.device_name!r} was not found")

                disconnected = asyncio.Event()

                def on_disconnect(_client) -> None:
                    if self._loop:
                        self._loop.call_soon_threadsafe(disconnected.set)

                async with BleakClient(device, disconnected_callback=on_disconnect) as client:
                    self._client = client
                    await client.start_notify(HC08_CHARACTERISTIC_UUID, self._notification)
                    self._connected.set()
                    backoff = 1.0
                    self.on_status(f"BLE connected: {self.device_name}")
                    stop_task = asyncio.create_task(self._stop.wait())
                    disconnect_task = asyncio.create_task(disconnected.wait())
                    done, pending = await asyncio.wait(
                        (stop_task, disconnect_task), return_when=asyncio.FIRST_COMPLETED
                    )
                    for task in pending:
                        task.cancel()
                    if client.is_connected:
                        await client.stop_notify(HC08_CHARACTERISTIC_UUID)
            except Exception as error:
                if not self._stop.is_set():
                    self.on_status(f"BLE interrupted: {error}")
            finally:
                self._client = None
                self._connected.clear()

            if self._stop.is_set() or not self.reconnect:
                break
            self.on_status(f"BLE reconnecting in {backoff:.0f} seconds...")
            try:
                await asyncio.wait_for(self._stop.wait(), timeout=backoff)
            except TimeoutError:
                pass
            backoff = min(backoff * 2.0, 15.0)
        self.on_status("BLE disconnected")

    def _notification(self, _characteristic, data: bytearray) -> None:
        for line in self._lines.feed(bytes(data)):
            self.on_line(line)

    async def _write(self, payload: bytes) -> None:
        if not self._client or not self._client.is_connected:
            raise RuntimeError("BLE is not connected")
        for start in range(0, len(payload), 20):
            await self._client.write_gatt_char(
                HC08_CHARACTERISTIC_UUID, payload[start:start + 20], response=False
            )

    def send(self, line: str) -> None:
        if not self._loop or not self._connected.is_set():
            raise RuntimeError("BLE is not connected")
        future = asyncio.run_coroutine_threadsafe(
            self._write((line.strip() + "\n").encode("ascii")), self._loop
        )
        future.result(timeout=5.0)

    def close(self) -> None:
        if self._loop and self._stop:
            self._loop.call_soon_threadsafe(self._stop.set)
        self._connected.clear()


class SimulatorTransport:
    """No-hardware transport for UI development, demos, and issue reproduction."""

    kind = "Simulator"

    def __init__(self, on_line: LineCallback, on_status: StatusCallback) -> None:
        import chess

        self.on_line = on_line
        self.on_status = on_status
        self._connected = False
        self._started = time.monotonic()
        self._board = chess.Board()
        self._pending_human = None
        self._human_white = True
        self._sequence = 1
        self._fault = False
        self._homed = False
        self._trolley_x = 5
        self._trolley_y = 6

    @property
    def is_connected(self) -> bool:
        return self._connected

    def _emit(self, line: str, delay: float = 0.04) -> None:
        timer = threading.Timer(delay, lambda: self.on_line(line))
        timer.daemon = True
        timer.start()

    def start(self) -> None:
        self._connected = True
        self.on_status("Simulator connected (no hardware movement)")
        self._emit("READY ACB1")

    def _board_hex(self) -> str:
        return board_hex_from_squares(set(self._board.piece_map()))

    def _telemetry(self) -> str:
        uptime = int(time.monotonic() - self._started)
        return (f"TELEM ACB2 {self._sequence} {int(self._homed)} {int(self._sequence >= 15)} "
                f"{int(self._fault)} 0 {self._trolley_x} {self._trolley_y} 1 1 1023 900 {uptime}")

    def send(self, line: str) -> None:
        import chess

        if not self._connected:
            raise RuntimeError("Simulator is not connected")
        text = line.strip()
        upper = text.upper()
        if text.startswith("!"):
            self._fault = True
            self._sequence = 10
            self._emit("ESTOP REMOTE")
        elif upper in ("PING", "HELLO"):
            self._emit("PONG ACB1")
        elif upper == "INFO":
            self._emit("INFO ACB2 simulator BOARD,TELEM,REMOTE,ESTOP,BTTEST,CALIBRATE,MANUAL")
        elif upper == "STATUS":
            self._emit(f"STATUS ACB1 {self._sequence} {int(self._homed)} {int(self._sequence >= 15)}")
        elif upper == "TELEM":
            self._emit(self._telemetry())
        elif upper == "BOARD":
            self._emit(f"BOARD {self._board_hex()}")
        elif upper == "BTTEST":
            self._emit("BT SIMULATED")
        elif upper == "CALIBRATE":
            self._sequence = 3
            self._emit("CALIBRATING")
            self._homed = True
            self._trolley_x, self._trolley_y = 5, 6
            self._sequence = 1
            self._emit("CALIBRATED e6", 0.25)
        elif upper.startswith("START "):
            self._board.reset()
            self._human_white = upper.endswith("W")
            self._sequence = 15
            self._emit(f"OK START {'W' if self._human_white else 'B'}")
            self._emit("SETUP PRESS A", 0.15)
            self._emit(f"SESSION {'W' if self._human_white else 'B'}", 0.5)
            self._sequence = 16 if self._human_white else 17
            self._emit("TURN HUMAN" if self._human_white else "TURN COMPUTER", 0.65)
        elif upper.startswith("SIMMOVE "):
            try:
                move = chess.Move.from_uci(text.split(maxsplit=1)[1].lower())
                if move not in self._board.legal_moves:
                    raise ValueError("illegal in current position")
                self._pending_human = move
                self._emit(f"MOVE {move.uci()}")
            except Exception as error:
                self._emit(f"ERR SIMMOVE {error}")
        elif upper == "ACCEPT" and self._pending_human:
            self._board.push(self._pending_human)
            self._pending_human = None
            self._sequence = 17
            self._emit("OK ACCEPT")
            self._emit("TURN COMPUTER", 0.1)
        elif upper == "REJECT":
            self._pending_human = None
            self._sequence = 18
            self._emit("OK REJECT")
        elif upper.startswith("PLAY "):
            try:
                uci = text.split()[1].lower()
                move = chess.Move.from_uci(uci)
                if move not in self._board.legal_moves:
                    raise ValueError("illegal in current position")
                self._emit(f"MOVING {uci}")

                def finish() -> None:
                    self._board.push(move)
                    self._sequence = 16
                    self.on_line(f"DONE {uci}")

                timer = threading.Timer(0.5, finish)
                timer.daemon = True
                timer.start()
            except Exception as error:
                self._emit(f"ERR PLAY {error}")
        elif upper.startswith("HEAD "):
            square = text.split(maxsplit=1)[1].lower()
            if not self._homed:
                self._emit("ERR CALIBRATE")
            elif len(square) != 2 or square[0] not in "abcdefgh" or square[1] not in "12345678":
                self._emit("ERR COMMAND")
            else:
                self._emit(f"MOVING HEAD {square}")
                self._trolley_x, self._trolley_y = ord(square[0]) - 96, int(square[1])
                self._emit(f"MOVED HEAD {square}", 0.25)
        elif upper.startswith("PIECE "):
            move = text.split(maxsplit=1)[1].lower()
            try:
                if not self._homed:
                    raise RuntimeError("CALIBRATE")
                if len(move) != 4:
                    raise ValueError("COMMAND")
                source = chess.parse_square(move[:2])
                target = chess.parse_square(move[2:])
                if source not in self._board.piece_map():
                    raise ValueError("SOURCE EMPTY")
                if target in self._board.piece_map():
                    raise ValueError("TARGET FULL")
                self._emit(f"MOVING PIECE {move}")
                piece = self._board.remove_piece_at(source)
                self._board.set_piece_at(target, piece)
                self._trolley_x = chess.square_file(target) + 1
                self._trolley_y = chess.square_rank(target) + 1
                self._emit(f"MOVED PIECE {move}", 0.25)
            except Exception as error:
                self._emit(f"ERR {error}")
        elif upper == "STOP":
            self._sequence = 1
            self._emit("STOPPED")
        elif upper.startswith("GAMEOVER"):
            self._sequence = 9
            self._emit("OK GAMEOVER")
        else:
            self._emit("ERR COMMAND")

    def close(self) -> None:
        self._connected = False
        self.on_status("Simulator disconnected")
