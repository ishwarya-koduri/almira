[‹ Index](README.md) · [‹ Prev: Product & Scope](01-product-and-scope.md) · [Next › Screens & Flows](03-screens-and-flows.md)

# 02 · UX & Design System

> The brief: *so beautiful you can't imagine it — few colors, trust-building, soft and pleasing, with the right fonts, sizes, spacing, and the best interactive controls.* This doc is the single source of truth for how Almira looks, feels, and responds.

## 1. North Star — "a private study, not a trading floor"
Almira should feel like a **quiet, well-made private study**: warm paper, considered typography, generous air, nothing shouting. Money apps earn trust through **restraint and clarity**, not dashboards full of color. Every screen answers one question calmly. The emotional target: *calm, cared-for, in control.* Three adjectives govern every decision — **calm, trustworthy, precise.**

Design tenets:
1. **Neutrals carry the design; color is a scalpel, not a bucket.** One accent, used sparingly, means more than ten.
2. **Typography is the interface.** Numbers are the hero; the type system does the heavy lifting.
3. **Air is a feature.** Generous, consistent spacing signals safety and quality.
4. **Motion is a whisper.** Subtle, physical, never decorative.
5. **Every control feels tactile and obvious.** The best interaction is one you don't have to think about.

## 2. Color — restrained and trust-building
The whole palette is **neutrals + one accent + two semantic hues + a rare gold + a muted category set used only as tiny identifiers.** That's it. This is deliberate: fewer colors read as premium, focused, and timeless.

### 2.1 Core tokens — Light
| Token | Hex | Use |
|---|---|---|
| `canvas` | `#FBF9F5` | app background (warm off-white "paper") |
| `surface` | `#FFFFFF` | cards, sheets |
| `surface-sunken` | `#F4F1EA` | insets, table stripes |
| `ink` | `#1C1A17` | primary text |
| `ink-muted` | `#6B6558` | secondary text |
| `ink-faint` | `#9A9384` | captions, placeholders |
| `hairline` | `#E7E2D8` | 1px borders, dividers |
| `accent` | `#0F5A57` | primary actions, focus, links (deep teal) |
| `accent-soft` | `#DCEBE9` | accent tint (selected rows, focus halo) |
| `positive` | `#1B7A43` | gains, success (used only semantically) |
| `caution` | `#B4520A` | warnings, dues, debt emphasis |
| `gold` | `#C9A227` | reserved for the net-worth hero only |

### 2.2 Core tokens — Dark
| Token | Hex |
|---|---|
| `canvas` | `#14130F` |
| `surface` | `#1D1B16` |
| `surface-sunken` | `#242019` |
| `ink` | `#F2EEE4` |
| `ink-muted` | `#B4AD9C` |
| `hairline` | `#332F27` |
| `accent` | `#3E9E99` (lifted for contrast) |
| `positive` | `#4FB477` · `caution` `#E0873F` · `gold` `#D8B95A` |

### 2.3 Category identifiers (muted, used only as 8px dots / thin chips — never large fills)
Gold `#C9A227` · Deposits `#4E7C59` · Mutual Funds `#3E6B99` · Equity `#6A5A99` · IPO `#A6555A` · Bonds `#7A6A55` · Retirement `#3F8A8A` · Insurance `#2F7F76` · Real Estate `#B06B3A` · Alternatives `#8A7F6A` · Cash `#7C8A6A`. All desaturated to sit quietly on paper. **Meaning never rides on color alone** (always paired with an icon/label) for accessibility.

### 2.4 Rules
- Any single screen shows **at most one accent action** and a small number of category dots. Debt uses `caution`-family so owe-vs-own separates at a glance.
- Gains/losses use `positive`/`caution` text, never red/green fills.
- Gold appears **once** per screen at most (the hero number).

## 3. Typography
A two-family pairing: a **humanist serif** for numbers, headlines, and moments of warmth; a **clean grotesk** for UI and dense text. Numbers use **tabular figures** and Indian grouping (₹1,76,875), with the amount-in-words treatment beneath the hero.

- **Display / numbers / headlines:** *Fraunces* (or *Newsreader* / *Source Serif 4*) — optical sizing on, slight softness.
- **UI / body / labels:** *Inter* (or *Geist Sans*) — neutral, legible at small sizes.
- **Mono (aligned figures in tables):** *Geist Mono* / *IBM Plex Mono*, tabular.

### 3.1 Type scale (rem @ 16px base; 1.2 modular ratio)
| Role | Size | Line | Weight | Family | Tracking |
|---|---|---|---|---|---|
| Display (net worth) | 2.98 (≈48px) | 1.05 | 500 | Serif | −1% |
| H1 | 2.07 (≈33px) | 1.15 | 500 | Serif | −0.5% |
| H2 | 1.73 (≈28px) | 1.2 | 500 | Serif | 0 |
| H3 | 1.44 (≈23px) | 1.25 | 600 | Sans | 0 |
| H4 / section | 1.2 (≈19px) | 1.3 | 600 | Sans | 0 |
| Body-lg | 1.13 (≈18px) | 1.5 | 400 | Sans | 0 |
| Body | 1.0 (≈16px) | 1.55 | 400 | Sans | 0 |
| Small / label | 0.875 (≈14px) | 1.45 | 500 | Sans | +0.5% |
| Caption | 0.78 (≈12.5px) | 1.4 | 500 | Sans | +1% |
| Overline | 0.72 (≈11.5px) | 1.3 | 600 | Sans | +6%, UPPERCASE |

Body never below 14px. Long numbers and tables use mono/tabular so columns align. Max reading width ≈ 68 characters.

## 4. Spacing, layout & shape
- **Base unit 4px.** Spacing scale: `4, 8, 12, 16, 20, 24, 32, 40, 48, 64, 96`. Use tokens (`space-2 = 8px`) not raw numbers.
- **Rhythm:** 24px between cards, 16px card padding (mobile) / 24px (desktop), 32–48px between major sections.
- **Grid:** mobile 4-col / 16px gutter / 20px margins; tablet 8-col; desktop 12-col, content max-width ~1120px, comfortable 720px for reading-heavy screens.
- **Radius:** `sm 8` (inputs, chips), `md 12` (buttons), `lg 16` (cards, sheets), `xl 24` (hero), `full` (avatars, toggles).
- **Elevation:** prefer **hairline borders over shadows** for calm. Two soft shadows only: `e1` (cards) `0 1px 2px rgba(28,26,23,.05), 0 1px 3px rgba(28,26,23,.04)`; `e2` (sheets/menus) `0 8px 30px rgba(28,26,23,.10)`. No hard drop shadows.
- **Icons:** one line-icon set (Lucide/Phosphor), 1.5px stroke, 20/24px; category icons share the set.

## 5. Motion
Physical and brief. Durations `fast 120ms`, `base 200ms`, `slow 320ms`; easing `standard cubic-bezier(.2,.0,.0,1)`, springy for sheets. Patterns: sheets slide up with a soft spring; menus fade+scale from origin (150ms); the **net-worth hero counts up** on first paint; toggles/checkboxes animate the mark; list items stagger 20ms on first load; skeleton shimmer for loading. **Respect `prefers-reduced-motion`** — cross-fade only.

## 6. The interactive component library
Every control below is specified with **anatomy, states (default / hover / focus / active / disabled / error / selected), interaction, motion, and accessibility.** States share tokens: focus = 2px `accent` ring + `accent-soft` halo; disabled = 40% opacity, no pointer; error = `caution` border + helper text; selected = `accent-soft` fill + `accent` mark.

### 6.1 Buttons
- **Primary:** `accent` fill, white text, radius-md, height 44 (mobile) / 40 (desktop), 600 weight. Hover darken 6%; active scale .98; focus ring. One per screen ideally.
- **Secondary:** `surface` fill, `hairline` border, `ink` text. **Ghost:** text-only accent. **Destructive:** `caution` text/border; fill only inside a confirm dialog.
- **FAB "➕ Capture":** 56px circle, `accent`, subtle `e2`, centered in the mobile bar; press ripples and opens the capture sheet.
- Loading = inline spinner replacing the label, width locked (no layout shift). Min tap target 44px.

### 6.2 Text, number & money fields
- Label above (Small/label), field height 44, radius-sm, `hairline` border → `accent` on focus. Helper/error text below reserves space (no jump).
- **Money field:** leading `₹`, auto-groups Indian style as you type, right-aligned tabular; on blur shows amount-in-words in faint helper ("₹1,00,000 — One Lakh"). **Validation on blur**, not per keystroke.
- **Number+unit:** trailing unit selector (g / units / shares). Textarea auto-grows to ~5 lines.

### 6.3 Select / Dropdown (single) & Combobox (searchable)
- Trigger looks like a field with a chevron; opens a menu in a **popover on desktop, a bottom sheet on mobile** (thumb-reachable).
- **Type-to-filter** the moment the list exceeds ~7 items; highlight matches; keyboard: ↑/↓ move, Enter select, Esc close, type to jump.
- Options show an optional leading icon/logo (institutions show their logo), a label, and an optional secondary line.
- Selected item gets a check + `accent-soft` row. Async lists show inline skeleton rows; empty search offers **"+ Add '<query>'"** (e.g., add a new institution inline — capture must never dead-end).
- Motion: menu fades+scales 150ms from the trigger; sheet springs up.

### 6.4 Multi-select & chips
- Multi-select renders chosen values as **removable chips** in the trigger; the menu uses checkboxes; a footer shows "3 selected · Clear."
- **Filter chips** (Investments list) are pill toggles; selected = `accent-soft` + check; a "Filters" chip opens a sheet for advanced combos; active filters summarized as a removable chip row.
- **Parse chips** (quick-add): the natural-language parse renders each detected field as an editable chip ("Gold", "₹1,00,000", "6.3 g", "ICICI", "3 Aug") — tap to edit, x to drop — before saving.

### 6.5 Radio, segmented control & choice cards
- **Radio group:** for 2–5 mutually-exclusive options. For prominent choices ("Just me" / "Me + my family") use **large choice cards** — a card per option with icon, title, one-line description, and a radio dot top-right; whole card is tappable, selected card gets `accent` border + `accent-soft` fill.
- **Segmented control:** the scope switcher (Me · Household · Member) and lens switcher — a pill track with a sliding `surface` thumb that animates (200ms spring) between segments; selected segment `ink`, others `ink-muted`.

### 6.6 Checkbox & toggle
- **Checkbox:** 20px, radius-sm; check draws in 120ms; used in multi-selects and "apply to all owners."
- **Toggle/switch:** 44×26 track, `full` radius; off = `hairline`, on = `accent`; knob slides with a soft spring. Used for privacy (Private/Shared), auto-renew, include-in-continuity, reminders. Always paired with a text label and current-state word ("Shared").

### 6.7 Steppers, sliders & percentage inputs
- **Share/allocation %**: a slider with a numeric field twin; multiple owners show linked sliders that keep the sum at 100% (dragging one adjusts the remainder with a gentle nudge and a "must total 100%" hint if broken).
- **Stepper** for small integer counts (quantity of coins), −/+ with hold-to-repeat.

### 6.8 Date & time
- Date field opens a calendar popover/sheet with **quick presets** relevant to context (Today · maturity presets like +1y/+3y/+5y for FDs; premium-due suggestions). Range picker for reports. Natural typing accepted ("3 aug 26") and normalized.

### 6.9 Pickers as bottom sheets (mobile-first)
- **Type picker:** a warm grid of category → type with icons and a search field; a "✨ Custom type" tile at the end.
- **Institution picker:** searchable, logo-led, with "+ Add institution" fallback.
- Sheets have a grabber handle, snap points (half / full), and dismiss on swipe-down or backdrop tap.

### 6.10 Global Search / Command palette
- A single search opens as a sheet (mobile) or `⌘K` palette (desktop). Grouped results (Investments · Liabilities · Accounts · Contacts · Documents), keyboard-navigable, each row deep-links. Recent and suggested queries when empty.

### 6.11 Lists, tables & cards
- **Investment card:** type-dot + icon, title, owner avatar-stack, linked-account line, value (tabular, right), a small return chip, and a next-date pill; long-press/hover reveals quick actions (valuation, doc, reminder, duplicate, archive). Mobile rows support **swipe actions**.
- **Tables** (reports): sticky header, tabular figures, right-aligned numbers, zebra via `surface-sunken`, group headers with subtotals; never more than needed.
- **Avatar stack** for joint owners (overlapping 24px avatars, "+2").

### 6.12 Tabs, accordions & progressive disclosure
- The capture form shows 3–5 essentials; **"▸ More details"** is an accordion revealing type-specific and custom fields with a smooth height animation. Detail screens use quiet underline tabs (Overview · Value history · Documents · Reminders).

### 6.13 Feedback: toasts, banners, dialogs, tooltips
- **Toast/snackbar:** bottom, 4s, with an **Undo** for destructive/soft-delete actions.
- **Banner:** the "Attention needed" cards on Home — soft `caution` or `accent-soft` background, an icon, a one-line reason, and a single action ("Fix").
- **Dialog:** only for confirmations (delete, share, emergency access); focus-trapped, Esc to cancel, primary action on the right.
- **Tooltip/inline hint:** brief, plain-language; sensitive concepts (nominee vs heir, XIRR) get a "?" that opens a one-paragraph explainer, never jargon.

### 6.14 States that are easy to forget
- **Empty:** warm and instructive ("No FDs yet — add your first in 20 seconds") with a single primary action, never a blank void.
- **Loading:** skeletons that match final layout (no spinners on content areas); the hero shows a shimmer then counts up.
- **Error:** plain, kind, actionable ("Couldn't save — check your connection. Your draft is safe."). Never a raw code.
- **Offline:** a slim banner; capture still works and syncs later.

## 7. Forms & capture feel
Validation on blur; errors inline and specific; the form **autosaves as a draft**; primary action is sticky at the bottom of the sheet ("Save" / "Save & add another"). Pre-fill aggressively (last institution/account, today, owner = Me). Human hints reassure ("6.3 g at ₹1,00,000 ≈ ₹15,873/g — looks right?"). Nothing is ever saved without a visible confirm state.

## 8. Signature moments (where "beautiful" is felt)
- **First-open reveal:** the net-worth hero counts up while cards stagger in — the "whole picture, finally" feeling.
- **Capture confirmation:** parsed chips assemble, then a soft check and the new card slides into the list.
- **Completeness ring** filling as nominee/linkage/proof get added — quiet, satisfying progress.
- **Continuity handbook:** the "For My Family" export feels like a bound document — serif headings, calm layout, the artifact a family keeps.

## 9. Accessibility & inclusivity
WCAG **AA+** contrast; visible focus rings; full keyboard nav; screen-reader labels on every value and control; **dynamic type** (layouts reflow, never clip); min 44px targets; meaning never by color alone. **Simple Mode** (elder): larger type, fewer options, "just my things," bigger tap targets. Full **dark mode** parity. Right-to-left ready; **regional-language** support planned (Telugu/Hindi first).

## 10. Voice & microcopy
Warm, plain, reassuring; short sentences; explain don't lecture. Money in words alongside figures. Never alarmist about debt or dues — factual and calm. Avoid jargon; when a term is unavoidable, one-tap explainer.

[‹ Index](README.md) · [‹ Prev: Product & Scope](01-product-and-scope.md) · [Next › Screens & Flows](03-screens-and-flows.md)
