[‹ Index](README.md)

# 23 · Privacy notice

**Status: a draft. It has not had legal review.** It describes what the product
does today, in the words the app shows. It is not a privacy policy in the legal
sense yet, and it does not state a legal conclusion anywhere. Doc 09 lists a
privacy policy as a launch requirement; this is the text that review starts
from.

**Where the product shows it.** The web client opens it as a sheet from
**Settings → Privacy notice**, from **Your data rights**, and from the bottom of
**onboarding** ("Read the privacy notice"), in English, Telugu and Hindi (`privacy.*` in
`static/app/i18n.js`, rendered by `static/app/privacy.js`). The native app has
no settings or onboarding screen to put it on yet, and no key-holder field of
its own: its generic sealed-field screen shows the key-holder guidance
(`WhereAndWhoWording`) only when the field key typed is `key_holder` or
`original_location`, so in practice few native users will see it. That gap is
open (known-issues 17). When the notice
below changes, change the `privacy.*` strings with it: `scripts/check-spec.py`
checks that the key-holder paragraph and its guidance exist in all three
languages and that both entry points link to it.

---

## The notice, as shown

### What Almira stores

What you record: holdings, loans, accounts, the documents you upload, wills and
paperwork, the people in your household and your contacts. Your phone number or
email address, to sign you in. A log of changes, so a household can see who
changed what.

### What we can read, and what we can't

Most of what you record is stored so that our server can read it. That is how
totals, reports and search work, and it is shown only to the people you share it
with. Anything you seal with your passphrase is encrypted on your device before
it is sent. Almira cannot read it, and cannot get it back for you if the
passphrase is lost.

### Where the original is, and who holds the key

On a record you can write where the original is, and who holds the key or the
papers. That second line names another person, someone who may never use Almira
and has not agreed to be written down.

Both lines are sealed with your passphrase on your device, so they are
end-to-end encrypted. Almira cannot read them, search them or print them, and we
never contact the person named.

Please write a role or a relationship — “Amma”, “the CA”, “my brother” — rather
than a full name, an address or a phone number.

Whether recording another person this way needs their consent is pending
legal review. We have not reached a conclusion, and this notice will change when
there is one.

### Recovery sheets and shares

If you make a recovery sheet or recovery shares, the codes are made on your
device and never sent to us. We keep a copy of your key locked with them, which
we cannot open. We do store, **not sealed**, who you said keeps the sheet or
each share, and when you last practised: family members who can see something
you sealed are told who to ask. Write a role, such as “our lawyer”, and never
where anything is. Anyone with the sheet, or two of the shares, can open what
you sealed.

### What you agree to, one purpose at a time

Almira asks separately for each thing it does with your data: keeping your
records and showing them to the people you choose, and sending you reminders
outside the app, by email, SMS or push. Nothing is sent outside the app until
you say yes, and we ask when a reminder would first help. Notices that protect
your account, or change what you may or must do, come either way: a sign-in
code, a new sign-in, a changed phone number or sign-in method, a request for
emergency access you can stop, being named someone's emergency contact or the
person to carry a household on, leaving a household, and your account being
closed or taken over. You can withdraw either consent in one tap
on Your data rights, the same way you gave it. Withdrawing the first means
closing your account. Every choice is kept as a dated line you can see.

### Your rights

You can see what Almira holds about you and who else can see it, ask for
something to be corrected, close your account and erase your data, name someone
to act on these rights if you die or cannot act yourself, and complain.
Settings → Your data rights has all five.

### Children

When you add a child, you do it as their parent or lawful guardian: you confirm
that once with a code, and a dated line on their profile records it. Almira has
no analytics or tracking of any kind, so nothing about a child's records is ever
used that way.

### Complaints

Complaints go to the grievance contact named on Your data rights, with the date
we reply by. We reply within the period shown there, and never more than 90
days.

### What we never do

Almira never moves money, never holds funds and never asks for a bank password.

---

## Your data rights

The DPDP Act 2023 and the DPDP Rules 2025 as things a person can do. The core
obligations (Rules 3, 5–16) commence on **13 May 2027**; this is built ahead of
them. Registration as a Consent Manager (from 13 November 2026) is not needed:
Almira collects consent for itself, not for other fiduciaries. The database side
is [Doc 05](05-security-and-privacy.md) §8.1 and migration V45.

**Where it is.** Web client: **Settings → Your data rights** (route `#/rights`,
`static/app/screens/rights.js`), not in the navigation — somewhere you go on
purpose. The native app has no screen for it yet.

**The page.** Five plain cards, each one tap:

| Card | What it does | API |
|---|---|---|
| **See** | s.11 summary: what is held about you (counts, never amounts or titles), why, and who else can see it — household members with a login, your emergency contact, live guest links, nominees, the message providers configured on this server | `GET /api/v1/me/privacy/summary` (audited) |
| **Correct** | a correction request for what you cannot edit yourself, with the date we reply by | `POST /api/v1/me/privacy/requests` `kind: correction` |
| **Erase** | the account-closure flow from Settings (`openClosure` in `lifecycle.js`), opened directly; a closure already under way goes to Settings, where it can be cancelled; a server without the closure API gets a sheet saying how to ask instead | lifecycle: `/api/v1/me/closure` |
| **Nominate** | s.14: one or more people who may exercise your rights if you die or cannot act; needs a step-up | `POST/DELETE /api/v1/me/privacy/nominees` |
| **Complain** | a grievance to the named contact, with the date we reply by | `POST /api/v1/me/privacy/requests` `kind: grievance` |

Below the cards:

- **The grievance contact** as a muted line: the configured name and address
  (`ALMIRA_GRIEVANCE_NAME`, `ALMIRA_GRIEVANCE_EMAIL`) and the published period
  (`ALMIRA_GRIEVANCE_RESPONSE_DAYS`, 1–90, default 30). Every rights reply from
  the API carries the same block. Unset, the line says the contact has not been
  named yet, rather than inventing one.
- **What you've agreed to**, purpose by purpose (Rule 3's itemised notice).
  `records` is given by using Almira and withdrawn by closing the account;
  `messages` has one button that says Give or Withdraw, the same size in the
  same place. Give opens the same question as below ("Want a reminder when this
  is due?"), with no channel ticked; a yes shows as "Given on 15 Sept 2026 · By
  email, SMS". Until then it says "Not given: nothing is sent outside the app
  until you say yes". Below it, the notice version in force, whether you
  accepted it, and "a draft, not yet legally reviewed" while that is true.
- **Who can act for you**: your emergency contact beside your nominees, with the
  one sentence that tells them apart — *your emergency contact is someone in
  your household who can ask to see the records you marked if you can't be
  reached; a nominee can use your data rights (see, correct or erase your data)
  if you die or can't act yourself.*
- **Your requests**, each with "We reply by" or its outcome.
- **Consent history** as a dated timeline: consents given and withdrawn, notice
  versions accepted, and parental consents you gave or withdrew.

**Asked when it helps.** Consent to messages is **opt-in** (V125, the owner's
decision: *"Ask at the moment the first reminder would be useful. Do not
inherit consent from people who were never asked."*). No `messages` event is a
no, for everyone, including everyone who signed up before V125: nobody was
migrated into consent. The web client asks right after a save that makes a
reminder — adding a holding with a maturity, premium, renewal or SIP date, or a
loan with an EMI day (`static/app/message-consent.js`):

> **Want a reminder when this is due?** It's already in your reminders here. We
> can also tell you outside the app a few days before, so it isn't missed.
> Where should we send it? ☐ Email ☐ SMS ☐ A notification on your phone
> — **Not now** · **Yes, remind me**

- Only the channels this server sends on are listed, and **none is ticked**.
  "Yes, remind me" is disabled until one is.
- **Yes** records a `given` event with the channels ticked, `asked_in =
  in_context` (or `settings` from Your data rights) and the notice version in
  force (`POST /api/v1/me/privacy/consents` with `channels`, `askedIn`; audited
  as `privacy.consent_give`). A yes covers only those channels. A yes recorded
  before V125 names no channels; its button said "Reminders by email or text",
  so it covers email and SMS.
- **Not now** records **no consent**. It keeps the question away for 90 days,
  on every device (`POST /api/v1/me/privacy/messages-ask/not-now`,
  `messages_consent_asks`, audited as `privacy.messages_not_now`). Closing the
  sheet records nothing, and the question comes back on the next visit.
- **Whether to ask** is the server's answer (`GET
  /api/v1/me/privacy/messages-ask`): only someone who has never answered, on a
  server that offers a channel, and not within 90 days of a "Not now". A
  withdrawal is an answer: someone who said no is not asked again.
- In-app reminders and notices are the same whatever the answer.

**Notices that protect your account.** These are not under consent to messages
— the person needs them to protect their account, to stop something being done
to them or in their name, or because the notice changes their own rights or
obligations — and they are not held by quiet hours or the daily limit. The last
kind is the owner's test (V140), asked of the person the notice is sent to:
*does it change YOUR rights or obligations?* Exactly these, and nothing else
(`app.message_is_essential`, V125 and V140, and
`MessageTemplates.ESSENTIAL_TEMPLATES`; one list, pinned together by
`MessagesConsentTest`):

| Notice | Template |
|---|---|
| A sign-in code by email | `otp_email` |
| A new sign-in | `auth.new_sign_in` |
| The phone number on your account was changed (also to the old number) | `auth.phone_changed` |
| An authenticator app or passkey added or removed | `auth.authenticator_added`, `auth.authenticator_removed`, `auth.passkey_added`, `auth.passkey_removed` |
| Recovery codes replaced, or one used | `auth.recovery_codes_replaced`, `auth.recovery_code_used` |
| "Are you there?" before emergency access begins | `emergency.check_in` |
| Emergency access has been requested for your records (you can stop it) | `emergency.requested` |
| A request was raised in your name because someone went quiet | `emergency.raised` |
| An emergency access request was stopped | `emergency.vetoed` |
| Your account will close, or is staying open | `lifecycle.closure.requested`, `lifecycle.closure.cancelled` |
| You were marked as passed away | `lifecycle.memorial.marked` |
| Someone has taken over as owner of your household | `lifecycle.successor.claimed` |
| You have been asked to leave a household | `lifecycle.departure.asked` |
| You were named someone's emergency contact — a duty placed on you (V140) | `emergency.named` |
| You've left a household — your access changed (V140) | `lifecycle.departure.completed.you` |
| The passed-away label you gave someone was taken away — a major change of state (V140) | `lifecycle.memorial.reversed` |
| You were named to carry a household on — sent to the successor only (V140) | `lifecycle.successor.named` |

A sign-in code by SMS is sent at sign-in, not as a notification, and is not
under consent either. Everything else sent outside the app is: reminders, the
Still true? digest, a key-holder question, **household news** — someone else
joining, leaving, staying after all or signing in for the first time as an adult
(`lifecycle.departure.started`, `.completed`, `.cancelled`,
`lifecycle.coming_of_age.welcomed`) — the note to the adults who run a household
that a child turns 18 (`lifecycle.coming_of_age.guardian`), and any kind of
message added later until it is deliberately put on the list above.

A child coming of age changes *that child's* rights, so it is essential to the
child. No message about it reaches the child today: a child is noticed only
while they have no login of their own, and is welcomed in the app when they
first sign in ("These are yours now"). The dormancy notices added with V120
(`lifecycle.household.dormant`, `.dormant.you`, `.running_again`,
`.ownership_accepted`) are not on the list; whether the owner's test puts them
there is recorded in known issues ("Consent to messages is asked for on the web
only, and some reminders are never asked about").

**Children.** Family → Add someone with a date of birth under 18 opens
"Adding Aarav's records" before anything is saved: parent or lawful
guardian, a declaration that you are 18 or older and the child's parent or
guardian, then a one-time code (the step-up). The member and the consent are
saved together, and the child's row shows "Consent as parent: Ishwarya, 14 Sep
2026". A child added before this shows "No parent's consent recorded yet" with
a button to record it. Only the adult who gave consent can withdraw it.


## Why the key-holder paragraph says what it says

The owner's decision, for this run: *"Key-holder consent: I'm not giving you a
legal answer. Design mitigation while you get one — the field is end-to-end
encrypted so we cannot read it, and the UI should guide people toward 'Amma' or
'the CA' rather than full names and addresses."*

So, while legal review of third-party consent is **pending**:

- **What is stored.** Two sealed values per record at most, `original_location`
  and `key_holder` ([Doc 20](20-where-and-who.md) §2). The server holds
  ciphertext, row presence, a timestamp and who sealed it (Doc 20 §6). No field
  asks for a location in plaintext any more: the older plaintext columns were
  retired in V33, and the two seeded type fields that asked the same question
  ("Where the agreement is" on a business stake, "Where the keys are" on crypto)
  in V34 (Doc 20 §1). The server refuses a request that still sends any of them,
  and the database refuses the keys. What it cannot stop is a person typing a
  location into a field that is not sealed — a title or the notes — so the
  editor asks for it on the sealed card instead.
- **That it names another person.** The notice says so plainly, rather than
  treating the key holder as the user's own data.
- **That it is sealed.** True of the database, not only of the client: nothing
  on the server can read, search or print either sealed line, including the
  family handbook and its PDF.
- **The guidance.** A role or a relationship is enough for a family to act on
  ("the key is with Amma") and identifies the person less than a full name, an
  address or a phone number would. The same sentence is the helper text under
  the key-holder field in the web client (`where.keyHolderHelp`, three
  languages) and in the native app's sealed-field screen. The web field's
  one-tap suggestions are roles and relationships in the reader's language
  (`where.keyHolderSuggestions`: Amma, Nanna, the CA, …), never the names of
  members or contacts, so the quickest choice is also the one the guidance asks
  for. The location line gets
  its own guidance (`where.locationHelp`): enough for the family to find it, no
  street address or locker number.
- **Pending, without a conclusion.** Doc 20 §4 records the open question
  (whether the DPDP Act's personal or domestic purpose exemption covers this).
  Nothing in the product or in this notice answers it.

## Not verified

- The notice has not been read by counsel. Nor have the Telugu and Hindi
  translations been checked by a native speaker. The four sections added with
  data rights (consent, rights, children, complaints) and every `rights.*`,
  `family.consent.*` and `stepUp.*` string exist in English only; Telugu and
  Hindi fall back to English until translated.
- Legal review of the data-rights design (whether a step-up code and a
  declaration are "verifiable" parental consent under Rule 10, whether the list
  of notices that protect your account is right to send without consent,
  whether the access summary is a sufficient s.11 answer) needs counsel and has
  not happened.
- The question "Want a reminder when this is due?" is asked on the web only,
  and only after adding a dated holding or a loan with an EMI day
  (known-issues, "Consent to messages is asked for on the web only, and some
  reminders are never asked about"). Its Telugu and Hindi, and the changed
  "What you agree to" paragraph, are machine drafts. Seen in a browser against
  a local server, in English, at desktop width (V125's change): adding a loan
  with an EMI day opened the sheet with nothing ticked, Email and Yes recorded
  `{email}` / `in_context`, and Your data rights showed "Given on 15 Sept 2026 ·
  By email". Not seen at phone width, at 200% text, or in Telugu or Hindi.
  `scripts/check-message-consent.js` (jsc) checks the sheet's behaviour.
- Seen in a browser against a local development server (V33's change): the link
  at the end of onboarding opened the notice in Telugu, and Settings → Privacy
  notice opened it in English, with every section and the draft banner. Hindi
  was rendered for the editor's helper text, not for the notice. Not seen at
  phone width.
- The native app has no screen for the notice itself, nor for Your data rights
  or the parental-consent step.
- Seen in a browser at 420px against a local development server (V45's
  change), in English: adding a ten-year-old opened the consent sheet, the code
  sheet, and saved the child with "Consent as parent: Ishwarya, 14 Sept 2026";
  on Your data rights, accepting the notice, giving consent to messages, the See
  sheet and naming a nominee each worked and appeared on the timeline. Not seen
  at 200% text, in Telugu or Hindi, or with the Erase card reaching a closure
  screen (there is none on this branch).

[‹ Index](README.md)
