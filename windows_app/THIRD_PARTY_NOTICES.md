# Third-party software notices

The companion is licensed under GNU GPL version 3 or later because it links to
the GPL-3.0-or-later `chess`/`python-chess` library.

- **chess / python-chess** — GPL-3.0-or-later —
  <https://github.com/niklasf/python-chess>
- **Bleak** — MIT — <https://github.com/hbldh/bleak>
- **pySerial** — BSD-3-Clause — <https://github.com/pyserial/pyserial>
- **Stockfish** — GPLv3 — <https://github.com/official-stockfish/Stockfish>
- **OpenCV** (optional camera edition) — Apache-2.0 — <https://opencv.org/>
- **Pillow** (optional camera edition) — HPND — <https://python-pillow.github.io/>
- **NumPy** (optional camera edition) — BSD-3-Clause — <https://numpy.org/>

Stockfish is not embedded in the default application build. The provided install
script downloads the official release separately, preserves its license when
present in the archive, and identifies the corresponding tagged source. Anyone
redistributing a build that embeds Stockfish must satisfy Stockfish's GPLv3 source
and license obligations.
