[‹ Index](README.md) · [‹ Prev: UX & Design System](02-ux-and-design-system.md) · [Next › Data Model](04-data-model.md)

# 03 · Screens & Flows

Uses the tokens and components from [Doc 02](02-ux-and-design-system.md). Wireframes are structural, not pixel-final.

## 1. Onboarding (install → first record in < 2 min)
```
Welcome → auth (phone, "🔒 encrypted")
   ↓
"How ready is your family?"  8 questions · Yes / Partly / No / Not sure   [Start] [Skip for now]
   ↓ the three things that matter most (worked out on the server, stored per person)
"Who are you setting this up for?"  ● For me   ○ For a parent or someone else [name · relationship]
   ↓ (for me) ● Just me  ○ Me and my family
   ↓ default visibility: ● Private   ○ Shared with household   (see Doc 05)
   ↓
The almirah: "The 15 things most Indian families have"  ◔ ring · three shelves · [Not for us]
```
Every step can be skipped, and nothing on it stands between someone and a first record.

### 1.1 The readiness check (X-30)
Eight questions about paperwork, never money, answered before any data: a will; nominees; where the originals are; a second person who knows who to call; one list; insurance; what is owed; the locker. `PUT /api/v1/me/readiness-check` stores the answers (V85, the person's own row under RLS) and returns at most three gaps. Importance is a fixed order, not a model — will 10, nominees 9, papers 8, second person 7, one list 6, insurance 6, loans 5, locker 4 — multiplied by the answer (no 1, not sure 0.8, partly 0.5, yes 0), ties to the earlier question (`ReadinessScoring`). Each gap names what to do and the shelf that closes it. It can be answered again from the guide.

### 1.2 The shelves (P-10)
Fifteen shelves in three rows of five, easiest first and the house and the will last: savings account, deposit, gold, health cover, life policy, PPF, EPF, mutual funds, shares, post office savings, NPS, loans, locker, property, will. `GET /api/v1/households/{id}/first-session` says which have something on them, **counted from the records the caller can see** — never stored, so a shelf empties if its record goes and never fills from someone else's private record. Skipped shelves (`PUT …/first-session`) leave the ring rather than holding it below 100. Each shelf opens a starting point (§3.3). Home shows the ring and the next three until every shelf that applies is filled.

### 1.3 Setting it up for someone (X-32)
"For a parent or someone else" adds them to the roster as a managed member and records `settingUpFor: someone` against them. From then on the shelves, starters and the capture form speak about that person ("Does Amma have a bank locker?", "Add jewellery for Amma"), a shelf counts only their records, and new records default to being theirs. Because only a holder may share a record with named people (V8), a record in Amma's name starts **Shared with the household**, so her helper can read it back; the form says so, and she can make any of it private when she joins. A card on the shelves invites her to confirm it with her own login.

### 1.4 The second family member's welcome (X-80)
Accepting an invitation opens `#/welcome` instead of Home: "Ishwarya invited you to the Koduri household", then three cards counted by `GET /api/v1/households/{id}/welcome` through row-level security as the invitee — what you'll see (records you can read and do not own, three names as a preview), what stays yours (what you own that is private), what they'll see of yours (what you own that is shared). The first action is optional: add one thing of your own, or just look around; either marks the welcome seen. The owner never gets one. The name is the household owner's on the roster, not necessarily whoever sent the link (known-issues 50).

### 1.5 Checklists that tick themselves (P-23)
`GET /api/v1/households/{id}/guidance/checklists`: *Getting started* (readiness check, first record, a second person, a goal) and *For the family, on a hard day* (a nominee, where the originals are, someone to call, a trusted person, the will). Every item is a question asked of the visible records; nothing is ticked by hand. The guide (`#/guide`) shows them beside short explainers (a nominee is not always the heir; what sealed means; private means private; the handbook).

### 1.6 Words and help (X-35, X-42)
Legal words stay — "nominee" is on every bank form — and a "?" beside a hard word opens one plain paragraph (`glossary.js`, words in `i18n.js` as `glossary.<code>.*`). User-facing copy no longer says "zero-knowledge" or "end-to-end encrypted"; it says sealed, and what that means. Settings → Help opens the guide and "Contact us": a WhatsApp or email link and one stated reply time from `almira.support.*` (`GET /api/v1/support/contact`). Unset — the default — the card says no way to reach us has been set up. No provider is called; the phone opens the link.

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
**As built on the web** (catch-up plan P-15, X-38, X-51): under the figure, a slim
line of net worth by month from when recording began (`/reports/net-worth-trend`),
today's point emphasised; then **To review** — one count and the first three
things waiting (a maturity of something you hold, a Still true? question, a
missing nominee or scan), cleared card by card, and "Nothing waiting. Your family
is in good shape." when empty; then Coming up; then "Where it sits" as one
labelled donut. Revisiting Home draws the last known view at once and refreshes
it quietly ("Updated just now"); placeholders only on the first load.

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

### 3.3 Starting points (X-31)
Each shelf has a ready-made card — SBI savings, SBI fixed deposit, wedding jewellery, family health cover, LIC policy, PPF, EPF, monthly SIP, shares, NSC, NPS, home loan, bank locker, house papers, will — and goals have "{child}'s degree · 2039", a wedding, retirement, a home. One tap opens the ordinary form with the name, the type, the institution (matched by name) and an obvious attribute filled in; nothing is saved until Save, and every value is editable. A starter never fills an identifier, an address, an amount, or where something is kept. Account, loan and will starters open their own screen's form (`state.pendingForm`). These are the client's, not the household's saved templates (the template module), which are shapes made from the family's own records.

### 3.4 Drafts and saving offline (X-83)
Each field that may be kept is written to this device as it is typed, with a small "✓ Saved on this phone" beside it; reopening the same type picks up where it stopped, and Home offers "Finish adding HDFC FD?". A save with no network is queued with a client-generated id and sent when the browser is back online (a resend after a lost response is `already_exists`, not a copy). `drafts.js` refuses, whatever a form asks: anything sealed, the where-and-who lines, and identifiers (account, policy, folio, PRAN, UAN, certificate numbers, phones, addresses, notes); a queued save carrying one is not queued. Drafts are keyed by user id, and every draft and queued save on the device is removed at sign-out. `scripts/check-drafts.js` asserts all of it.

**Quick add reads real sentences.** "HDFC FD 3 lakh 7.1% matures 5 March 2028 nominee Aarav" comes back as labelled chips — Type · Where · Amount · Rate · Matures · Nominee — with the sentence shown above them and the words each chip came from underlined. What it reads (`QuickAddParser`, pinned by `QuickAddParserTest`):

- **Amounts** as written and said here: `1L`, `2.5Cr`, `50k`, `Rs. 3,00,000/-`, `three lakh`, `one crore twenty lakh`, `1 lakh 50 thousand`, `dedh lakh`, `dhai crore`. The amount chip carries the figure in words ("Three Lakh Rupees"), so a missing zero is caught before it is saved. A bare number under 1,000, a number only in words ("five years") and anything glued into a code (an ISIN) are not money.
- **Rate** only with a percent: `7.1%`, `@ 6.8% p.a.`, `7.25 percent`. It fills the type's interest rate, or a bond's coupon.
- **Maturity** only after a cue word straight before a date: `matures 5 March 2028`, `maturity 12/01/2030`, `due on 30 Nov`. A yearless maturity is the next one, not the last; `matures in March 2028` names no day and is left for the form to ask.
- **Nominee** after `nominee`, with a relationship said first kept separately: `nominee wife Priya`. It stops at an acronym, a type word, an institution or a month. The form has no nominee field, so a nominee read from the sentence is said on the form ("Nominee: Aarav. Saved with this holding."), can be dropped there, and is saved right after the holding — linked to the household member of that name if there is one.
- **Didn't understand.** Words nothing was made of are never folded into the name. Leftover words can be the name only before the first fact (amount, rate, date, nominee) or right beside the type or institution words — "wedding" in `1L gold wedding coins at ICICI`, "SBI" in `SBI FD 2L 7% joint with Sita`. Anywhere else they come back as a greyed "Didn't understand" chip, even when that leaves no name: in `HDFC FD 3 lakh 7.1% matures 5 March 2028 nominee Aarav joint with Sita` the name is left for the form to ask. A run that starts with a cue word (`matures in March 2028`) is never the name.

## 4. Investment detail
Value + return chip · linkage · nominee(s) · **encumbrance** (loan against it) · goal(s) · **visibility** · value-history chart · documents · reminders · notes · custom fields. Quiet underline tabs; quick actions in the header.

**As built on the web** (X-53): a panel from the right on a tablet or desktop (640px wide from 1100px), a bottom sheet on a phone. The value and a small line of its recorded history come first, then four sections one at a time — **Details** (fields, return, duplicate, renew, trash) · **Papers** (scans attached, with "Attach a scan"; where the original is; the sealed note) · **Family** (owners as avatars, who can see it, nominees) · **Reminders** (maturity, last confirmed, reminders set). To review opens it at the section that fixes the card.

**The holding row** (X-54): category icon · title with one needs-doing line in ink (the first thing To review holds for that record) · owners' initials in their member colours · value (no "at cost" on every row) · who can see it in words: "Only owner", "Household", "Some people".

## 5. Family & permissions
```
● Me (owner)        ₹10.8L   12 holdings
● Spouse (editor)   shared: 5 · private: —(hidden count)
● Child (managed)   ₹2.0L     3 holdings
[ + Add member ]  [ Invite by link ]
Roles = capabilities · Visibility = what each can see (Doc 05)
```
Note: you never see another member's *private* count or values — only what they've shared with you.

**As built on the web** (X-56): each person is an avatar in their member colour, with their role in plain words ("Runs the household", "Can add and edit", "No sign-in"). **"What Ravi sees"** opens Home as Ravi would see it, built by the server from what *you* can see (`GET /households/{id}/members/{memberId}/preview`): the records he sees too, what they add up to, and your records that are not in his view. His own private records are in neither list — they are never read — so the preview is a lower bound of his view and says so ([Doc 05 §3.8](05-security-and-privacy.md)).

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
