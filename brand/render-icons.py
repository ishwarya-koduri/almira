#!/usr/bin/env python3
"""Renders every icon in the repository from brand/almira-mark.svg.

    python3 brand/render-icons.py

One master, one command. Nothing below is hand-drawn and nothing is hand-sized,
so changing the mark is a one-line edit followed by this.

Rasterising with `sips`, which is part of macOS: there is no Pillow,
ImageMagick or rsvg on this machine, and installing one to render an icon would
be a poor trade — the same reasoning as scripts/make-icons.py, which this
replaces for the web icons.

Every PNG it writes is committed, so no build ever depends on this running.

--- The three safe zones, which are the whole reason this is not one resize ---

A square logo cannot be dropped into a round hole. Each platform crops
differently, and each variant below exists because of a specific crop:

  full        the mark edge to edge. Web `purpose: any`, the iOS app icon (iOS
              rounds the corners itself and crops nothing else), and the
              favicon.
  safe        the mark at 80%, on the same ground. Web `purpose: maskable`,
              where the guarantee is only that a circle of 80% diameter
              survives — at 100% the dome and the plinth would be shaved.
  foreground  the mark on transparency, sized so that its own bounding circle
              fits inside the 72dp Android guarantees of a 108dp canvas — 0.60,
              not the 0.667 the safe square suggests. The difference is the
              plinth: it is a wide pill at the very bottom, so its rounded ends
              are the furthest thing from the centre and the first thing a
              round launcher mask takes off. Measured, not guessed; the working
              is in ADAPTIVE_SCALE below.
  monochrome  the foreground in one flat colour and without the wordmark, for
              Android 13's themed icons. The wordmark goes because a themed
              icon is a silhouette: six serif letters flattened to a single
              colour at launcher size are mud, and the arch and keyhole say
              the same thing more clearly.
  symbol      the mark without the wordmark, full bleed, for the favicon and
              the shell mark. Rendered at 16, 22 and 32 next to the full
              lockup, the wordmark is a grey smear and the symbol is clean at
              all three — so below about 48px the letters are not a wordmark,
              they are dirt. This is the ordinary logomark-versus-lockup
              distinction, applied where it is measurable rather than where it
              is fashionable.
"""

from __future__ import annotations

import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MASTER = ROOT / "brand" / "almira-mark.svg"

WEB_ICONS = ROOT / "backend/src/main/resources/static/icons"
ANDROID_RES = ROOT / "app/androidApp/src/androidMain/res"
IOS_ICONS = ROOT / "app/iosApp/iosApp/Assets.xcassets/AppIcon.appiconset"

# Read out of the master so there is exactly one place a colour is written.
GROUND = "#0F4034"

# Android's five buckets, as a multiple of the 48dp baseline.
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}

# How much of the 108dp adaptive canvas the mark may occupy.
#
# The mark's drawn extent in master units is x 120-904, y 87-958, so its centre
# is (512, 522) and the furthest drawn point from that centre is the outside of
# a plinth end-cap: 538 units away to the cap's centre plus its 34-unit radius,
# 572 in all, or 0.558 of the canvas. Android guarantees a circle of radius
# 0.333. 0.333 / 0.558 = 0.597.
#
# At the 0.667 the safe *square* implies, those two plinth caps sit outside the
# circle and a round launcher shaves them — which is visible, because the
# plinth is the one element that reads as a straight line.
ADAPTIVE_SCALE = 0.60


def read_master() -> tuple[str, str]:
    """The style block and the mark's contents, lifted out of the master."""
    svg = MASTER.read_text()
    style = re.search(r"<style>(.*?)</style>", svg, re.S)
    mark = re.search(r'<g id="mark">(.*?)</g>\s*</svg>', svg, re.S)
    if not style or not mark:
        sys.exit(f"{MASTER} is not shaped as expected — is <style> or <g id=\"mark\"> missing?")
    return style.group(1), mark.group(1)


def variant(
    style: str,
    mark: str,
    *,
    scale: float,
    ground: bool,
    flatten: str | None = None,
    wordmark: bool = True,
) -> str:
    """One composed SVG at 1024, ready to rasterise."""
    if flatten:
        # Every fill and stroke becomes the one colour, for the themed icon.
        # `fill: none` on the arch is left alone, so it stays hollow.
        style = re.sub(
            r"(fill|stroke):\s*#[0-9A-Fa-f]{6}",
            lambda m: f"{m.group(1)}: {flatten}",
            style,
        )
    if not wordmark:
        mark = re.sub(r"<text class=\"word\".*?</text>", "", mark, flags=re.S)
    offset = 1024 * (1 - scale) / 2
    background = f'<rect width="1024" height="1024" fill="{GROUND}"/>' if ground else ""
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024" '
        'width="1024" height="1024">\n'
        f"<defs><style>{style}</style></defs>\n"
        f"{background}\n"
        f'<g transform="translate({offset:.3f} {offset:.3f}) scale({scale})">{mark}</g>\n'
        "</svg>\n"
    )


def rasterise(svg_text: str, sizes: list[int], name: lambda_or_str, out_dir: Path) -> list[Path]:
    """Render once at 1024, then box-downsample to each size.

    Rendering straight at 48px puts the wordmark through the rasteriser at a
    size it cannot resolve; downsampling from 1024 antialiases instead.
    """
    out_dir.mkdir(parents=True, exist_ok=True)
    written = []
    with tempfile.TemporaryDirectory() as tmp:
        source = Path(tmp) / "variant.svg"
        source.write_text(svg_text)
        base = Path(tmp) / "variant-1024.png"
        run(["sips", "-s", "format", "png", str(source), "--out", str(base)])
        for size in sizes:
            target = out_dir / (name(size) if callable(name) else name)
            if size == 1024:
                shutil.copyfile(base, target)
            else:
                run(["sips", "-z", str(size), str(size), str(base), "--out", str(target)])
            written.append(target)
    return written


def run(command: list[str]) -> None:
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != 0:
        sys.exit(f"{' '.join(command[:2])} failed:\n{result.stdout}\n{result.stderr}")


lambda_or_str = object  # documentation only; `name` is a str or size -> str


def main() -> None:
    if not shutil.which("sips"):
        sys.exit("sips is missing — this script needs macOS.")
    style, mark = read_master()

    full = variant(style, mark, scale=1.0, ground=True)
    safe = variant(style, mark, scale=0.8, ground=True)
    symbol = variant(style, mark, scale=1.0, ground=True, wordmark=False)
    foreground = variant(style, mark, scale=ADAPTIVE_SCALE, ground=False)
    monochrome = variant(
        style, mark, scale=ADAPTIVE_SCALE, ground=False, flatten="#FFFFFF", wordmark=False
    )

    written: list[Path] = []

    # --- the web client ----------------------------------------------------
    written += rasterise(full, [192], "icon-192.png", WEB_ICONS)
    written += rasterise(full, [512], "icon-512.png", WEB_ICONS)
    written += rasterise(safe, [192], "icon-maskable-192.png", WEB_ICONS)
    written += rasterise(safe, [512], "icon-maskable-512.png", WEB_ICONS)
    # iOS home-screen bookmarks crop to a rounded rectangle, not a circle, and
    # Apple composites on white if there is any transparency — so: full bleed.
    written += rasterise(full, [180], "apple-touch-icon.png", WEB_ICONS)
    # A vector favicon, so a browser tab is never a resized photograph — and
    # the symbol rather than the lockup, because a tab is 16px.
    (WEB_ICONS / "favicon.svg").write_text(symbol)
    written.append(WEB_ICONS / "favicon.svg")

    # --- Android -----------------------------------------------------------
    for bucket, factor in DENSITIES.items():
        # The legacy square icon, for launchers older than adaptive icons.
        written += rasterise(
            full, [round(48 * factor)], "ic_launcher.png", ANDROID_RES / f"mipmap-{bucket}"
        )
        # The adaptive layers live on a 108dp canvas.
        written += rasterise(
            foreground, [round(108 * factor)],
            "ic_launcher_foreground.png", ANDROID_RES / f"mipmap-{bucket}",
        )
        written += rasterise(
            monochrome, [round(108 * factor)],
            "ic_launcher_monochrome.png", ANDROID_RES / f"mipmap-{bucket}",
        )

    (ANDROID_RES / "values").mkdir(parents=True, exist_ok=True)
    (ANDROID_RES / "values" / "ic_launcher_background.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!-- Generated by brand/render-icons.py. The adaptive icon's back layer is\n"
        "     flat, so it is a colour rather than a bitmap: the launcher parallaxes\n"
        "     the two layers against each other and a flat colour cannot shimmer. -->\n"
        "<resources>\n"
        f'    <color name="ic_launcher_background">{GROUND}</color>\n'
        "</resources>\n"
    )

    adaptive = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!-- Generated by brand/render-icons.py. -->\n"
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/ic_launcher_background"/>\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>\n'
        '    <monochrome android:drawable="@mipmap/ic_launcher_monochrome"/>\n'
        "</adaptive-icon>\n"
    )
    for folder in ("mipmap-anydpi-v26",):
        (ANDROID_RES / folder).mkdir(parents=True, exist_ok=True)
        for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
            (ANDROID_RES / folder / name).write_text(adaptive)
            written.append(ANDROID_RES / folder / name)

    # --- iOS ---------------------------------------------------------------
    # One 1024 image. Since Xcode 14 a single-size app icon is the whole set:
    # the system renders every other size from it, and listing twenty slots
    # only creates twenty ways to be inconsistent.
    written += rasterise(full, [1024], "icon-1024.png", IOS_ICONS)
    (IOS_ICONS / "Contents.json").write_text(
        '{\n  "images" : [\n    {\n      "filename" : "icon-1024.png",\n'
        '      "idiom" : "universal",\n      "platform" : "ios",\n'
        '      "size" : "1024x1024"\n    }\n  ],\n'
        '  "info" : { "author" : "brand/render-icons.py", "version" : 1 }\n}\n'
    )
    written.append(IOS_ICONS / "Contents.json")

    print(f"wrote {len(written)} files from {MASTER.relative_to(ROOT)}:")
    for path in written:
        size = path.stat().st_size
        print(f"  {path.relative_to(ROOT)}  ({size:,} bytes)")


if __name__ == "__main__":
    main()
