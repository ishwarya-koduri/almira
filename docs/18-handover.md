[‹ Index](README.md)

# 18 · Handover — picking this up on another machine

Written at the point where the office Mac stops being involved. Everything below
is either in the repository or is something only a person with accounts and a
personal machine can do.

---

## 1 · What exists

A complete backend and web client for Phases 0–4, and a native app proven
end to end on both platforms as a thin slice.

| | |
|---|---|
| **API** | Frozen at v1. 109 paths, 152 operations, 164 schemas, in [`docs/api/openapi-v1.json`](api/openapi-v1.json). Additive-only, enforced by a contract test that fails the build. |
| **Web client** | Framework-free ES modules, installable as a PWA, English/Telugu/Hindi. It exercises every flow the native app will need. |
| **Privacy** | Per-record visibility enforced in PostgreSQL, with the application connecting as a role that cannot bypass it. |
| **Tests** | 375 unit and full-stack, 59 SQL privacy assertions, 151 end-to-end checks over real HTTP. |
| **Native app** | Kotlin Multiplatform with Compose Multiplatform, one shared module for both platforms. The thin slice runs on Android and iOS and is tagged `v0.1.0-thinslice`: OTP sign-in, capture from the type's `field_schema`, per-viewer net worth, Keystore/Keychain session storage behind biometrics, and zero-knowledge seal and open. Verified on an emulator and a simulator, never on a physical device. |
| **Brand** | One master, [`brand/almira-mark.svg`](../brand/almira-mark.svg). Every icon in the repository — web, Android and iOS — is rendered from it by `brand/render-icons.py`. |
| **Deployment** | [`deploy/`](../deploy) and [Doc 17](17-deploying.md): Dockerfile, compose stack, database bootstrap, `.env.production.example`, smoke test. Written; never run against a real host. |

## 2 · What is deliberately not built

Not oversights — decisions, each with its reasoning written down where the code
would have gone.

| | Where it is explained |
|---|---|
| The native app beyond the thin slice | The slice proves the architecture; the remaining Phase-1 surface — goals, reports, documents, family, continuity — is unbuilt on both platforms. [Doc 09](09-build-and-launch-plan.md) |
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

**3. The native app's thin slice is done** — `v0.1.0-thinslice`. What is left
is widening it to the Phase-1 surface, and the order that worked for the slice
is the order to keep: one reviewable stage at a time, Android first, iOS as a
target-add, with the acceptance check written before the code. The four gates
the slice had to pass are in the tag's own message.

Two things that stage taught, worth carrying:

- **Both clients render the server's description of a form, never their own.**
  That is why the app and the web cannot drift on what a Fixed Deposit has in
  it. [`docs/api/README.md`](api/README.md) is the note that travels with the
  contract — the behaviour a schema cannot express, every line learned the hard
  way.
- **A known-answer vector beats a round trip.** The zero-knowledge scheme is
  asserted against checked-in constants the browser produced, not against each
  platform agreeing with itself, which is the only way a compensating
  difference in key derivation gets caught. See
  [`docs/zk-interop-acceptance.md`](zk-interop-acceptance.md) B4.

**4. Book the penetration test** once something is deployed. It needs a real
environment, not a laptop. [Doc 16 §6](16-controls-and-threat-model.md) lists
what it should cover.

## 4 · What has never been verified

Stated plainly, because the rest of the documentation claims a lot. Two entries
came off this list when the thin slice landed; what follows is what is still
only reasoned about.

**Still unverified:**

- **Any deployment.** The Dockerfile and compose stack are written from the code
  they deploy and validated with `docker compose config`. The development stack
  in `dev-personal/` runs, and that is not the same thing: nothing has ever been
  started from `deploy/` against a real host, with real TLS, a real domain or a
  managed database.
- **The provider adapters against real providers.** Each has a sandbox that is
  exercised by tests; none has spoken to the real service. No SMS has ever been
  sent — every sign-in in this repository read the code out of a log or off the
  development screen that prints it.
- **Backups and restore.** Commands are in [Doc 17 §6](17-deploying.md). An
  untested backup is a hope, and nothing has been restored from one.
- **A physical phone.** The Android work ran on an emulator and the iOS work on
  a simulator. Both are faithful about layout and about the APIs, and both lie
  about the things that matter next: a real fingerprint or face, a real SMS
  arriving for autofill, a real Secure Enclave, thermal behaviour, and what an
  app looks like after a real device reboot. The simulator also has no App Store
  signing, so nothing has ever been installed the way a person would install it.
- **The PWA installed on a real device.** Manifest, icons, service-worker
  routing, content types and every installability criterion are checked against
  a running server — see below for exactly what that covered — but the install
  prompt, the offline launch and the home-screen icon on a real phone still
  need a real browser on a real device.
- **The widened native surface.** The thin slice is one path through the app.
  Goals, reports, documents, family, continuity, import, tax — none of that
  exists on either platform, so none of it has been tested there.
- **Anything under load, or with more than one household's worth of data.** The
  seeded household has single-digit holdings. No pagination, no query plan and
  no dashboard total has met a realistic volume.
- **Release builds of the apps.** Both platforms have only ever been built and
  run in debug. `FLAG_SECURE` on Android is deliberately off in debug, so the
  screenshot and recents protection has been reasoned about rather than watched
  working — see `docs/known-issues.md`.

**What the thin slice did verify**, so it is not claimed twice: per-viewer net
worth to the rupee against the web on both platforms; capture through the
server's `field_schema`; a session surviving a process kill and a cold start
gated by the device's own authentication; and zero-knowledge interop in both
directions across Telugu, an emoji, a trailing space, an empty value and the
server's 47,967-byte ceiling, with moved ciphertext and a one-byte-different
passphrase both refused. The evidence is in the `v0.1.0-thinslice` tag message
and in [`docs/zk-interop-acceptance.md`](zk-interop-acceptance.md).

**What "installability checked" means, precisely.** Lighthouse has never run
here — it is not installed and there is no npm on the machine. What was checked,
against the running server in a browser: the manifest parses and declares name,
short name, a `start_url` inside its scope and `display: standalone`; all four
icons return 200 with the right content type and their real pixel dimensions
match what they declare; both a 192 and a 512 exist for `purpose: any` and for
`purpose: maskable`; the service worker registers, activates and has a fetch
handler; and the origin is a secure context. That is the criteria list, verified
one at a time. It is not a Lighthouse report and does not replace one.

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

And for the apps, on a machine with Xcode and the Android SDK:

```bash
cd app && ./gradlew :shared:testDebugUnitTest        # the shared core, on the JVM
./gradlew :shared:iosSimulatorArm64Test              # the same tests, natively
./gradlew :androidApp:installDebug                   # onto a running emulator
../dev-personal/uitest/run.sh                        # the iOS thin slice, on the simulator
```

`dev-personal/RUNNING.md` is the step-by-step version of that, written for
someone who has not opened Xcode or Android Studio before.

The end-to-end suites and the seeder refuse to run against anything that is not
a local server reporting `environment: development`. `scripts/smoke-prod.sh` is
the only script written to touch a deployment.

**Nothing about this machine is required.** No global tooling was installed
for the backend or the web client: JDK 21, Docker and a browser are the whole
list. The apps need more — Xcode and the Android SDK — and both are confined to
`~/Developer/almira-personal` by `env.sh`, which is what `GRADLE_USER_HOME`,
`ANDROID_HOME` and `KONAN_DATA_DIR` are pointed at; `dev-personal/README.md`
records what that costs on disk and how to remove it. `backend/var/` is generated on
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
