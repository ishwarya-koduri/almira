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

### To restore this exactly

```bash
sudo xcode-select --switch /Library/Developer/CommandLineTools
# or, equivalently, the documented reset:
sudo xcode-select --reset
```

Then confirm `xcode-select -p` reads `/Library/Developer/CommandLineTools`
again. The standalone Command Line Tools were here first and installing Xcode
does not remove them — so nothing that depends on `/usr/bin/clang` today stops
working, whichever way the pointer is set.

To undo the rest: delete `/Applications/Xcode.app`, delete
`~/Library/Developer` (which did not exist before today, so removing it restores
the machine exactly), and remove simulator runtimes from Xcode's Settings →
Platforms. Xcode's caches live in `~/Library/Caches/com.apple.dt.Xcode`.
