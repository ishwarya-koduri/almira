[‹ Index](README.md)

# 24 · Watching real families use it

**Status: a protocol. No session has been run.** Every judgement about how
Almira feels to use — the flows in [Doc 03](03-screens-and-flows.md), the
design rules in [Doc 02](02-ux-and-design-system.md), every UX row in the plan —
is an inference by the people who built it. Nobody who did not build it has ever
opened it. This page is how that changes: five families, watched in silence,
with every pause written down and turned into work.

---

## 1 · Who

**Five households**, each session with the person who would actually keep the
record, and anyone else from the family who wants to sit in.

| # | Household | Why this one | Language |
|---|---|---|---|
| 1 | **A parent over 60** with their own holdings — FDs, LIC, gold, a pension | The person the continuity promise is ultimately for; small text, unfamiliar words and a shaky hand all show up here first | Their choice |
| 2 | **A Telugu-first household** | Telugu is one of Almira's two Indian languages, and no native speaker outside the team has read it ([Doc 14](14-localization.md)) | Telugu, run by a Telugu speaker |
| 3 | A couple in their 30s–40s with a home loan and mutual funds | The most common first user; the one who will invite the others | English or Hindi |
| 4 | A household where **one person manages everything** and the spouse knows little | The "no one is left guessing" case, from the side that would be left guessing | Their choice |
| 5 | A family that **already keeps a spreadsheet or a notebook** | The strongest competitor is what they already do | Their choice |

Across the five, try to cover: at least one Android phone below mid-range, one
person who reads the phone at a larger text size, and one person who is not
the most confident member of their family with apps.

**Not** friends of the team, investors, or anyone who has seen a demo. If the
only people available know us, say so in the findings, and weight them less.

**Recruiting.** Through people who know the family, not an advert. Tell them the
truth: *"We are building an app for a family's financial record and want to watch
someone use it for the first time. It takes about an hour. We will not see your
real accounts."* Offer a thank-you of fixed value that does not depend on what
they say (for example a ₹1,000 gift voucher — the amount is the owner's call).

## 2 · What they use, and what they must not type

Sessions run on a **staging instance with a fresh account**, never production,
and never the team's development laptops with real data on them.

People will reach for their real passbook. **They must not type real account
numbers, policy numbers, PAN, Aadhaar or anything they would not say out loud.**
Hand them a printed card of made-up but realistic details (an SBI FD of
₹2,50,000 at 7.1% maturing next March; 40 grams of gold; an LIC policy with
premium ₹18,400 a year; a home loan with ₹31,20,000 outstanding) and let them
use real *kinds* of things — "my father's FD" — with invented numbers.

If someone types something real anyway, stop, delete it with them watching, and
note that it happened: it is a finding about how the form invites real data.

## 3 · Consent

Read this aloud, in their language, before anything is switched on. Give them
the printed copy to keep. Record their answer to each question on the consent
sheet, with the date and both names. **If they say no to recording, run the
session with notes only.** If they say no to anything else, do not run it.

The Telugu and Hindi versions of this script need native review before first
use; until then, the facilitator of session 2 translates it live and notes that
they did.

> Thank you for doing this.
>
> We are building an app called Almira. It keeps a record of what a family owns
> and owes. We want to watch someone use it for the first time, because we cannot
> see its problems ourselves any more.
>
> **We are testing the app, not you.** If something is confusing, that is the
> app's fault, and it is exactly what we need to find. There are no wrong
> answers and nothing you can break.
>
> I will ask you to try a few things. I will mostly stay quiet, even if you get
> stuck — that is on purpose, not rudeness. If you want to stop, or skip
> something, just say so. You can stop at any time and we will still give you
> the thank-you.
>
> Please **do not type your real account numbers or ID numbers**. Here is a card
> with made-up details to use instead.
>
> **May we record the screen and your voice?** The recording is only for our
> team, to check our notes. It is not shared outside the team, it is deleted
> [within 90 days], and your name is not written next to anything we keep.
> *(Yes / No)*
>
> **May we quote what you say**, without your name, in our own notes about what
> to fix? *(Yes / No)*
>
> **May we contact you again** for a follow-up? *(Yes / No)*
>
> If you change your mind later, tell [CONTACT] and we will delete the recording
> and our notes from your session.
>
> Do you have any questions before we start?

**Who else is in the room.** A family member helping is fine and is itself a
finding — note who helped with what. A child taking the phone from a parent is
the most important thing that can happen in session 1; note the moment and what
the parent was trying to do.

## 4 · The room

- **Their own phone** if they are willing and it can reach staging; otherwise a
  mid-range Android at their usual text size. Do not "fix" their settings.
- **Their home** if possible, at the table where the papers are actually kept.
- **Two people from the team, at most**: a facilitator who speaks, and a note
  taker who does not. The note taker sits out of the person's line of sight.
- **Recording**: the phone's own screen recorder, and one audio recorder on the
  table. No camera on the person's face.

## 5 · The tasks

Read each task from a card, in plain words, **without the names of any screen,
button or feature**. "Add your father's fixed deposit", not "Use the ＋ Add
control to capture a Fixed Deposit". Then be quiet.

Stop a task when they finish, when they say they would give up, or after **five
minutes stuck** — whichever is first — and move on without explaining.

| # | Task card | What it tests |
|---|---|---|
| 1 | *"This is a new app. Open it and get to the point where you could start using it."* | Sign-in with a one-time code; onboarding; the first screen's first impression |
| 2 | *"Add the fixed deposit on your card."* | Capture: finding where to add, choosing the type, the amount field and its Indian grouping, dates |
| 3 | *"Add the gold on your card."* | A holding measured in grams, not rupees |
| 4 | *"Add the home loan."* | Something owed, not owned; whether the total goes the way they expect |
| 5 | *"What is your family worth now, according to the app? Say it out loud."* | Reading the headline figure; lakhs and crores read aloud |
| 6 | *"Make the gold something only you can see."* | Visibility per record ([Doc 05](05-security-and-privacy.md)) — the hardest idea in the product |
| 7 | *"Write down where the FD receipt is kept, so your family could find it. Your family should be able to read it; the company that makes the app should not."* | Sealing with a passphrase ([Doc 20](20-where-and-who.md)); whether "we cannot recover it" is understood |
| 8 | *"Suppose you were not here next year. Show me what your family would need to do to find all this."* | Continuity, emergency access and readiness ([Doc 22](22-handover-readiness.md)) |
| 9 | *"Switch the app to [Telugu / Hindi / English] and back."* | Only for sessions where it is natural; half-translated screens ([Doc 14](14-localization.md)) |
| 10 | *"Is there anything here you would not trust? Why?"* | Asked last, open-ended; the answer to the question the public site ([Doc 17 §10](17-deploying.md)) is meant to settle |

Afterwards, **five minutes of questions**, still without leading:

- *"What was this app for, in your words?"*
- *"Who in your family would you want to use it, and who would you not?"*
- *"What would stop you putting your real details in?"*
- *"Was there a moment you wanted to stop?"*

Do not demo anything afterwards, however tempting. A demo teaches them the answer
and the next person they tell will not have had one.

## 6 · What to record

The note taker writes **observations, with a timestamp**, not interpretations.
"02:14 — taps Investments, goes back, taps Home, pauses 9 s" rather than "found
navigation confusing".

Record every:

- **Pause** longer than about three seconds, and where the finger was.
- **Wrong turn**: a tap that goes somewhere they then leave.
- **Question or remark** they say aloud, word for word, in the language they said it.
- **Misread**: a word, a number or an icon understood as something else. Numbers
  read aloud wrongly ("seventeen lakh" for ₹1,76,875) matter most.
- **Workaround**: zooming, turning the phone, asking someone, reading glasses.
- **Hesitation to type**: when they reach for real details, or refuse a field.
- **Moment of trust or distrust**, and what caused it.
- **Help from the family**: who, with what.
- **Task outcome**: done unaided / done with a hint from the family / given up /
  stopped at five minutes.

After each session, facilitator and note taker spend **fifteen minutes together**
writing the session sheet: the task outcomes, the ten most important
observations, and anything that surprised either of them. Do it before the next
session, while it is still in memory.

**What is kept, and for how long.** Session sheets without names; recordings
deleted by the date promised in consent; the consent sheet (with names) kept
separately from both, only as long as needed to honour a withdrawal. Nothing
from a session goes into the repository except anonymised findings.

## 7 · From findings to plan rows

A finding is not a fix. Hold the fixes until all five sessions are done, so one
memorable family does not set the plan.

1. **One card per observation**, from all five session sheets. Keep the
   timestamp and session number on it.
2. **Group** cards describing the same underlying problem. Name each group with
   what the person experienced, not the fix: "cannot tell whether the loan made
   the total bigger or smaller", not "add a minus sign".
3. **Count** how many of the five households hit it, and whether it stopped a
   task.
4. **Rate** each group:

   | | Meaning |
   |---|---|
   | **Blocker** | Stopped a task, or made someone give real data they should not have, or produced a wrong belief about their money or their privacy |
   | **Serious** | Slowed a task badly or needed family help, in two or more households |
   | **Minor** | Noticed, recovered from, in one household |

   A wrong belief about privacy ("so my brother can't see this?" when he can) is
   always a blocker, even in one household.

5. **Write a plan row** for every blocker and serious group, in the plan's own
   shape:
   - **title** — the experience, in the person's words where possible;
   - **detail** — what happened, how many of five, which tasks;
   - **evidence** — "Usability sessions [dates], households [numbers]", with one
     short anonymised quote;
   - **beautiful** — what it should feel like instead, not a spec.

   Minor groups go into one row listing them, so they are not lost and do not
   crowd the plan.
6. **Where a finding shows a document is wrong** — Doc 02 or 03 describes a
   flow that does not work for people — fix the document in the same change
   that files the row, or add an entry to [known-issues](known-issues.md) saying
   why not yet.
7. **Close the loop.** When a row is done, the next round of sessions includes a
   task that would have hit it.

**Retire X-01 from the plan** only when all five sessions have been run and
their rows filed. Run the protocol again after any change to capture,
visibility or continuity large enough to make an old finding meaningless.

## Before the first session

- [ ] A staging instance with no real data, reachable from a phone.
- [ ] Printed made-up details cards (§2), in English, Telugu and Hindi.
- [ ] Consent script (§3) printed in each language, Telugu and Hindi reviewed
      by a native speaker; `[CONTACT]` and the retention period filled in.
- [ ] Task cards (§5) printed, one task per card, in each language.
- [ ] A session sheet template (§6).
- [ ] The facilitator for session 2 is a fluent Telugu speaker.
- [ ] A dry run with someone from outside the team who is not one of the five.

[‹ Index](README.md)
