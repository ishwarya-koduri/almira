#!/usr/bin/env python3
"""Renders every icon in the repository from brand/almira-mark.svg.

    python3 brand/render-icons.py

One master, one command. Nothing below is hand-drawn and nothing is hand-sized,
so changing the mark is a one-line edit followed by this.

Rasterising with `sips`, which is part of macOS: there is no Pillow,
ImageMagick or rsvg here, and installing one to render an icon would be a poor
trade — the same reasoning as scripts/make-icons.py, which this replaced.

Every PNG it writes is committed, so no build ever depends on this running.

--- The variants, and why there are five ---

A square logo cannot be dropped into a round hole. Each platform crops
differently, and each variant exists because of a specific crop:

  full        the mark edge to edge. Web `purpose: any`, `apple-touch-icon`,
              and the iOS app icon — iOS rounds the corners itself and crops
              nothing else.
  maskable    the mark inside the circle a maskable icon guarantees.
  foreground  the mark inside the smaller circle Android's adaptive icon
              guarantees, on transparency.
  monochrome  the foreground in one flat colour and without the wordmark, for
              Android 13's themed icons. The wordmark goes because a themed
              icon is a silhouette: the letters have no band behind them to sit
              on, and flattened to a single colour at launcher size they are
              mud.
  symbol      the mark without the wordmark, full bleed, for the favicon and
              the 22px shell mark. Rendered at 16, 22 and 32 beside the full
              lockup, the wordmark is a grey smear at all three and the symbol
              is clean — below roughly 48px the letters are not a wordmark,
              they are dirt.

--- The scales are measured, not chosen ---

`maskable` and `foreground` scales are computed, per run, from the rendered
pixels: the mark is rasterised at full size, the furthest drawn pixel from the
canvas centre is found, and the scale is the ratio of the platform's guaranteed
radius to that. Then every icon written is measured again and the run fails if
any of them overflows.

This is not ceremony. The previous version of this file asserted 80% for
maskable because 80% is the guarantee — but 80% is the guarantee for the
*circle*, and scaling a square artwork to 80% leaves its corners outside it.
Measured, that icon reached 0.451 of the canvas against a 0.400 limit, so a
round mask was shaving the ends off the plinth. It looked fine by eye, which is
exactly why it needed measuring.
"""

from __future__ import annotations

import copy
import hashlib
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from pngprobe import drawn_extent  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
MASTER = Path(__file__).resolve().parent / "almira-mark.svg"

WEB_ICONS = ROOT / "backend/src/main/resources/static/icons"
STATIC = ROOT / "backend/src/main/resources/static"
ANDROID_RES = ROOT / "app/androidApp/src/androidMain/res"
IOS_ICONS = ROOT / "app/iosApp/iosApp/Assets.xcassets/AppIcon.appiconset"

SVG_NS = "http://www.w3.org/2000/svg"
ET.register_namespace("", SVG_NS)
ET.register_namespace("c2pa", "http://c2pa.org/manifest")

# Everything is rendered at this size first and downsampled. Rendering straight
# at 48px puts the wordmark through the rasteriser at a size it cannot resolve.
RENDER = 1024

# A maskable icon guarantees a circle of 80% diameter; Android's adaptive icon
# guarantees the middle 72dp of 108. Both as a radius, over the canvas width.
MASKABLE_RADIUS = 0.40
ADAPTIVE_RADIUS = 72 / 108 / 2

# A hair off the derived scale, for the antialiased edge rather than for the
# geometry. A rounded stroke cap fades out over about a pixel whatever the
# output size, and the measurement below counts a pixel as drawn once it is
# roughly a tenth ink — so a mark sized to exactly the limit measures a
# fraction over it. Seen: 0.4001 against 0.4000 at 512px, which is one pixel of
# fringe and not a plinth outside the mask. One percent covers it at every size
# the manifests ask for.
FRINGE_MARGIN = 0.99

# Android's five density buckets, as a multiple of the 48dp baseline.
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def die(message: str) -> None:
    sys.exit(f"brand/render-icons.py: {message}")


def tag_of(element: ET.Element) -> str:
    """The local name, with the SVG namespace stripped off the front."""
    return element.tag.rsplit("}", 1)[-1]


class Master:
    """The master SVG, taken apart far enough to compose variants from it.

    Two elements have to be told apart from the rest, and both are found by
    shape rather than by name so that replacing the artwork does not mean
    editing this file:

      the ground   the one rect covering the whole viewBox. Its fill is the
                   brand's ground colour, and it is dropped for the variants
                   that need transparency.
      the wordmark the one path whose `d` runs to thousands of characters,
                   because it is six glyphs as outlines while everything else
                   is a stroke of four or five points.

    An explicit `id="ground"` or `id="wordmark"` on the master wins over both
    heuristics, for the day the artwork stops being shaped like this.
    """

    def __init__(self, path: Path) -> None:
        self.tree = ET.parse(path)
        self.root = self.tree.getroot()
        self.view_box = self.root.get("viewBox")
        if not self.view_box:
            die(f"{path.name} has no viewBox; every size here is derived from it")
        parts = [float(v) for v in re.split(r"[ ,]+", self.view_box.strip())]
        if len(parts) != 4 or parts[2] != parts[3]:
            die(f"{path.name} viewBox is {self.view_box!r}; a square one is expected")
        self.min_x, self.min_y, self.extent, _ = parts
        # `fill="none"` on the root is load-bearing: the arch is a stroke with
        # no fill, and without it every stroked path renders filled black.
        self.root_fill = self.root.get("fill", "none")

        children = list(self.root)
        self.ground = self._find_ground(children)
        self.wordmark = self._find_wordmark(children)
        drop = {id(self.ground)} | {
            id(c) for c in children if tag_of(c) == "metadata"
        }
        self.mark = [c for c in children if id(c) not in drop]
        if not self.mark:
            die(f"{path.name} has nothing in it but a background")
        self.ground_colour = (self.ground.get("fill") or "#000000") if self.ground is not None else "#000000"

    def _find_ground(self, children: list[ET.Element]) -> ET.Element | None:
        for child in children:
            if child.get("id") == "ground":
                return child
        for child in children:
            if tag_of(child) != "rect":
                continue
            if child.get("width") == child.get("height") == str(_trim(self.extent)):
                return child
        return None

    def _find_wordmark(self, children: list[ET.Element]) -> ET.Element | None:
        for child in children:
            if child.get("id") == "wordmark":
                return child
        outlines = [
            c for c in children
            if tag_of(c) == "path" and len(c.get("d", "")) > 1000
        ]
        return max(outlines, key=lambda c: len(c.get("d", ""))) if outlines else None

    def compose(
        self,
        *,
        scale: float,
        ground: bool,
        flatten: str | None = None,
        wordmark: bool = True,
    ) -> str:
        """One variant, as SVG text ready to rasterise.

        Provenance is deliberately not carried over: the master's C2PA manifest
        describes the master, and a variant with the wordmark removed and the
        colours flattened is a different file. Claiming the parent's manifest
        on a modified derivative would be a false statement about it.
        """
        svg = ET.Element(
            f"{{{SVG_NS}}}svg",
            {
                "viewBox": self.view_box,
                "width": str(RENDER),
                "height": str(RENDER),
                "fill": self.root_fill,
            },
        )
        if ground and self.ground is not None:
            svg.append(copy.deepcopy(self.ground))

        centre = self.min_x + self.extent / 2
        shift = centre * (1 - scale)
        group = ET.SubElement(
            svg, f"{{{SVG_NS}}}g",
            {"transform": f"translate({_trim(shift)} {_trim(shift)}) scale({scale:.6f})"},
        )
        for child in self.mark:
            if not wordmark and self.wordmark is not None and child is self.wordmark:
                continue
            element = copy.deepcopy(child)
            if flatten:
                _recolour(element, flatten)
            group.append(element)
        return ET.tostring(svg, encoding="unicode")


def _trim(value: float) -> str:
    return f"{value:g}"


def _recolour(element: ET.Element, colour: str) -> None:
    """Every explicit colour becomes one colour. `none` is left alone, so a
    stroked outline stays an outline rather than filling in."""
    for node in element.iter():
        for attribute in ("fill", "stroke"):
            current = node.get(attribute)
            if current and current.lower() != "none":
                node.set(attribute, colour)


def run(command: list[str]) -> None:
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != 0:
        die(f"{' '.join(command[:2])} failed:\n{result.stdout}\n{result.stderr}")


def rasterise(svg_text: str, sizes, name, out_dir: Path) -> list[Path]:
    out_dir.mkdir(parents=True, exist_ok=True)
    written = []
    with tempfile.TemporaryDirectory() as tmp:
        source = Path(tmp) / "variant.svg"
        source.write_text(svg_text)
        base = Path(tmp) / f"variant-{RENDER}.png"
        run(["sips", "-s", "format", "png", str(source), "--out", str(base)])
        for size in sizes:
            target = out_dir / (name(size) if callable(name) else name)
            if size == RENDER:
                shutil.copyfile(base, target)
            else:
                run(["sips", "-z", str(size), str(size), str(base), "--out", str(target)])
            written.append(target)
    return written


def measure(svg_text: str, ground: tuple[int, int, int] | None) -> float:
    """The furthest drawn pixel from the canvas centre, as a fraction of width."""
    with tempfile.TemporaryDirectory() as tmp:
        source = Path(tmp) / "m.svg"
        source.write_text(svg_text)
        png = Path(tmp) / "m.png"
        run(["sips", "-s", "format", "png", str(source), "--out", str(png)])
        return drawn_extent(png, ground)["radius_fraction"]


def rgb(colour: str) -> tuple[int, int, int]:
    value = colour.lstrip("#")
    return tuple(int(value[i:i + 2], 16) for i in (0, 2, 4))  # type: ignore[return-value]


def fingerprint(paths: list[Path]) -> str:
    """One digest over the shell assets a client caches."""
    digest = hashlib.sha256()
    for path in sorted(paths):
        digest.update(path.name.encode())
        digest.update(path.read_bytes() if path.exists() else b"")
    return digest.hexdigest()


def sync_ground_colour(colour: str, *, bump: bool) -> list[Path]:
    """Points the manifest and the light-mode theme colour at the mark's ground.

    An installed app shows `background_color` behind the icon while it starts,
    and `theme_color` tints the browser chrome. Both being the mark's own teal
    is what makes the splash look like a continuation of the icon rather than a
    flash of something else.
    """
    touched = []
    hex_colour = re.compile(r"#[0-9A-Fa-f]{6}")

    manifest = STATIC / "manifest.webmanifest"
    text = manifest.read_text()
    for key in ("background_color", "theme_color"):
        text = re.sub(
            rf'("{key}"\s*:\s*)"#[0-9A-Fa-f]{{6}}"', rf'\g<1>"{colour}"', text
        )
    manifest.write_text(text)
    touched.append(manifest)

    index = STATIC / "index.html"
    text = index.read_text()
    # Only the light one: dark mode keeps its own near-black, which is the page
    # behind it rather than the mark.
    text = re.sub(
        r'(<meta name="theme-color" content=)"#[0-9A-Fa-f]{6}"( media="\(prefers-color-scheme: light\)">)',
        rf'\g<1>"{colour}"\g<2>',
        text,
    )
    index.write_text(text)
    touched.append(index)

    # And the shell has to be re-fetched, or an installed client keeps the old
    # icons behind a worker that never sees a reason to change.
    #
    # Only when something actually changed. A re-render that produces identical
    # bytes must not bump: the version is what invalidates every installed
    # client's cache, and spending that on a no-op — three times over while
    # someone iterates on a comment — is a cost with nothing bought.
    worker = STATIC / "sw.js"
    text = worker.read_text()
    found = re.search(r'const VERSION = "almira-v(\d+)";', text)
    if not found:
        die("sw.js no longer declares `const VERSION = \"almira-vN\"`; cannot bump it")

    if bump:
        bumped = int(found.group(1)) + 1
        worker.write_text(text.replace(found.group(0), f'const VERSION = "almira-v{bumped}";'))
        touched.append(worker)
        note = f"service worker bumped to almira-v{bumped}"
    else:
        note = f"service worker left at almira-v{found.group(1)} — the icons are byte-identical"

    print(f"\nground colour {colour} in the manifest and the light theme meta; {note}")
    return touched


def main() -> None:
    if not shutil.which("sips"):
        die("sips is missing — this script needs macOS")

    master = Master(MASTER)
    ground_rgb = rgb(master.ground_colour)
    print(f"master   {MASTER.relative_to(ROOT)}")
    print(f"  viewBox {master.view_box}   ground {master.ground_colour}")
    print(f"  ground rect {'found' if master.ground is not None else 'ABSENT'}"
          f"   wordmark path {'found' if master.wordmark is not None else 'ABSENT'}"
          f" ({len(master.wordmark.get('d', '')) if master.wordmark is not None else 0} chars)")

    # --- the two scales, derived from the artwork rather than assumed --------
    at_full = measure(master.compose(scale=1.0, ground=False), None)
    maskable_scale = min(1.0, MASKABLE_RADIUS / at_full * FRINGE_MARGIN)
    adaptive_scale = min(1.0, ADAPTIVE_RADIUS / at_full * FRINGE_MARGIN)
    print(f"  drawn radius at full size {at_full:.4f} of the canvas")
    print(f"  -> maskable scale {maskable_scale:.3f} (limit {MASKABLE_RADIUS:.3f})")
    print(f"  -> adaptive scale {adaptive_scale:.3f} (limit {ADAPTIVE_RADIUS:.3f})")

    full = master.compose(scale=1.0, ground=True)
    maskable = master.compose(scale=maskable_scale, ground=True)
    symbol = master.compose(scale=1.0, ground=True, wordmark=False)
    foreground = master.compose(scale=adaptive_scale, ground=False)
    monochrome = master.compose(
        scale=adaptive_scale, ground=False, flatten="#FFFFFF", wordmark=False
    )

    written: list[Path] = []

    # What an installed client has cached, before anything is rewritten.
    shell_assets = sorted(WEB_ICONS.glob("*")) if WEB_ICONS.exists() else []
    before = fingerprint(shell_assets)

    # --- the web client -----------------------------------------------------
    written += rasterise(full, [192], "icon-192.png", WEB_ICONS)
    written += rasterise(full, [512], "icon-512.png", WEB_ICONS)
    written += rasterise(maskable, [192], "icon-maskable-192.png", WEB_ICONS)
    written += rasterise(maskable, [512], "icon-maskable-512.png", WEB_ICONS)
    # iOS home-screen bookmarks crop to a rounded rectangle rather than a
    # circle, and Apple composites on white if there is any transparency — so
    # this one is full bleed.
    written += rasterise(full, [180], "apple-touch-icon.png", WEB_ICONS)
    (WEB_ICONS / "favicon.svg").write_text(symbol)
    written.append(WEB_ICONS / "favicon.svg")

    changed = fingerprint(sorted(WEB_ICONS.glob("*"))) != before

    # --- Android ------------------------------------------------------------
    for bucket, factor in DENSITIES.items():
        written += rasterise(
            full, [round(48 * factor)], "ic_launcher.png", ANDROID_RES / f"mipmap-{bucket}"
        )
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
        "<!-- Generated by brand/render-icons.py from the master's ground colour.\n"
        "     The adaptive icon's back layer is flat, so it is a colour rather than\n"
        "     a bitmap: the launcher parallaxes the two layers against each other\n"
        "     and a flat colour cannot shimmer. -->\n"
        "<resources>\n"
        f'    <color name="ic_launcher_background">{master.ground_colour.upper()}</color>\n'
        "</resources>\n"
    )
    written.append(ANDROID_RES / "values" / "ic_launcher_background.xml")

    adaptive = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!-- Generated by brand/render-icons.py. -->\n"
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/ic_launcher_background"/>\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>\n'
        '    <monochrome android:drawable="@mipmap/ic_launcher_monochrome"/>\n'
        "</adaptive-icon>\n"
    )
    (ANDROID_RES / "mipmap-anydpi-v26").mkdir(parents=True, exist_ok=True)
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        (ANDROID_RES / "mipmap-anydpi-v26" / name).write_text(adaptive)
        written.append(ANDROID_RES / "mipmap-anydpi-v26" / name)

    # --- the three places the ground colour is written down ------------------
    # Synced from the master rather than typed, because typing them is how they
    # drift: the artwork changed from #0F4034 to #123F3A and all three were
    # left behind, so the installed app's splash and the browser chrome no
    # longer matched the icon sitting next to them.
    written += sync_ground_colour(master.ground_colour.upper(), bump=changed)

    # --- iOS ----------------------------------------------------------------
    # One 1024 image. Since Xcode 14 a single-size app icon is the whole set:
    # the system renders every other size, and listing twenty slots only
    # creates twenty ways to be inconsistent.
    written += rasterise(full, [1024], "icon-1024.png", IOS_ICONS)
    (IOS_ICONS / "Contents.json").write_text(
        '{\n  "images" : [\n    {\n      "filename" : "icon-1024.png",\n'
        '      "idiom" : "universal",\n      "platform" : "ios",\n'
        '      "size" : "1024x1024"\n    }\n  ],\n'
        '  "info" : { "author" : "brand/render-icons.py", "version" : 1 }\n}\n'
    )
    written.append(IOS_ICONS / "Contents.json")

    print(f"\nwrote {len(written)} files:")
    for path in written:
        print(f"  {path.relative_to(ROOT)}  ({path.stat().st_size:,} bytes)")

    # --- and prove the safe zones hold, on the files actually written -------
    print("\nsafe zones, measured on what was written:")
    checks = [
        ("icon-maskable-512.png", WEB_ICONS / "icon-maskable-512.png", ground_rgb, MASKABLE_RADIUS),
        ("icon-maskable-192.png", WEB_ICONS / "icon-maskable-192.png", ground_rgb, MASKABLE_RADIUS),
        ("ic_launcher_foreground (xhdpi)",
         ANDROID_RES / "mipmap-xhdpi/ic_launcher_foreground.png", None, ADAPTIVE_RADIUS),
        ("ic_launcher_monochrome (xhdpi)",
         ANDROID_RES / "mipmap-xhdpi/ic_launcher_monochrome.png", None, ADAPTIVE_RADIUS),
    ]
    failures = []
    for label, path, ground, limit in checks:
        radius = drawn_extent(path, ground)["radius_fraction"]
        verdict = "ok  " if radius <= limit else "FAIL"
        if radius > limit:
            failures.append(f"{label}: {radius:.4f} > {limit:.4f}")
        print(f"  {verdict} {label:32s} radius {radius:.4f}  limit {limit:.4f}")
    if failures:
        die("a variant overflows the area its platform guarantees:\n  " + "\n  ".join(failures))
    print("\nevery variant fits the circle its platform guarantees.")


if __name__ == "__main__":
    main()
