# Moving Almira to a second machine

This is a **copy**, not a migration. The machine you are moving from keeps
working exactly as it does now, and nothing on it is deleted at any point.

Two files do the work:

| | |
|---|---|
| [`dev-personal/move/INVENTORY.md`](dev-personal/move/INVENTORY.md) | every path this project touches outside the repository, each marked *recreate* or *copy* |
| [`dev-personal/move/verify.sh`](dev-personal/move/verify.sh) | stands the whole stack up on the new machine and proves it works |

---

## The short version

**On the old machine** — copy four things onto a stick or into a password
manager. Nothing is removed:

```bash
# 1. The Android debug keystore. Small, and the one people get wrong.
cp ~/Developer/almira-personal/android-user-home/debug.keystore  /Volumes/stick/

# 2. The development key-encryption key — only if you also take the database.
cp backend/var/dev-kek  /Volumes/stick/dev-kek.host
docker run --rm -v almira-personal-documents:/v -v /Volumes/stick:/out \
  alpine cp /v/dev-kek /out/dev-kek.container

# 3. The database, only if you want this exact seeded data and its sealed fields.
docker run --rm -v almira-personal-dbdata:/v -v /Volumes/stick:/out \
  alpine tar czf /out/almira-dbdata.tgz -C /v .
```

The fourth is the SSH access to the repository, and it is **not** a copy: see
below.

**On the new machine:**

```bash
# JDK 21, Docker and git first. Then:
git clone <the repository> almira && cd almira
./dev-personal/move/verify.sh
```

That one command installs the folder-local Android SDK, brings up Postgres,
Redis and the application, seeds a household, runs the backend and shared test
suites, and builds the Android app. It prints `ok` per step and a summary at the
end.

Then put the copied files back:

```bash
mkdir -p ~/Developer/almira-personal/android-user-home
cp /Volumes/stick/debug.keystore ~/Developer/almira-personal/android-user-home/
cp /Volumes/stick/dev-kek.host backend/var/dev-kek     # if you took the database
```

---

## The four things that do not come back on their own

Read [`INVENTORY.md`](dev-personal/move/INVENTORY.md) for the full reasoning.
In one line each:

1. **`debug.keystore`** — a new one changes the app's signing certificate, which
   changes the SMS Retriever app hash, which stops OTP autofill working
   **silently**. The message still arrives; the code just never fills in.
2. **`dev-kek`** — wraps the data key in `encryption_keys`. Take the database
   without it and that row can never be opened. Take both or neither. (Today it
   protects nothing — 0 MFA secrets, 0 documents — so re-seeding is a clean
   escape.)
3. **The database volume** — only if you want the eight sealed values, which
   were written under a specific passphrase and cannot be re-seeded.
4. **Repository access** — `origin` is `git@github-personal:…`, and
   `github-personal` is an alias in the old machine's `~/.ssh/config`, not a
   hostname. Recreate the stanza on the new machine and **generate a new SSH
   key** there rather than copying the private one. Two keys can be revoked
   independently; one cannot.

There is **no release signing keystore yet** — nothing has been built for
release on either platform. When one exists it outranks everything on this list:
an Android app can only ever be updated by a build signed with the same key.

---

## What the new machine needs

| | |
|---|---|
| Backend, web, Android | JDK 21, Docker, git. Linux is fine. |
| iOS | a Mac with Xcode. ~22 GB, App Store, manual. |

iOS is deliberately **not** part of the one command: Xcode cannot be installed
unattended, and the other three targets should not be blocked behind it. Once
Xcode is present, `./dev-personal/uitest/run.sh` exercises the iOS thin slice.

---

## Verifying the copy, and what "verified" means

`verify.sh` succeeds when the stack is up with row-level security enforced, the
backend suite passes, the shared module's tests pass on the JVM and — on macOS —
natively, and the Android app builds. That is the success criterion for this
move and nothing more: it does **not** prove the app runs on a physical device,
that iOS builds, or that anything is deployable.

It refuses to run if something is already answering on the stack's port, so
running it on the old machine cannot disturb what is there. To stand up a second
copy alongside an existing one, give it a port:

```bash
ALMIRA_PERSONAL_PORT=18090 ./dev-personal/move/verify.sh
```

It **removes nothing** — not even the two scratch containers it creates for the
backend suite, unless you pass `--cleanup`.

---

## One thing to fix when you feel like it

The Gradle wrapper JARs are not in the repository — a `*.jar` line in
`.gitignore` catches them and the exception meant to rescue them is anchored to
the repository root, so it misses `app/` and `backend/`. A fresh clone therefore
cannot run `./gradlew` at all.

`verify.sh` works around it by downloading the Gradle distribution the wrapper
already names and regenerating the jar, so a clean clone works today — at the
cost of a 130 MB download that should not be necessary. The fix is one line:

```gitignore
!**/gradle/wrapper/gradle-wrapper.jar
```

then `git add -f app/gradle/wrapper/gradle-wrapper.jar backend/gradle/wrapper/gradle-wrapper.jar`.
Left for you to decide, because changing what the repository contains is a
decision rather than a deliverable.
