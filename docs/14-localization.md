[‹ Index](README.md)

# 14 · Language — what is translated, and what is not

Three languages: **English, Telugu and Hindi**, chosen for who this is for. The
switch is in Settings, applies instantly, and is remembered per browser. The
screen is redrawn in place: the words showing stay until the same screen is
ready in the new language, then swap at the same scroll position with focus on
the button that was pressed (`app/redraw.js`, `scripts/check-redraw.js`). It
used to empty the page first, which on a phone looked like Settings had gone
blank (X-05). A
missing key falls back to English rather than showing the key: a screen in two
languages is confusing, a screen showing `home.attention.title` is broken.

## Two rules

**1 · Numbers stay Indian-grouped in every language.** ₹1,76,875 is not an
English convention — it is how the amount is written here — and rendering it as
₹176,875 under a Telugu heading would be a mistranslation of the money. Dates do
follow the reader's language, via `Intl`.

**2 · The server's own sentences are English for now.** Error messages,
disclaimers, the note explaining why a return is missing — all of them are
written once, on the server, so that a phone, a PDF and the web client cannot
disagree about a figure or a caveat. Sending a locale with every request and
maintaining three copies of every sentence is a real project, not a switch, and
pretending otherwise would produce half-translated screens that look broken.

## What is translated today

| | |
|---|---|
| ✅ | Navigation, the app shell, the ＋ Add control |
| ✅ | Home — the headline, its labels, the empty state |
| ✅ | Settings — the language card itself, extra-private mode, shared links, connected services, exchange rates |
| ✅ | For my family — the whole screen, including the estate, contacts and emergency-access sections |
| ✅ | Dates, in the reader's language — "in 4 days" too |
| 🟡 | Every other screen's words, including Preferences, Import a statement, Reading your photo and the offline copy — machine-drafted Telugu and Hindi, not yet reviewed (below) |

Telugu and Devanagari are set in Noto Sans and Noto Serif, served from the app
itself, with a taller line height than Latin; the Latin faces have neither
script (Doc 25 §3). The destination names — Home, Holdings, Family plan,
Reports, You — are translated.

## Machine-drafted words, awaiting a native speaker

Every key English has now has Telugu and Hindi words. Most of them are
**machine-drafted and have not been reviewed by a native speaker**; they must be
reviewed before the app is shown to real families in those languages.

| | English keys | Hand-written in `i18n.js` | Machine-drafted |
|---|---|---|---|
| Telugu | 1,979 | 224 | 1,755 (`app/i18n-te.js`) |
| Hindi | 1,979 | 224 | 1,755 (`app/i18n-hi.js`) |

A draft is used only where `i18n.js` has no hand-written entry for the key; a
hand-written entry always wins. Names, acronyms and examples (EPF, NPS, SMS,
"Infosys", the quick-capture example) are deliberately left as written.

**To review one:** read it on the screen that shows it, write the corrected
words into the `te` or `hi` block of `i18n.js`, and delete the key from the
draft file. `scripts/check-i18n.js` (run with `jsc -m`) fails if a key is in
both, if a draft's `{placeholders}` or ₹ figures differ from English, if a draft
remains for a key English no longer has, or if a key has neither. When a draft
file is empty, that language is fully reviewed.

## What is not, yet

| | |
|---|---|
| ⛔ | The capture form, whose labels come from the taxonomy in the database |
| ⛔ | Every sentence the server writes: errors, notes, disclaimers, the family handbook PDF |

## What it would take to finish

1. **Native review of the drafts** — above. The client's own words are all
   through `t()`, and `scripts/check-i18n.js` keeps it that way.
2. **The taxonomy** — `investment_types.label` and each field's label live in the
   database, seeded by `db/taxonomy.py`. They need a translations table keyed by
   `(type_code, field_key, language)`, seeded the same way. This is the piece
   that matters most for capture, because those labels are most of what a person
   reads while typing.
3. **Server sentences** — a `Accept-Language` header, message bundles on the
   server, and a decision about the canonical formatted strings (`valueFormatted`
   and friends) which exist precisely so the surfaces cannot disagree. Probably:
   translate the sentences, leave the figures alone.

Nothing above is hard. It is simply not done, and this page says so rather than
letting someone discover it by switching to Telugu and finding half a screen.

[‹ Index](README.md)
