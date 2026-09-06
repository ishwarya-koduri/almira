#!/usr/bin/env python3
"""
Generates the app icons from the palette in docs/02, with no image library.

There is no Pillow, ImageMagick or rsvg on the machine this was built on, and
adding one to install an icon would be a poor trade — so this writes PNGs
directly: zlib-compressed scanlines wrapped in the four chunks a PNG needs. It
supersamples 4x and box-downsamples, which is what gives the diagonals of the
"A" their smooth edges.

Re-run after changing the palette:

    python3 scripts/make-icons.py

Everything it writes is committed, so a build never depends on this running.
"""

import struct
import zlib
from pathlib import Path

ACCENT = (0x0F, 0x5A, 0x57)      # --accent
CANVAS = (0xFB, 0xF9, 0xF5)      # --canvas
GOLD = (0xC9, 0xA2, 0x27)        # --gold, used once, as the crossbar

OUT = Path(__file__).resolve().parent.parent / "backend/src/main/resources/static/icons"
SS = 4                            # supersampling factor


def png(path: Path, pixels, width: int, height: int) -> None:
    """pixels: list of rows, each a list of (r, g, b, a) tuples."""
    raw = bytearray()
    for row in pixels:
        raw.append(0)                                   # filter type 0
        for r, g, b, a in row:
            raw += bytes((r, g, b, a))

    def chunk(kind: bytes, data: bytes) -> bytes:
        return (struct.pack(">I", len(data)) + kind + data
                + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF))

    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)   # 8-bit RGBA
    path.write_bytes(
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + chunk(b"IEND", b"")
    )


def inside_rounded_square(x, y, size, radius):
    if radius <= 0:
        return True
    cx = min(max(x, radius), size - radius)
    cy = min(max(y, radius), size - radius)
    return (x - cx) ** 2 + (y - cy) ** 2 <= radius ** 2


def inside_letter(x, y, size, scale):
    """
    The 'A' of the brand mark, drawn as two legs and a crossbar.

    Coordinates are fractions of the icon so the same shape works at every
    size; `scale` shrinks it for the maskable icon, whose corners are cropped
    by the platform.
    """
    u = (x / size - 0.5) / scale + 0.5
    v = (y / size - 0.5) / scale + 0.5
    if not (0.18 <= v <= 0.82):
        return False

    # How far the legs have spread at this height, and how thick they are.
    t = (v - 0.18) / 0.64
    half = 0.06 + 0.26 * t
    thickness = 0.085
    on_left = abs(u - (0.5 - half)) < thickness / 2
    on_right = abs(u - (0.5 + half)) < thickness / 2
    if on_left or on_right:
        return True

    # The crossbar, set low the way a serif 'A' carries it.
    if 0.60 <= v <= 0.60 + 0.075:
        bar_half = 0.06 + 0.26 * ((0.62 - 0.18) / 0.64)
        return abs(u - 0.5) <= bar_half
    return False


def render(size: int, *, maskable: bool) -> list:
    big = size * SS
    radius = 0 if maskable else int(big * 0.22)
    letter_scale = 0.66 if maskable else 0.86     # maskable keeps to the safe zone
    bar_v = None

    rows = []
    for y in range(size):
        row = []
        for x in range(size):
            r = g = b = a = 0
            for sy in range(SS):
                for sx in range(SS):
                    px, py = x * SS + sx, y * SS + sy
                    if not inside_rounded_square(px, py, big, radius):
                        continue
                    if inside_letter(px, py, big, letter_scale):
                        # The crossbar in gold; the legs in the canvas colour.
                        u = (px / big - 0.5) / letter_scale + 0.5
                        v = (py / big - 0.5) / letter_scale + 0.5
                        colour = GOLD if 0.60 <= v <= 0.675 and abs(u - 0.5) < 0.30 else CANVAS
                    else:
                        colour = ACCENT
                    r += colour[0]
                    g += colour[1]
                    b += colour[2]
                    a += 255
            n = SS * SS
            row.append((r // n, g // n, b // n, a // n))
        rows.append(row)
    return rows


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    for size in (192, 512):
        png(OUT / f"icon-{size}.png", render(size, maskable=False), size, size)
    for size in (192, 512):
        png(OUT / f"icon-maskable-{size}.png", render(size, maskable=True), size, size)
    # iOS masks the corners itself, so the touch icon is full-bleed.
    png(OUT / "apple-touch-icon.png", render(180, maskable=True), 180, 180)
    for file in sorted(OUT.iterdir()):
        print(f"{file.name:28} {file.stat().st_size:>7,} bytes")


if __name__ == "__main__":
    main()
