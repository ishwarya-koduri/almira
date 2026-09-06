# Almira

> Every rupee your family has invested or owes — physical, digital, listed, unlisted, insured, or one-of-a-kind — in one calm, private place, so nothing is ever forgotten and no one is ever left guessing.

The product documentation lives in [`docs/`](docs/README.md). This file covers the code.

**Status: Phases 0–2 complete — backend and web client.**

Phase 1 gave the whole balance sheet: phone-OTP sign-in, households and members,
type-aware capture including the universal "record anything" type, accounts with
encrypted numbers, liabilities and **true net worth**, nominees, reminders and a
cash-flow calendar, an encrypted document vault, global search, and the
per-record privacy model enforced by PostgreSQL row-level security.

Phase 2 adds what makes those records worth keeping: goals and what funds them;
returns (XIRR, CAGR, realised against unrealised, computed only where the data
supports them); the India tax layer (80C, 80D, 80CCD(1B), 24(b), and capital
gains from tax lots); three faster ways in — natural-language quick add,
server-side document reading, and spreadsheet import; templates, duplicate and
maturity rollover; and reports — completeness, concentration, liquidity, and
export as CSV, XLSX or PDF.

Every one of them is computed under the caller's own row-level security, so
derived data leaks no more than the records it derives from — with a test for
each. Notifications remain a logging stand-in until provider accounts exist, and
the native app is Phase 3's, built against the frozen v1 contract below.
Phases 3–4 are laid out in [`docs/10`](docs/10-phases-user-stories-and-dod.md).

---

## The one idea to understand first

Almira separates two things that most apps conflate:

| | |
|---|---|
| **Role** | what you may **do** — invite people, edit shared records, manage billing |
| **Visibility** | what you may **see** — decided per record, never by role |

**No role can read another member's Private records. Not editor, not admin, not the person who created the household.** Joining a household is not surrendering financial privacy ([docs/05 §3](docs/05-security-and-privacy.md)).

This is enforced in three places, so a bug in one cannot leak data:

1. **PostgreSQL row-level security** — the authoritative gate ([`db/migrations/V4__rls_privacy.sql`](db/migrations/V4__rls_privacy.sql))
2. **The service layer** — re-checks the same predicate
3. **The API** — returns `404`, never `403`, for a record you may not see; a `403` would confirm it exists

### Why the application uses a second database role

PostgreSQL lets a table's **owner** bypass its own RLS policies. An application connecting as the owner would make every policy in `V4` decorative — the code would look correct and enforce nothing.

So there are two roles:

- `almira` — owns the schema, runs Flyway migrations, never serves a request
- `almira_app` — serves every request, owns nothing, and cannot opt out of RLS

`GET /health` reports the connected role, so a regression back to the owner is visible at a glance.

### Sensitive data is encrypted before it is stored

Account numbers, policy numbers and document contents are encrypted with a
**per-household data key**, itself wrapped by a key-encryption key that lives
outside the database. A dump of Postgres yields ciphertext and a wrapped key that
nothing in the dump can open ([`crypto/`](backend/src/main/kotlin/tech/bhrigu/almira/crypto)).

Every value is bound to *where it lives* — household, table, column — so a
ciphertext cannot be moved to another row and decrypted there. Without that,
anyone able to write the database could copy an account number into another
family's record and have the application decrypt it for them.

By default only the **last four digits** of a number are kept. The rest is stored
only if someone explicitly opts in, and seeing it again needs a fresh
confirmation on that session, recorded in the audit log.

**There is no key in this repository.** Each install generates its own
development key on first run into a gitignored `backend/var/dev-kek`. Nothing to
leak, and rotating is `rm backend/var/dev-kek` — which makes every development
record unreadable, exactly as rotating a KEK should, and better met here than in
production. A test fails the build if any 32-byte base64 literal appears in the
source tree, because a repository containing something key-shaped teaches
everyone who reads it that keys in version control are sometimes fine.

Outside development a key must be supplied deliberately: `LocalKeyManagement`
refuses to start rather than fall back to a generated one, since a deploy that
"works" while protecting data with a key nobody chose and nothing durable holds
is worse than one that will not start. It is the seam a managed KMS plugs into,
not a substitute for one.

---

## The v1 API contract

The API is **frozen at v1** and lives at `/api/v1/**`. The contract is committed
as [`docs/api/openapi-v1.json`](docs/api/openapi-v1.json) — 78 paths, 107
operations, 119 schemas — and that file is the handoff artefact the mobile app is
built against.

**The rule for the life of v1 is additive-only.** New endpoints, new optional
request fields and new response fields are fine. Removing anything, renaming
anything, changing a type, or making an existing request field required are not;
those go to `/api/v2`, and v1 stays as it is for as long as a released app
depends on it.

That is enforced, not merely intended. `OpenApiContractTest` diffs the live API
against the frozen copy on every build and fails on anything a v1 client would
notice. It judges request and response schemas differently, because it matters
who sends what:

| | Response — the client **reads** it | Request — the client **sends** it |
|---|---|---|
| Remove a field | breaking | breaking |
| Add a field | fine | fine, if optional |
| Make it required | fine | **breaking** |
| Stop requiring it | **breaking** | fine |
| Change its type | breaking | breaking |

Without this, a Phase 2 refactor that renamed a field would pass every backend
test — the backend would be perfectly consistent with itself — and surface as a
crash on someone's phone, weeks later, in a build that had already shipped.

For a genuinely additive change, re-freeze deliberately and commit the result
alongside the code:

```bash
./scripts/freeze-api-spec.sh
```

Browsable docs are at <http://localhost:8080/docs>.
[`docs/api/README.md`](docs/api/README.md) is the note that travels with the
spec: the behaviour a schema cannot express — refresh-token reuse, why a hidden
record is a 404 and not a 403, why two members see different net worth and both
are right, client-supplied ids for offline capture, and how to build capture
forms from the type schema rather than hand-writing one per type.

---

## Running it locally

**You need:** JDK 21, Docker.

```bash
./scripts/dev.sh
```

That starts the database and cache, waits for both, and runs the backend. The
rest of this section is what it does, in case you want the pieces separately.

```bash
docker compose -f infra/docker-compose.yml up -d
```

Postgres on **55432** and Redis on **56379** — offset so Almira never collides with another Postgres you already run. This also creates the `almira_app` role and the separate `almira_test` database.

```bash
cd backend && ./gradlew bootRun
```

Flyway migrates on startup. Then:

Then open <http://localhost:8080> — the web client is served by the backend as
part of the same artifact. API documentation is at <http://localhost:8080/docs>,
and `GET /health` reports the connected database role:

```json
{ "status": "ok", "database": "up", "dbRole": "almira_app", "rlsEnforced": true }
```

### About the web client

The launch client is **Kotlin Multiplatform + Compose** for Android and iOS, as
decided in [docs/09](docs/09-build-and-launch-plan.md) — capture is a phone
activity. This web client is the companion the same document plans for, and it
does two jobs now: it makes Phase 0 usable today, and it renders the
[docs/02](docs/02-ux-and-design-system.md) design system in full — palette, type
scale, spacing, motion, dark mode — so the Compose theme is validated against a
real screen before it is written. Every token in `app/tokens.css` maps to one in
that theme.

It is deliberately framework-free: no Node, no build step, one deployable. That
suits a companion surface. If this ever becomes the primary surface, revisit
both that choice and the token storage noted in `app/api.js`.

### Signing in without an SMS bill

With `ALMIRA_OTP_PROVIDER=log` (the default) the OTP is returned in the response and printed to the log, so the whole flow works with no SMS provider, no Twilio account, and no DLT registration:

```bash
curl -s -X POST localhost:8080/api/auth/otp/request \
  -H 'Content-Type: application/json' -d '{"phone":"9876543210"}'
```

Switch `ALMIRA_OTP_PROVIDER` to `twilio` or `msg91` and the field disappears. **This must never be enabled in production** — it hands anyone a login for any phone number.

---

## Tests

Three suites, each proving something the others cannot.

```bash
# 1. Unit + full-stack integration (290 tests)
cd backend
ALMIRA_TEST_DB_URL=jdbc:postgresql://localhost:55432/almira_test \
ALMIRA_TEST_REDIS_HOST=localhost ALMIRA_TEST_REDIS_PORT=56379 \
  ./gradlew test
```

Point the suite at the compose stack as above, or omit the variables and it starts its own containers via Testcontainers. Either way it runs as `almira_app`, so it exercises the real security boundary. It refuses to run against a database named `almira` — the suite writes and deletes.

```bash
# 2. Row-level security, asserted in SQL, independent of any application code
docker cp db/tests/rls_privacy_test.sql almira-db:/tmp/t.sql
docker exec -e PGPASSWORD=app_dev_password almira-db \
  psql -h 127.0.0.1 -U almira_app -d almira -v ON_ERROR_STOP=1 -f /tmp/t.sql
```

45 assertions. If a future refactor bypasses a service, the policies still have to hold.

```bash
# 3. End-to-end over real HTTP, against a running server
./scripts/e2e-phase0.sh   # 65 checks
./scripts/e2e-phase2.sh   # 40 checks
```

The first covers the whole journey with two signed-in users: sign-in, invitation
merge, capture, type-aware validation, privacy, true net worth, encumbrance,
nominees, concurrency, offline idempotency, valuations and trash.

The second covers what Phase 2 built on top, and the arithmetic that ties it
together: a renewal that keeps its history and moves the total exactly once, a
goal funded by the renewal rather than by both records, returns that stay null
until the data earns them, an import previewed before it is committed — and
every derived surface, down to the CSV export, hiding precisely what the records
it derives from hide.

### Something to look at

```bash
./scripts/demo-data.sh
```

Fills a fresh install with one plausible household — two adults, a child, a joint
flat with the loan secured against it, accounts, a SIP, and one private holding
so the privacy model is visible on screen rather than only in tests. It prints
the phone numbers to sign in with.

---

## Layout

```
almira/
├─ backend/            Spring Boot (Kotlin, Gradle) — the API and the web client
│  ├─ src/main/resources/static/
│  │  └─ app/           the web client: design tokens, components, screens
│  └─ src/main/kotlin/tech/bhrigu/almira/
│     ├─ config/       datasources, and RlsDataSource — where identity meets the database
│     ├─ security/     JWT, request identity, session revocation
│     ├─ auth/         phone OTP, rotating refresh tokens
│     ├─ household/    households, members, roles
│     ├─ invitation/   invites that claim an existing member rather than duplicating them
│     ├─ crypto/       envelope encryption — the KMS seam and per-household keys
│     ├─ catalog/      asset taxonomy, institutions, custom types and fields
│     ├─ investment/   capture, validation, visibility, valuations, nominees
│     ├─ account/      accounts, linkage, encrypted numbers, step-up reveal
│     ├─ liability/    debts, responsibility shares, encumbrance
│     ├─ document/     the vault: encrypted proofs, single-use download tickets
│     ├─ reminder/     due dates, auto-generation, the notification sweep
│     ├─ reports/      net-worth trend and the cash-flow calendar
│     ├─ search/       one search across everything, through RLS
│     ├─ dashboard/    totals and breakdowns, per viewer
│     └─ audit/        append-only activity log
├─ db/
│  ├─ migrations/      Flyway — the schema, RLS policies, and the seeded taxonomy
│  ├─ taxonomy.py      generates V6; edit this, not the SQL
│  └─ tests/           SQL-level privacy assertions
├─ infra/              docker-compose and the two-role Postgres init
├─ scripts/            end-to-end test
└─ docs/               the product documentation set
```

### Why spring-jdbc and not JPA

This service leans on PostgreSQL features an ORM fights with: policies driven by a per-transaction setting, `jsonb` attributes, `numeric` money, deferred constraints, and views. Explicit SQL keeps all of it visible — and when the security model *is* the SQL, hiding the SQL is the wrong trade.

### The taxonomy is data, not code

12 categories, 36 investment types and 91 institutions are seeded by [`V6`](db/migrations/V6__seed_taxonomy.sql), generated from [`db/taxonomy.py`](db/taxonomy.py). Each type carries a `field_schema` that drives **both** the client's capture form and server-side validation, so a form can never ask for something the API will reject.

A household's own custom type lives in the same table with a `household_id`. Promoting one into the official taxonomy is a change of one column — no migration, no data movement.

---

## Conventions

- **Money is `numeric`, never a float.** Quantities too — grams and units matter.
- **Amounts render in Indian grouping** (₹1,76,875) with an amount-in-words line, computed server-side so a phone, a PDF and the family handbook all agree.
- **A value is never fabricated.** Every holding reports how its value is known — `valued`, `at_cost`, `custom_field` or `unknown` — and the UI says so rather than printing a confident number nobody measured.
- **Writes carry a `version`.** A stale write is rejected with a `409` and the current state, never silently overwritten.
- **Creates accept a client-supplied `id`**, so an offline capture keeps its identity and a retry is idempotent.
- **Errors are plain and kind** — no codes, no stack traces, and nothing that reveals whether a record you may not see exists.
- **A due date on the 31st still falls due in February.** Recurring dates clamp to the month's last day and then recover, rather than sliding to the 28th for ever.
- **Nothing is forecast.** Trends are drawn only between recorded snapshots; the cash-flow calendar projects known EMIs and premiums and nothing else.

## Not goals

No money movement. No bank credentials, ever. No buy/sell advice. No ads. These boundaries are what make the rest trustworthy ([docs/08 §6](docs/08-differentiation-and-standout.md)).
