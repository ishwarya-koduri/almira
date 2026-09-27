# The Almira mark

`almira-mark.svg` is the master: a lock plate off an almirah door, centred with
clear ground all round. Every icon in the repository is rendered from it, so
changing the mark is one edit and one command:

```bash
python3 brand/render-icons.py
```

It writes 29 files, prints each one, and then measures what it wrote. All of
them are committed, so no build ever depends on the script running.

| | |
|---|---|
| ground | `#14685C` |
| brass, the plate | `#E0BC7E` |
| lock, through the plate | `#123F3A` |
| cream, the wordmark | `#FAF6F0` |
| viewBox | `0 0 100 100` |
| the plate | `31..69` by `25..75`, corner `9` — 38 by 50 of the canvas |
| the keyhole | circle at `(50, 43)` r `7.5`, stem to `63` |
| wordmark | outlined paths — no font is needed to re-render |

The keyhole is **cut through** the plate with `fill-rule="evenodd"`, and the
colour behind it is a separate `id="lock"` path in the same outline. Two
reasons, and neither is decoration. The lock stays the darkest thing in the
mark however light the ground gets, which is what keeps it legible: measured
against the brass, the plate holds 3.7:1 and the keyhole 6.5:1. And a hole
survives being flattened to one colour, where a keyhole painted on the plate
would vanish into it — see the themed icon below.

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
| monochrome | **derived** | transparent, one colour | no | Android 13 themed icons — and the only variant that drops the lock, so the hole shows |

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
drawn radius, symbol only, as drawn  0.2790 of the canvas
drawn radius, symbol only, recentred 0.2790   (nudged by +0.000 +0.000 master units)
headroom 0.96 of the guaranteed radius
 -> maskable scale 1.000  (target 0.3840, limit 0.4000)
 -> adaptive scale 1.000  (target 0.3200, limit 0.3333)
```

**Both scales come out at 1.0, and that is the mark rather than a coincidence.**
The plate reaches 0.2790 of the canvas from the centre, inside the 0.3333 an
adaptive icon guarantees and well inside the 0.4000 a maskable one does, so
nothing has to be shrunk to survive a crop and nothing has to be nudged to sit
in the middle of one. The art is already centred and already small.

The machinery is kept, because it is what would catch the mark growing back:
`SAFE_HEADROOM` still sizes into a circle 4% smaller than the platform's
promise, the recentring nudge is still computed, and every written file is
still measured with the run failing on overflow. On this artwork both come out
as no-ops, and the numbers above are how you would know if that stopped being
true.

#### What the measuring caught on the mark this replaced

Worth keeping, because it is why the script measures at all.

The previous artwork — an arch to the edges of the tile, a rail across it and a
plinth underneath — measured **0.5618**, and had to be scaled to 0.576 for the
adaptive layer and 0.692 for the maskable one. That is the arithmetic behind
what it looked like on a phone: a mark drawn to its own edges has to be shrunk
to survive the crop, so it ends up crowded *and* small.

Before that, the script asserted 80% for maskable because 80% is the guarantee
— but 80% is the guarantee for the *circle*, and square artwork at 80% leaves
its corners outside it. Measured, that icon reached **0.451** against 0.400: a
round mask was shaving the ends off the plinth. It looked fine by eye, which is
exactly why it needed measuring. The fix was `SAFE_HEADROOM` at 0.96 in place
of a 1% fringe allowance, plus recentring the drawn art in the mask rather than
in the master's canvas, which on that mark was worth 0.5618 → 0.5553.

### The themed icon drops the lock

A themed icon is a silhouette: one flat colour, no ground. Flatten this mark
naively and the plate and the keyhole become the same white, so the icon is a
blank brass tablet with the whole idea missing from it.

So the keyhole is a hole in the plate rather than a shape drawn on it, and the
coloured `id="lock"` path that fills that hole is the one element dropped from
the themed variant. Nothing is redrawn and nothing is special-cased in the
geometry; the hole does the work that colour does everywhere else.

Asserted on the rendered pixels rather than on the SVG, because "the SVG has a
hole in it" and "the PNG Android ships has a hole in it" are different claims.
Down the centre column of the themed icon there must be exactly **one**
interior blank run, it must measure the keyhole's own height — 63 − 35.5 = 27.5
units, scaled — and the coloured foreground, where the lock is drawn in, must
have **none**. The run prints all three:

```
themed   1 interior gap(s): 27.78% at y=0.352
plain fg 0 interior gap(s): none
the hole the flattening leaves: 27.78% of height   (the keyhole is 27.50%)
```

### One size for iOS

Since Xcode 14 a single 1024×1024 image is the whole app icon set — the system
renders every other size. Listing twenty slots would only create twenty ways to
be inconsistent.

## What else the script owns

**The ground colour, in the three places it is written down** — the manifest's
`background_color` and `theme_color`, and the light-mode `theme-color` meta tag.
Synced from the master rather than typed, because typing them is how they drift:
when the artwork moved from `#0F4034` to `#123F3A`, all three were left behind
and the installed app's splash no longer matched the icon in front of it. The
move from `#123F3A` to `#14685C` carried them along on its own, which is the
whole return on that.

Two copies are outside the script and are checked instead of synced:
`site/assets/mark.svg`, which `scripts/check-site.py` requires to be byte
identical to `static/icons/favicon.svg`, and `--mark-ground` in
`site/assets/site.css`. Copy the favicon over the site mark after a render.

**The service-worker version** — bumped, because the shell is cached cache-first
and an installed client otherwise keeps the old icons behind a worker with no
reason to change. Bumped **only when the icon bytes actually differ**: the
version is what invalidates every client's cache, and spending that on a no-op
re-render buys nothing.

## How the master is taken apart

Three elements have to be told apart from the rest:

- **the ground** — the one `rect` covering the whole viewBox. Its fill is the
  brand colour, and it is dropped for the variants that need transparency.
- **the wordmark** — the one `path` whose `d` runs to thousands of characters,
  because it is six glyphs as outlines while everything else is a handful of
  points.
- **the lock** — what shows through the keyhole in colour, dropped from the
  themed variant.

Each is found by `id` first, and the master names all three. The shape
heuristics behind the id are kept for the ground and the wordmark, which are
recognisable without one. The lock is not — it is the same outline as the hole
it fills — so it is named or it is absent, and a master without one renders
every variant as before while the check at the end of the run reports the
flattened keyhole rather than letting it through. The script prints what it
found on every run.

**Provenance is deliberately not carried into the derived files.** A variant
with the wordmark removed and the colours flattened is a different file, and
claiming a parent manifest on it would be a false statement about it. The
master that came before this one carried a C2PA manifest describing its own
bytes; this artwork is drawn here rather than supplied, and the manifest was
dropped with the artwork it described rather than left behind to vouch for
something else.

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
