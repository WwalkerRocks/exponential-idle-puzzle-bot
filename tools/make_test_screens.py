"""Builds the reader's test fixtures from the screenshots in tools/screens.

Each screenshot is first cropped down to the minigame popup: the phone status bar, the game's
stats strip above the popup and the game's tab bar below it are blacked out (they are not needed
and may be personal). The masked PNG is written back, then two raw grayscale fixtures are written:
original size and 1080 px wide (the phone's resolution).

Fixture format (gzip): 4-byte big-endian width, 4-byte big-endian height, then width*height luma
bytes (same weights as GrayImage.fromArgb). Usage: py tools/make_test_screens.py
"""
import gzip
import pathlib
import struct

import numpy as np
from PIL import Image

TOP = 0.119     # popup starts below this fraction of the screen height
BOTTOM = 0.9225  # game tab bar starts at this fraction
FILL = 8         # the game's near-black background

root = pathlib.Path(__file__).resolve().parent
out = root.parent / "app" / "src" / "test" / "resources" / "screens"
out.mkdir(parents=True, exist_ok=True)
for png in sorted((root / "screens").glob("*.png")):
    a = np.asarray(Image.open(png).convert("RGB")).copy()
    h = a.shape[0]
    a[: int(h * TOP)] = FILL
    a[int(h * BOTTOM):] = FILL
    im = Image.fromarray(a)
    im.save(png, optimize=True)
    for width in (im.width, 1080):
        img = im if width == im.width else im.resize((width, im.height * width // im.width), Image.BICUBIC)
        px = np.asarray(img).astype(np.uint32)
        luma = ((px[:, :, 0] * 77 + px[:, :, 1] * 150 + px[:, :, 2] * 29) >> 8).astype(np.uint8)
        dest = out / f"{png.stem}_{width}.gray.gz"
        dest.write_bytes(gzip.compress(struct.pack(">ii", img.width, img.height) + luma.tobytes(), 9, mtime=0))
        print(dest.name, img.size, dest.stat().st_size, "bytes")
