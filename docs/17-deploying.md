[‹ Index](README.md)

# 17 · Deploying Almira

Everything needed to go from a bare Linux host to a running instance, and an
honest list of what is still missing. Nothing here has been run against a real
server — it is written from the code it deploys, and the first person to use it
should expect to correct a line or two.

---

## 1 · What you need before you start

| | |
|---|---|
| A host | 2 vCPU / 4 GB is comfortable. The JVM is given 70% of container memory. |
| Docker | with the Compose plugin |
| A domain | with DNS pointing at the host |
| A TLS terminator | Caddy, nginx or a load balancer in front. **Almira does not terminate TLS** — it sets HSTS and assumes something in front holds the certificate. |
| Four secrets | a JWT signing secret, a key-encryption key, a database password, a Redis password |

## 2 · The whole thing

```bash
git clone <this repo> almira && cd almira

cp .env.production.example .env.production
openssl rand -base64 48    # → ALMIRA_JWT_SECRET
openssl rand -base64 32    # → ALMIRA_KMS_MASTER_KEY   (must decode to exactly 32 bytes)
openssl rand -base64 24    # → ALMIRA_DB_OWNER_PASSWORD
openssl rand -base64 24    # → ALMIRA_DB_APP_PASSWORD
openssl rand -base64 24    # → ALMIRA_REDIS_PASSWORD
$EDITOR .env.production    # fill in every REQUIRED value

# Bring up the database and cache, then create the non-owner runtime role.
# The bootstrap ends by checking that role and fails if it can bypass RLS.
docker compose -f deploy/docker-compose.prod.yml --env-file .env.production up -d db redis
docker compose -f deploy/docker-compose.prod.yml --env-file .env.production run --rm db-bootstrap

# Build and start the application. Flyway migrates on first boot.
docker compose -f deploy/docker-compose.prod.yml --env-file .env.production up -d --build app

# Confirm from outside what the application already checked on the way up:
curl -s http://127.0.0.1:8080/health
```

That last command prints:

```json
{"status":"ok","database":"up","dbRole":"almira_app","rlsEnforced":true,"environment":"production"}
```

**A runtime role that can bypass row-level security does not get this far.**
PostgreSQL lets a superuser, a BYPASSRLS role and a table's owner skip its
row-level security, so an application serving as any of them would read with
every privacy policy switched off — and nothing else would look wrong. The
application refuses to start in that case, before migrating (§3), and
`bootstrap-db.sql` refuses to finish for such a role before that. `/health`
still reports the role, so it is visible from outside without signing in.

**If `environment` is `development`**, one-time codes are echoed in API
responses and the signing-secret, encryption-key and page-checksum checks are
relaxed. On anything reachable by anybody but you, that is a sign-in for any
phone number. Any other value — including an empty one — is strict.

Then point your TLS terminator at `127.0.0.1:8080` and run the smoke test:

```bash
./scripts/smoke-prod.sh https://almira.example.com
```

It signs two people into one household and asserts they see **different** net
worth. That single property is what the whole product rests on: if it holds
through a real deployment — proxy, pool, runtime role and all — then privacy is
enforced end to end. It writes data, and prints the SQL to remove it afterwards
— but only after `/health` has passed: if the service, the database,
`rlsEnforced` or the role fails, it stops before signing anybody in.

## 3 · What refuses to start, and why

These checks fire before the first request, all for the same reason: a
deployment that runs with a development placeholder looks entirely healthy, and
nothing about it appears wrong.

| Missing or default | What happens |
|---|---|
| `ALMIRA_KMS_MASTER_KEY` | Refuses to start. Data encrypted with a key nobody chose, kept nowhere durable, is worse than an application that will not boot. |
| `ALMIRA_JWT_SECRET` still the development value, or shorter than 32 characters | Refuses to start. The default is printed in this repository; anybody could mint a session with it. |
| Postgres `data_checksums` is `off` | Refuses to start. See below. |
| The runtime role (`ALMIRA_DB_APP_USER`) can bypass row-level security | Refuses to start, **in every environment**. See below. |

The first three are relaxed in `development` so the app runs out of the box with no
configuration — which is exactly why the environment flag has to be right. The
runtime-role check is never relaxed.

### The runtime role: refused before migrating, in every environment

Row-level security is the privacy model, so a role that gets past it is not a
misconfiguration to report but a deployment with no privacy. Until 2026-09-15
production started regardless and only `/health` said so. Now `RuntimeRoleCheck`
asks, **as the runtime role**, which roles it is or is a member of, and the
application refuses to start if any of them

- is a superuser, or has BYPASSRLS;
- has CREATEROLE (before PostgreSQL 16 that can grant itself membership of the
  owner, which is the rest of this list);
- owns a table with row-level security enabled;

or if `ALMIRA_DB_APP_USER` is `ALMIRA_DB_OWNER_USER`.

**CREATEROLE stays a refusal** (owner's decision, 2026-09-15). On PostgreSQL
16 CREATEROLE alone no longer lets a role grant itself membership of arbitrary
roles, so there the refusal is stricter than the attack it was written for
strictly needs. It is kept: the compose image is 16 but a managed database may
be older, a role that can create roles can make ones this check would then have
to chase, and the runtime role has no reason to create anybody. A provider that insists on CREATEROLE for the
application role is a provider to give a different role, not a reason to relax
this. No code changed with the decision. The refusal names the role
and the attribute — `the runtime database role 'almira_app' (ALMIRA_DB_APP_USER)
has BYPASSRLS`, or `… is a member of 'almira' (so can SET ROLE to it), which is a
SUPERUSER`. A catalogue it cannot read refuses too.

It runs **before Flyway migrates**, so a refused start has written nothing, and
once more after migrating and before the web server is created, because a
migration runs as the owner and could hand a table to the runtime role. There is
no development or test relaxation: `dev-personal` and the test suite use the
same two-role split, and a suite run as a bypassing role would pass while
production leaked. `dev-personal/up.sh` still checks the role before starting the
container, as a second line; `RuntimeRoleCheckTest` starts the real application
as each kind of wrong role and proves Flyway's `migrate` was never reached and no
web server started.

### Every refusal comes before the database is migrated

A refusal after Flyway has applied pending migrations — column drops included —
has refused too late. Two mechanisms keep every refusal in front, and a test
fails if a new one is outside both:

- **Settings that need no database** (the JWT secret, the key's length,
  sign-in channels, OTP and provider bounds, and the privacy, plans, support and
  continuity properties) are refused by `StartupSettingsCheck` and the other
  `EnvironmentPostProcessor`s, before any application context exists.
- **Checks that read the database or reach a provider** — page checksums, the
  runtime role, the key-encryption key, an S3 bucket — are `StartupRefusal`
  beans. The Flyway bean takes every one of them as a parameter and verifies
  each before configuring Flyway, so implementing the interface is the whole of
  the wiring.

`StartupRefusalOrderTest` fails if a `StartupRefusal` bean is not a dependency
of the Flyway bean, if a refusal registered from outside the application does
not stop a real start before `migrate`, or if a class that says "Refusing to
start" (or a properties class that refuses in its init block) is outside both
mechanisms.

### Correction: "never on a missing value" was false for two of these three

Until 2026-09-13 this document said the checks relax only on an **explicit**
`development`, and never on a missing value. That was true of the page-checksum
check and **false for the other two**. It is recorded here rather than quietly
made true, because a security document that was wrong for a stretch should say
so, and because anyone who deployed from a copy of this file in that window
should know what they were relying on.

**What was wrong.** `application.yml` read `environment: ${ALMIRA_ENV:development}`.
A jar started without `ALMIRA_ENV` therefore decided it was a development
machine, and the JWT and KMS checks — each correct for the environment it was
handed — were handed `development` and relaxed. The page-checksum check was not
affected, because it reads `ALMIRA_ENV` directly and requires it to be set.

**What that allowed, reproduced rather than inferred.** Against a server started
with `ALMIRA_ENV` unset and no secrets configured:

- it reported `"environment":"development"`;
- it **generated a development key-encryption key** on disk, silently;
- a token signed with the development JWT secret — which is committed to this
  repository — for a session id the server had never issued, returned **200**
  from `/api/v1/me` as a real user. A token signed with a wrong secret returned
  401, so signatures were being checked; the secret was simply public.

**Who was exposed.** Nobody, in practice: nothing has been deployed, and every
launcher this project ships sets the variable — the Dockerfile and production
compose file to `production`, `dev-personal` and `bootRun` to `development`.
The exposure was any other way of starting the jar.

**What changed.** `application.yml` no longer has a default, so a missing
`ALMIRA_ENV` resolves to an empty value, and empty is not development. Each
check now applies its strict rule unless `development` was actually chosen, and
the refusal names `ALMIRA_ENV` rather than only the symptom. Proved on the real
jar across five boots:

| `ALMIRA_ENV` | Other configuration | Result |
|---|---|---|
| unset | none | **refuses** — names `ALMIRA_ENV`; no key file generated |
| `development` | none | starts |
| `production` | real JWT secret and KMS key | starts |
| `production` | published JWT secret | refuses |
| unset | real JWT secret and KMS key | **starts**, reporting `"environment":""` |

The last row is deliberate and worth being clear about: **each check fails
closed, rather than the whole application refusing on a missing variable.** An
unset environment never unlocks a relaxation, but when everything a strict run
requires has been supplied, no check has anything to object to. Nothing in the
application gates a protection on `== "production"` — every one is
`!= development` — so an empty value runs everything strict.

Pinned by `EnvironmentDefaultTest`, which reads the packaged `application.yml`
itself with the operating-system environment removed, and was watched failing
with the old default restored.

### What this breaks, on purpose

Running the application from an IDE's run button, or `java -jar` by hand,
without `ALMIRA_ENV` now refuses to start against a development setup — no JWT
secret, no key, and a development database without page checksums. That is the
check working. Set `ALMIRA_ENV=development` in the run configuration; see the
repository README.

### Page checksums: decided, and why it is a refusal rather than a warning

`data_checksums` makes Postgres detect a flipped bit on a page instead of
serving it. It is **off by default** — `initdb`'s default, and off on this
project's development database today.

For most applications that is a shrug. Here it is not, for a reason specific to
what this stores: a zero-knowledge ciphertext is the one kind of data where the
server **cannot** tell that it has been corrupted, because reading it is exactly
the thing the server cannot do. Every other column would eventually look wrong
to somebody. A sealed field looks perfect until the day its owner opens it and
it fails — and by then the good backup may have rotated away. A silently
flipped bit in a financial record is worse than an outage, because an outage is
noticed.

So, three things, and they have to be in this order:

1. **The production database is created with `--data-checksums`.** It is an
   `initdb` flag. It cannot be turned on later without stopping the server and
   running `pg_checksums --enable` over every page, which is downtime
   proportional to the database size — so this has to be right *before* there
   is data, or it is an outage to fix.
2. **The application refuses to boot when it is off.** A provisioning step that
   can be skipped will be skipped. The check reads `show data_checksums` at
   startup and stops, so a year cannot pass on an unprotected volume.
3. **The check fails closed.** It relaxes only when the environment is
   *explicitly* `development` — never "unless production", never on a missing
   or unrecognised value. An environment-gated safety check is the kind that
   gets quietly disabled by a typo in a deployment variable, so the typo has to
   fail towards refusing rather than towards running.

**The development database stays as it is.** Enabling checksums there would
mean recreating the volume, which means destroying it, and this project does not
remove things from a working machine. The startup check warns there instead, so
the difference is visible without being fatal.

### Provider modes: absent is normal, nonsense refuses

`ProviderModeCheck` reads every `ALMIRA_PROVIDER_<NAME>_MODE` before the
application context exists. Each is `disabled`, `sandbox` or `live`
([Doc 13, "The switch"](13-providers-and-going-live.md#the-switch)).

| Setting | What happens |
|---|---|
| `disabled` (any provider, or all of them) | **Starts.** The provider is not offered: status `DISABLED`, calls answer 409 `provider_disabled`, a disabled notification channel is skipped. `aa` is `disabled` unless set — Account Aggregator is cut from v1. |
| `ALMIRA_PROVIDER_EMAIL_MODE=disabled` with `email` in `ALMIRA_SIGN_IN_CHANNELS` | Refuses, naming both (`SignInChannels`, as the context starts — not `ProviderModeCheck`): email sign-in would be offered with no way to send a code. Enable the email provider, or take email out of the channels (which ends the email alpha and signs email-only accounts out). |
| `off` | Refuses, naming `disabled`. It was the old spelling and used to crash startup for three providers. |
| `live` | Refuses: no live adapter exists for any provider yet. |
| anything else (`liev`) | Refuses rather than guessing. |
| `ALMIRA_OTP_PROVIDER` other than `log` | Refuses: `log` is the only one-time-code sender that exists. |
| `ALMIRA_OTP_SEND_TIMEOUT` zero, negative or over `15s` | Refuses. A code is sent once, under this timeout, with a person waiting; the default `5s` is right unless a provider measurably needs more ([Doc 13, "Interactive and background"](13-providers-and-going-live.md#interactive-and-background)). |

Proven on the real application context, each provider disabled alone and all
together (`ProviderDisabledStartupTest`), and once on the built jar with all six
disabled.

## 4 · The key, and what losing it means

`ALMIRA_KMS_MASTER_KEY` wraps every household's data key. Account numbers,
policy numbers and document contents are unreadable without it — including to
you.

- Back it up somewhere that is **not** the database backup, and not this host.
- The application **refuses to start** in production without one. That is
  deliberate: a deploy that "works" while protecting data with a key nobody
  chose and nothing durable holds is worse than one that will not start.
- `local` is the seam a managed KMS plugs into. With `aws` or `gcp` the key
  never leaves the KMS and `ALMIRA_KMS_KEY_ID` names it instead.
- Zero-knowledge fields ([Doc 12](12-end-to-end-encryption.md)) are a separate
  thing entirely: those are encrypted in the browser and this key does not open
  them. Nothing does, without the passphrase.

## 5 · Sign-in before SMS works

**This is the one thing that will block your first testers, so read it before
you invite anybody.**

Sign-in is **phone plus one-time code**, and **the only code sender that exists
is `log`, which works only when `ALMIRA_ENV=development`.** On a deployment,
`POST /api/v1/auth/otp/request` answers:

```
503  {"error":{"code":"otp_unavailable","message":"Sign-in by text message isn't available on this server yet. No code was sent."}}
```

No code is generated, stored, logged or returned. **Until a real SMS sender is
built, a deployed Almira cannot sign anybody in.** Health, the smoke test's
unauthenticated checks and restores all still work; anything behind sign-in
does not.

### Correction: the log-read alpha this section used to describe was a sign-in for anyone

Earlier versions of this section said the `log` sender was "workable for a
closed alpha where you are present and reading the log for each tester". That
was wrong, and worse than the risk it named (people who can read the log).

The `log` sender also told the API to return the code in the response, and
nothing checked the environment before doing so. Reproduced on 2026-09-13
against a jar built from `4ddf264`, with `ALMIRA_ENV=production`, a real signing
secret, a real key and the OTP provider left at its documented default — an
anonymous request for somebody else's number:

```
POST /api/v1/auth/otp/request {"phone":"+919000018168"}
200  {"requestId":"d6386b03-…","expiresInSeconds":300,"resendAfterSeconds":30,"developmentCode":"143003"}
```

Anyone who could reach the server could sign in as anyone. Nothing had been
deployed, so no account was exposed; the compose file and this document would
have shipped it. The same boot also wrote the code to the log at WARN, and
wrote a malformed request body's content to the log at ERROR.

After the fix, the identical request against the identical configuration
returns the 503 above, Redis holds no key for that number, and the log contains
neither the code nor the malformed body. With `ALMIRA_ENV=development` the code
is still returned and logged, which is what local development and every
end-to-end suite rely on.

**Do not work around this by deploying with `ALMIRA_ENV=development`.** That
restores the echo *and* relaxes the signing-secret, key and checksum checks at
the same time. A closed alpha before real SMS needs a different mechanism —
say, a short list of tester numbers whose codes go to an operator channel — and
that is a change to the authentication surface, to be designed and signed off
rather than configured.

What the code path now guarantees, each with a test that was watched failing
with its fix removed (`backend/src/test/kotlin/tech/almira/auth/`):

| Guarantee | Test |
|---|---|
| Outside development the log sender generates, stores and sends nothing | `OtpServiceTest` |
| The code is echoed only when development was explicitly chosen, whatever the sender claims | `OtpServiceTest` |
| Redis holds an HMAC keyed from the signing secret over purpose, phone and code — not a SHA-256 a million-entry table reverses | `OtpServiceTest` |
| A code works once, including when correct attempts race | `OtpServiceTest` |
| Wrong guesses are counted atomically; 48 parallel guesses get at most the five allowed | `OtpServiceTest` |
| Five-minute lifetime, 30-second resend cooldown, per-number and per-network hourly caps on requests | `OtpServiceTest` |
| At most 30 wrong codes per network per hour across all numbers; over it, even a correct code gets no verdict and the challenge is left for its owner | `OtpServiceTest`, `ClientAddressTest` |
| Length 6–8, lifetime ≤ 10 minutes and 1–10 attempts, or the application refuses to start | `OtpServiceTest` |
| The per-network cap counts the proxy-vouched address, not a client-written `X-Forwarded-For` | `ClientAddressTest` |
| No code reaches any log event (every logger at DEBUG), response body or header, across request, resend, wrong, malformed, right and reused, for sign-in and step-up | `OtpCodeNeverLeaksTest` |

Comparison is `MessageDigest.isEqual` over the stored and computed HMACs. That
is constant-time in the JDK, and because what is compared is a keyed MAC, a
timing difference would reveal MAC bytes rather than digits of the code. No
timing measurement was made; that sentence is reasoning, not a result.

**The reverse proxy must append to `X-Forwarded-For`** (nginx
`proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;`, Caddy by
default). Tomcat trusts the header only from loopback and private addresses —
which covers a proxy on the same host reaching the container through Docker —
and takes the right-most address that is not one of those. A proxy that passes
the client's header through untouched makes the per-network limit forgeable
again; a proxy on a public address needs `server.tomcat.remoteip.internal-proxies`.
The same address is what a guest-link view is recorded against, which had the
same forgeable-header bug until `ShareAuditAddressTest`.

**Real SMS in India** needs more than a provider account. Every message template
must be registered on a telecom operator's DLT portal along with the sender ID,
and unregistered templates are dropped silently by the operator. Budget days,
not hours. [Doc 13 §4](13-providers-and-going-live.md) has the sequence.

**Email sign-in exists for a closed alpha — and cannot deliver yet.** Earlier
versions of this paragraph said there was no email sign-in path. There now is:
`POST /api/v1/auth/otp/email/request` and `/verify`, with the same hardening as
phone (keyed storage, single use, atomic attempt counting, per-address and
per-network caps), restricted to `ALMIRA_ALPHA_EMAIL_ALLOWLIST`, and built so an
address outside the list gets an answer that cannot be told apart from one inside
it. `ALMIRA_SIGN_IN_CHANNELS=email` makes it the only sign-in, so no tester ends
up with two accounts. What it still lacks is a real email sender: no provider has
been chosen, so outside development every request answers 503 `otp_unavailable`,
exactly as phone does. The unblock is a sending domain (SPF, DKIM, DMARC) and a
provider account, then a live email adapter watched failing all four ways.
Two operational facts about the allowlist: **removing an address takes a
restart**, and that restart signs the tester out of every session they hold
(audited as `auth.session_ended_not_allowlisted`); and a sign-in email that
fails is **not shown to the tester**. Every email sign-in request, listed or
not, is queued, and the outbox worker sends a listed address's code and drops
anything else without calling the provider, so a stranger typing addresses
costs nothing. The code step shows `sent` at the same moment for every address
(`GET /api/v1/auth/otp/email/delivery/{requestId}`, which the reverse proxy
must pass through like the other auth endpoints) and, for everyone, "Didn't
arrive in two minutes? Contact us", linked to `ALMIRA_SUPPORT_CHANNEL` when it
is set — so set it before inviting testers. A failed send is logged for the
operator: `ERROR SIGN-IN EMAIL REFUSED` when the provider refuses a listed
address, ProviderCalls' WARN and `PROVIDER ACCOUNT PROBLEM` ERROR otherwise.
[Doc 13 §5](13-providers-and-going-live.md) has the design and
[Doc 18 §3](18-handover.md) the alpha sequence.

## 6 · Backups, and restores that prove themselves

A backup is two things taken together, and there are scripts so nobody has to
remember the flags:

```bash
# Take one: database, documents, and a manifest that a restore checks against.
./scripts/backup.sh --project almira-prod --env-file .env.production --out /srv/backups

# Prove it, into an EMPTY stack — this is the half people skip.
./scripts/restore.sh --project almira-prod-drill --env-file .env.drill \
  --from /srv/backups/almira-<timestamp>
```

`backup.sh` writes `database.dump` (pg_dump custom format), `documents.tgz`
(the documents volume), and `manifest.json` — the sha256 of both files, the row
count of every table *as it is in the dump*, the migration version, and the
server's checksum setting. It takes the database first and the documents second,
because documents are only ever added or soft-deleted, so every document row in
the dump has its file in a tarball taken just after. It checks everything it
needs — the database answering, the documents volume existing, the bodies table
being where it expects — **before** it writes the dump, so a refused backup
leaves nothing behind. On a deployment whose documents are in object storage it
refuses unless told, explicitly, that they are not in this backup ("Documents
in object storage", below).

**Queued bodies are deliberately not in the backup.** The dump has the
`outbound_message_bodies` and `sign_in_code_email_bodies` tables but none of their
rows (`pg_dump --exclude-table-data`), so a backup taken while a reminder or a
sign-in email was waiting to go holds no rendered message and no queued sign-in
(address and request id, from which the server derives a live code). A backup
holding them would be a credential store (owner's decision, 2026-09-15). The
manifest lists both under `excluded_table_data`. Because the exclusion matches
by name and is silent when it matches nothing, `backup.sh` refuses if any table
whose name contains `bod` is not one of the two it knows. On the restored server a message that was still queued is recorded failed,
once, as `body_not_restored` and is not sent ([Doc 13](13-providers-and-going-live.md),
"After a restore").

**The key-encryption key is deliberately not in the backup.** Account numbers
and documents are unreadable without it, so a backup that carried it would be a
backup that carried the data in the clear. Keep `ALMIRA_KMS_MASTER_KEY` somewhere
that is neither this host nor these files (§4). A database restored without the
documents leaves every holding pointing at a missing scan; documents restored
without the key are ciphertext; a restore with the wrong key now refuses to
start (below). **An untested backup is a hope, not a backup.**

### The restore refuses to lie to you

`restore.sh` stops at the first thing that is wrong, in this order:

1. **The files match the manifest's sha256.** A single flipped byte in the dump
   or the tarball stops it here.
2. **The target is empty and protected**: no table in the database, page
   checksums on, and an empty documents volume — all checked before anything is
   written to the target. It will not restore over a database that already has
   tables — that is a merge nobody designed — and it will not restore onto a
   volume without `--data-checksums`, because the application would refuse to
   start on it anyway (§3) and learning that after a two-hour restore is worse
   than learning it now. (The documents volume used to be checked only after
   `pg_restore`, which left a restored database beside a volume it then refused.)
3. **The runtime role**, created by the same idempotent bootstrap as a fresh
   install, so the dump's grants have a role to land on.
4. **pg_restore**, stopping on the first error.
5. **The documents**, extracted into the volume checked in step 2.
6. **Verify** — the part that makes it a proven restore rather than a completed
   one:
   a. every table has the row count the manifest recorded;
   b. the **structural sweep** (below);
   c. the **stored-digest check** (below).

### Two ciphertext checks, cheap one first

A backup that restores is not the same as a backup that restored **correctly**,
and for sealed fields the difference is invisible: the server cannot read them,
so a truncated or bit-flipped ciphertext restores without complaint and fails
months later in front of the one person who needed it.

**The structural sweep** (`deploy/restore/sweep.sql`). Every `sealed_values`
ciphertext must be valid base64url, at least 33 bytes decoded, version byte `1`,
envelope key version ≥ 1; every `e2e_keys` row must have a salt of at least 16
bytes and a wrapped key and verifier that parse the same way, and iterations
≥ 100 000. It applies the **client's** parse rules, which are stricter than what
the server accepts on write — so a row that fails here is one no conforming
client could open, whether a restore damaged it or it was written malformed. No
schema change; it reads what is already there and catches truncation and header
damage, which is what a bad restore actually looks like. It names the exact row,
because a sweep that only says "something is wrong" sends someone to read a
million rows by hand.

**The stored digest** (`V31`, checked by `deploy/restore/digest-check.sql`). A
server-computed SHA-256 of each ciphertext, written beside it by a trigger and
compared on restore. It closes the one case the sweep cannot see: a flipped bit
*inside* a well-formed envelope, which parses perfectly and simply will not
open. It is last because it is the only one needing a schema change, and it is
meaningful only after a **whole** restore — pg_dump loads data before it creates
triggers, so the digests come back as they were written; a data-only restore
would recompute them from the restored bytes and pass on anything. It is **not**
an integrity control against an attacker (anyone who can change a ciphertext
through SQL fires the trigger too); see [Doc 12 §9](12-end-to-end-encryption.md).
It catches accidental damage between a write and a restore, and nothing else.

When a row is named, `./scripts/restore-row.sh --table … --id …` copies that one
row's ciphertext back from the backup — triggers suppressed, so it does not
stamp a new digest over damaged bytes — and re-runs both checks. **Before** it
writes, it checks the backup's copy of that row against the sweep's rules and its
stored digest, and that the live row exists. If the backup's copy is also bad,
that is an older-backup problem: the script says so and the live row keeps the
bytes it had. (It used to write first and find out from the re-check.)

### Watched failing, not just watched passing

Every check above was watched failing before it was trusted (docs/19), on a real
restored copy on this machine:

- a `sealed_values` ciphertext **truncated** to 30 bytes: the sweep named that
  row (`decodes to 30 bytes; the smallest envelope is 33`) and no other, the
  restore stopped, `restore-row.sh` put it back byte-for-byte, and the field
  opened again;
- a **single body byte flipped** through a trigger-suppressing write: the sweep
  passed (the envelope is still well-formed) and the **digest check** caught it;
  the same flip through an ordinary `UPDATE` did *not* trip it, because the
  trigger recomputed the digest — the documented limit, not a bug;
- one **on-disk page byte flipped** in the stopped database's volume: Postgres
  refused the page with `invalid page in block 0` and `page verification
  failed`, which is the checksum layer (§3) doing the job the digest sits behind;
- a **whole-application round trip**: seed two members with a private holding, a
  stored account number, an uploaded document and three sealed values (including
  an empty one), back up, restore into an empty stack, start the app, and read
  every piece back *through the API* — the second member still saw a different
  total (RLS survived), the account number decrypted, the document downloaded
  byte-for-byte, and each sealed value opened with its passphrase in an
  independent implementation of the client envelope
  (`deploy/restore/drill/`, checked against docs/12's fixed vector).

### One more refusal the drill turned up

Starting a restored copy with the **wrong** `ALMIRA_KMS_MASTER_KEY` used to boot,
report ready, and fail only when someone revealed an account number — as a 500.
That is the likeliest mistake in a restore, since the key lives somewhere else on
purpose, and it looked like a healthy deployment. The application now checks at
startup that the loaded key opens the household keys already in the database, and
**refuses** if it does not, naming the key it has and the key the data expects.
An empty database passes; a key that matches some households and not others warns
rather than taking the matching ones down. Reproduced both ways in a container:
the right key logs `opens all N household key(s)` and serves; the wrong key logs
`Refusing to start — ALMIRA_KMS_MASTER_KEY is not the key this database was
encrypted with` and does not.

Since 2026-09-15 it refuses **before Flyway migrates**. It used to wait for the
migrations so that it could read the table, which meant a restored copy started
with the wrong key had every pending migration applied to it and then refused.
A database with no key table yet (a fresh install) has nothing to check.

### The scripts' own refusals are tested

`scripts/tests/` runs `backup.sh`, `restore.sh`, `restore-row.sh`,
`bootstrap-prod-db.sh`, `freeze-api-spec.sh` and `smoke-prod.sh` for real —
against throwaway Postgres containers and volumes it removes afterwards, or with
a stub `curl` — and proves that each refusal comes **before** the thing it
refuses: no dump written, no `pg_restore` run, no live row overwritten, no
frozen spec emptied, nobody signed in. `./scripts/tests/run-all.sh` runs them
all; `SCRIPT_TEST_PREFIX` names the containers.

### A drill with nothing but Docker

The drill above needs the compose stack. The database half of it can be proven
on any machine with Docker, and should be, every time a migration lands:

```bash
./scripts/restore-drill-local.sh            # two throwaway containers, removed at the end
```

It starts a Postgres with page checksums, applies every migration in version
order, seeds a small household **through row-level security as the runtime
role** (`deploy/restore/drill/local-seed.sql`: an owner's private FD, an admin
spouse's private SIP, a child's FD, a shared holding, a loan, a measurement
count), takes a `pg_dump -Fc`, restores it into a second empty container with the
runtime role created first, and stops unless all of these hold on the copy:

- every table has exactly the rows it had in the source;
- every table's RLS switch and every policy's text are identical, so a policy
  that did not come back is named, not guessed at;
- each of the two adults sees exactly their own private holding, and the runtime
  role still cannot read the measurement counts;
- `db/tests/rls_privacy_test.sql` passes against the copy (it rolls itself back);
- the ciphertext sweep finds nothing.

First run, 14 Sep 2026, on a developer machine: 57 tables and 186 rows equal, 53
tables with RLS on and 201 policies identical, 70 privacy assertions passed.
**Watched failing** three ways, by changing the copy straight after
`pg_restore`: one table's rows deleted (named, with both counts), RLS disabled on
`investments` (named), and one policy dropped (named, with its text).

It does not take the documents volume, start the application or check the KMS
key; `backup.sh`, `restore.sh` and `deploy/restore/drill/` above do.

`--from <backup>` drills a backup `backup.sh` took, instead of a fresh dump: it
checks where the backup's documents are (above) and that `database.dump` matches
its manifest before starting anything, restores the dump into one throwaway
container, and requires the manifest's row counts, the privacy suite and the
sweep to hold. The source-comparison of policies and the seeded-people check
have nothing to compare with and say so.

**The backup on the developer machine, replaced** (2026-09-15). The only backup
set there was the deploy session's drill of 13 Sep (`almira-20260913T094311Z`:
migration 31, 55 tables and 239 rows of synthetic data, one document). It was
removed, and a new one taken from current code the way this section says: the
production image built from `22d0060` with `deploy/Dockerfile`, the production
compose file with the drill overlay under the throwaway project
`ws-signin-backup-ops-source` and its own env file (filesystem documents),
db-bootstrap, `drill.py seed` (two members, a private holding, a revealable
account number, a 4,118-byte document, three sealed values), then
`scripts/backup.sh`. It is `almira-20260915T044027Z`: migration 130, PostgreSQL
16.14 with checksums on, 100 tables and 286 rows in the dump, `documents.tgz`
with the one document, `documents.in_this_backup: true`, and
`outbound_message_bodies` left out. Proven three ways before both stacks were
removed: `restore.sh` into an empty second project (sha256, empty target, 100
tables and 286 rows equal, sweep 0 defects, 5 digests match), the restored app
started with the same KMS key and `drill.py verify --manifest` read every piece
back through the API (RLS totals, account number, document byte for byte, all
three sealed values opened), and `restore-drill-local.sh --from` (rows equal, 96
tables with RLS and 338 policies, 298 privacy assertions, no defective
ciphertext). On the same stack, `ALMIRA_BACKUP_DOCUMENTS=external` was refused by
`backup.sh` and by `restore.sh --verify-only` with nothing written. It is kept
outside git with its KMS key in a separate directory; both are synthetic.

### Documents in object storage

`ALMIRA_STORAGE_PROVIDER=s3` stores documents in S3 or anything S3-compatible
(R2, MinIO, an Indian-region provider) instead of the documents volume. The
filesystem stays the default, and nothing S3 is constructed or called unless this
is set. What reaches the bucket is the same ciphertext the filesystem holds, so a
misconfigured bucket leaks nothing readable; a private bucket is still the rule.

It refuses to start without `ALMIRA_S3_BUCKET` and `ALMIRA_S3_REGION`, with only
one of the two keys, or when a `HeadBucket` with these credentials fails; an
unknown provider name also refuses (`StorageProviderCheck`). Leave both keys
empty to use the SDK's default chain (an instance role). Most S3-compatible
stores want `ALMIRA_S3_PATH_STYLE=true`. Keys are `ALMIRA_S3_PREFIX`
(`documents/`) + `<household>/<document>`.

Proven by `S3DocumentStorageTest` and `S3DocumentApiTest` against MinIO; not yet
run against a real provider.

**The bucket is not in `backup.sh`, and a backup says so rather than let anyone
assume** (owner's decision, 2026-09-15: *neither refuse nor warn — require
explicit acknowledgement*). With `ALMIRA_STORAGE_PROVIDER=s3` in the env file,
`backup.sh` refuses to run — before it so much as asks the database whether it
is up — unless the command is run with `ALMIRA_BACKUP_DOCUMENTS=external`:

```bash
ALMIRA_BACKUP_DOCUMENTS=external ./scripts/backup.sh --project almira-prod --env-file .env.production --out /srv/backups
```

With it, the backup takes no `documents.tgz`, prints `DOCUMENTS ARE NOT IN THIS
BACKUP. They live in S3 bucket '…' under prefix '…'` at the start and the end,
and writes the same into `manifest.json`:

```json
"documents": {"in_this_backup": false, "provider": "s3", "bucket": "almira-docs",
              "prefix": "documents/", "region": "ap-south-1", "endpoint": "",
              "acknowledged_with": "ALMIRA_BACKUP_DOCUMENTS=external", "note": "Documents are NOT in this backup. …"}
```

The bucket, prefix, region and endpoint are named; no key is, and an endpoint
is cut to scheme, host and port so a user, password or signed query in it
never reaches a backup. A filesystem backup records
`{"in_this_backup": true, "provider": "filesystem", "file": "documents.tgz"}`
and behaves exactly as before; `ALMIRA_BACKUP_DOCUMENTS=external` with the
filesystem provider is a contradiction and refused, and so is any value of it
but `external`. The flag is read from the command's environment, not the env
file, so it is a decision made per invocation (a cron line says it in plain
sight).

`restore.sh` applies the same rule as its step 0, from the files alone and
before anything is started or written (`--verify-only` included): a manifest
that says the documents are not in the backup needs the same flag and says, at
the start, at step 5 and at the end, that the documents are not restored; a
backup that holds its documents refuses the flag; a backup whose documents are
in a bucket refuses a target on the filesystem provider, where every document
would be missing; and a manifest from before the field is treated per the
target env file's provider. `restore-drill-local.sh --from` and `drill.py verify
--manifest` refuse the same way before they start a container or ask the
server anything. The one implementation is `scripts/lib/backup_documents.py`;
`scripts/tests/backup-checks-before-dumping.sh` and
`restore-checks-before-writing.sh` prove every refusal leaves nothing behind
(watched failing with each check moved after its action). Turn on the
provider's object versioning or replication: that, not these backups, is what
brings documents back.

**Refusing a filesystem target is the rule; data-only testing has one override,
named so nobody types it by accident** (owner's decision, 2026-09-15: *a restore
that produces a database where every document is a broken link is worse than no
restore*). For a test that needs the rows and not the files:

```bash
ALMIRA_BACKUP_DOCUMENTS=external \
ALMIRA_RESTORE_DOCUMENTS_ABSENT=every-document-will-be-a-broken-link \
  ./scripts/restore.sh --project almira-datatest --env-file .env.datatest --from /srv/backups/almira-…
```

It works only for the case it exists for — a backup whose documents are in a
bucket, into a filesystem target — and only with the acknowledgement too. Any
other value, or the override where no document would be missing, is refused
before anything is written, and `restore-drill-local.sh --from` refuses it (a
drill restores no documents to be absent). The restore prints `DATA-ONLY TEST
RESTORE: DOCUMENTS ABSENT` at the start and the end, and once the database is
in, appends to the backup's own `manifest.json`:

```json
"documents_absent": [{"restored_at": "…", "project": "almira-datatest", "target_env_file": ".env.datatest",
                      "override": "ALMIRA_RESTORE_DOCUMENTS_ABSENT=every-document-will-be-a-broken-link", "note": "…"}]
```

The manifest's file hashes cover the dump and the tarball, not the manifest, so
the backup still verifies; the stamp is written to a temporary file and renamed,
and a manifest the restore cannot write to is refused in step 0. Never run the
service on such a restore.

### The single small VPS this assumes

One box runs Postgres, Redis and the application (§1). The compose file caps the
app container's memory, because the JVM otherwise sizes its heap from the host's
total and leaves Postgres to the OOM killer on a 4 GB machine. A restore drill
brings up a **second** stack beside the live one; it must use a different project
name, its own volumes and a different host port (`deploy/restore/drill/` shows
the overlay). DigiLocker requires a server located in India, so the region is
constrained to an Indian one rather than free — say so to whoever provisions the
box. None of this has run on a real VPS yet; it has run only in matching
containers on one developer machine, and the first real deploy should expect to
correct a line.

### The environment-variable contract

`.env.production.example` is the authoritative list, with a REQUIRED/optional
marker on each. What matters at deploy time, beyond the four secrets in §1:

- **Required, no safe default:** `ALMIRA_DB_NAME`, `ALMIRA_DB_OWNER_USER`,
  `ALMIRA_DB_APP_USER` and the passwords; `ALMIRA_JWT_SECRET`,
  `ALMIRA_KMS_MASTER_KEY`, `ALMIRA_REDIS_PASSWORD`. `ALMIRA_IMAGE_TAG` is
  required by the compose file — the git commit you built, never `latest`.
- **Compose-only knobs, with defaults:** `ALMIRA_HOST_PORT` (loopback port,
  default 8080, so a second stack can sit beside the first) and
  `ALMIRA_APP_MEMORY` (container cap, default 1536m).
- **Optional, with application defaults — do NOT stub these as empty in the
  compose file, because an empty value overrides the default:**
  `ALMIRA_OTP_MAX_PER_IP_PER_HOUR`, `ALMIRA_OTP_MAX_VERIFY_FAILURES_PER_IP_PER_HOUR`,
  `ALMIRA_SIGN_IN_CHANNELS` (default `phone`), the per-provider
  `ALMIRA_PROVIDER_*_MAX_ATTEMPTS` / `_RETRY_BACKOFF`, the
  `ALMIRA_PROVIDER_EMAIL_*` group, `ALMIRA_OTP_SEND_TIMEOUT` (default `5s`, must
  be >0 and ≤15s) and `ALMIRA_OUTBOX_POLL_INTERVAL` (default `PT2S`).
- **Optional, off unless set:** `ALMIRA_OPS_HEALTH_TOKEN` (at least 32
  characters; a shorter one refuses to start), the operator's view of `/health`
  (§8).
- **Before 13 May 2027, not optional in practice:** `ALMIRA_GRIEVANCE_NAME`
  and `ALMIRA_GRIEVANCE_EMAIL`, the grievance contact every rights reply names
  (docs/23 "Your data rights"). Unset, the server starts and the page says no
  contact has been named. `ALMIRA_GRIEVANCE_RESPONSE_DAYS` (default 30) outside
  1–90 refuses to start.
- **The one footgun:** enabling the email channel
  (`ALMIRA_SIGN_IN_CHANNELS` including `email`) with an **empty**
  `ALMIRA_ALPHA_EMAIL_ALLOWLIST` **refuses to start**, by design — an open email
  sign-in is a sign-in for anyone. Set the allowlist, or leave email off. Email
  still cannot deliver outside development until a live adapter exists (§5).

The compose file passes the required set and the two knobs by value, and the
sign-in variables (`ALMIRA_SIGN_IN_CHANNELS`, `ALMIRA_ALPHA_EMAIL_ALLOWLIST`,
`ALMIRA_OTP_SEND_TIMEOUT`, `ALMIRA_OTP_MAX_VERIFY_FAILURES_PER_IP_PER_HOUR`, the
`ALMIRA_WEBAUTHN_*` passkey settings) and the `ALMIRA_PROVIDER_SMS_*`, `_EMAIL_*`
and `_PUSH_*` groups **by name only** (`ALMIRA_SIGN_IN_CHANNELS:` with no value).
Compose passes a name-only variable through when `.env.production` sets it and
leaves it unset in the container when it does not, so the application's default
still applies — the reason above. Until 2026-09-14 these were not listed at all,
and setting them in `.env.production` did nothing: a production stack could not
be switched to the email alpha. Check what the container will get with
`docker compose -f deploy/docker-compose.prod.yml --env-file .env.production config`.
No provider is chosen by this; a live SMS or email adapter is still to be written
(§5).

## 7 · The web client is installable

The client is a PWA: a manifest, icons, and a service worker that caches the app
shell. "Add to Home Screen" works on Android Chrome and iOS Safari, and the
shell opens without a network.

What it does **not** do, deliberately: **no API response is ever cached.** Cache
Storage is persistent per-origin storage, so a cached response would leave a
family's balance sheet readable on a shared laptop long after they signed out,
with none of the protections the rest of the product spends its effort on.
Offline, the shell opens and says it needs a connection. Offline data sync is a
separate project with a synchronisation model attached to it, and is out of
scope.

**The one exception is opt-in and is not a cache** (P-21). On a device where
someone turns on Settings → *Readable without a connection*, the page keeps an
encrypted copy of the family handbook and the people who help, and shows it
when the API cannot be reached. It is an allowlist of fields, encrypted under a
non-extractable WebCrypto key held apart from it, deleted on sign-out and after
30 days, and it never passes through the service worker. Doc 16 §2 "The
offline copy" is the threat model.

The libraries under `/app/vendor` (pdf.js, tesseract.js and its English model —
`app/vendor/SOURCE`) are cached the first time they are used, in a cache of
their own that a shell change does not discard; they are never precached. Only
opening a statement or reading a photo fetches them, from this server.

Two notes for whoever verifies it:

- It needs **HTTPS** (or localhost). A service worker will not register over
  plain HTTP on a real hostname, so the install prompt will not appear until TLS
  is in front of it.
- The cache turns over by itself. The build appends a fingerprint of every
  static file to the worker's `VERSION` (`almira-v32+<12 hex>`), so a changed
  asset changes the served `sw.js`, and the build fails if the `VERSION` line is
  missing (known-issues 3).
- `scripts/check-service-worker.js` asserts the routing decisions — shell
  precached, `/api` never cached, offline navigation falls back to the shell,
  the vendor cache survives a shell change — without a browser.
  `scripts/browser-checks/serve.py` serves `on-device.html`, which checks the
  offline copy's encryption and deletion, a protected PDF and a photo in a
  real browser. Installability and the offline launch itself still need a
  real device; they could not be verified on the machine this was built on.

## 8 · Watching it: health, logs and alerts

Three probes, none of which needs a session or carries household data:

| Path | Answers | Use it for |
|---|---|---|
| `/health/live` | `200 {"status":"alive"}` while the process serves HTTP; touches nothing else | a restart policy. Never make it depend on the database: that restarts a healthy app in a loop through a database outage |
| `/health/ready` | `200` when the database answers as the runtime role **with RLS in force** and Redis answers; `503` naming which is false | the load balancer and the compose healthcheck |
| `/health` | the role name, `rlsEnforced` and the environment; with the operator token, also `signInEmailNotDelivered` (below) | a human, and the outside check below |

**The outside check.** `./scripts/check-health.sh --base https://<host>` probes all
three and prints one line; it fails when the app is down, not ready, not enforcing
RLS, or running with development settings. Run it every minute from somewhere
that is **not the host** (a cron on another box, or any uptime monitor pointed at
`/health/ready`), with `--alert-cmd` set to your own mail, SMS or chat command. It
sends nothing anywhere by itself.

**The operator's view.** Set `ALMIRA_OPS_HEALTH_TOKEN` (`openssl rand -hex 32`) and
`/health` requested with `X-Almira-Ops-Token: <it>` also answers
`"signInEmailNotDelivered": {"lastHour": N, "newest": "<time>"}` — how many sign-in
emails an address **on the allowlist** did not get in the last hour. Put the same
token in a file only the checking user can read and add
`--ops-token-file <file>`: the check then fails, and runs `--alert-cmd`, while N is
above zero, and also when the view is missing (a wrong token, or none set on the
server), so a broken check is not a quiet one. The token goes as a header read
from a private temporary file, never on a command line. Without the header, or
with a wrong one, `/health` is byte-for-byte what everybody else gets: a public
count would tell someone who had just asked for a code whether that address is
listed ([Doc 13](13-providers-and-going-live.md) §5). It is not on
`/health/ready`, so an alert never takes a server out of rotation.

**Logs.** The production compose file sets `ALMIRA_LOG_FORMAT=json`: one object
per line, `ts`, `level`, `logger`, `thread`, `message`, and for an error `error`
(the exception classes) and `frames`. Deliberately **no MDC and no exception
messages**, because a message is where personal data escapes (a constraint
violation quotes the phone number that collided); emails and runs of ten or more
digits are scrubbed from the message as a second line. Call sites already mask
phones and emails. `text` is the readable default for a terminal; any other value
refuses to start. The application's own loggers run at DEBUG (`application.yml`),
which is a lot of transaction lines for a collector: set
`LOGGING_LEVEL_TECH_BHRIGU_ALMIRA=INFO` in production if volume matters. Checked
on a running jar: 20 000 lines from a load test parsed as JSON, and none held a
test phone number.

### When to be woken, and what to do

Alert on these, most urgent first. Each has the first thing to look at.

| Signal | Means | First move |
|---|---|---|
| `check-health.sh` fails on `live` for 2 minutes | the app is down | `docker compose … ps` and `logs app --tail 200`; a refusal to start names its reason in one line (§3) |
| `ready` is 503 with `rlsEnforced:false` | the app is connected as the schema owner: **every member can see every record**. The startup check (§3) should make this impossible, so it also means that check was bypassed or the role was changed while running | stop the app now (`… stop app`), then fix `ALMIRA_DB_APP_USER` (§2). Do not wait for users to leave |
| `ready` is 503 with `database:false` or `redis:false` | nobody can sign in | Postgres or Redis container health, disk space (`df -h`), memory (§6 "single small VPS") |
| any `"level":"ERROR"` line | an unhandled server fault, answered `500 internal_error` | the `logger`, `error` and `frames` fields; the request that caused it is not logged by design, so reproduce from the route |
| repeated `one-time code by sms failed` or `… by email failed` (WARN) | sign-in codes are not being delivered | the provider's status page and balance; the `Providers:` line at startup says which mode each is in |
| `SIGN-IN EMAIL NOT DELIVERED` (ERROR), or `check-health.sh --ops-token-file` failing on it | a tester on the email allowlist was shown their code as sent and did not get it: the provider failed or refused it, a stopped worker left it unconfirmed, it expired in the queue, or email sign-in was switched off while it waited. The line has the outcome and the failure kind, never the address. Unlisted addresses never raise it | the provider (`WARN PROVIDER CALL FAILED`, `ERROR PROVIDER ACCOUNT PROBLEM`, `ERROR SIGN-IN EMAIL REFUSED` with the masked address); then, on the owner connection, `select status, failure, finished_at from sign_in_code_emails where operator_alert order by finished_at desc`. Tell the tester to ask again once it is fixed |
| `MESSAGES LOST IN A RESTORE` (ERROR), once per restore, or `check-health.sh --ops-token-file` failing on it for the hour after | queued messages — reminders, notices, sign-in emails — were waiting when the backup was taken, came back without their bodies (backups leave them out), and will never be sent. The line has the count, the restore's time, whether `restore.sh` recorded it and the backup's name; never a message or a person | on the owner connection, `select channel, template, created_at from outbound_messages where failure = 'body_not_restored'` and `select count(*) from sign_in_code_emails where failure = 'body_not_restored'`. Decide whether anyone needs telling by hand: a reminder due soon, or a tester who asked for a code just before |
| `DORMANT HOUSEHOLD NEEDS AN OPERATOR` (ERROR), once per household | a dormant household has been open to everyone in it for 90 days and nobody took it on, so the app no longer lets anyone take it on (V147). The line has the household id and dates, never a name | `./scripts/dormancy-repair.sh … list`. Either a documented repair (evidence seen, a second operator or a stated reason for acting alone, the wait counted from the notice being sent, or from one recorded as given by post, phone or in person, which then needs two operators — docs/05 §12.7), or a deliberate decision to leave it dormant, written down |
| `notification outbox drain failed` (WARN) more than a few times an hour | reminders are queuing, not sending | database health, then the provider |
| the newest backup directory is older than 26 hours | backups have stopped | the cron that runs `backup.sh`, disk space |
| `/health` says `"environment":"development"` | development settings in production (codes echoed, checks relaxed) | set `ALMIRA_ENV=production` and restart |

After any incident that users could see, say so in plain words where they will
look, with what is wrong and when it will be fixed, before they have to ask.

## 9 · Load, measured once on a laptop

`./scripts/load-test.sh --base http://127.0.0.1:<port>` signs `--users` people in
on a development server, gives each a household and three holdings, then sends
`--requests` at `--concurrency`: 45% dashboard, 25% holdings list, 15% `/me`, 10%
adding an FD, 5% still-true. It reports p50/p95/p99/max per kind and fails on any
non-2xx or a p95 over `--p95-ms` (800 by default). It refuses anything that is not
loopback, not in development mode, or a port a personal stack publishes. Start the
server on a throwaway database with `ALMIRA_OTP_MAX_PER_IP_PER_HOUR` above the
number of people.

Run once, 14 Sep 2026, against the built jar on a developer laptop, with a
throwaway Postgres and Redis in Docker, **while the machine was also running
other test suites (load average 40–66)**. Treat the numbers as a floor, not a
capacity figure:

| Run | Requests | Throughput | p50 | p95 | p99 | Failures |
|---|---|---|---|---|---|---|
| 5 people, one at a time | 500 | 10/s | 38 ms | 195 ms | 472 ms | 0 |
| 20 people, 20 at a time | 2 000 | 37/s | 401 ms | 1 178 ms | 1 618 ms | 0 |

No request failed under either. The dashboard is the slowest read (p95 1 214 ms
at 20 concurrent) and adding a holding the slowest write (p95 1 322 ms). The
20-at-a-time run is over the 800 ms budget; whether that is this laptop's load or
the application needs a run on the real host with nothing else on it, which is
the first thing to do after the first deploy.

## 10 · The public website

`site/` is the website people read before they decide to trust Almira: a home
page, security, what we never do, and placeholders for pricing, what we measure
and status, with Telugu and Hindi home pages as unreviewed drafts. **It has not
been deployed.** Nothing below has been run against a real host.

It is plain files. There is no build step, no script on any page, and nothing
fetched from another host: the fonts are committed under `site/assets/fonts`
with their licences, and every page carries a Content-Security-Policy meta that
would block anything else. `python3 scripts/check-site.py` (also run by
`scripts/dev.sh test`) fails if that stops being true — a script, an embed, an
off-origin URL, a broken link, a colour that has drifted from the app's tokens,
text below 13px, or font bytes that are not the ones `fonts/SOURCE` records.

### Where it goes

Serve it from its own hostname, separate from the app's. The app's origin holds
sessions and a service worker; a marketing page has no reason to share either,
and a separate origin means a mistake on one cannot script the other.

Say the site is `example.in` and the app `app.example.in` (neither is decided —
[Doc 13](13-providers-and-going-live.md) needs the domain for email sign-in
anyway). With Caddy, which is one of the terminators §1 names:

```caddy
example.in {
	root * /srv/almira/site
	file_server

	# Every page's "Sign in" links here; this is the only place that knows
	# where the app lives. A 302, so changing the app's host later is one edit.
	redir /sign-in https://app.example.in/ 302

	header {
		# The meta tag in each page cannot set frame-ancestors; the header can.
		Content-Security-Policy "default-src 'none'; style-src 'self'; img-src 'self'; font-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
		Strict-Transport-Security "max-age=63072000; includeSubDomains"
		X-Content-Type-Options "nosniff"
		Referrer-Policy "no-referrer"
		Permissions-Policy "camera=(), microphone=(), geolocation=()"
		-Server
	}
	@fonts path /assets/fonts/*
	header @fonts Cache-Control "public, max-age=604800"

	# The site promises it measures nothing. Access logs with addresses in them
	# are a measurement; keep them off, or short-lived and addressless.
	log {
		output discard
	}
}
```

`includeSubDomains` on the apex covers the app's host too, which is already
HTTPS-only (§4 of Doc 15), so it costs nothing — but check that no other
subdomain still serves plain HTTP before you turn it on.

Copy the directory with `rsync -a --delete site/ host:/srv/almira/site/`. Any
static host that can set response headers and a redirect will do the same job;
one that injects its own analytics or a cookie banner will not, because the
footer on every page says there is none.

### Before it goes up

- **The Telugu and Hindi pages** say on the page that they are unreviewed, and
  ask not to be indexed. Leave both until a native speaker has read them; then
  remove the notice and the `robots` meta together (the check insists on both
  while the notice is there).
- **Placeholders** — pricing, what we measure, status, the security contact
  address and the pen-test summary are honest placeholders, not finished
  pages. Known-issues 25 lists what each is waiting on.
- **When the mark changes**, `brand/render-icons.py` rewrites the app's
  `static/icons/favicon.svg` but not `site/assets/mark.svg`; copy it across. The
  check fails until you do.
- **When a colour token changes** in `static/app/tokens.css`, change it in
  `site/assets/site.css`. The check fails until you do.
- **Status is written by hand.** During an incident it is updated as
  [Doc 26](26-incident-response.md) describes, by editing `site/status.html` and
  copying the directory again. Keep the host's credentials where the on-call
  person can reach them without the app being up.

## 11 · Not covered here

Named rather than implied:

- **Metrics and log collection.** Logs are JSON on stdout and the probes are
  above; nothing in this repository collects, stores or graphs either, and the
  alerts in §8 need something (an uptime monitor, a log shipper) to watch for them.
- **A live public status.** `site/status.html` carries the one-line public status
  ("Nothing to report", or what is wrong and when it will be fixed) and is hosted
  apart from the app (§10), but a person edits it by hand when the outside check
  in §8 raises an alarm; nothing updates it automatically.
- **Automated backups.** The commands above are manual; §8 says what to alert on
  once a cron runs them.
- **A deploy to a real host.** Everything above has run in containers on one
  developer machine only.
- **Zero-downtime deploys.** `up -d --build app` restarts the container.
- **Horizontal scaling.** Nothing prevents more than one instance — sessions and
  rate limits are in Redis, not in memory — but it has not been tried.
- **An external penetration test.** Still to schedule, against a deployed
  environment ([Doc 15 §11](15-security-whitepaper.md)).

[‹ Index](README.md)
