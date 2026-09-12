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
from pngprobe import centre_column_gaps, drawn_extent  # noqa: E402

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

# How much of the guaranteed radius to actually use. The remaining 4% is
# clearance, and it is geometry rather than a rasteriser fudge: the art is
# sized to sit inside a circle smaller than the one the platform promises, so
# the antialiased edge of a rounded cap — about a pixel at any output size — is
# covered by daylight instead of by a rounding allowance.
#
# The previous version of this file leant on a 1% fringe margin instead, which
# left the maskable icon measuring 0.3960 against a 0.4000 limit: compliant,
# and with almost nothing in hand.
SAFE_HEADROOM = 0.96

# In the themed icon only, the door seam stops here instead of at its drawn
# end. Flattened to one colour the seam runs into the keyhole and the mark
# reads as a plain arch with a bar across it — the lock, which is the whole
# idea, disappears. Ending the seam at 29 leaves the gap below to do the work
# that colour does everywhere else.
#
# 29 plus the seam's own 3.75-unit round cap is 32.75; the keyhole circle
# begins at 45 - 7.6 = 37.4. Four and a half units of clear ground, asserted
# after rendering rather than assumed.
MONOCHROME_SEAM_END = 29.0

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
        self.seam = self._find_seam(children)
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

    def _find_seam(self, children: list[ET.Element]) -> ET.Element | None:
        """The door seam: the one path that is a single vertical line.

        Found by shape, like the others. Nothing else in the mark is one — the
        rail and the plinth are horizontal, the arch is a long curve, the
        keyhole stem is a closed polygon and the wordmark is outlines — so
        `M x y V y2` identifies it without an id and without a name.
        """
        for child in children:
            if child.get("id") == "seam":
                return child
        for child in children:
            if tag_of(child) != "path":
                continue
            if SEAM_SHAPE.match(child.get("d", "")):
                return child
        return None

    def compose(
        self,
        *,
        scale: float,
        ground: bool,
        flatten: str | None = None,
        wordmark: bool = True,
        seam_end: float | None = None,
        shift: tuple[float, float] = (0.0, 0.0),
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

        # Scale about the canvas centre, after an optional nudge that puts the
        # *drawn* art in the middle rather than the master's canvas. For a mask
        # what matters is being centred in the hole, and the art sits a little
        # low in its own square — so this buys back most of what the headroom
        # above costs, instead of simply shrinking the mark.
        centre = self.min_x + self.extent / 2
        offset_x = centre * (1 - scale) + scale * shift[0]
        offset_y = centre * (1 - scale) + scale * shift[1]
        group = ET.SubElement(
            svg, f"{{{SVG_NS}}}g",
            {"transform": f"translate({offset_x:.5f} {offset_y:.5f}) scale({scale:.6f})"},
        )
        for child in self.mark:
            if not wordmark and self.wordmark is not None and child is self.wordmark:
                continue
            element = copy.deepcopy(child)
            if seam_end is not None and self.seam is not None and child is self.seam:
                _shorten_seam(element, seam_end)
            if flatten:
                _recolour(element, flatten)
            group.append(element)
        return ET.tostring(svg, encoding="unicode")


def _trim(value: float) -> str:
    return f"{value:g}"


SEAM_SHAPE = re.compile(r"^\s*M\s*([\d.]+)[\s,]+([\d.]+)\s*V\s*([\d.]+)\s*$")


def _shorten_seam(element: ET.Element, end: float) -> None:
    """Rewrites the seam's end point, leaving everything else about it alone."""
    found = SEAM_SHAPE.match(element.get("d", ""))
    if not found:
        die("the seam stopped looking like a vertical line; cannot shorten it")
    x, y = found.group(1), found.group(2)
    element.set("d", f"M{x} {y} V{_trim(end)}")


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


def extent_of(svg_text: str, ground: tuple[int, int, int] | None) -> dict:
    """Rasterises a variant and measures what it actually drew."""
    with tempfile.TemporaryDirectory() as tmp:
        source = Path(tmp) / "m.svg"
        source.write_text(svg_text)
        png = Path(tmp) / "m.png"
        run(["sips", "-s", "format", "png", str(source), "--out", str(png)])
        return drawn_extent(png, ground)


def measure(svg_text: str, ground: tuple[int, int, int] | None) -> float:
    """The furthest drawn pixel from the canvas centre, as a fraction of width."""
    return extent_of(svg_text, ground)["radius_fraction"]


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

    # --- the two scales, derived from the art that is actually used ---------
    #
    # From the symbol, because every cropped icon is now symbol-only. Worth
    # saying that this changed nothing: measured both ways the radius is
    # 0.5618, because the wordmark is nowhere near the edge. What sets it is
    # the plinth — a wide pill at the very bottom whose round end-caps reach
    # x=88, y=93 of a 100 canvas — so dropping the wordmark buys no room at
    # all, and the headroom below had to come from somewhere else.
    symbol_art = master.compose(scale=1.0, ground=False, wordmark=False)
    at_full = measure(symbol_art, None)

    # Where the drawn art actually sits in its square, so it can be centred in
    # the mask rather than in the master's canvas.
    box = extent_of(symbol_art, None)["box_fraction"]
    shift = (
        (0.5 - (box[0] + box[2]) / 2) * master.extent,
        (0.5 - (box[1] + box[3]) / 2) * master.extent,
    )
    at_centred = measure(
        master.compose(scale=1.0, ground=False, wordmark=False, shift=shift), None
    )

    maskable_scale = min(1.0, MASKABLE_RADIUS * SAFE_HEADROOM / at_centred)
    adaptive_scale = min(1.0, ADAPTIVE_RADIUS * SAFE_HEADROOM / at_centred)
    print(f"  drawn radius, symbol only, as drawn  {at_full:.4f} of the canvas")
    print(f"  drawn radius, symbol only, recentred {at_centred:.4f}"
          f"   (nudged by {shift[0]:+.3f} {shift[1]:+.3f} master units)")
    print(f"  headroom {SAFE_HEADROOM:.2f} of the guaranteed radius")
    print(f"  -> maskable scale {maskable_scale:.3f}"
          f"  (target {MASKABLE_RADIUS * SAFE_HEADROOM:.4f}, limit {MASKABLE_RADIUS:.4f})")
    print(f"  -> adaptive scale {adaptive_scale:.3f}"
          f"  (target {ADAPTIVE_RADIUS * SAFE_HEADROOM:.4f}, limit {ADAPTIVE_RADIUS:.4f})")

    # The lockup survives in exactly one place: icon-512, which is the size an
    # install dialog and a splash screen use, and the only one where six serif
    # letters are letters rather than a grey smudge. Everywhere else — the
    # 192, the maskables, apple-touch, both launcher icons, the favicon and the
    # shell mark — is symbol-only.
    lockup = master.compose(scale=1.0, ground=True)
    symbol = master.compose(scale=1.0, ground=True, wordmark=False)
    maskable = master.compose(scale=maskable_scale, ground=True, wordmark=False, shift=shift)
    foreground = master.compose(
        scale=adaptive_scale, ground=False, wordmark=False, shift=shift
    )
    monochrome = master.compose(
        scale=adaptive_scale, ground=False, flatten="#FFFFFF", wordmark=False,
        shift=shift, seam_end=MONOCHROME_SEAM_END,
    )

    written: list[Path] = []

    # What an installed client has cached, before anything is rewritten.
    shell_assets = sorted(WEB_ICONS.glob("*")) if WEB_ICONS.exists() else []
    before = fingerprint(shell_assets)

    # --- the web client -----------------------------------------------------
    written += rasterise(symbol, [192], "icon-192.png", WEB_ICONS)
    written += rasterise(lockup, [512], "icon-512.png", WEB_ICONS)
    written += rasterise(maskable, [192], "icon-maskable-192.png", WEB_ICONS)
    written += rasterise(maskable, [512], "icon-maskable-512.png", WEB_ICONS)
    # iOS home-screen bookmarks crop to a rounded rectangle rather than a
    # circle, and Apple composites on white if there is any transparency — so
    # this one is full bleed.
    written += rasterise(symbol, [180], "apple-touch-icon.png", WEB_ICONS)
    (WEB_ICONS / "favicon.svg").write_text(symbol)
    written.append(WEB_ICONS / "favicon.svg")

    changed = fingerprint(sorted(WEB_ICONS.glob("*"))) != before

    # --- Android ------------------------------------------------------------
    for bucket, factor in DENSITIES.items():
        written += rasterise(
            symbol, [round(48 * factor)], "ic_launcher.png", ANDROID_RES / f"mipmap-{bucket}"
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
    written += rasterise(symbol, [1024], "icon-1024.png", IOS_ICONS)
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

    # --- and prove it, on the files actually written ------------------------
    #
    # Every file with a circular guarantee is measured, not a sample of them:
    # the densities are separate renders and a downsample is where a rounded
    # cap grows a pixel.
    print("\nsafe zones, measured on every file that has one:")
    limited: list[tuple[str, Path, tuple[int, int, int] | None, float]] = [
        ("icon-maskable-192.png", WEB_ICONS / "icon-maskable-192.png", ground_rgb, MASKABLE_RADIUS),
        ("icon-maskable-512.png", WEB_ICONS / "icon-maskable-512.png", ground_rgb, MASKABLE_RADIUS),
    ]
    for bucket in DENSITIES:
        for layer in ("foreground", "monochrome"):
            limited.append((
                f"ic_launcher_{layer} ({bucket})",
                ANDROID_RES / f"mipmap-{bucket}/ic_launcher_{layer}.png",
                None,
                ADAPTIVE_RADIUS,
            ))

    failures: list[str] = []
    for label, path, ground, limit in limited:
        radius = drawn_extent(path, ground)["radius_fraction"]
        headroom = (limit - radius) / limit
        ok = radius <= limit
        if not ok:
            failures.append(f"{label}: radius {radius:.4f} > limit {limit:.4f}")
        print(f"  {'ok  ' if ok else 'FAIL'} {label:34s} radius {radius:.4f}"
              f"  limit {limit:.4f}  ({headroom * 100:+.1f}% in hand)")

    # --- and that the lock survives being flattened -------------------------
    #
    # In one colour the seam runs into the keyhole and the mark reads as an
    # arch with a bar. The seam is shortened for that variant alone, so the
    # check has two halves: the gap is there in the themed icon, and it is
    # *not* there anywhere else — which is what makes the change scoped rather
    # than merely intended.
    print("\nthe keyhole gap, down the centre column:")
    themed = ANDROID_RES / "mipmap-xxxhdpi/ic_launcher_monochrome.png"
    plain = ANDROID_RES / "mipmap-xxxhdpi/ic_launcher_foreground.png"

    themed_gaps = centre_column_gaps(themed, None)
    plain_gaps = centre_column_gaps(plain, None)

    # Both variants already have gaps below the keyhole — one between its stem
    # and the rail, one between the rail and the plinth. So the discriminator
    # is not "does it have a gap", which was the first attempt and passed on
    # the wrong gap: it is that the themed icon has exactly one *more* gap than
    # the plain one, and that the extra one is above the rest.
    for label, gaps in (("themed  ", themed_gaps), ("plain fg", plain_gaps)):
        rendered = ", ".join(f"{(b - a) * 100:.2f}% at y={a:.3f}" for a, b in gaps) or "none"
        print(f"  {label} {len(gaps)} interior gap(s): {rendered}")

    # From the geometry: 37.4 - (29 + 3.75) = 4.65 master units, scaled, over a
    # 100-unit canvas.
    expected = (37.4 - (MONOCHROME_SEAM_END + 3.75)) / 100 * adaptive_scale
    opened = (themed_gaps[0][1] - themed_gaps[0][0]) if themed_gaps else 0.0
    print(f"  the gap the shortening opens: {opened * 100:.2f}% of height"
          f"   (geometry predicts {expected * 100:.2f}%)")

    if len(themed_gaps) != len(plain_gaps) + 1:
        failures.append(
            f"the themed icon has {len(themed_gaps)} interior gaps and the plain foreground "
            f"{len(plain_gaps)}; shortening the seam should open exactly one more"
        )
    elif opened < expected * 0.6:
        failures.append(
            f"the gap above the keyhole is {opened * 100:.2f}% of height against "
            f"{expected * 100:.2f}% predicted — the lock has merged into the seam"
        )
    elif plain_gaps and themed_gaps[0][0] >= plain_gaps[0][0]:
        failures.append(
            "the themed icon's extra gap is not above the plain one's first — the seam "
            "shortening has landed somewhere other than between the seam and the keyhole"
        )

    if failures:
        die("measured on the rendered files:\n  " + "\n  ".join(failures))
    print("\nevery variant fits the circle its platform guarantees,"
          " and the lock survives the flattening.")


if __name__ == "__main__":
    main()
