# Almira

> Every rupee your family has invested or owes — physical, digital, listed, unlisted, insured, or one-of-a-kind — in one calm, private place, so nothing is ever forgotten and no one is ever left guessing.

The product documentation lives in [`docs/`](docs/README.md). This file covers the code.

**Status: Phase 0 complete — backend and a working web client** — phone-OTP sign-in, households and members, invitations that merge rather than duplicate, type-aware capture including the universal "record anything" type, list/detail/trash, a dashboard, and the per-record privacy model enforced by PostgreSQL row-level security. Phases 1–4 are laid out in [`docs/10`](docs/10-phases-user-stories-and-dod.md).

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

---

## Running it locally

**You need:** JDK 21, Docker.

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
# 1. Unit + full-stack integration (51 tests)
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

27 assertions. If a future refactor bypasses a service, the policies still have to hold.

```bash
# 3. End-to-end over real HTTP, against a running server
./scripts/e2e-phase0.sh
```

51 checks covering the whole Phase 0 journey with two signed-in users: sign-in, invitation merge, capture, type-aware validation, privacy, totals, concurrency, offline idempotency, valuations and trash.

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
│     ├─ catalog/      asset taxonomy, institutions, custom types and fields
│     ├─ investment/   capture, validation, visibility, valuations
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

## Not goals

No money movement. No bank credentials, ever. No buy/sell advice. No ads. These boundaries are what make the rest trustworthy ([docs/08 §6](docs/08-differentiation-and-standout.md)).
