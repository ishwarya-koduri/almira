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
