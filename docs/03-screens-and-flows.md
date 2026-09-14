[‹ Index](README.md) · [‹ Prev: UX & Design System](02-ux-and-design-system.md) · [Next › Data Model](04-data-model.md)

# 03 · Screens & Flows

Uses the tokens and components from [Doc 02](02-ux-and-design-system.md). Wireframes are structural, not pixel-final.

## 1. Onboarding (install → first record in < 2 min)
```
Welcome → auth (email/Google/Apple, "🔒 encrypted")
   ↓
"Who are we tracking for?"  ● Just me   ○ Me + my family   (choice cards)
   ↓ (family) add members: [+ Spouse] [+ Child] [+ Parent]
   ↓ set your default visibility: ● Private   ○ Shared with household   (see Doc 05)
   ↓
"Add your first thing"  [🥇 Gold][🏦 FD][📈 Stocks][📊 MF][🛡 Insurance][✨ Custom]  ·  [Import spreadsheet]  ·  [Skip]
```

## 2. Home
```
┌─────────────────────────────────────────────┐
│ Household ▾   [Me][Household][Members]   🔍   │
│                                              │
│  TRUE NET WORTH                              │
│  ₹ 14,93,750        Assets 17,68,750         │
│  Fourteen Lakh Ninety-Three…  − Debts 2,75,000│
│                                              │
│ Lens: [Class][Member][Institution][Goal]     │
│ ┌ donut ┐  ┌ Assets vs Liabilities ┐         │
│ └───────┘  └───────────────────────┘         │
│ Upcoming(30d): FD matures · LIC due · EMI     │
│ Attention: 3 no nominee · 1 will mismatch ·   │
│            2 not verified in 8 months          │
└─────────────────────────────────────────────┘
```
Scope switcher and lens switcher are segmented controls ([Doc 02 §6.5](02-ux-and-design-system.md#65-radio-segmented-control--choice-cards)). Each viewer's totals reflect only what they're permitted to see ([Doc 05](05-security-and-privacy.md)).

## 3. Capture (hero)
```
┌─────────────────────────────────────────────┐
│ ← Add                                        │
│ Quick add: "1L gold 6.3g at ICICI Aug"    ⏎  │
│ 📷 Scan   🎙 Speak   📄 Import                │
│                                              │
│ Type ▾ [ Gold ]           (or ✨ Custom type)│
│ Owner ▾ [ Me ]   Joint? [+]                  │
│ Visibility ⦿ Private  ○ Household            │
│ Amount [₹1,00,000]   Weight [6.3 g]          │
│ ▸ More details (folio, storage, nominee, goal)│
│ ▸ + Add custom field                         │
│ [ Save ]              [ Save & add another ]  │
└─────────────────────────────────────────────┘
```
Five input modes converge here (smart form, quick-add parse chips, scan/OCR, voice, spreadsheet import); type-aware fields; validation on blur; autosaves a draft. Visibility is set at capture and editable later.

**Read on this device** (P-11, P-13). Two of those modes read a file without it, or its password, leaving the phone or laptop:

- **A photo or a scan.** *Read a document* with an image reads the words with tesseract.js in the browser (`app/ocr.js`), then sends the words with the photo to `parse-document` — the photo is stored as proof, as a PDF is. Each field comes back as a chip beside the patch of the photo it was read from; nothing is a holding until the form is saved. A browser without WebAssembly SIMD, or a photo with no words found, falls back to filling it in by hand.
- **A consolidated account statement.** *Import a statement (CAS)* opens the password-protected PDF with pdf.js in the browser (`app/pdf-text.js`); a missing or wrong password is said as such, and a small lock opens when it is right. *Check what we found* lists a suggested row for every line that names a folio — likely name, folio and value, with the words each came from — grouped by the household member it matches, or under "Not yet matched to a person". Any line can be added as a row by hand. The kept rows become a CSV for the ordinary import (person, fund, folio, market value), dry run first; the value is saved as today's valuation. The suggestions are generic, not a parser for any registrar's layout (known-issues 46).

**Quick add reads real sentences.** "HDFC FD 3 lakh 7.1% matures 5 March 2028 nominee Aarav" comes back as labelled chips — Type · Where · Amount · Rate · Matures · Nominee — with the sentence shown above them and the words each chip came from underlined. What it reads (`QuickAddParser`, pinned by `QuickAddParserTest`):

- **Amounts** as written and said here: `1L`, `2.5Cr`, `50k`, `Rs. 3,00,000/-`, `three lakh`, `one crore twenty lakh`, `1 lakh 50 thousand`, `dedh lakh`, `dhai crore`. The amount chip carries the figure in words ("Three Lakh Rupees"), so a missing zero is caught before it is saved. A bare number under 1,000, a number only in words ("five years") and anything glued into a code (an ISIN) are not money.
- **Rate** only with a percent: `7.1%`, `@ 6.8% p.a.`, `7.25 percent`. It fills the type's interest rate, or a bond's coupon.
- **Maturity** only after a cue word straight before a date: `matures 5 March 2028`, `maturity 12/01/2030`, `due on 30 Nov`. A yearless maturity is the next one, not the last; `matures in March 2028` names no day and is left for the form to ask.
- **Nominee** after `nominee`, with a relationship said first kept separately: `nominee wife Priya`. It stops at an acronym, a type word, an institution or a month. The form has no nominee field, so a nominee read from the sentence is said on the form ("Nominee: Aarav. Saved with this holding."), can be dropped there, and is saved right after the holding — linked to the household member of that name if there is one.
- **Didn't understand.** Words nothing was made of are never folded into the name. Leftover words can be the name only before the first fact (amount, rate, date, nominee) or right beside the type or institution words — "wedding" in `1L gold wedding coins at ICICI`, "SBI" in `SBI FD 2L 7% joint with Sita`. Anywhere else they come back as a greyed "Didn't understand" chip, even when that leaves no name: in `HDFC FD 3 lakh 7.1% matures 5 March 2028 nominee Aarav joint with Sita` the name is left for the form to ask. A run that starts with a cue word (`matures in March 2028`) is never the name.

## 4. Investment detail
Value + return chip · linkage · nominee(s) · **encumbrance** (loan against it) · goal(s) · **visibility** · value-history chart · documents · reminders · notes · custom fields. Quiet underline tabs; quick actions in the header.

## 5. Family & permissions
```
● Me (owner)        ₹10.8L   12 holdings
● Spouse (editor)   shared: 5 · private: —(hidden count)
● Child (managed)   ₹2.0L     3 holdings
[ + Add member ]  [ Invite by link ]
Roles = capabilities · Visibility = what each can see (Doc 05)
```
Note: you never see another member's *private* count or values — only what they've shared with you.

## 6. Liabilities
```
Liabilities                         Total 2.75L
🏠 HDFC Home Loan  out ₹2,40,000  EMI ₹22,000
   secured by → Flat, Kakinada
💳 Card outstanding out ₹35,000
[ + Add liability ]
```

## 7. Goals
```
🎓 Aarav UG 2039   ◕ 38%  ₹6L/₹16L   funded by SSY, 2 MFs
🏖 Retirement 2045 ◔ 12%
[ + New goal ]
```
Progress rings ([Doc 02 §6.11](02-ux-and-design-system.md)); a goal shows what funds it and the shortfall.

## 8. Continuity / Heir mode (the "For My Family" flow)
```
For My Family — if I'm unreachable, here's everything.
Trusted contact: Spouse   ·  unlocks after 14 days inactivity (owner can veto)
Included assets (14):
  • LIC Term — policy 5567 — nominee: Spouse — will: Spouse — docs: Vault — call: Ramesh
    [ How your family claims this ▸ ]   ← Transmission Assistant steps
  • ICICI FD — receipt in home locker
  • Gold 137.5g — home + bank locker
[ Export family handbook (PDF) ]
```
Private items marked include-in-continuity appear here **only under emergency access**, reconciling privacy with continuity ([Doc 05 §3](05-security-and-privacy.md#3-the-intra-household-privacy-model)).

## 9. Search
One field → grouped results (Investments · Liabilities · Accounts · Contacts · Documents); keyboard-navigable; deep-links.

## 10. Key journeys (happy paths)
- **Migrate:** Import spreadsheet → map columns → preview/dedupe → first-open reveal.
- **Add-and-forget:** Quick-add → confirm chips → save → optional one-tap nominee/proof nudge.
- **Renewal:** Maturity reminder → "mark done" → auto-drafts the renewed FD → confirm.
- **Handover:** Set trusted contact → (later) emergency request → time-delay + veto window → unlock → heir opens Transmission Assistant.

[‹ Index](README.md) · [‹ Prev: UX & Design System](02-ux-and-design-system.md) · [Next › Data Model](04-data-model.md)
