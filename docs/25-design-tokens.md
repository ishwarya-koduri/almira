[‹ Index](README.md) · [‹ UX & Design System](02-ux-and-design-system.md)

# 25 · Design tokens

Doc 02 says why Almira looks the way it does. This is the reference for what
the values are, where they live, and which rules a change to them has to keep.

**The single source for the web client** is
`backend/src/main/resources/static/app/tokens.css`. No colour, shadow, type
size or duration is written anywhere else: `base.css` and the screens use the
names below. `scripts/check-design-tokens.js` reads both files and fails when a
text pair drops under its contrast ratio, when the device-chosen and
Settings-chosen copies of a theme drift apart, when `base.css` writes a literal
colour or a size under 13px, or when a motion token reaches 200ms.

```sh
/System/Library/Frameworks/JavaScriptCore.framework/Versions/A/Helpers/jsc scripts/check-design-tokens.js
/System/Library/Frameworks/JavaScriptCore.framework/Versions/A/Helpers/jsc -m scripts/check-format.js
```

**The native app** has its own copy in
`app/shared/src/commonMain/kotlin/tech/bhrigu/almira/shared/theme/`
(`AlmiraColors.kt`, `AlmiraTypography.kt`, `AlmiraDimensions.kt`). It has not
been updated to this revision — §9 lists exactly what differs. Until it is, a
value here wins, and a change to either copy is a change to both.

---

## 1. Themes

Three, with the same layout in each. Only tokens change.

| Theme | Chosen by | Text contrast it holds |
|---|---|---|
| Light | Settings → Appearance, or the device in light mode | 4.5:1 |
| Dark | Settings, or the device in dark mode | 4.5:1 |
| Clear | Settings, or the device in light mode asking for more contrast (`prefers-contrast: more`) | 7:1, and a border on every block |

The choice is an attribute on `<html>` (`data-theme="light" | "dark" | "clear"`,
absent for System), set by `app/prefs.js` and, before the first paint, by the
inline script in `index.html`. It is stored per browser, never sent to the
server: how a page looks belongs to the device in someone's hand.

A dark-mode device that asks for more contrast stays dark. Clear is a light
theme, and turning someone's dark screen white is not "more contrast" to them.

## 2. Colour roles

Colour has roles, not moods. Each role is one token, and each token has one
role.

| Role | Token | Light | Dark | Clear | Used for — and never for |
|---|---|---|---|---|---|
| Ground | `canvas` | `#FBF9F5` | `#14130F` | `#FFFFFF` | the page |
| Block | `surface` | `#FFFFFF` | `#1D1B16` | `#FFFFFF` | cards, sheets, inputs |
| Inset | `surface-sunken` | `#F4F1EA` | `#242019` | `#F2F2EF` | tracks, unselected segments |
| Text | `ink` | `#1C1A17` | `#F2EEE4` | `#111111` | body, titles, errors |
| Secondary text | `ink-muted` | `#6B6558` | `#B4AD9C` | `#3D3A34` | **every** caption, notice and helper line |
| Faint | `ink-faint` | `#9A9384` | `#8A8474` | `#5C574C` | dividers, disabled chrome. **Never text** — 2.9:1 in light |
| Divider | `hairline` | `#E7E2D8` | `#332F27` | `#5C574C` | 1px rules between rows |
| Block edge | `block-border` | = `hairline` | = `hairline` | `#111111` | the border around a card, input, chip |
| Action | `accent` | `#0F5A57` | `#3E9E99` | `#0F5A57` | the **one** primary action on a screen, meters, rings, chart lines |
| Action label | `accent-ink` | `#FFFFFF` | `#0B1C1B` | `#FFFFFF` | text on `accent` |
| Action as text | `accent-text` | `#0F5A57` | `#8FD0CB` | `#0B4845` | links, a selected tab or chip's label |
| Selection tint | `accent-soft` | `#DCEBE9` | `#1E3B39` | `#E3EFEE` | behind a selected tab, chip or row |
| The figure | `brass` | `#8A6D10` | `#D8B95A` | `#5E4A00` | the net-worth number, once per screen |
| Decoration | `gold` | `#C9A227` | `#D8B95A` | `#5E4A00` | the hairline under that number, a decorative ring. **Never text** in light — 2.4:1 |
| Owed | `caution` | `#B4520A` | `#E0873F` | `#8A3D00` | money owed, EMIs and dues. **Nothing else** — not errors, not disclaimers, not urgency |
| Owed tint | `caution-soft` | `#FCF5EE` | `#33251A` | `#FFFFFF` | behind an "EMI due" pill |
| Gain | `positive` | `#1B7A43` | `#4FB477` | `#125C31` | a realised gain written as text; never paired with red |
| Focus | `focus` | `#0F5A57` | `#8FD0CB` | `#111111` | the 2px focus ring |
| Toast text | `on-strong` | `#FFFFFF` | `#14130F` | `#FFFFFF` | text on an `ink` ground |
| Scrim | `scrim` | ink at 35% | black at 55% | black at 60% | behind a sheet |

What this replaced, and why:

- The net-worth number was `gold`, 2.42:1 on white — under even the 3:1 that
  large text needs. It is `brass` now (4.9:1), with gold kept as a 48px rule
  beneath it. In dark mode the two are the same bright gold, which is 9:1.
- Captions were `ink-faint` (2.9:1). They are `ink-muted` (5.5:1); hierarchy
  comes from size and weight.
- Disclaimers, tax notes and "informational only" sat in rust boxes. They are
  notices now (§5), and rust means money owed and nothing else.
- The completeness score and goal rings were gold. A score is progress, not
  treasure: rings are `accent`, and a score is set in `ink`.
- `caution-soft` moved from `#FBEFE4` to `#FCF5EE`, because `caution` text on the
  old tint was 4.47:1.
- An error is `ink` with an alert mark and a 2px `ink` edge on the field, not
  rust.

### Measured ratios

`check-design-tokens.js` prints all of them; these are the ones the plan named.

| Pair | Light | Dark | Clear |
|---|---|---|---|
| `ink` on `canvas` | 16.5 | 16.0 | 18.9 |
| `ink-muted` on `canvas` | 5.5 | 8.3 | 11.3 |
| `ink-muted` on `surface-sunken` | 5.1 | 7.3 | 10.1 |
| `accent-ink` on `accent` (primary button) | 8.0 | 5.5 | 8.0 |
| `accent-text` on `accent-soft` (selected) | 6.5 | 6.9 | 8.8 |
| `brass` on `surface` (the figure) | 4.9 | 9.0 | 8.6 |
| `caution` on `surface` (owed) | 5.1 | 6.3 | 7.6 |
| `caution` on `caution-soft` (EMI pill) | 4.7 | 5.4 | 7.6 |

### Category colours

`--cat-gold #C9A227 · deposits #4E7C59 · mutual_funds #3E6B99 · equity #6A5A99 ·
ipo #A6555A · bonds #7A6A55 · retirement #3F8A8A · insurance #2F7F76 ·
real_estate #B06B3A · alternatives #8A7F6A · cash #7C8A6A · universal #6B6558`.

Identical in every theme. They are recognition, never meaning: each appears as
a line icon on a 14% tint of itself (§6) or an 8px dot, always beside the
category's name.

### Member colours

`--member-1 #3E6B99 · 2 #A6555A · 3 #4E7C59 · 4 #6A5A99 · 5 #3F8A8A · 6 #8A7F6A`
(X-54, X-56). Identical in every theme. A member's colour is chosen from their id
(`memberToneIndex` in `glance.js`), not their place on a roster, so Ravi is the
same colour on every list and for every viewer. The colour is only ever a 2px
ring and a 16% tint behind initials written in `ink`; no text is set in it.

## 3. Type

| Family token | Stack | For |
|---|---|---|
| `font-display` | Fraunces → Noto Serif Telugu → Noto Serif Devanagari → Iowan Old Style → Georgia → serif | numbers, titles, amount in words |
| `font-ui` | Inter → Noto Sans Telugu → Noto Sans Devanagari → system UI | everything else |
| `font-mono` | system monospace | a one-time code |

The Latin faces have no Telugu or Devanagari glyphs; the Noto faces follow them
in the stack, so a Telugu word in a Fraunces heading is set in Noto Serif Telugu
rather than whatever the phone substitutes.

**Self-hosted** (P-36). `static/app/fonts/` holds Google Fonts' own subset files
— Fraunces and Inter in Latin and Latin Extended, the four Noto faces in their
script subset — downloaded from `fonts.gstatic.com` (Fraunces v38, Inter v20,
Noto Sans Telugu v30, Noto Serif Telugu v29, Noto Sans Devanagari v30, Noto
Serif Devanagari v34), each a variable file covering weights 400–600. Their SIL
Open Font Licence texts sit beside them. No page requests anything from Google.
`unicode-range` in each `@font-face` means an English reader never downloads a
Telugu or Devanagari file, and a Telugu reader downloads only Telugu. To update
a face, fetch the `css2` API for it with a current browser's user agent, take
the same subsets, and keep the licence.

**Scale.** 13 · 15 · 16 · 19 · 23 · 28 · 40px, in rem:

| Token | rem | px | Use |
|---|---|---|---|
| `text-display` | 2.5 | 40 | the one figure (capped at 10.5vw so it never splits across lines) |
| `text-h1` | 1.75 | 28 | a screen title |
| `text-h2` | 1.4375 | 23 | a section title |
| `text-h3` / `text-lg` | 1.1875 | 19 | a card title, amount in words on a wide screen |
| `text-base` / `text-h4` | 1 | 16 | body — on a phone too |
| `text-sm` | 0.9375 | 15 | labels, buttons, notices |
| `text-caption` / `text-overline` | 0.8125 | 13 | **the floor.** Nothing is smaller |

Line height: `leading-body 1.55`, `leading-tight 1.2`, and `leading-indic 1.7`
wherever the document language is Telugu or Hindi, because their vowel signs
collide at a Latin line height.

**Larger text** (D-04) is a setting of its own — Settings → Text size:
Standard, Larger (×1.1875, 16 → 19px), Largest (×1.375, 16 → 22px). It
multiplies whatever the browser or phone already chose, so it adds to a parent's
system setting rather than replacing it. Tap targets are in rem too, so they grow
with the words. The layouts were checked with the root at 200% at 375px wide:
nothing scrolls sideways; the bottom-tab labels stop growing at the width a
word fits in (never under 13px), and scope switchers scroll within themselves.

## 4. Space, size, shape, motion

- **Space:** `space-1…24` = 4, 8, 12, 16, 20, 24, 32, 40, 48, 64, 96px.
- **Targets:** `target` 2.75rem (44px) for every control — a `.btn-sm` is
  smaller-looking, not smaller to hit; `row-height` 4rem (64px) for a whole
  tappable row and a bottom tab.
- **Radius:** `radius-sm 8` inputs, chips · `radius-md 12` buttons ·
  `radius-lg 16` cards, sheets · `radius-xl 24` the hero · `radius-full`.
- **Elevation:** `e1` and `e2` only; Clear has none, and draws an edge instead.
- **Motion** (X-73): `fast 120ms`, `base 160ms`, `slow 190ms`; `ease`
  `cubic-bezier(.2,0,0,1)`. Motion only confirms that something happened — a
  sheet arriving, a selection moving — and never decorates. Every duration is
  under 200ms. With `prefers-reduced-motion: reduce` the tokens become 0ms and
  `base.css` stops keyframes too. The net-worth count-up and list stagger in
  Doc 02 §5 are not built, and should not be.

## 5. Layout (D-02)

| Width | Shape | Navigation | Grid |
|---|---|---|---|
| under 600px | phone | five bottom tabs; the + floats above their right end | 4 columns |
| 600–1099px | tablet, small laptop | an 88px rail: icons with labels under them, + at the top | 8 columns |
| 1100px and up | desktop | a 224px sidebar: labels beside icons, "+ Add" at the top | 12 columns |

`gutter` 16px, `content-max` 1200px, `reading-max` 720px, `rail-width` 88px,
`sidebar-width` 224px, `panel-width` 480px, `panel-wide` 640px (a record's
detail from 1100px, X-53). A media query cannot read a custom
property, so 600 and 1100 are written as numbers in `base.css` and nowhere else.
Inside `main`, blocks that go two-up (`.grid-2`) decide by the width `main`
actually has (a container query), so the rail never squeezes two cards into a
tablet's remaining width.

**The five destinations** (X-37), identical on every shape:

| Destination | Opens | Also holds |
|---|---|---|
| Home | `#/home` | — |
| Holdings | `#/investments` | `#/liabilities`, `#/accounts` |
| Family plan | `#/continuity` | `#/where`, `#/goals` |
| Reports | `#/reports` | `#/tax` |
| You | `#/settings` | `#/family` |

Every earlier address still works. The destination's own name works too
(`#/holdings`, `#/plan`, `#/you`). Where a destination holds more than one
screen, a single scrolling row of sections sits at the top of the page. "Family"
(the people in the household) is labelled **Household** there, so it is never
read beside "Family plan". The + is always the same control in the same place
and opens capture.

## 6. Blocks (D-06)

Built once, in `ui.js` and `base.css`; a screen composes them rather than
drawing its own.

| Block | Where | Notes |
|---|---|---|
| Hero | `.hero`, `.hero-amount`, `.hero-words`, `.hero-side` | Brass figure, gold rule, amount in words. On a phone the arithmetic (assets, owed, counts) is behind "See the breakdown"; from 600px it is always shown. |
| To review / Next due | a `.card` with a `.list` of `.list-row` | On Home these come straight after the hero, before any breakdown (X-55). |
| Progress ring | `ring(percent, { label, size })` | Teal; `size: "lg"` for a screen's main ring. Always has an accessible label. |
| Readiness ring | `partRing(parts, { center, label })` | One teal ring in four parts, a quarter per check, filled as far as that check is done; a check that does not apply is an empty dashed quarter. Words above the number, never a lone percentage (X-33). |
| Holding row | `.list-row.holding-row` with `categoryIcon()`, `.title`, `.meta` + `.needs`, `avatarStack()`, `.amount`, a privacy `.pill` | 64px, the whole row is the button. One needs-doing line in ink; owners' initials (hidden on a phone); "Only owner", "Household" or "Some people". No amount shows a muted "Add amount", not a dash; no "at cost" on every row (X-54). |
| Person | `avatar(memberId, name, { size, label })`, `avatarStack(owners, label)` | Initials on the member's colour (§2). Decorative beside a written name; given a label when it stands alone. |
| To review | `reviewCard()`, `openReview()` in `review.js` | A count and the first three on Home, then one card at a time with its one or two answers and "Not now". Empty is a sentence: "Nothing waiting. Your family is in good shape." (X-51). |
| Side panel / bottom sheet | `sheet({ title, body, footer, wide })` | A bottom sheet on a phone; from 600px a 480px panel from the right, full height. `wide` makes it 640px from 1100px, for a record's detail (X-53). |
| Last known view | `api.peek(path)`, `updatedNote()` | A revisited screen draws what it showed last, at once, and says "Updated just now" when the quiet refresh lands. Memory only, per person, cleared by any write and on sign-out; never a sealed value (`cache.js`, X-38). |
| Notice | `notice(text)`; `.banner` looks the same | Muted text after an ⓘ mark. `role="alert"` or `{ tone: "alert" }` makes it ink with an alert mark. Never a coloured box (X-52). |
| Chart | `areaTrend(points, { summary })`, `donut(segments, { summary })` | §7 |
| On demand | `onDemand(label, build)` | With Data Saver on, the block waits behind a button (§8). |
| Money | `money(formatted, value)` | §8 |
| Date | `when(iso)` | §8 |

Controls (D-09): `segmented()` for two to four choices; `chipRow()` for filters
— one row that scrolls sideways on a phone and wraps only where there is room;
a search picker for five or more; a sheet for pickers. A destructive action is
`.btn-danger`, which is text-style (ink, underlined), followed by an undo toast
— it never looks like the primary action. Buttons never wrap (X-70); a long
label that will not fit on a phone uses `.btn-collapse` with a `.btn-label` and
an `icon("arrow", "icon.btn-icon")`, and keeps its name as `aria-label`.

## 7. Charts (D-08)

- **Trend:** an area in `chart-area` (accent at 14%) under a 2px `chart-line`,
  three `chart-grid` rules, and one emphasised end point. Only the first and
  last labels on the axis.
- **Share:** a donut with its labels listed beside it — name, then value — not
  a legend to match colours against.
- **Meters:** `accent` on `surface-sunken`.
- Chart text is `chart-text` (`ink-muted`). No red/green pair anywhere; owed
  is `caution` against owned `accent`.
- Every chart takes a one-sentence `summary` as its accessible name, because a
  shape is not a number. The same sentence is written under a trend as a caption.
- `areaTrend(points, { fromZero: false })` fits the line to its own range, for
  a figure whose movement is small beside its size. Its axis still names only
  dates, never a truncated amount.

Where they are drawn (P-15): Home's net worth by month under the hero, from the
month recording began; "Where it sits" as one donut in the category colours; a
holding's recorded values in its detail panel. Each waits behind "Show the
chart" with Data Saver on.

## 8. Money, dates, data

- **Figures** (D-11): the server's formatted string wherever it sent one, in
  Indian grouping. `money()` also renders the short form — ₹42 L, ₹1.25 Cr,
  truncated rather than rounded — which a phone's lists show in place of the
  full figure; a detail view always shows the full figure, and a screen reader
  always hears it. Amount in words sits under the hero in the reader's language
  (the server sends it).
- **Dates:** `when()` writes "in 4 days" with the date after it where there is
  room, and in its accessible name and tooltip always. Days are calendar days,
  so "tomorrow" does not become "today" at 6pm. `format.js` holds these rules
  with no DOM, and `scripts/check-format.js` tests them.
- **Noise rows** (X-71): a breakdown drops a category holding ₹0
  (`withoutZeroRows`), and nothing owed is no row rather than a dash.
- **Data Saver** (X-72): Settings → Data saver is Automatic, On or Off.
  Automatic follows the browser's `Save-Data` signal. When saving, `<html>`
  carries `data-save-data="on"`, the display face falls back to a system serif so
  the Fraunces files are never fetched, blocks built with `onDemand()` wait for a
  tap, and the page says "Light mode for your data" once at the top.

## 9. The native app, against this revision

Not changed in this revision — the `app/` tree is being worked on elsewhere.
To bring `AlmiraColors.kt` and friends in line:

| Native | Now | Should be |
|---|---|---|
| `AlmiraColors` | no `brass`, `accentText`, `focus`, `onStrong`, `blockBorder` | add them with the §2 values |
| `AlmiraLightColors.cautionSoft` | `#FBEFE4` | `#FCF5EE` |
| hero figure colour | `gold` | `brass` |
| a Clear palette | absent | §2 Clear column, plus borders on cards |
| type scale | Doc 02 §3.1 (smallest ≈11.5px) | §3 scale, 13sp floor, scaled by the system font scale and an in-app Larger text |
| fonts | Fraunces, Inter (TTF) | add the four Noto faces for Telugu and Devanagari |
| motion | — | §4 durations, and none when the system's animator scale is 0 |
| navigation | — | §5 destinations: bottom tabs on a phone, a `NavigationRail` from 600dp |

Names map mechanically: `surface-sunken` → `surfaceSunken`, `ink-muted` →
`inkMuted`, and so on.

[‹ Index](README.md) · [‹ UX & Design System](02-ux-and-design-system.md)
