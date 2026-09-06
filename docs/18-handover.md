[‹ Index](README.md)

# 18 · Handover — picking this up on another machine

Written at the point where the office Mac stops being involved. Everything below
is either in the repository or is something only a person with accounts and a
personal machine can do.

---

## 1 · What exists

A complete backend and web client for Phases 0–4, minus the native app.

| | |
|---|---|
| **API** | Frozen at v1. 109 paths, 152 operations, 164 schemas, in [`docs/api/openapi-v1.json`](api/openapi-v1.json). Additive-only, enforced by a contract test that fails the build. |
| **Web client** | Framework-free ES modules, installable as a PWA, English/Telugu/Hindi. It exercises every flow the native app will need. |
| **Privacy** | Per-record visibility enforced in PostgreSQL, with the application connecting as a role that cannot bypass it. |
| **Tests** | 375 unit and full-stack, 59 SQL privacy assertions, 151 end-to-end checks over real HTTP. |
| **Deployment** | [`deploy/`](../deploy) and [Doc 17](17-deploying.md): Dockerfile, compose stack, database bootstrap, `.env.production.example`, smoke test. Written; never run against a real host. |

## 2 · What is deliberately not built

Not oversights — decisions, each with its reasoning written down where the code
would have gone.

| | Where it is explained |
|---|---|
| The native app | [Doc 09](09-build-and-launch-plan.md); the v1 contract is its handoff artefact |
| Email sign-in | [Doc 17 §5](17-deploying.md) — a change to the authentication surface, with account-enumeration questions attached |
| Real SMS delivery | [Doc 13 §4](13-providers-and-going-live.md) — needs DLT registration, which is days of paperwork |
| DigiLocker, Account Aggregator, WhatsApp | [Doc 13](13-providers-and-going-live.md) — adapters and sandboxes exist; each needs an account |
| Push notifications | Needs device-token registration, which needs the native app first |
| Offline data sync | [Doc 17 §7](17-deploying.md) — the shell opens offline; syncing records is a separate project |
| Object storage for documents | The `DocumentStorage` interface is the seam; only the filesystem implementation exists |

## 3 · The order I would do things in

**1. Deploy the web client somewhere real.** It is the fastest way to learn what
is actually wrong, and everything else benefits from a running instance. Follow
[Doc 17](17-deploying.md). Expect to correct a line or two — none of it has been
run against a real host.

**2. Sort out sign-in before inviting anybody.** Log-only OTP works for a
controlled alpha where you read the container log per tester. Start the DLT
registration early: it is the longest-lead item in the whole product and nothing
about it is technical.

**3. Then the native app**, on a machine that can install Xcode and Android
Studio. Build the thin slice on a device first — sign in, capture one holding,
see the list — before widening to the Phase-1 surface. Generate the client from
`openapi-v1.json`; do not hand-write it. [`docs/api/README.md`](api/README.md) is
the note that travels with the contract: it holds the behaviour a schema cannot
express, and every line of it was learned the hard way.

**4. Book the penetration test** once something is deployed. It needs a real
environment, not a laptop. [Doc 16 §6](16-controls-and-threat-model.md) lists
what it should cover.

## 4 · What has never been verified

Stated plainly, because the rest of the documentation claims a lot.

- **The PWA on a real device.** Manifest, icons, service-worker routing and
  content types are all checked; registration, the install prompt and the
  offline launch need a browser in a top-level context over HTTPS. The machine
  this was built on had neither Chrome nor a non-iframe preview.
- **Any deployment.** The Dockerfile and compose stack are written from the code
  they deploy and validated with `docker compose config`. No container was ever
  started from them.
- **The provider adapters against real providers.** Each has a sandbox that is
  exercised by tests; none has spoken to the real service.
- **Backups and restore.** Commands are in [Doc 17 §6](17-deploying.md). An
  untested backup is a hope.

## 5 · Picking the repository up

```bash
git clone git@github.com:<you>/almira.git && cd almira
./scripts/dev.sh                 # brings up Postgres and Redis, runs the backend
./scripts/demo-data.sh           # a household with realistic shapes in it
```

Then, to see that everything holds:

```bash
./scripts/dev.sh test            # unit and full-stack, then the SQL privacy suite
./scripts/e2e-phase0.sh          # with the server running, in another terminal
./scripts/e2e-phase2.sh
./scripts/e2e-phase3.sh
```

The end-to-end suites and the seeder refuse to run against anything that is not
a local server reporting `environment: development`. `scripts/smoke-prod.sh` is
the only script written to touch a deployment.

**Nothing about this machine is required.** No global tooling was installed:
JDK 21, Docker and a browser are the whole list. `backend/var/` is generated on
first run — the development key-encryption key is created there per install, so
a fresh clone makes its own and no key travels with the repository.

## 6 · The two things worth not forgetting

**The privacy model is the product.** Role is what you may do; visibility is
what you may see; no role can read another member's private records. It is
enforced in PostgreSQL because that is the only place it cannot be forgotten,
and the application connects as a role that cannot bypass it. If a future change
makes `/health` report the schema owner, everything else in this repository is
decorative. [Doc 15](15-security-whitepaper.md) is the long version.

**Never show a number the data did not earn.** No fabricated valuations, no
projected returns, no total that quietly omits what it could not convert. Where
a figure cannot be computed honestly, the API returns null and a sentence saying
why, and the client renders the sentence. That rule is why several features look
smaller than they could have been, and it is the one worth defending.

[‹ Index](README.md)
