# What we measure

Almira counts twelve things, per day, on its own server, so that after the first
families use it we can see where people stop. This page lists every one of them.
If it is not on this page, it is not counted.

The choice to measure is deliberate, and so is how little. A count is a number
for a day: "on 14 September, 3 households added their first holding". It does not
say who, which household, or what they entered.

## The events

| Event | Counted when | Steps |
|---|---|---|
| `sign_in_completed` | Someone signs in with a one-time code (phone or email). | — |
| `household_created` | A household is set up. | — |
| `invite_sent` | Someone is invited to a household. | — |
| `invite_accepted` | An invitation is accepted. | — |
| `first_holding_added` | A household adds its very first holding. Counted by the database alongside `holding_added`; a holding deleted and added again is not a second first. | — |
| `holding_added` | A holding is added (typed, from a template or a duplicate). | — |
| `liability_added` | A loan or other amount owed is added. | — |
| `document_uploaded` | A document is uploaded. | — |
| `estate_contact_added` | A contact is added to the estate plan. | — |
| `still_true_confirmed` | Someone answers "Still true?" with yes. | — |
| `handbook_printed` | The family handbook PDF is produced. | — |
| `capture_abandoned` | The add form is closed without saving. | 1 how to add, 2 pick a type, 3 the form |

`capture_abandoned` is the only one the web client reports (it is the only one
the server cannot see). Everything else is counted by the server, after the
action has been saved; an action that fails or is rolled back counts nothing.

## What a count holds

One row per day, event and step, with a number. That is the whole table
(`measurement_daily_counts`, V70): `day`, `event`, `step`, `count`. There is no
column for a person, a household, a member, a record, an amount, a device or an
address, so there is nothing to join a count back to.

The day is India's calendar day.

## What is never counted

- **Amounts, names, numbers, document contents, or anything typed.** The API has
  no field that could carry them: the server events take none, and the one client
  request carries a form name (`capture`) and a step (1–3).
- **Anything about someone under 18.** An event about a holding, loan, account or
  document owned or held by a member under 18, an invitation for such a member, or
  anything done by a login that is an under-18 member, is not counted. The check
  is in the database function, so no code path can skip it.
- **Anything done through a guest link.** A guest borrows someone's view to read;
  that is not the person using Almira.
- **Anything by someone who has turned it off.** Settings → What we measure. The
  switch is per person and takes effect on the next action. It stores one row
  saying you opted out (`measurement_opt_outs`), which only you can read.

## How it is kept that way

- **Server-side only.** No analytics script, pixel, cookie, or third-party
  service, in the web client, the app or the build. Nothing leaves the database.
- **One way in.** The application's database role cannot read or write the counts
  table at all. It can only call `app.count_product_event`, which checks the list,
  the opt-out, minors and guest links, and then adds one. Proven in SQL by
  `db/tests/rls_privacy_test.sql` and over HTTP by `MeasurementApiTest`.
- **The list is closed.** The event names are a check constraint on the table; a
  new event is a migration, and a change to this page in the same commit.
- **An operator can turn it all off.** `ALMIRA_MEASUREMENT_ENABLED=false` counts
  nothing for anyone.
- **Counting never gets in the way.** It runs after the action commits, in its own
  transaction; a failure is logged by class name and ignored.

## Reading the counts

As the database owner, never through the API:

```sql
select day, event, step, count
from measurement_daily_counts
where day >= current_date - 28
order by day, event, step;
```

Small numbers in a small alpha can still be telling ("1 household printed its
handbook today"). Share weekly totals, not daily rows, outside the team.
