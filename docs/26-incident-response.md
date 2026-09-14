[‹ Index](README.md)

# 26 · When something goes wrong — incident and breach response

**Status: written in advance, never used, never rehearsed, and not reviewed by
counsel.** It is here so that nobody writes a notice to a family in a panic.
The legal duties below are summarised from the Digital Personal Data Protection
Rules, 2025 as this document understands them; a lawyer should read this page
before launch and correct it where it is wrong (see *Not verified*, at the end).

The people named in §2 are **roles with no names in them yet**. A plan with no
owner is the gap this document exists to close, and it is not closed until the
owner fills in §2. Known-issues 25 tracks it.

---

## 1 · The duties, in one place

The DPDP Act treats a **personal data breach** broadly: any unauthorised
processing, or accidental disclosure, acquisition, sharing, use, alteration,
destruction of **or loss of access to** personal data, that compromises its
confidentiality, integrity or availability. There is no size threshold. One
family's records shown to the wrong person is a breach. So is losing a
household's documents with no restorable backup.

When Almira becomes aware of one, Rule 7 asks two things.

**Tell each affected person, without delay** — concisely, clearly and in plain
language, through their account or the phone number or email address they gave
us. The notice must say:

1. what happened: the nature, extent, timing and location of the breach;
2. the consequences likely to matter to them;
3. what we have done, and are doing, to reduce the harm;
4. what they can do to protect themselves;
5. how to reach a person who can answer on our behalf.

"Without delay" is not "once the investigation is finished". Notify on what is
known, say what is not known yet, and send an update when it is.

**Tell the Data Protection Board, twice:**

- **without delay** — a description of the breach: its nature, extent, timing,
  location and likely impact;
- **within 72 hours of becoming aware of it** (or longer, only if the Board
  agrees to a written request) — a detailed report: updated information; the
  broad facts, circumstances and reasons that led to it; what was done or is
  proposed to reduce the risk; any findings about who caused it; what has been
  done to stop it happening again; and a report on the notices sent to the
  people affected.

The clock starts when we **become aware**, not when we are sure. Write that
moment down (§5, step 1); it is the one timestamp everything else is measured
from.

Two other duties to check with counsel at the same time, because they have
shorter clocks and are easy to forget in the middle of the DPDP work:

- **CERT-In.** Its 2022 directions ask for certain cyber incidents to be
  reported within **six hours** of noticing them. Whether and how they apply to
  Almira's operator is a question for counsel before launch, not during an
  incident.
- **Contracts.** A provider (SMS, email, hosting) may be the source of the
  breach or may have its own notice duty to us or from us.

## 2 · Who does what

Fill this in before launch. One person may hold several roles in a small team;
the **incident lead** and the **communications lead** should still be two
people when there are two, because the person fixing the system is the worst
person to be writing to frightened families at 3 a.m.

| Role | Name | Backup | Reached by | Does |
|---|---|---|---|---|
| **Incident lead** | *not named* | *not named* | *—* | Declares the incident and its severity, owns the timeline, decides when it is over. The only person who can downgrade a severity. |
| **Technical lead** | *not named* | *not named* | *—* | Contains it, preserves evidence (§4), finds the cause, fixes it. |
| **Communications lead** | *not named* | *not named* | *—* | Writes and sends the notices (§6, §7), updates the status page, answers replies. Nothing goes out that this person has not read. |
| **Data protection contact** | *not named* | *not named* | *—* | Files the Board intimation and the 72-hour report; talks to counsel; keeps the record of who was told what, and when. |
| **Counsel** | *not retained* | | | Reads the Board report before it is filed and the first user notice before it is sent, if that can happen without delaying it. |

The **contact address in every notice** (§6, item 5) must be a real, monitored
address or number before launch. It is written as `[CONTACT]` below.

## 3 · Severity

Severity decides how fast people move, not whether the duties apply. **Any
confirmed personal data breach at any level triggers §1.**

| Level | What it means | Examples specific to Almira | Response |
|---|---|---|---|
| **S1** | Personal data of people **confirmed** exposed, altered, destroyed or unreachable | A family's unsealed records readable by someone outside it; an RLS policy found letting one member read another's private holding in production; a database dump with `ALMIRA_KMS_MASTER_KEY` taken with it; documents lost with no restorable backup | Everyone in §2 now, day or night. Board intimation without delay; user notices without delay. |
| **S2** | A breach is **plausible and not yet ruled out** | A leaked `ALMIRA_JWT_SECRET` or database password; a dump taken **without** the master key (ciphertext plus readable records — see §8); an unexplained admin login on the host; the audit log showing reads nobody can account for | Incident lead and technical lead now. Decide within hours whether it is S1. If in doubt, it is S1. |
| **S3** | A weakness, with **no sign** anyone used it | A reported vulnerability; a pen-test finding; a dependency advisory that reaches our code | Fix on a deadline set by the incident lead. Record why it was judged not to be a breach. |
| **S4** | Service trouble, **no** personal data at risk | The app down, sign-in codes not arriving, a slow restore that did complete | Status page note. Not a breach — unless data turns out to be lost, when it becomes S1. |

Record every downgrade with a reason. "We looked and found nothing" is a
reason only when the log of what was looked at is attached.

## 4 · Preserve the evidence first

Evidence is lost by fixing things. Before changing anything that is not needed
to stop active harm, the technical lead:

1. **Writes down the time of awareness** and how we found out, in UTC and IST.
2. **Snapshots the database** with `scripts/backup.sh` ([Doc 17 §6](17-deploying.md))
   to a location the compromised host cannot write to. The backup's manifest
   records what it contains; keep it with the snapshot.
3. **Copies `activity_log`** out separately as well. It is append-only for the
   application role (`R__grants.sql`), so it is the most trustworthy record of
   who read and changed what — but the owner role can still alter it, so a copy
   taken early is the one to trust.
4. **Saves the application's stdout and the reverse proxy's access logs.** Doc 17
   §8 says plainly that nothing collects the application's logs today; whatever
   `docker logs` still holds is all there is. Save it before a restart discards
   it.
5. **Records the running state**: `docker ps`, image digests and tags
   (`ALMIRA_IMAGE_TAG` is the commit that was built), the host's login history,
   and `GET /health` (which reports the database role the app is connected as).
6. **Hashes what was saved** (`shasum -a 256`) and writes the hashes into the
   incident record, with who took each copy and when.

Never copy a secret into the incident record, a chat or a ticket. Say which
secret, not what it is.

## 5 · The steps, in order

Keep one incident record — a plain document with a timeline, in UTC and IST —
from the first minute. Every step below adds a line to it.

1. **Declare.** Whoever notices tells the incident lead. The incident lead sets a
   severity and writes the time of awareness. **The 72-hour clock starts here.**
2. **Preserve** (§4).
3. **Contain.** Only what stops the harm:
   - *Sessions.* Revoking every session in SQL (as the owner role:
     `update user_sessions set revoked_at = now(), revoked_reason = 'incident'
     where revoked_at is null;` and the same for `refresh_tokens`) ends refresh.
     It does **not** reach the Redis revocation cache, so access tokens already
     issued keep working for up to fifteen minutes. To end them at once, rotate
     `ALMIRA_JWT_SECRET` and restart the app.
   - *Secrets.* Rotate whichever leaked: database passwords (both roles), Redis,
     the JWT secret, provider credentials.
   - *The master key.* If `ALMIRA_KMS_MASTER_KEY` may be exposed, treat every
     account number, policy number and document as readable by the attacker.
     There is **no key-rotation procedure** today (Doc 16 lists it as a gap);
     writing and testing one is part of the fix, not something to improvise on a
     live database.
   - *Shared links.* A guest link can be withdrawn; if the leak is through links,
     withdraw them.
   - *Take it offline* if the harm is ongoing and cannot be stopped otherwise.
     Put a note on the status page first.
4. **Assess** who is affected and what of theirs, using `activity_log` and the
   access logs. Keep the list of affected people as a query, not a spreadsheet
   of phone numbers passed around.
5. **Intimate the Board without delay** with what is known (§7, first part).
6. **Notify the affected people without delay** (§6). Send in the language their
   account is set to. Update the status page (§6, last template) at the same
   time, so a person who hears about it elsewhere finds the same words.
7. **File the detailed report within 72 hours** (§7, second part). If the facts
   are not all in, file what is known and ask the Board in writing for more time
   for the rest — before the 72 hours run out, not after.
8. **Fix the cause.** Where it was a privacy rule, the fix carries a failing
   test first, the way every privacy fix in this repository does, and an
   assertion in `db/tests/rls_privacy_test.sql` where RLS was involved.
9. **Follow up** with the affected people when something they were told changes,
   or when it is over.
10. **Review within two weeks.** Blameless: what happened, how we found out, how
    long each step took against §1, what we would do differently. Every change it
    calls for becomes a row in the plan and, where it is an open problem, an entry
    in [known-issues](known-issues.md). Update this document with anything it got
    wrong.

## 6 · Notices to the people affected

Rules for every notice:

- **Plain, calm, specific.** No "we take your security seriously". Say what
  happened to *their* information.
- **The five things Rule 7 asks for**, in that order, under plain headings.
- **Do not say "your data is safe"** unless it is true of everything of theirs
  that was involved. Do say what stayed protected, and exactly why (§8).
- **Never ask for anything** — no code, no password, no link to click to "verify".
  Say so in the notice, because phishing follows breach news.
- **Amounts, if any, in Indian grouping.** Dates as "14 September 2026".
- Send through the app and the contact the person registered. Nothing in a
  notice should need them to sign in to understand it.

The Telugu and Hindi versions below are **drafts that need native review** before
launch. Square brackets are filled in at the time; keep them short.

### English

> **Subject: Something happened to your information on Almira**
>
> **What happened**
> On [date], [what happened, in one or two sentences — e.g. "a mistake in an
> update let some members of other families see the names and amounts of
> holdings in your family's record, for about three hours"]. We found out on
> [date and time IST].
>
> **What it means for you**
> [What of theirs was involved — e.g. "The names, types and amounts of 4 of your
> holdings could have been seen. Account numbers, documents and anything you
> sealed with your passphrase were not."] [The likely consequence, plainly — e.g.
> "Someone could know roughly what your family owns. They cannot move money with
> it; Almira cannot move money at all."]
>
> **What we have done**
> [e.g. "We closed the gap within an hour of finding it, signed everyone out, and
> have fixed the cause. We have told the Data Protection Board of India."]
>
> **What you can do**
> [Specific, short steps — e.g. "Sign in again and check your records. Be wary of
> calls or messages that mention your investments."] We will never ask you for a
> code, a password or a bank detail in a message or a call about this.
>
> **Who to contact**
> [CONTACT — a named team and an address or number that a person answers.] We
> will write again when we know more.

### Telugu — needs native review

> **విషయం: Almiraలో మీ సమాచారానికి సంబంధించి ఒక సంఘటన జరిగింది**
>
> **ఏమి జరిగింది**
> [తేదీ]న, [ఏమి జరిగిందో ఒకటి రెండు వాక్యాల్లో]. మాకు [తేదీ, సమయం IST]కు తెలిసింది.
>
> **మీకు దీని అర్థం ఏమిటి**
> [మీ సమాచారంలో ఏది ప్రభావితమైంది.] [దాని వల్ల జరగగల పరిణామం, సూటిగా.] మీ పాస్‌ఫ్రేజ్‌తో మీరు సీల్ చేసినవి మేము చదవలేము.
>
> **మేము ఏమి చేశాము**
> [ఉదా: "లోపాన్ని గుర్తించిన గంటలోనే మూసివేశాము, అందరినీ సైన్ అవుట్ చేశాము, కారణాన్ని సరిచేశాము. భారత డేటా ప్రొటెక్షన్ బోర్డుకు తెలియజేశాము."]
>
> **మీరు ఏమి చేయవచ్చు**
> [చిన్న, స్పష్టమైన సూచనలు.] ఈ విషయంపై మేము ఎప్పుడూ మెసేజ్‌లో గానీ ఫోన్‌లో గానీ కోడ్, పాస్‌వర్డ్ లేదా బ్యాంక్ వివరాలు అడగము.
>
> **ఎవరిని సంప్రదించాలి**
> [CONTACT]. మరిన్ని వివరాలు తెలిసినప్పుడు మళ్ళీ రాస్తాము.

### Hindi — needs native review

> **विषय: Almira पर आपकी जानकारी से जुड़ी एक घटना हुई है**
>
> **क्या हुआ**
> [तारीख] को, [क्या हुआ, एक-दो वाक्यों में]। हमें [तारीख और समय IST] को इसका पता चला।
>
> **आपके लिए इसका क्या मतलब है**
> [आपकी कौन-सी जानकारी प्रभावित हुई।] [इसका संभावित असर, साफ़ शब्दों में।] जो कुछ आपने अपने पासफ़्रेज़ से सील किया है, उसे हम नहीं पढ़ सकते।
>
> **हमने क्या किया है**
> [जैसे: "गड़बड़ी का पता चलने के एक घंटे के भीतर उसे बंद किया, सभी को साइन आउट किया, और कारण ठीक किया। हमने भारत के डेटा संरक्षण बोर्ड को सूचित किया है।"]
>
> **आप क्या कर सकते हैं**
> [छोटे, साफ़ कदम।] इस बारे में हम कभी भी मैसेज या कॉल पर कोड, पासवर्ड या बैंक की जानकारी नहीं माँगेंगे।
>
> **किससे संपर्क करें**
> [CONTACT]। और जानकारी मिलने पर हम फिर लिखेंगे।

### The status page

`site/status.html` ([Doc 17 §9](17-deploying.md)) carries the same five parts in
fewer words, without anything that identifies a family:

> **[Date] — [one-line title, e.g. "Some family records were visible to the wrong
> people"]**
> What happened: [one sentence, with the time window]. Who is affected: [e.g.
> "about 40 families; each has been told directly"]. What we did: [one sentence].
> What you can do: [one sentence, or "nothing is needed if you have not had a
> message from us"]. Contact: [CONTACT]. *Updated [time IST].*

Leave the notice up after the incident is over, with its last update saying so.

## 7 · The reports to the Board

The form and channel the Board prescribes take precedence over this outline;
check them before launch and replace this section if they differ.

**Intimation, without delay** — one page:

- who we are, and the data protection contact (§2);
- when the breach happened, where, and when we became aware of it;
- what happened, as far as known: nature and extent (what kinds of data, roughly
  how many people);
- the likely impact on the people affected;
- what is not known yet, and when the detailed report will follow.

**Detailed report, within 72 hours of awareness:**

1. Updated and detailed information about the breach.
2. The broad facts: events, circumstances and reasons that led to it — the
   timeline from the incident record.
3. Measures taken or proposed to reduce the risk.
4. Any findings about the person who caused it.
5. Remedial measures to prevent it happening again.
6. The notices sent to affected people: how many, when, through which channels,
   in which languages, and a copy of each version.

Attach the evidence hashes from §4, not the evidence.

## 8 · What a breach reveals, so the notice is accurate

Almira was designed so that a breach reveals as little as possible
([Doc 05](05-security-and-privacy.md), [Doc 15 §9](15-security-whitepaper.md)).
Say exactly as much as is true:

| If this was taken or seen | Then these are readable | And these are not |
|---|---|---|
| A **database dump only** | Everything not encrypted: names, holdings, amounts, liabilities, household members, contacts, the activity log, phone numbers and email addresses | Account numbers, policy numbers and document contents (envelope-encrypted; the key is not in the database); every sealed field (Doc 12) |
| A dump **and** `ALMIRA_KMS_MASTER_KEY` | All of the above, plus account and policy numbers and documents | Sealed fields — the server never had the passphrase |
| A **live session** or a flaw in the app | Whatever that account, or that flaw, could read | Sealed fields, unless the attacker also had the person's passphrase |
| A **compromised web client build** served to users | Potentially anything typed into it after the compromise, including passphrases (Doc 15 §9) | Nothing can be promised; say so |

Only the last four digits of most numbers are stored at all (Doc 15 §4); a
notice may say so where it applies to that person's records.

## Not verified

- **Not reviewed by counsel.** The summary of Rule 7 and the Act's definition is
  this document's reading; the Rules' commencement dates, the Board's prescribed
  form and channel, and whether the CERT-In directions apply to Almira's
  operator are all unconfirmed.
- **Never rehearsed.** No part of §4 or §5 has been run, including the session
  revocation SQL and the JWT-secret rotation. A tabletop exercise before launch
  should walk an S1 from awareness to the 72-hour report, with a clock.
- **No names in §2** and no real `[CONTACT]`.
- **No key-rotation procedure** exists for `ALMIRA_KMS_MASTER_KEY`.
- **No log collection**: the application's logs survive only as long as the
  container does (Doc 17 §8), which limits what §4 can preserve.
- The Telugu and Hindi notices **need native review**.

[‹ Index](README.md)
