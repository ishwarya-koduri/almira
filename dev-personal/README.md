# dev-personal — the isolated stack for personal development

A second, deliberately namespaced copy of the backing services, for working on
this product on a machine that is already carrying somebody else's containers.

```bash
./dev-personal/up.sh --seed      # build, start, wait for healthy, seed a household
./dev-personal/down.sh           # stop and delete every trace, including the data
```

The application lands on <http://localhost:18080> — the web client, the API and
the docs are all served from there.

## What isolation means here, exactly

| | |
|---|---|
| Compose project | `almira-personal` — never the directory-derived default |
| Containers | `almira-personal-db`, `almira-personal-redis`, `almira-personal-app` |
| Network | `almira-personal-net`, its own bridge |
| Volumes | `almira-personal-dbdata`, `-redisdata`, `-documents` |
| Published | `127.0.0.1:18080` and nothing else — Postgres and Redis are reachable only from the compose network, so they cannot collide with a 5432 already in use |

`down.sh` is scoped by project name, so it can only remove what this stack
created. It prints what is left of the project and what is still running, which
is the part worth reading.

## Why it is a development stack

`ALMIRA_ENV=development`, on purpose:

- one-time codes are echoed in the API response and printed to the log, so
  sign-in works with no SMS provider;
- `scripts/demo-data.sh` agrees to run against it (it refuses anything that is
  not a local server reporting `development`);
- the key-encryption key is generated per install into the `documents` volume
  rather than being supplied — so no key material is in this directory or in
  the repository, and `down.sh` destroys it with everything else.

For the real thing see [`deploy/`](../deploy) and
[docs/17](../docs/17-deploying.md); that stack publishes nothing but the app,
demands a real key, and refuses to start without one.

## Two roles, here as everywhere

`postgres-init/01-app-role.sh` creates `almira_app` on first start: a non-owner
role with `nobypassrls`. PostgreSQL lets a table's owner bypass its own
row-level security, so serving traffic as the owner would switch off every
privacy policy in the product while everything still looked healthy. `up.sh`
refuses to report success unless `/health` says `"rlsEnforced":true`.

---

## Running the backend test suite on this machine

The suite brings up its own Postgres and Redis with Testcontainers, and on this
machine Testcontainers cannot find Docker: Desktop's socket lives at
`~/.docker/run/docker.sock`, and passing `DOCKER_HOST` through Gradle did not
reach the test JVM. Rather than fight that, the suite's own escape hatch does
the job — it accepts external services, and refuses outright to run against a
database named `almira`, so there is no way to point it at real data by
accident.

Two throwaway containers, in this project's namespace, removed afterwards:

```bash
docker run -d --name almira-personal-testpg -p 127.0.0.1:15432:5432 \
  -e POSTGRES_DB=almira_zktest -e POSTGRES_USER=almira -e POSTGRES_PASSWORD=dev \
  postgres:16-alpine
docker exec almira-personal-testpg psql -U almira -d almira_zktest -c \
  "create role almira_app login password 'app_dev_password';
   grant connect on database almira_zktest to almira_app;
   grant usage on schema public to almira_app;"

docker run -d --name almira-personal-testredis -p 127.0.0.1:16379:6379 redis:7-alpine
```

```bash
ALMIRA_TEST_DB_URL="jdbc:postgresql://localhost:15432/almira_zktest" \
ALMIRA_TEST_DB_OWNER_USER=almira ALMIRA_TEST_DB_OWNER_PASSWORD=dev \
ALMIRA_TEST_DB_APP_USER=almira_app ALMIRA_TEST_DB_APP_PASSWORD=app_dev_password \
ALMIRA_TEST_REDIS_HOST=127.0.0.1 ALMIRA_TEST_REDIS_PORT=16379 \
./gradlew --no-daemon test --tests "*E2eApiTest*"
```

```bash
docker rm -f almira-personal-testpg almira-personal-testredis
```

The throwaway Postgres publishes a loopback port because the test JVM runs on
the host; the stack's own `almira-personal-db` still publishes nothing, which is
the rule that keeps it from colliding with anything else on this machine.

---

## The machine's Xcode state, before we touched it

Recorded **2026-09-12, before installing anything**, because `xcode-select` is
the one part of an Xcode install that is system-wide: it decides which developer
directory *every* tool on this machine uses, including for the office projects.
Everything else Xcode brings is either folder-local or cleanly deletable.

| | |
|---|---|
| `xcode-select -p` | **`/Library/Developer/CommandLineTools`** |
| That path | exists, `root:wheel`, dated 2 Jul 2026 |
| Standalone Command Line Tools | **installed** — `com.apple.pkg.CLTools_Executables` 26.6.0.0.1781586589 |
| `/Applications/Xcode*.app` | **none** |
| `xcodebuild` | not available (CLT instance, no Xcode) |
| `xcrun simctl` | not available |
| `~/Library/Developer` | **does not exist** |
| `clang` / `swift` / `make` | `/usr/bin/…`, i.e. served by the CLT above |
| `git` | `/opt/homebrew/bin/git` — Homebrew's, not the CLT's, so unaffected either way |
| Free space | 664 GiB |

### After installing Xcode 26.6 — and a correction

`xcode-select -p` now reads `/Applications/Xcode.app/Contents/Developer`, and
the first version of this note said the installer had *changed the system-wide
setting*. **It had not.** There is no persisted selection on this machine and
there never was:

```
$ ls -l /var/db/xcode_select_link
ls: /var/db/xcode_select_link: No such file or directory
```

That file is what `xcode-select --switch` creates. With it absent,
`xcode-select -p` reports a **fallback**: the Xcode that exists, or the Command
Line Tools if none does. So the only thing that changed is which answer the
fallback gives —

| | resolves to |
|---|---|
| before: no link, no Xcode | `/Library/Developer/CommandLineTools` |
| now: no link, Xcode present | `/Applications/Xcode.app/Contents/Developer` |

**Which makes the teardown simpler than feared.** Deleting `/Applications/Xcode.app`
restores the previous answer by itself; there is no pointer to put back. And
`sudo xcode-select -s …` should be run **only if something insists on it**,
because it would *create* a persisted setting that was never here — a new piece
of system state to undo later, reversible with `sudo xcode-select --reset`.

One tool does insist: the native iOS-simulator integration refuses to run
without that link. Everything in this project is driven through `xcrun simctl`
instead, which does not care, so the link stays absent.

The standalone Command Line Tools are untouched and still installed, so nothing
depending on `/usr/bin/clang` changes either way.

### What it costs on disk, measured

| Where | Size | Recovered by |
|---|---|---|
| `~/Developer/almira-personal` | **13 GB** | deleting the folder |
| `/Library/Developer/CoreSimulator` | **19 GB** | removing the iOS 26.5 runtime (Xcode → Settings → Platforms) |
| `/Applications/Xcode.app` | **3.7 GB** | deleting the app |
| `~/Library/Developer` | **2.1 GB** | deleting it — it did not exist before today |
| `~/Library/Caches/com.apple.dt.Xcode` | 716 KB | deleting it |

Of the folder-local 13 GB: `android-sdk` 5.6 GB, `gradle-home` 3.9 GB,
`android-user-home` 2.3 GB, `konan` 1.5 GB — the last being the Kotlin/Native
toolchain that arrived with the first iOS compile, exactly where
`KONAN_DATA_DIR` pointed it.

**A correction on the estimate.** Xcode was quoted at "~35–40 GB on disk". The
application is **3.7 GB**; the bulk is the simulator runtime at 19 GB in
`/Library/Developer/CoreSimulator`, which is a separate download and separately
removable. The total is about 25 GB outside the project folder rather than 40,
and the largest single piece is the easiest to delete.

### After the iOS actuals stage — remeasured

Nothing new went system-level in this stage; the numbers are here because the
folder-local figure moved and because one thing about `xcode-select` changed.

| Where | Size | Change |
|---|---|---|
| `~/Developer/almira-personal` | **13 GB** | unchanged in total; `xcode-derived` is 147 MB of it |
| `/Library/Developer/CoreSimulator/Volumes` | **16 GB** | the iOS 26.5 runtime, as before |
| `~/Library/Developer/CoreSimulator` | **2.2 GB** | the booted device's own data |
| `/Applications/Xcode.app` | **3.7 GB** | unchanged |
| `~/Library/Developer/Xcode` | 144 MB | unchanged |

System-level total remains about **22 GB**, all of it still removable in the
three separate pieces listed above. Xcode's build output is confined to
`almira-personal/xcode-derived` via `-derivedDataPath`, which is why
`~/Library/Developer/Xcode` has stayed at 144 MB rather than growing per build.

**`xcode-select` is now pointed at Xcode.** `xcode-select -p` returns
`/Applications/Xcode.app/Contents/Developer`, where earlier in this work it
returned the standalone Command Line Tools. Recorded because the earlier note
here said the link was deliberately left alone.

`/var/db/xcode_select_link` still does not exist on this macOS version — the
selection is recorded elsewhere. That matters for one thing only: the native
iOS-simulator panel checks for that link specifically and therefore still
reports "Xcode is installed but not selected", which on this machine is a false
negative rather than a real misconfiguration. `xcode-select -p` and
`xcrun --find simctl` both resolve to Xcode. Everything in this project is
driven through `xcrun simctl` directly, which does not consult the link.

### Enrolling Face ID on the simulator

The lock reports `DeviceCredentialOnly` on a fresh simulator, because no face is
enrolled. Two commands, no admin, nothing persisted outside the simulator:

```
xcrun simctl spawn <device> notifyutil -s com.apple.BiometricKit.enrollmentChanged 1
xcrun simctl spawn <device> notifyutil -p com.apple.BiometricKit.enrollmentChanged
```

After which the lock reports `Biometric` and the biometric branch of
`LAContext` is the one being exercised. This is the equivalent of the
Simulator's Features → Face ID → Enrolled toggle.

### Clearing the OTP throttle — and one silent failure to avoid

Five codes an hour per phone, plus a tighter cap per source address, is right
for production and far too few for a testing loop. To clear it:

```
./dev-personal/clear-otp-limits.sh                 # every counter
./dev-personal/clear-otp-limits.sh 9889190735      # one number, plus addresses
```

**Do not reach for the obvious one-liner.** This one looks like it works:

```
docker exec almira-personal-redis sh -c \
  'redis-cli --scan --pattern "otp:rate:*" | xargs -r redis-cli del'
```

It prints `SCAN error: NOAUTH Authentication required.` on stderr, **exits 0**,
and deletes nothing — because this Redis runs with `requirepass`. Measured side
by side with three counters planted:

| | exit code | keys left |
|---|---|---|
| the one-liner above | 0 | 3 |
| `clear-otp-limits.sh` | 0 | 0 |

That mattered in practice: for a whole working session the throttle was reported
as cleared each time and never was — the counters were only ever expiring on
their own an hour later. Nothing was damaged, but the log said something untrue,
and a maintenance command that cannot do what it says must fail rather than
return quietly.

So the script checks every step and exits non-zero with a message if any of them
does not hold: the container missing, the password unreadable, Redis refusing
it, a delete count that disagrees, or keys still present afterwards. Both
failure paths verified:

```
$ ./clear-otp-limits.sh                    # container stopped
almira-personal-redis is not running. Start the stack with ./dev-personal/up.sh   → exit 1

$ ./clear-otp-limits.sh                    # wrong password
Redis did not accept the password taken from the container: AUTH failed …        → exit 1
```

The password is read from the running container's own command line, which is
where compose already resolved `${ALMIRA_REDIS_PASSWORD}`. Nothing is hardcoded
and nothing duplicates the default: change it in the compose file and the script
follows.

### Running the iOS UI tests

```
./dev-personal/uitest/run.sh                                  # all of them
./dev-personal/uitest/run.sh test3_captureAHoldingAndSeeTheTotalMove
```

The runner sets up the four things a run needs and each of which broke a run by
being absent: the device booted, Face ID enrolled (enrolment does not survive
the shutdown `xcodebuild test` performs, so the second run of a pair would
otherwise meet a passcode-only device), iOS's first-run keyboard tutorials
marked as seen (they float over the whole app, are invisible to its
accessibility tree, and swallow every tap), and the OTP throttle cleared.

It also starts `bridge.py`, which does the two things a test inside the
simulator cannot do for itself: post the Face-ID-matched notification, and
soft-delete the holding the capture test creates. The one-time code needs no
bridge — the app prints it on screen when the server reports no SMS provider is
configured.
