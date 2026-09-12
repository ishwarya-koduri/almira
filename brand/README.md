# The Almira mark

`almira-mark.svg` is the master — the supplied artwork, with its C2PA
provenance manifest left intact. Every icon in the repository is rendered from
it, so changing the mark is one edit and one command:

```bash
python3 brand/render-icons.py
```

It writes 26 files, prints each one, and then measures what it wrote. All of
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

| Variant | Scale | Ground | Used for |
|---|---|---|---|
| full | 100% | teal | web `purpose: any`, `apple-touch-icon`, the iOS app icon |
| maskable | **derived** | teal | web `purpose: maskable` |
| foreground | **derived** | transparent | Android adaptive icon, front layer |
| monochrome | **derived** | transparent, one colour, no wordmark | Android 13 themed icons |
| symbol | 100% | teal, no wordmark | the favicon and the web shell's 22px mark |

### The two derived scales are measured, not chosen

The mark is rasterised at full size, the furthest drawn pixel from the canvas
centre is found, and the scale is the ratio of the platform's guaranteed radius
to that. Then every icon written is measured again, and the run **fails** if any
of them overflows.

For the current artwork:

```
drawn radius at full size 0.5618 of the canvas
 -> maskable scale 0.705 (limit 0.400)
 -> adaptive scale 0.587 (limit 0.333)
```

This is not ceremony. The previous version of this script asserted 80% for
maskable, because 80% is the guarantee — but 80% is the guarantee for the
*circle*, and scaling square artwork to 80% leaves its corners outside it.
Measured, that icon reached **0.451** against a 0.400 limit, so a round mask was
shaving the ends off the plinth. It looked fine by eye, which is exactly why it
needed measuring. The plinth is what drives both numbers: it is a wide pill at
the very bottom, so its end-caps are the furthest drawn points from the centre
and the first thing a round mask takes.

The `FRINGE_MARGIN` of 1% is for the antialiased edge, not the geometry. A
rounded stroke cap fades out over about a pixel at any output size and the
measurement counts a pixel as drawn once it is roughly a tenth ink, so a mark
sized to exactly the limit measures a hair over — 0.4001 against 0.4000 at
512px, seen and dealt with rather than rounded away.

### Two variants drop the wordmark

**The themed icon**, because a themed icon is a silhouette in one flat colour:
the letters have no band behind them to sit on and become mud at launcher size.

**The symbol**, for the favicon and the 22px shell mark. Rendered at 16, 22 and
32 beside the full lockup, the wordmark is a grey smear at all three while the
symbol stays clean — below roughly 48px the letters are not a wordmark, they are
dirt. Everything 180px and up gets the full lockup, so the two app icons are
identical.

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
