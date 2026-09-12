# The Almira mark

`almira-mark.svg` is the master. Every icon in the repository is rendered from
it, so changing the mark is one edit and one command:

```bash
python3 brand/render-icons.py
```

That writes 25 files and prints each one. All of them are committed, so no build
ever depends on the script running.

## What it renders, and why there are four variants

A square logo cannot be dropped into a round hole, and each platform crops
differently. Each variant exists because of a specific crop, not for taste.

| Variant | Scale | Ground | Used for |
|---|---|---|---|
| full | 100% | teal | web `purpose: any`, `apple-touch-icon`, the favicon, the iOS app icon |
| safe | 80% | teal | web `purpose: maskable` |
| foreground | 60% | transparent | Android adaptive icon, front layer |
| monochrome | 60% | transparent, one colour, no wordmark | Android 13 themed icons |
| symbol | 100% | teal, no wordmark | the favicon and the web shell's mark |

**80% for maskable** because that is the whole guarantee: a circle of 80%
diameter survives. At full bleed a round mask shaves the dome and the plinth.

**60% for Android**, not the 66.7% the safe *square* implies, and the
difference is the plinth. The mark's drawn extent is x 120–904, y 87–958 in
master units, so its centre is (512, 522) and the furthest drawn point from
that centre is the outside of a plinth end-cap — 538 units to the cap's centre
plus its 34-unit radius, 572 in all, or 0.558 of the canvas. Android guarantees
a circle of radius 0.333. 0.333 / 0.558 = 0.597. At 0.667 a round launcher
takes the ends off the plinth, which shows, because the plinth is the one
element that reads as a straight line.

**No wordmark on the monochrome.** A themed icon is a silhouette in one flat
colour. Six serif letters at launcher size, with no band behind them to sit on,
are mud; the arch and the keyhole say the same thing legibly.

**No wordmark below about 48px either.** Rendered at 16, 22 and 32 beside the
full lockup, the wordmark is a grey smear at all three while the symbol stays
clean — so in a browser tab and in the 22px shell mark the letters are not a
wordmark, they are dirt. This is the ordinary logomark-versus-lockup
distinction, drawn where it is measurable rather than where it is fashionable.
Everything 180px and up — `apple-touch-icon`, both PWA sizes, both app icons —
gets the full lockup.

**One size for iOS.** Since Xcode 14 a single 1024×1024 image is the whole app
icon set — the system renders every other size. Listing twenty slots would only
create twenty ways to be inconsistent.

## Rasterising

With `sips`, which is part of macOS. There is no Pillow, ImageMagick or rsvg on
this machine and installing one to render an icon is a poor trade — the same
call `scripts/make-icons.py` made before this replaced it.

Each variant is rendered once at 1024 and downsampled to every size it is
needed at, rather than rendered at each size directly: at 48px the rasteriser
cannot resolve the wordmark at all, and downsampling antialiases instead.

## One thing to know before editing

The wordmark is **live text** in Georgia, not outlines. That keeps the master
editable, and the rendered PNGs are committed so the shipped icons never depend
on a font being present. But a re-render on a machine without Georgia will pick
the next serif in the stack and the letterforms will shift slightly. If the mark
ever needs to be exactly reproducible anywhere, convert the `<text>` element to
paths — at which point it stops being editable as text, which is why it has not
been done yet.
