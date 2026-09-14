[‹ Index](README.md) · [‹ Handover readiness](22-handover-readiness.md)

# 27 · Continuity signals

Five small things that make the handover work on the day it is needed, not only
on the day it is written down:

| # | Plan row | What it answers |
|---|---|---|
| §1 | P-22 | *If I go quiet, does anything happen?* An inactivity trigger beside request-based emergency access. |
| §2 | P-22, X-60 | *How do I say "I'm here" without a password?* One-tap links. |
| §3 | X-60 | *Can the person I named still be reached?* A yearly tap, and a dated tick. |
| §4 | P-26 | *Does the key holder actually know?* One gentle question, Yes or Not sure. |
| §6 | P-27 | *What if they're in hospital?* A chain: first, second, third. |
| §7 | P-29 | *Is there enough term cover?* The ratio, in words, as information. |

Status: **built** (migration V95, backend, web client). The native app has no
surface for any of it yet (known-issues 54). Nothing here sends anything outside
the notification outbox (Doc 13), and no channel but in-app is on unless it is
configured.

---

## 1. If you go quiet

Emergency access (Doc 05 §6, V20, V25) starts when someone the owner named
**asks**. An owner can now also say: *if you haven't seen me for a while, start
that request for me.*

**Off unless the owner turns it on.** Turning it on, or changing the period,
needs a fresh step-up (Doc 05 §5) and at least one named contact who can sign in
— without one, going quiet could start nothing, and the server says so
(`nobody_to_ask`). Turning it off never needs a step-up: it only closes a way in.

**The timeline** (`InactivityTimeline`, pure and unit-tested):

| day | what happens |
|---|---|
| 0 | After the owner's quiet period (60 days, 3, 6 months or a year; 90 days by default) with no sign of them, a calm check-in: *"A quick hello from Almira … If all is well, one tap is enough."* |
| 30 | No answer: the same, once more, and this time it says what happens next. |
| 60 | Still none: **the ordinary emergency request is raised**, once per named contact who can sign in, in that contact's name (`emergency_requests.raised_by = 'inactivity'`). |

After day 60 nothing is new. The contact's own waiting period runs (1–90 days,
chosen when they were named), the owner can veto it, and — V25's rule — the
window opens only if the owner has shown no sign of themselves since the request.
Both sides are told (`emergency.requested` to the owner, `emergency.raised` to
the contact, both essential templates).

**"A sign of you"** is one of: a session used (`user_sessions.last_used_at`), an
"I'm here" (§2, or the button in the app), or turning the setting on. One SQL
definition, `app.member_present_since`, serves the unlock rule and the sweep, so
the two cannot disagree. V95 redefines `app.emergency_reveals` and
`app.has_open_emergency_window` with it: **an "I'm here" keeps an emergency
window shut exactly as a sign-in does.** An "I'm here" also vetoes any request
the sweep raised in the owner's name, because it is the owner's own word.

**Each step counts from the step before it actually happening.** A server that
was down for a month sends the first reminder, not a request. A stamp older than
the owner's last sign of presence belongs to a finished cycle and counts for
nothing.

**The sweep** (`ContinuitySweep`) runs hourly on the owner connection, as
StillTrueSweep does, and acts only inside each household's daytime (09:00–20:00
in `households.time_zone`). It skips anyone memorialised
(`app.notifications_stopped`) or no longer an active member. Each step is stamped
on the owner's row, under `for update`, in the same transaction that decides it,
before the message is handed to the outbox — so a second server or a re-run finds
nothing to do. Every step writes `activity_log`
(`continuity.inactivity.reminder`, `continuity.inactivity.raised`,
`emergency.request` with `raisedBy`).

**Who sees the setting.** Only the owner (`inactivity_checks_own`). A contact
learns of it when it matters: when a request is raised in their name.

**API.** `GET|PUT /api/v1/households/{id}/emergency/inactivity`
(`{ enabled, periodDays }`), `POST …/inactivity/check-in`. The view carries
`stage` (`off`, `quiet`, `reminded_once`, `reminded_twice`, `raised`), the three
`steps` with `day`, `code`, a sentence, `at` and `done`, `contactsWhoCanAsk`,
`shortestWaitDays` and `lastPresenceAt`. `EmergencyRequestRow.raisedBy` says how
a request began.

## 2. One-tap links

The check-in and the yearly reachability question each carry a link — **when
`ALMIRA_PUBLIC_URL` is set** (`almira.continuity.link-base-url`, https only
except localhost). Without it no link is made, and the message says to open
Almira and tap the button there.

- **What it is.** 256 random bits, URL-safe; only its SHA-256 is stored
  (`continuity_links.token_hash`), as for a guest link. "Signed" in the plan's
  sense — nobody can forge one — without a signing key that could leak.
- **Single use, thirty days.** Thirty days is the gap between one step and the
  next, so a link is never live after the step it belonged to. Unknown, used and
  expired links all answer the same `404` sentence.
- **Where the token travels.** In the fragment (`/#/here/<token>`), which a
  browser never sends to a server; the page removes it from the address bar at
  once. The page asks for one tap, then POSTs the token in the body to
  `/api/v1/continuity-links/redeem` — so it is in no access log, and a mail
  scanner that fetches the link only loads a page.
- **Reveals nothing.** The answer is `{ purpose, message }`: "Thank you. We've
  noted that you're here. Nothing else has changed." No name, no household, no
  record. It needs no password because it can do nothing but say "here".
- **Who can spend one.** Nobody reads the table. The runtime role holds no
  privilege on `continuity_links` at all; `app.redeem_continuity_link(hash)`
  spends a link and records what it means (a check-in, or a reachability tick
  with `via = 'link'`) in one definer function. A reachability link for someone
  who is no longer the named contact confirms nothing.
- **What a leaked link can do.** Say "I'm here" for the owner, which keeps a
  window shut — the safe direction — or tick "reachable" once. It cannot open
  anything. The body that carries it is kept only until the message is sent
  (`outbound_message_bodies`, Doc 13); the in-app row keeps the title and never
  the body.

## 3. Still reachable, once a year

Each trusted contact who can sign in is asked once a year: *"Ishwarya still
counts on you … Once a year we check that you still can be."* One tap — the link,
or **Yes, I can still be reached** under *You are trusted by* in the app — leaves
a dated tick the owner sees beside the name: **Can be reached · 14 Sep 2026**.

- A year is counted from the latest of: when the contact was named, when they
  were last asked, and when they last confirmed. A contact named today was told
  today, so the first question comes a year later.
- The owner can **Ask now**, at most once a week (`409 asked_recently`).
- Only the person named can confirm, only for themselves, and a tick is never
  rewritten (V95 policy, and no UPDATE or DELETE grant). The owner, anyone else
  in the household and anyone outside it get `404`.
- The question is `continuity.reachable`, which is *not* essential: quiet hours
  and the one-a-day limit apply.
- `TrustedContact` gains `reachableConfirmedAt` and `reachabilityAskedAt`.

## 4. Do they know where it is?

The readiness score is what the owner says. This asks the person who would have
to act: *"Do you know where the SBI locker key is?"* — **Yes** or **Not sure**.

**What the question can carry: the record's title, and nothing else.** The
server takes it from the record under the asker's row-level security; nobody
types the question. So nobody can put the sealed location or the sealed key
holder in it by mistake, and the server could not read either if it tried
(Doc 20). The title was already plaintext. Asking shows it to the person asked
even when the record is private to the asker, and the sheet says so before it
is sent. That holds only for a record the asker owns, owes, holds, filed or
uploaded. Someone who merely sees a record — through a grant or an emergency
window — may ask only a member who already sees it by ordinary sight
(`app.member_would_see`); otherwise the ask is a 404, in the service and in
`key_holder_asks_insert` (V106).

**Who can be asked: a member with a login**, not the asker. This is a question
to someone who is already in the household, never a message to the third party
a sealed key-holder line may name — Doc 20 §4's promise that *they are never
contacted* still holds, because the question is not addressed from the sealed
line at all. The owner chooses whom to ask.

**Who learns what.** The asker and the person asked see the question and the
answer; nobody else in the household learns who was asked
(`key_holder_asks_read`). Only the person asked answers, and the column grant
lets an answer change the answer and nothing else. At most 20 questions wait for
an answer per asker. Asking, answering and withdrawing are audited.

**On readiness, beside the score and never in it.** `HandoverReadiness` gains
`keyHolderAnswers` — `asked`, `knows`, `notSure`, `waiting`, the latest question
per record and person, and a sentence ending "This does not change the score." —
for the viewer's own questions. It is information for the reasons Doc 22 §4.1
gives: a "yes" is a person's word about what they remember, and scoring it would
move every score on the day it shipped.

**API.** `GET|POST /api/v1/households/{id}/key-holder-asks`,
`POST …/{askId}/answer` (`{ answer: "yes" | "not_sure" }`), `DELETE …/{askId}`.

## 5. Templates

| template | to | essential (Doc 13) |
|---|---|---|
| `emergency.check_in` | the owner, days 0 and 30 | yes |
| `emergency.requested` | the owner, when the sweep raises a request | yes |
| `emergency.raised` | the contact it was raised for | yes |
| `continuity.reachable` | a trusted contact, yearly or on Ask now | no |
| `continuity.key_holder_ask` | the person asked | no |
| `continuity.key_holder_answer` | the asker | no |

`emergency.*` uses the emergency "why" sentence; `continuity.*` uses the
household one. Titles never carry an amount or a record; the key-holder question
is in the body, which a push never shows.

## 6. The access chain

"Key with Amma" fails the day Amma is in hospital. The where-and-who editor has
two more lines under the key holder: **If they can't be reached**
(`key_holder_2`) and **And after them** (`key_holder_3`).

- **Sealed exactly like the first.** Where holder names are sealed today, the
  chain is sealed too: ordinary sealed values under Doc 12, written through
  `/e2e/values`, rotated by `/e2e/key`, carried by recovery copies, searchable on
  the device. The server has no plaintext for any position.
- **Checked with them.** Beside each position, a tick: `access_chain_confirmations`
  holds only the position and the date. Only the person who sealed that name may
  set or clear it — nobody else can read who it names (`409
  sealed_by_someone_else`, and the policy). Writing a name again, or removing it,
  removes its tick (a trigger on `sealed_values`); a passphrase change rewraps the
  key and rewrites no value, so it keeps them.
- **Visibility** follows the record, as every sealed value does.
- **Empty backups are not gaps.** The chain is not a readiness check, and the
  "only records with a gap" filter still means location or first key holder.
- `WhereAndWhoRecord` gains `keyHolder2`, `keyHolder3` and `chain`
  (`[{ position, confirmedAt, confirmedByMe }]`); `fieldKeys` names the two new
  keys. `PUT|DELETE /api/v1/households/{id}/where-and-who/{recordType}/{recordId}/chain/{position}/confirmation`.
- The handbook prints the backups' labels and "sealed by" lines, never the names.

## 7. Term cover and what the household spends

Doc 08 §3 named "term cover looks low for your dependents" as a differentiator
and insisted it be *factual, timely, neutral*. This is the factual part, and it
stops there.

**Words first:** *"Term cover is about 4.1× annual expenses."* then *"That is
roughly 4 years of what you said the household spends, before inflation or
returns."* and *"You've said one person depends on it: Aarav."* A soft teal ring
beside the figure shows the multiple on a fixed 0–20× scale, labelled with the
multiple itself. **There is no target, no "enough", no red, and no "should".**

- **Cover** is the sum of `sum_assured` on active `insurance_term` holdings whose
  cover has not ended, in rupees, that the viewer sees by ordinary sight (never
  through an emergency window, as Doc 22 §5). Endowment and ULIP sums assured are
  partly savings and are not counted; a term policy in another currency is
  counted as `notCounted` and said.
- **Annual expenses and dependants** are entered by the viewer for themselves,
  and only they see them (`protection_inputs`, one row per person per household).
  Dependants are chosen from the household's people, not yourself. The audit row
  records that inputs changed, never the amount.
- **Rounded down** to one decimal, so 3.99 is never called 4.
- **No number without both.** Without expenses the status is `needs_expenses` and
  the sentence says what to add; without cover, `no_term_cover`.
- **Caveats, first one always visible:** "This is information, not advice.
  Almira does not know your loans, goals, savings or the ages of the people who
  depend on you…"

`GET /api/v1/households/{id}/continuity/protection`,
`PUT …/protection/inputs` (`{ annualExpenses, dependantMemberIds }`).

## 8. Web client

On *Family plan → Overview* (`#/continuity`):

- **Ready to hand over** gains one muted line with an info mark: how many of the
  people you asked say they know.
- **Do they know where it is?** — questions for you with Yes / Not sure, the
  questions you asked with their answers, and **Ask someone** (a record and a
  person; the sheet says only the title goes in the question).
- **Emergency access** — a dated tick or "Not confirmed yet · Asked …" and **Ask
  now** under each person you named; **Yes, I can still be reached** under each
  person who named you.
- **If you go quiet** — the three steps as a timeline with their dates, **I'm
  here**, **Change** (a sheet with the four periods and a step-up) and **Turn
  off**.
- **Term cover and what the household spends** — the headline, the ring, the
  figure with its amount in words, the policies counted, the caveats, and a sheet
  for expenses and dependants.
- **Where it is** editor: the two backup lines and a "Checked with them" box per
  position; the record's card shows backups only when there are some, with
  "Checked 14 Sep 2026".
- `#/here/<token>` — the one-tap page, answered before sign-in.

The print button stays the screen's one primary action. New strings are English
only (`quiet.*`, `reach.*`, `ask.*`, `protect.*`, `chain.*`, `here.*`,
`ready.asked*`) and fall back to English in Telugu and Hindi (known-issues 55).
Server sentences stay English (Doc 14). The service worker version is bumped.

## 9. What is verified, and what is not

**Verified by tests** (`InactivityCheckApiTest`, `ReachabilityApiTest`,
`KeyHolderAskApiTest`, `AccessChainApiTest`, `ProtectionAdequacyApiTest`,
`ContinuitySignalsMathTest`, and the V95 block of `db/tests/rls_privacy_test.sql`):

- off by default; turning on needs a step-up and someone who can ask;
- nothing before the period; two reminders thirty days apart; a request per
  contact on day 60, once per cycle, in the contact's name, waiting, with both
  told; "I'm here" in the app vetoes it; any presence starts the count again;
- a raised request opens in silence and **shuts again on a check-in** — the V95
  change to `app.emergency_reveals`;
- a link works once, without a password, reveals no name, and used, expired and
  malformed links read the same; only the hash is stored;
- only the person named confirms reachability; Ask now once a week; the sweep
  asks once a year; a link for someone no longer named confirms nothing;
- a question carries the title; only the person asked answers; nobody else in the
  household sees it; a private record cannot be asked about; readiness shows the
  answer and the score does not move;
- only the sealer ticks a chain position; a rewritten or removed name loses its
  tick; a private record's chain is not there for anyone else;
- cover against expenses rounds down; each person's view is their own; ended
  cover is not cover; inputs are validated;
- at the database, as `almira_app`: no read or write on `continuity_links`, no
  direct call to `app.member_present_since`, no one else's setting, tick,
  question, chain tick or protection input, and none of it under a guest link.

**Not verified.**

- A real email carrying a one-tap link, opened on a phone. The link is built and
  the page is exercised by hand against a local server; no provider has sent one
  (Doc 13).
- Whether the words calm rather than alarm a real family. No usability session
  has covered this (Doc 24).
- Whether a family recording a sealed second key holder, or asking a relative
  "do you know where", raises the consent questions Doc 20 §4 leaves with
  counsel. The design keeps them small; it does not answer them.

[‹ Index](README.md) · [‹ Handover readiness](22-handover-readiness.md)
