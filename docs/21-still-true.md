[‹ Index](README.md)

# 21 · Still true?

The owner asked for this: *"Records not confirmed in N months resurface; default
N=12, argue for per-type periods if warranted; provider-agnostic against the
notify fake."* And, about the cost: *"Cheap once push is live: a query and a
nudge."*

The questions it asks:

- Is this policy still in force?
- Is this loan still running, and is that still what is owed?
- Is this bank account still open?
- Is this still the will that counts?

A registry nobody updates becomes wrong, then abandoned (docs/08). A bigger form
does not fix that. One small question at the right moment does.

This document is the spec, and it records the decisions made when the feature
was built. Status: **built** (migrations V29 and V30, backend, web client). The native
app has no surface for it yet.

---

## 1. Which records are asked about

The record types that exist, and whether a "still true?" question makes sense for
each one:

| Record | Asked? | Why |
|---|---|---|
| `investment` (includes insurance, deposits, gold, property, anything custom) | yes | This is the registry. It covers `active` and `matured`: a matured FD still sitting there is exactly the one to ask about. |
| `liability` | yes, `active` only | A closed loan has nothing left to confirm. |
| `account` (savings, demat, folio, wallet, locker) | yes | "Is this bank still linked" is the account question. |
| `estate_document` (will, POA, trust…) | yes, `draft` and `executed` | A superseded or revoked will has already been answered. |
| `document` (a scanned file) | no | A scan is proof of a moment, and the moment does not change. Whether it is still current is a different question with its own column (`expires_on`). |
| `member`, `contact`, `goal`, `reminder` | no | These are people, targets or dates, not facts about money that go stale on their own. |

The same four types are the only values `record_confirmations.record_type`
accepts.

## 2. Periods: per type, and why a flat 12 would be wrong

The owner's default is 12 months, and it stays the answer wherever there was no
argument against it. There were three kinds of argument, each from the data
model:

1. **A balance goes stale faster than a holding.** An FD's principal does not
   move. A savings buffer or a credit card's outstanding is wrong within a
   season. Nothing in the schema updates either one automatically: no feed
   touches `cash` holdings, and `liability_current` is simply the latest row
   someone typed into `liability_balances`.
2. **A loan's outstanding drops with every EMI, and nothing here amortises it.**
   After 12 months unconfirmed, a home loan's figure is out by twelve EMIs, and
   that goes straight into net worth. So 6 months.
3. **An estate document changes with a life event, not a calendar.** People
   remake a will when there is a marriage, a birth or a death, not every spring.
   Asking every year teaches people to dismiss the question, and dismissing it
   is exactly the habit that loses a will. So 24 months.

The table below is the whole policy. `StillTrueDocTest` reads it back out of
`app.still_true_period_months`, so this table and the code cannot disagree.

<!-- still-true-periods:start -->
| record_type | subtype | months |
|---|---|---|
| `investment` | `cash` | 3 |
| `investment` | *(any other category)* | 12 |
| `liability` | `credit_card` | 3 |
| `liability` | *(any other kind)* | 6 |
| `account` | *(any kind)* | 12 |
| `estate_document` | *(any kind)* | 24 |
<!-- still-true-periods:end -->

For an investment, the subtype is the asset category code (`cash` covers
`savings_buffer`, `cash_on_hand` and `loan_given`). For the other types it is
the record's own `kind` column.

Things that were considered and left at 12:

- **Market-priced holdings** (mutual funds, listed shares). Their value moves
  daily, but the question is not about the value, it is "do you still hold
  this". Twelve months is enough for that.
- **Nominee facts.** A nominee belongs to a holding (`investment_nominees`), not
  to a record of its own, so the holding's confirmation covers it. There is no
  case for asking about a nominee more often than about the holding.

### Dates that mean the answer may have changed

Some records carry their own moment of change. For those, the flat clock is the
wrong trigger, and the date is the right one:

| Record | Date | Where |
|---|---|---|
| investment | `maturity_date` | column |
| investment | `renewal_date` (health and other renewing policies) | `attributes` |
| liability | `end_date` | column |

A record is due **7 days after** such a date, if the date falls after its last
confirmation. That is long enough for the proceeds or the renewal receipt to
have arrived, and soon enough that the person still remembers. A date on or
before the last confirmation has already been answered, so it does not ask again.
The due date is the earlier of the date-based one and the flat period.

An unreadable `renewal_date` (it is free text in `attributes`) counts as no date.
It does not break the list: `app.try_date` returns null rather than failing the
cast.

`premium_due_date` is deliberately **not** a key date. It recurs, nobody edits it
after each payment, and it would ask the same question every year about a policy
that has not changed. The premium reminder (docs/03 §10) already covers it.

## 3. What counts as confirmation

**An explicit "still true" counts, and so does a value refresh. Nothing else
does.**

| Action | Counts? | Why |
|---|---|---|
| `POST …/still-true/{type}/{id}/confirm` | yes | That is the question, answered. |
| Adding a valuation to a holding | yes | Nobody types a current value without looking at a statement. `addValuation` already stamped `investments.last_verified_at`, and the anchor reads it. |
| Recording a loan balance (`liability_balances`) | yes | Same reason. The anchor is the latest balance row's `created_at`: when it was typed, not the `as_of_date` it describes. An imported balance counts too, because it came from the lender. |
| Editing a field (title, notes, rate) | no | An edit touches one field. Correcting a spelling is not checking that the policy is still in force. |
| Changing visibility or who it is shared with | no | Sharing something says nothing about whether it is still true. |
| Changing nominees | no | This answers "who", not "is it still there". |
| Snoozing | no | It moves the question. It does not answer it. |

The anchor, "last confirmed", is the latest of: when the record was created, its
last value refresh, and its last explicit confirmation. A brand-new record is
therefore never due. This is the cold-start lesson in docs/api/README: a new user
is not greeted with a list of problems they have not had time to have.

Confirming a holding also sets `investments.last_verified_at`. That keeps the
detail sheet's "Last confirmed" line and the dashboard's freshness card in
agreement with the answer.

## 4. Who is asked

The person asked is **someone who owns the record**, in a household where they
can write — or, when none of its owners could answer, **whoever recorded it**
(below):

| Record | Asked |
|---|---|
| investment | its owners (`investment_ownerships`) |
| liability | its holders (`liability_holders`) |
| account | its holders (`account_holders`) |
| estate_document | the member whose document it is, and whoever recorded it |

Nobody is ever asked about a record they cannot see. The predicates are the same
security-definer functions the read policies use for ownership (`owns_investment`,
`owes_liability`, `holds_account`, `owns_estate_document`). In the read
predicate, ownership always grants sight (docs/05 §3.1).

The asker must also have an active membership with the `owner`, `admin` or
`editor` role. A `viewer` could not act on the answer, and a person who has left
the household is no longer asked.

Being able to see a record is not enough. An admin can see a household-visible
FD, but is not asked about it, and confirming it answers **404**, the same as a
private record or one that does not exist. Seeing a record is not the same as
knowing whether it is still in force.

**When no owner can answer, whoever recorded it is asked.** The case this
feature exists for is a parent's LIC policy or FD, typed in by the adult child
and owned by a member who never signs in. Asking owners only (as V29 first did)
asks nobody about exactly those records: they never come back, nobody can
confirm them, and nothing says so. V30 adds one rule:

- The owners are asked when at least one of them *could* answer: a member with
  a live login and an active `owner`/`admin`/`editor` membership in the
  household.
- Otherwise the record's `created_by` is asked, provided they can still write in
  the household **and** can see the record without owning it: household
  visibility, or a scoped grant. Emergency access does not count.
- A private record for a member with no login is visible to nobody in the app,
  so it is asked of nobody. That is not new: nobody could open it before this
  feature either.
- Once an owner joins with a login and can write, the question becomes theirs,
  and the recorder stops being asked.
- If the recorder has left, or the record has no `created_by`, nobody is asked.
  Falling back further, to the household's admins, was considered and rejected:
  it would ask people who did not type the figures and are no better placed to
  know, and it would make "an admin who can see it is not asked" untrue.

The alternative, asking every writer who can see such a record, was rejected for
the same reason. The recorder is the one person who demonstrably knew the facts
once.

The sight check sits in the predicate itself (`app.still_true_recorder_answers`),
not only in the view's security-invoker reads, because the sweep reads on the
owner connection, which row-level security does not restrict.

**Joint records.** Each owner is asked. One answer is the record's answer,
because whether a policy is still in force is a fact about the policy. The same
goes for a snooze. A per-person snooze was considered and rejected: it would let
two co-owners hold two different "next time" dates for one fact.

## 5. Snooze

`POST …/still-true/{type}/{id}/snooze` with `{"until": "YYYY-MM-DD"}`.

- It must be after today, where "today" is the **household's** date
  (`households.time_zone`). Otherwise it answers `400 snooze_past`.
- It must be within 366 days. Otherwise it answers `400 snooze_too_far`. Beyond a
  year it is not a snooze but a refusal to answer, and 12 months is already the
  period.
- It sets `record_confirmations.snoozed_until`. The record is due again on the
  later of its due date and the snooze date.
- Confirming clears the snooze.
- The web client offers "Ask me in a month". The API accepts any date in range.

## 6. How it surfaces

### The list, in the app

`GET /api/v1/households/{householdId}/still-true` returns
`{ "items": [ … ] }`. It lists the records that are due now and that the caller
is asked about, ordered by due date. Each item carries:

- `recordType`, `recordId`, `title`
- `periodMonths`
- `lastConfirmedAt`, the anchor
- `keyDate`, when a date brought it back
- `dueOn` (snooze included), `snoozedUntil`
- `isDue`
- `reason`: `period` or `key_date`

`confirm` and `snooze` return the same item as it stands after the change.

Every read goes through the view `still_true_items` under the caller's row-level
security. It is the same view the sweep reads (§7). What the list shows and what a
notification counted therefore cannot drift apart.

The web client shows it as a **"Still true?"** card on Home, with "Yes, still
true" and "Ask me in a month" on each row, in English, Telugu and Hindi. It is a
card and not a screen, because the answer should be one tap from the place
people already look. If the list fails to load (for example, an older server
with no endpoint), there is simply no card. Home does not fail with it.

### The nudge

The sweep sends each person **one message per run**, not one per record:

- template `still_true.digest`
- title `Still true? N records to check`

**The title is a count. It never carries a record's title, an institution or an
amount.** A notification lands on a lock screen and in `outbound_messages`, which
docs/05 §5 calls the least protected thing written. The existing reminders put a
record's title in theirs, and this deliberately does not.

Delivery goes through the existing `Notifier`s, so `RecordingNotifier` writes an
`outbound_messages` row for `in_app` and one per configured channel (SMS, email,
push). Every channel is still a sandbox (docs/13), and the channels are not told
who the recipient is (known-issues 13). Nothing in this feature depends on a
provider. Going live changes where a row goes, not whether it exists.

### Not nagging

The table `record_confirmation_nudges` holds one row per person and record, with
when that person was last told. A due record is included in a nudge only if one
of these is true:

- that person has never been nudged about it, or
- it has been confirmed or snoozed since their last nudge and has fallen due
  again (the nudge's local date is before the new due date), or
- it has been ignored for 30 days. Then it is asked once more, and at most once a
  month.

The row is per person on purpose. Nudging one joint owner must not count as
having nudged the other.

**Quiet hours.** A household is swept only between 09:00 and 19:59 in its own
time zone. A question about a will at three in the morning is how a notification
permission gets revoked. The sweep runs hourly, so every household is reached
inside its day.

### When delivery fails (docs/13, "When a provider fails")

The nudge rows are written and committed **before** anything is sent, and
delivery happens after the commit:

- Each channel's send goes through `ProviderCalls`. `UNAVAILABLE` and `TIMEOUT`
  are retried there, within the call. `REJECTED` and `INSUFFICIENT_BALANCE` are
  not retried. The outcome is recorded in `outbound_messages.failure` with its
  attempts.
- **The next sweep does not resend.** A timed-out push may already have arrived,
  and a second one is the nag. The record is still in the in-app list either way,
  which is the surface that always works.
- One notifier throwing does not stop the next notifier, or the next person.
- No network call is made while holding one of the owner pool's two connections.

The trade-off is plain: delivery is **at most once** per nudge. If the process
dies between the commit and the send, that person is not told this cycle. They
see it in the app, and they are asked again after 30 days.

## 7. The sweep, and the connection it runs on

`StillTrueSweep` (hourly, at :15) takes three steps:

1. **On the owner connection, with no user**: `select distinct household_id from
   still_true_records where is_due`, restricted to households inside quiet hours.
   The owner bypasses row-level security, so this sees every household.
2. **On the owner connection**: the active `owner`/`admin`/`editor` memberships
   in those households.
3. **For each person**: one transaction on the owner connection. It runs
   `set_config('app.user_id', <person>, true)` (transaction-local, cleared at
   commit, exactly as `RlsTransactionManager` does for a request), reads their
   `nudge_eligible` rows from `still_true_items`, writes the nudge rows, and
   commits. Then it delivers.

Two ways this could have silently done nothing, and what stops each:

- **The runtime pool.** An unqualified `DataSource` injection resolves to the
  `@Primary` runtime pool. There, a job with no user is denied by every policy,
  step 1 returns nothing, and the sweep "succeeds" hourly. So the sweep injects
  `@Qualifier("ownerDataSource")`. `StillTrueSweepTest` puts a due record in
  place and requires its owner to have been told. It also runs an identical
  sweep built on the runtime pool, to show the hazard is real.
- **No user context.** The ownership helpers read `app.user_id`. Without the
  per-person `set_config`, `still_true_items` is empty on the owner connection
  too. That is intended: a sweep that forgets whose view it is reading tells
  nobody, rather than telling everybody.

**One instance.** The sweep takes no lock. Two app instances running it at the
same :15 could both mark and deliver the same nudge. `ReminderWorker` has the
same property; both assume a single scheduling instance, which is what the
deployment runs today. A second instance needs an advisory lock around `run()`.

Privacy here is enforced in **who is asked**, not in what the job can read. That
is the same principle as `ReminderWorker`, and the ownership predicate is the one
the read policies use.

## 8. Storage

- `record_confirmations`: primary key `(record_type, record_id)`, plus
  `household_id`, `confirmed_at`/`confirmed_by` and `snoozed_until`/`snoozed_by`.
  RLS: read, insert and update only where the record itself is visible to the
  caller (`app.linked_record_visible`, which is invoker-rights). Insert and update
  also require `can_write_household`. A confirmation filed under the wrong
  household is ignored by the view, because it joins on household.
- `record_confirmation_nudges`: primary key `(user_id, record_type, record_id)`.
  RLS: read your own rows. There is no write policy, so only the sweep, on the
  owner connection, writes here.
- `still_true_records` (security invoker) is every askable record with its
  anchor, key date, due date and `is_due`, in the household's time zone.
- `still_true_items` (security invoker) is the records the current `app.user_id`
  is asked about, plus `nudged_at` and `nudge_eligible`.
- `app.still_true_period_months(record_type, subtype)` holds §2's table.
- `app.try_date(text)` parses a date, and returns null instead of failing.
- `app.still_true_owner_can_answer(record_type, record_id)` and
  `app.still_true_recorder_answers(record_type, record_id)` (V30, security
  definer, booleans only) hold §4's recorder fallback.

Audit: `record.confirm_still_true` and `record.snooze_still_true` (the snooze
records the from and to dates).

## 9. What is verified, and what is not

**Watched failing** (the protection was removed, the test failed for the stated
reason, and passed again once it was restored):

- The sweep on the runtime pool (qualifier removed): the owner is told nothing.
- The sweep without the per-person `set_config`: the owner is told nothing.
- No nudge rows written: a second sweep nags, joint owners are told twice, and a
  failed channel is resent.
- No quiet hours: a household at 03:00 is nudged.
- A record title in the notification title: the count-only assertion fails.
- The ownership predicate removed from `still_true_items` (on the test database,
  then restored): an admin who can see a household-visible FD is asked about it.
- The valuation removed from the anchor: a refreshed holding stays due.
- Any audited action counted as confirmation: a visibility change and a snooze
  both wrongly clear the question.
- The period for loans changed to 12: the per-type test fails.
- `record_confirmations`' insert policy opened (`with check (true)`): the
  database accepts a confirmation from someone who cannot see the record.
- `confirm` not stamping `last_verified_at`: the holding's "Last confirmed" stays
  empty.
- The one-year snooze limit removed: `snooze_too_far` is not returned.
- Asking owners only (V29 as first written): a household-visible FD owned by a
  member with no login is asked of nobody, in the list and by the sweep.
- The recorder's sight check removed from `still_true_recorder_answers`: the
  sweep nudges the recorder about a private record they cannot open. (The list
  endpoint still hid it, through row-level security, which is why the test that
  catches this is a sweep test.)
- The "no owner can answer" check removed: the recorder is still asked after the
  owner has joined with a login.

**Tested but not watched failing:**

- Key-date timing (a maturity brings a record back 7 days later, and not again
  once answered).
- The unreadable `renewal_date`.
- The per-person nudge row. Its absence was not simulated, because it is a table
  shape and not a line of code. The joint-owner test would catch a per-record
  row.
- The viewer not being asked.
- The `snooze_past` and `record_type_invalid` refusals.

**Not verified:**

- Real delivery on any channel. All of them are sandboxes, and none knows the
  recipient (known-issues 13).
- The sweep's cost at scale. It is one short transaction per writer in each
  household that has anything due. That is fine for a closed alpha. At tens of
  thousands of households, step 3 wants batching by household.
- The hourly schedule itself firing in a deployed server. Tests call `run()`
  directly.
- The native app. It has no "still true?" surface and does not read the
  endpoint.
- The web card was exercised by hand in a browser against a development server,
  not by an automated test. On a seeded list (a loan due on its period, an FD
  due on its period, and a matured FD due on its date), confirm and snooze each
  removed their row, wrote the database rows and the audit entries, and showed
  their toast. It was checked in English, Telugu and Hindi, and at phone width.
  `monthFromToday`'s end-of-month clamp has no test.

**Known overlap:** the dashboard's "Not confirmed in over six months" attention
card (`DashboardService`) predates this. It uses a flat 6 months for holdings
only. The two now disagree about when a holding is stale. See known-issues 18.
