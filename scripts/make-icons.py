#!/usr/bin/env python3
"""Superseded by brand/render-icons.py.

This drew the icons — a letter "A" on the accent colour with a gold crossbar —
because there was no logo yet and a placeholder that matched the palette beat a
default. There is a logo now, brand/almira-mark.svg, and every icon in the
repository is rendered from it:

    python3 brand/render-icons.py

Kept as a stub rather than deleted so that anything still calling it fails
loudly instead of quietly overwriting the real icons with the old placeholder —
which is exactly what running the previous contents of this file would do.
"""

import sys

sys.exit(
    "scripts/make-icons.py is superseded. The icons come from the logo now:\n"
    "    python3 brand/render-icons.py\n"
    "Running the old placeholder generator would overwrite them."
)
