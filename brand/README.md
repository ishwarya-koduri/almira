# The Almira mark

`almira-mark.svg` is the master — the supplied artwork, with its C2PA
provenance manifest left intact. Every icon in the repository is rendered from
it, so changing the mark is one edit and one command:

```bash
python3 brand/render-icons.py
```

It writes 29 files, prints each one, and then measures what it wrote. All of
them are committed, so no build ever depends on the script running.

| | |
|---|---|
| ground | `#123F3A` |
| cream | `#FAF6F0` |
| gold | `#E0BC7E` |
| viewBox | `0 0 100 100` |
| wordmark | outlined paths — no font is needed to re-render |

## What it renders, and why there are five variants

A square logo cannot be dropped into a round hole. Each platform crops
differently, and each variant exists because of a specific crop rather than for
taste.

| Variant | Scale | Ground | Wordmark | Used for |
|---|---|---|---|---|
| lockup | 100% | teal | yes | **`icon-512.png`, and nothing else** |
| symbol | 100% | teal | no | `icon-192`, `apple-touch-icon`, the Android legacy launcher icon, the iOS app icon, the favicon and the shell mark |
| maskable | **derived** | teal | no | web `purpose: maskable` |
| foreground | **derived** | transparent | no | Android adaptive icon, front layer |
| monochrome | **derived** | transparent, one colour | no | Android 13 themed icons — and the only variant with a shortened seam |

### The wordmark appears in exactly one icon

`icon-512.png` is the size an install dialog and a splash screen use, and the
only one where six serif letters are letters. Everywhere else — including the
iOS app icon and the Android launcher icon, both of which looked acceptable at
a desk and turned out to be an illegible smudge under the shelf on a real
launcher — the mark is symbol-only.

### The two derived scales are measured, not chosen

The mark is rasterised at full size, the furthest drawn pixel from the canvas
centre is found, and the scale is the ratio of the platform's guaranteed radius
to that. Then every icon written is measured again, and the run **fails** if any
of them overflows.

For the current artwork:

```
drawn radius, symbol only, as drawn  0.5618 of the canvas
drawn radius, symbol only, recentred 0.5553   (nudged by +0.000 -0.879 master units)
headroom 0.96 of the guaranteed radius
 -> maskable scale 0.692  (target 0.3840, limit 0.4000)
 -> adaptive scale 0.576  (target 0.3200, limit 0.3333)
```

**The wordmark is not what sets the radius.** Measured both ways it is 0.5618
either way, because the wordmark sits nowhere near the edge. What sets it is
the plinth: a wide pill at the very bottom whose round end-caps reach x=88,
y=93 of a 100-unit canvas, making them the furthest drawn points from the
centre and the first thing a round mask takes. So dropping the wordmark buys no
room at all, and the clearance has to come from somewhere else.

It comes from two places:

- **`SAFE_HEADROOM`, 0.96.** The art is sized into a circle 4% smaller than the
  one the platform promises. That 4% is geometry — daylight — rather than a
  rounding allowance, which is what the previous version used: a 1%
  `FRINGE_MARGIN` that left the maskable icon at 0.3960 against a 0.4000 limit.
  Compliant, and with almost nothing in hand.
- **Recentring.** The drawn art sits a little low in its own square, so for the
  cropped variants it is nudged up by 0.879 units to put the *art* in the
  middle of the mask rather than the master's canvas. That drops the radius
  from 0.5618 to 0.5553 and buys back most of what the headroom costs, so the
  mark ends up essentially the size it was with four times the clearance
  instead of a fringe allowance.

Before this, the script asserted 80% for maskable because 80% is the guarantee
— but 80% is the guarantee for the *circle*, and square artwork at 80% leaves
its corners outside it. Measured, that icon reached **0.451** against 0.400: a
round mask was shaving the ends off the plinth. It looked fine by eye, which is
exactly why it needed measuring.

### The themed icon gets a shortened seam

Flattened to one colour the door seam runs into the keyhole, and the mark reads
as a plain arch with a bar across it — the lock, which is the whole idea,
disappears. So in that variant alone the seam ends at y=29 instead of its drawn
38. Its 3.75-unit round cap puts it at 32.75; the keyhole circle begins at
45 − 7.6 = 37.4. Four and a half units of clear ground, and the gap does the
work that colour does everywhere else.

Asserted in both directions, because "intended to be scoped" and "is scoped"
are different claims: the themed icon must have exactly **one more** interior
gap down its centre column than the plain foreground, and that extra gap must
sit above the others. Both variants already have gaps below the keyhole — stem
to rail, rail to plinth — so merely checking that a gap exists passes on the
wrong one. That was the first attempt, and it did.

### One size for iOS

Since Xcode 14 a single 1024×1024 image is the whole app icon set — the system
renders every other size. Listing twenty slots would only create twenty ways to
be inconsistent.

## What else the script owns

**The ground colour, in the three places it is written down** — the manifest's
`background_color` and `theme_color`, and the light-mode `theme-color` meta tag.
Synced from the master rather than typed, because typing them is how they drift:
when the artwork moved from `#0F4034` to `#123F3A`, all three were left behind
and the installed app's splash no longer matched the icon in front of it.

**The service-worker version** — bumped, because the shell is cached cache-first
and an installed client otherwise keeps the old icons behind a worker with no
reason to change. Bumped **only when the icon bytes actually differ**: the
version is what invalidates every client's cache, and spending that on a no-op
re-render buys nothing.

## How the master is taken apart

Two elements have to be told apart from the rest, and both are found by shape
rather than by name, so replacing the artwork does not mean editing the script:

- **the ground** — the one `rect` covering the whole viewBox. Its fill is the
  brand colour, and it is dropped for the variants that need transparency.
- **the wordmark** — the one `path` whose `d` runs to thousands of characters,
  because it is six glyphs as outlines while everything else is a stroke of
  four or five points.

An explicit `id="ground"` or `id="wordmark"` on the master overrides both, for
the day the artwork stops being shaped like this. The script prints which it
found on every run.

**Provenance is deliberately not carried into the derived files.** The master's
C2PA manifest describes the master; a variant with the wordmark removed and the
colours flattened is a different file, and claiming the parent's manifest on it
would be a false statement about it. The master keeps its manifest; `favicon.svg`
does not, which is also why it is 744 bytes rather than 28 KB.

## Rasterising

With `sips`, which is part of macOS. There is no Pillow, ImageMagick or rsvg
here and installing one to render an icon is a poor trade — the same call
`scripts/make-icons.py` made before this replaced it. That file is now a stub
that exits non-zero, because running its old contents would overwrite these with
the placeholder it used to draw.

Each variant renders once at 1024 and downsamples to every size it is needed at,
rather than rendering at each size directly: at 48px the rasteriser cannot
resolve the wordmark at all, and downsampling antialiases instead.

`pngprobe.py` is a small PNG reader — zlib-inflated scanlines and the five row
filters — written because the safe-zone claims above are worth measuring and
there is no image library to measure them with.
