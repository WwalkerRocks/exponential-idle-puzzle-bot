"""Builds Arrow Puzzle test fixtures.

Usage: py tools/make_arrow_fixtures.py NAME screenshot.png tiles.txt "d,d,d,..."
  NAME        e.g. easy / hard / expert
  tiles.txt   one "left top right bottom" per tile, as the game's accessibility tree reports them
  directions  the true direction of each tile (0 = up, clockwise; 4 steps on square boards, 6 on hex)

Masks everything outside the minigame popup (status bar, game stats, tab bar), saves the masked PNG
to tools/screens/arrow_NAME.png and writes app/src/test/resources/arrows/NAME.{gray.gz,txt}.
"""
import gzip
import pathlib
import struct
import sys

import numpy as np
from PIL import Image

TOP, BOTTOM, FILL = 0.119, 0.9225, 8

root = pathlib.Path(__file__).resolve().parent
name, png, tiles_file, dirs = sys.argv[1:5]
a = np.asarray(Image.open(png).convert("RGB")).copy()
h = a.shape[0]
a[: int(h * TOP)] = FILL
a[int(h * BOTTOM):] = FILL
im = Image.fromarray(a)
im.save(root / "screens" / f"arrow_{name}.png", optimize=True)

out = root.parent / "app" / "src" / "test" / "resources" / "arrows"
out.mkdir(parents=True, exist_ok=True)
px = a.astype(np.uint32)
luma = ((px[:, :, 0] * 77 + px[:, :, 1] * 150 + px[:, :, 2] * 29) >> 8).astype(np.uint8)
(out / f"{name}.gray.gz").write_bytes(gzip.compress(struct.pack(">ii", im.width, im.height) + luma.tobytes(), 9, mtime=0))
tiles = [line.split() for line in open(tiles_file) if line.strip()]
d = dirs.split(",")
assert len(d) == len(tiles), (len(d), len(tiles))
(out / f"{name}.txt").write_text("".join(f"{' '.join(t)} {v}\n" for t, v in zip(tiles, d)))
print(name, im.size, len(tiles), "tiles")
