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
docker compose -f deploy/docker-compose.prod.yml --env-file .env.production up -d db redis
docker compose -f deploy/docker-compose.prod.yml --env-file .env.production run --rm db-bootstrap

# Build and start the application. Flyway migrates on first boot.
docker compose -f deploy/docker-compose.prod.yml --env-file .env.production up -d --build app

# The one check that matters before anybody signs in:
curl -s http://127.0.0.1:8080/health
```

That last command must print:

```json
{"status":"ok","database":"up","dbRole":"almira_app","rlsEnforced":true,"environment":"production"}
```

**If `dbRole` names the schema owner, stop.** PostgreSQL lets a table's owner
bypass its own row-level security, so the application would be reading with
every privacy policy switched off — and nothing else would look wrong. The
runtime role exists for exactly this, and `/health` reports it so the mistake is
visible from outside, without signing in.

**If `environment` is not `production`**, one-time codes are being echoed in API
responses and the encryption checks are relaxed.

Then point your TLS terminator at `127.0.0.1:8080` and run the smoke test:

```bash
./scripts/smoke-prod.sh https://almira.example.com
```

It signs two people into one household and asserts they see **different** net
worth. That single property is what the whole product rests on: if it holds
through a real deployment — proxy, pool, runtime role and all — then privacy is
enforced end to end. It writes data, and prints the SQL to remove it afterwards.

## 3 · What refuses to start, and why

Two checks fire before the first request, both for the same reason: a
deployment that runs with a development placeholder looks entirely healthy, and
nothing about it appears wrong.

| Missing or default | What happens |
|---|---|
| `ALMIRA_KMS_MASTER_KEY` | Refuses to start. Data encrypted with a key nobody chose, kept nowhere durable, is worse than an application that will not boot. |
| `ALMIRA_JWT_SECRET` still the development value, or shorter than 32 characters | Refuses to start. The default is printed in this repository; anybody could mint a session with it. |
| **Postgres `data_checksums` is `off`** | **Refuses to start.** *(decided; lands with the item-4 artefacts — see below)* |

All three are relaxed in `development` so the app runs out of the box with no
configuration — which is exactly why the environment flag has to be right.

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

Sign-in is **phone plus one-time code**. Three provider settings exist in the
configuration; **only `log` is implemented**. It writes the code to the
application log:

```bash
docker compose -f deploy/docker-compose.prod.yml --env-file .env.production \
  logs -f app | grep 'DEV OTP'
```

That is workable for a closed alpha where you are present and reading the log
for each tester, and it is not acceptable for anybody beyond that: the code for
any phone number is visible to anyone who can read the logs.

**Real SMS in India** needs more than a provider account. Every message template
must be registered on a telecom operator's DLT portal along with the sender ID,
and unregistered templates are dropped silently by the operator. Budget days,
not hours. [Doc 13 §4](13-providers-and-going-live.md) has the sequence.

**Email is not a fallback today.** `users.email` is stored and shown, but there
is no email sign-in path: `AuthRepository.findByPhone` is the only lookup, and
`OtpSender.send(phone, code)` is the only delivery interface. Adding it is a
small, well-shaped change — an `EmailOtpSender` implementing the existing
interface, an email branch in the OTP request and verify endpoints, and a unique
lookup on `users.email` — but it is a change to the **authentication surface**,
with an account-enumeration and account-takeover question attached to it, and it
is not something to add quietly during a deployment. It is written up here as
the gap it is.

## 6 · Backups

Two things, and they must be taken together:

```bash
# The database
docker compose -f deploy/docker-compose.prod.yml --env-file .env.production \
  exec -T db pg_dump -U "$ALMIRA_DB_OWNER_USER" -Fc almira > almira-$(date +%F).dump

# The documents (encrypted at rest; useless without the key, and equally
# useless if you lose them)
docker run --rm -v almira_documents:/var -v "$PWD":/backup alpine \
  tar czf /backup/almira-documents-$(date +%F).tgz -C /var .
```

A database restored without the documents leaves every holding pointing at a
missing scan. Documents restored without the key are ciphertext. **Test a
restore before you need one** — an untested backup is a hope.

### Verifying a restore, not just taking one

*Decided; lands with the item-4 artefacts.* A backup that restores is not the
same as a backup that restored **correctly**, and for sealed fields the
difference is invisible: the server cannot read them, so a truncated or
corrupted ciphertext restores without complaint and fails months later in front
of the one person who needed it.

Two checks, in this order.

**A structural sweep, after every restore.** Every row in `sealed_values` must
carry a ciphertext that is valid base64url, at least 33 bytes decoded, with
version byte `1` and a key version of at least 1. Every row in `e2e_keys` must
have a salt of at least 16 bytes and a wrapped key and verifier that parse the
same way. No schema change and no new column — it reads what is already there,
and it catches truncation and header damage, which are what a bad restore
actually looks like.

It names the exact row. A sweep that says "something is wrong" is a sweep that
sends someone to read a million rows by hand.

And it is proved the way everything else here is proved: by deliberately
corrupting a ciphertext in a **restored copy**, confirming the sweep names that
row and no other, and restoring it. A sweep nobody has watched fail is a sweep
nobody should trust.

**Then a stored digest — after the sweep works, not before.** A server-computed
SHA-256 of each ciphertext, written beside it and compared during restore
verification. Internal, additive, no change to the frozen v1 API. It closes the
one case the sweep cannot see: a flipped bit *inside* the body, which parses
perfectly and simply will not open.

It is deliberately last. It is the only one of the three that needs a schema
change, and it is worth nothing until the two cheaper checks are running — see
[Doc 12 §9](12-end-to-end-encryption.md) for why it is not an integrity control
against an attacker and why verifying it on write buys nothing.

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

Two notes for whoever verifies it:

- It needs **HTTPS** (or localhost). A service worker will not register over
  plain HTTP on a real hostname, so the install prompt will not appear until TLS
  is in front of it.
- `scripts/check-service-worker.js` asserts the routing decisions — shell
  precached, `/api` never cached, offline navigation falls back to the shell —
  without a browser. Installability and the offline launch itself still need a
  real device; they could not be verified on the machine this was built on.

## 8 · Not covered here

Named rather than implied:

- **Log aggregation, metrics and alerting.** The application logs to stdout and
  exposes `/health`; nothing collects either.
- **Automated backups.** The commands above are manual.
- **Zero-downtime deploys.** `up -d --build app` restarts the container.
- **Horizontal scaling.** Nothing prevents more than one instance — sessions and
  rate limits are in Redis, not in memory — but it has not been tried.
- **An external penetration test.** Still to schedule, against a deployed
  environment ([Doc 15 §11](15-security-whitepaper.md)).

[‹ Index](README.md)
