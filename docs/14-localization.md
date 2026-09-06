[‹ Index](README.md)

# 14 · Language — what is translated, and what is not

Three languages: **English, Telugu and Hindi**, chosen for who this is for. The
switch is in Settings, applies instantly, and is remembered per browser. A
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
| ✅ | Dates, in the reader's language |

## What is not, yet

| | |
|---|---|
| ⛔ | Investments, Liabilities, Accounts, Goals, Tax, Reports, Family — headings and field labels |
| ⛔ | The capture form, whose labels come from the taxonomy in the database |
| ⛔ | Every sentence the server writes: errors, notes, disclaimers, the family handbook PDF |

## What it would take to finish

1. **The remaining client screens** — mechanical: move each literal into
   `i18n.js` and translate. The machinery is done and the pattern is set.
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
