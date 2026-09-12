#!/usr/bin/env python3
"""A tiny PNG reader, so the safe-zone claims can be measured instead of argued.

There is no Pillow on this machine, and installing one to check an icon would be
the same poor trade the rest of this directory declines. A PNG is zlib-deflated
scanlines with a one-byte filter per row, so reading one is short.

Only what is needed: 8-bit RGB or RGBA, no interlacing, no palettes. Anything
else raises, because a silent wrong answer here would defeat the point.
"""

from __future__ import annotations

import struct
import zlib
from pathlib import Path


def read_rgba(path: Path) -> tuple[int, int, bytearray]:
    """Returns (width, height, rgba) with four bytes per pixel, row-major."""
    blob = path.read_bytes()
    if blob[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError(f"{path} is not a PNG")

    width = height = depth = colour = 0
    data = bytearray()
    offset = 8
    while offset < len(blob):
        (length,) = struct.unpack(">I", blob[offset:offset + 4])
        kind = blob[offset + 4:offset + 8]
        payload = blob[offset + 8:offset + 8 + length]
        if kind == b"IHDR":
            width, height, depth, colour, _, _, interlace = struct.unpack(">IIBBBBB", payload)
            if depth != 8 or colour not in (2, 6) or interlace != 0:
                raise ValueError(
                    f"{path}: only 8-bit RGB/RGBA without interlacing is supported "
                    f"(got depth={depth} colour={colour} interlace={interlace})"
                )
        elif kind == b"IDAT":
            data += payload
        elif kind == b"IEND":
            break
        offset += 12 + length

    channels = 4 if colour == 6 else 3
    raw = zlib.decompress(bytes(data))
    stride = width * channels
    out = bytearray(width * height * 4)
    previous = bytearray(stride)

    position = 0
    for row in range(height):
        filter_type = raw[position]
        position += 1
        line = bytearray(raw[position:position + stride])
        position += stride

        # The five PNG filters, from the spec. Each byte is predicted from its
        # left neighbour, the row above, or both.
        for index in range(stride):
            left = line[index - channels] if index >= channels else 0
            up = previous[index]
            up_left = previous[index - channels] if index >= channels else 0
            if filter_type == 0:
                pass
            elif filter_type == 1:
                line[index] = (line[index] + left) & 0xFF
            elif filter_type == 2:
                line[index] = (line[index] + up) & 0xFF
            elif filter_type == 3:
                line[index] = (line[index] + ((left + up) >> 1)) & 0xFF
            elif filter_type == 4:
                estimate = left + up - up_left
                a, b, c = abs(estimate - left), abs(estimate - up), abs(estimate - up_left)
                nearest = left if (a <= b and a <= c) else (up if b <= c else up_left)
                line[index] = (line[index] + nearest) & 0xFF
            else:
                raise ValueError(f"{path}: unknown row filter {filter_type}")
        previous = line

        for column in range(width):
            source = column * channels
            target = (row * width + column) * 4
            out[target:target + 3] = line[source:source + 3]
            out[target + 3] = line[source + 3] if channels == 4 else 255

    return width, height, out


def drawn_extent(path: Path, ground: tuple[int, int, int] | None) -> dict:
    """How far the drawn artwork reaches from the canvas centre, as a fraction.

    "Drawn" means opaque and — where the variant has an opaque ground — not the
    ground colour. Returns the bounding box and, more usefully, the radius of
    the smallest circle centred on the canvas that contains every drawn pixel,
    because that is the shape every launcher mask is guaranteed to contain.
    """
    width, height, rgba = read_rgba(path)
    centre_x, centre_y = (width - 1) / 2, (height - 1) / 2
    min_x, min_y, max_x, max_y = width, height, -1, -1
    worst = 0.0

    for y in range(height):
        row = y * width
        for x in range(width):
            index = (row + x) * 4
            if rgba[index + 3] <= 16:
                continue
            if ground is not None:
                near = (
                    abs(rgba[index] - ground[0]) < 12
                    and abs(rgba[index + 1] - ground[1]) < 12
                    and abs(rgba[index + 2] - ground[2]) < 12
                )
                if near:
                    continue
            min_x, max_x = min(min_x, x), max(max_x, x)
            min_y, max_y = min(min_y, y), max(max_y, y)
            worst = max(worst, ((x - centre_x) ** 2 + (y - centre_y) ** 2) ** 0.5)

    if max_x < 0:
        raise ValueError(f"{path}: nothing drawn at all")
    return {
        "size": (width, height),
        "box": (min_x, min_y, max_x, max_y),
        "box_fraction": (min_x / width, min_y / height, (max_x + 1) / width, (max_y + 1) / height),
        "radius_fraction": worst / width,
    }
