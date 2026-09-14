[‹ Index](README.md) · Prev [27 Continuity signals](27-continuity-signals.md)

# 28 · Plans, and asking for help

Two things a family meets only when something is about to change: what Almira
costs, and what happens when they need a person to help. Both are built around
one idea — neither may cost the family its privacy or its record.

---

## 1 · Plans and the price

**No price has been decided.** Every screen that could show one says that in
those words, and `GET /api/v1/plans` answers `price.decided: false` with no
amount. No price is not a price of zero: zero would be a decision.

**One price for the family.** A plan is priced per household and covers
everyone in it. There is no per-person field anywhere, in configuration or in
the API (`price.perHousehold` is always `true`), because a record that leaves a
parent out to save money is the failure this product exists to prevent.

**Definitions are configuration** (`almira.plans`, `PlanProperties`):

```yaml
almira:
  plans:
    default-plan: family        # every household without a plan row
    grace-days: 30              # ordinary use after paid-through
    definitions:
      family:
        name: Family plan
        period: year            # month | year
        # price-inr: 0000       # whole rupees for the whole household; absent = not decided
```

The application refuses to start if the default plan is not defined, a price is
negative, a period is not `month` or `year`, or the grace period is outside
0–365 days.

**What we will never do** is part of the price page, not a footnote
(`neverDo`): sell or rent your data; give investment advice or sell a product;
move your money.

**No payment gateway.** Deliberately not built: nobody has chosen one, and
there is no price to charge. `paymentsEnabled` is `false`. When a gateway comes,
it sets the same `paid_through` an operator sets today (§3), so nothing about
lapsing changes.

The web client shows the plan under Settings → *Your plan*: the plan's name,
"No price has been decided yet", the one-family-price line, the never-do list
and the promise below.

---

## 2 · When a plan ends: read-only, never locked

> **Your family's record is never held hostage.**

A household has at most one `household_plans` row (V101). Its state is worked
out on every request from `paid_through`, the grace period and today's date in
India — never stored — so nothing has to run at midnight for a plan to end, and
nothing that fails to run can end one early.

| State | When | What works |
|---|---|---|
| `active` | no row, `paid_through` null, or on/before `paid_through` | everything |
| `grace` | the `grace_days` after `paid_through` | everything; the plan says it has ended |
| `read_only` | from `paid_through + grace_days + 1` | everything below; no other change |

`GET /api/v1/households/{id}/plan` returns the state, `readOnly`,
`readOnlyFrom`, the price, `alwaysAvailable` and `promise`. Members only; 404
otherwise.

**What a read-only household still does** (`PlanReadOnlyGuard`):

- **Every read.** Every GET, so every screen, the family handbook and its PDF,
  reports and the tax pack. Also the reads that are POSTs because they are
  audited: revealing an account number, opening a document, practising recovery.
- **Everything outside the household.** Download everything
  (`GET /me/export`), closing an account (`/me/closure`), sign-in, preferences,
  data-rights requests and support codes are not under `/households/{id}`.
- **Leaving and handing on.** Departures, naming or removing a successor,
  changing a member's role or removing a member — closing an owner's account
  can require one of these first.
- **Life events and continuity.** Marking someone as passed away and undoing
  it, coming of age, parental consent and its withdrawal, emergency access
  (naming a contact, asking, vetoing, withdrawing), and recovery copies of
  sealed values.
- **Who may see the record.** Changing a record's visibility, guest links (for
  a CA or an heir) and taking back an invitation.

Anything else under `/households/{id}` that is not a GET is answered
`403 plan_read_only` before any service runs, with the sentence: *"Your
family's plan has ended, so nothing here can be changed for now. You can still
see everything, open the handbook, download everything, or close your account.
Your family's record is never held hostage."* The web client shows the same
promise as a quiet notice at the top of every screen while read-only.

**Why an interceptor and not RLS.** A lapsed plan is a commercial state, not a
privacy rule. Row-level security still decides who sees and changes what; the
guard only decides whether a household in good standing is being changed. A
non-member cannot read the household's plan row, so they get the same 404 as
ever, never a hint that a plan lapsed.

**Proof.** `PlanReadOnlyApiTest` sends every POST, PUT, PATCH and DELETE under
`/households/{householdId}` in the live OpenAPI document to a lapsed household:
each allowlisted one must not be refused by the plan, every other one must be,
and every allowlist line must match a real endpoint. It also shows a refused
write stores nothing, that reads, the handbook PDF, Download everything and
closing an account work while read-only, that the grace period changes
nothing, that renewing lifts it on the next request, and that an outsider gets
404. A new write endpoint fails that test until someone decides which side it
is on.

---

## 3 · Setting a plan: operators, not the app

There is no operator role in the application and no gateway to call back, so
nothing the application can do sets a plan. `household_plans` has no write
policy and the runtime role's write grants are revoked (the SQL privacy suite
checks both). An operator sets it as the schema owner:

```
./scripts/household-plan.sh --env-file .env.production --operator "ops-1" \
    <household-id> family 2027-03-31 30 "renewed by bank transfer, ref 1042"
./scripts/household-plan.sh ... <household-id> family none      # nothing ends
```

That calls `ops.set_household_plan`, which writes the row and a `plan.set`
entry in `activity_log` (plan, dates, operator — not the note), which the
household's admins can read. The `ops` schema is never granted to
`almira_app`, and `R__grants` does not grant it, so a compromised application
cannot reach it. The owner's database credentials are the authentication.

---

## 4 · Share a support code

Support cannot see a family's records, and should not need to. A person who
wants help goes to Settings → *Get help*, sees exactly what a code would share,
and makes one. They read the code to support; support reads the diagnostics.

**What a code carries** — nothing else is accepted (`SupportCodeService`):

| Key | From | Shape |
|---|---|---|
| `appVersion` | client | a version string, no spaces |
| `platform` | client | `web`, `android` or `ios` |
| `screen` | client | a route name such as `investments` — never an address with an id |
| `language` | client | `en`, `te` or `hi` |
| `errorCodes` | client | up to 10 error codes such as `plan_read_only` |
| `flags` | client | up to 20 named switches, each only `true` or `false` |
| `apiVersion` | server | the API version |
| `households` | server | how many households the person is in |
| `anyHouseholdReadOnly` | server | whether any has a lapsed plan |

**Never** amounts, names, record titles, documents, the person's id, phone or
email. A value outside its shape is refused with `400 validation_failed` naming
the field, rather than trimmed: a client that sends a title there has a bug
worth hearing about. Unknown fields are ignored and never stored.

**Its life.** Ten characters from an alphabet without 0/O, 1/I/L or U, shown
as `ABCDE-FGHJK`, returned once, stored only as a SHA-256 hash (V102). It stops
working 24 hours after it was made — the database refuses a longer life — or
the moment the person takes it back. At most five work at a time. Taking one
back is final, and the row stays as the record that it existed.

| API | |
|---|---|
| `POST /api/v1/me/support-codes/preview` | exactly what a code would share; stores nothing |
| `POST /api/v1/me/support-codes` | makes one; `code` is in this answer only |
| `GET /api/v1/me/support-codes` | your codes: diagnostics, expiry, taken back, how many times support opened it |
| `POST /api/v1/me/support-codes/{id}/revoke` | takes one back; someone else's is 404 |

**How support reads one.** There is no operator endpoint: nothing new faces the
internet. Support runs, as the schema owner:

```
./scripts/support-code.sh --env-file .env.production --operator "support-1" \
    ABCDE-FGHJK "import screen fails, ticket 1042"
```

`ops.lookup_support_code` needs a name and a reason, accepts the code in any
case with or without the dash, returns only the diagnostics and the two dates,
and refuses an expired or taken-back code. **Every attempt is audited**
(`support_code.lookup` with the operator, the reason and whether it matched), a
guess included, and a successful look is counted on the row — so the person
sees "opened by support once". The runtime role cannot call it, cannot change a
code's contents, lifetime or lookup count, cannot undo a revocation and cannot
delete a code; a guest session sees none. `SupportCodeApiTest` and the SQL
privacy suite prove each.

---

## 5 · Not built

- **A payment gateway, and a price.** Owner decisions; see §1.
- **Telling a family before a plan ends.** The state is there (`grace`); no
  reminder is sent yet.
- **Native screens** for the plan, the read-only notice and support codes. The
  API is the same for the app; `app/` has not been changed.
- **A support tool with a screen.** The lookup is a script run as the owner.
  Anything with a login would need an operator role, its own sign-in and step-up,
  and is a larger decision than this change.

[‹ Index](README.md)
