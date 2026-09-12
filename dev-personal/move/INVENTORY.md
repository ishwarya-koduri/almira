# What this project touches outside the repository

Taken from this machine on 2026-09-12 by reading it, not from memory. Every
path below is either **RECREATE** — the new machine makes its own, and nothing
is lost — or **COPY** — it cannot be regenerated, and losing it costs something
specific that is named here.

Nothing in this document asks you to delete anything. Moving to a second machine
is a copy; this machine keeps working exactly as it does now.

---

## COPY — cannot be regenerated

### 1. `~/Developer/almira-personal/android-user-home/debug.keystore`

**2 KB. The one small file people get wrong.**

It looks disposable — Android regenerates a debug keystore automatically if it
is missing — and for most projects it is. Not for this one. The SMS Retriever
app hash is derived from the signing certificate:

```
base64(SHA-256(packageName + " " + signature.toCharsString()))[0..10]
```

A new debug keystore means a new certificate, which means a new app hash, which
means **the OTP SMS template no longer matches and autofill silently stops
working** — the message still arrives, the code simply never fills itself in.
Nothing errors. You would look at the app.

So: copy it, or accept that you must re-derive the hash on the new machine and
update the SMS template to match. The app prints its own hash on the sign-in
screen in debug builds, so re-deriving is easy — it is *remembering to* that is
the problem.

### 2. The development key-encryption key — but only if you also copy the database

There are two, for two different ways of running the backend:

| Where | Used by |
|---|---|
| `backend/var/dev-kek` (44 bytes) | the backend run on the host, `./scripts/dev.sh` |
| `dev-kek` inside the Docker volume `almira-personal-documents` | the containerised stack, `./dev-personal/up.sh` |

Each wraps the data key in the `encryption_keys` table. Today that table has
**one row**, and what it protects is empty — `users.mfa_secret_enc` has 0
non-null rows and `documents` has 0 rows — so regenerating the KEK and
re-seeding costs nothing at all right now.

The coupling is still real and will bite the first time either table has
content: **copy the database volume without the KEK and that wrapped data key
can never be opened again.** Copy both or neither.

Note what is *not* affected: `e2e_keys` (1 row) and `sealed_values` (8 rows) are
zero-knowledge — protected by the user's passphrase, not by the KEK — so they
survive a KEK change intact. They will not survive losing the passphrase, which
is the point of them.

### 3. Docker volume `almira-personal-dbdata` (67 MB) — only if you want *this* data

The seeded Koduri household, the two people, the eight sealed values. All of it
is reproducible with `./dev-personal/up.sh --seed`, with one exception: the
**sealed values were written with a passphrase** (`correct horse battery staple `,
with the trailing space — see `docs/zk-interop-acceptance.md`). Re-seeding
produces a household with no sealed fields, and the interop evidence would have
to be re-created.

If you do copy it, copy the KEK with it. See above.

### 4. The SSH key behind the `github-personal` alias — **do not copy it**

`git remote get-url origin` returns `git@github-personal:ishwarya-koduri/almira.git`.
`github-personal` is not a hostname; it is an alias defined in this machine's
`~/.ssh/config`:

```
Host github-personal
  HostName github.com
  User git
  IdentityFile <a private key on this machine>
  IdentitiesOnly yes
```

**A clone on a clean machine fails without that stanza**, with a DNS error that
does not mention SSH config at all.

The stanza must be recreated on the new machine. The **private key should not
be copied** — generate a fresh one there (`ssh-keygen -t ed25519`) and add its
public half to GitHub. Copying a private key between machines multiplies the
number of places a compromise can start, for no benefit; two keys can be
revoked independently, one cannot.

### 5. A release keystore — **does not exist yet, and this is the warning for when it does**

There is no release signing key in this project today: nothing has been built
for release on either platform. The moment one exists it becomes the single most
important file in this list, above everything else here.

An Android app can only ever be updated by a build signed with the same key.
Lose it and the listing cannot be updated — not by you, not by Google, not by
anyone; the only path is a new listing with a new package name and every
existing install stranded. It belongs in a password manager or an offline
backup, not only on a laptop.

Same shape on iOS, less terminal: distribution certificates can be revoked and
reissued from the Apple Developer account, so losing one costs an afternoon
rather than the app.

---

## RECREATE — the new machine makes its own

None of these need copying. Together they are **13 GB**, and every byte of it
comes back from a download or a build.

| Path | Size | How it comes back |
|---|---|---|
| `~/Developer/almira-personal/android-sdk` | 5.6 GB | `sdkmanager` — `verify.sh` installs it |
| `~/Developer/almira-personal/gradle-home` | 3.9 GB | Gradle's own cache; fills on first build |
| `~/Developer/almira-personal/android-user-home/avd` | 2.1 GB | `avdmanager create avd` — `verify.sh` creates it |
| `~/Developer/almira-personal/konan` | 1.5 GB | downloaded by Gradle on the first Kotlin/Native build |
| `~/Developer/almira-personal/xcode-derived` | 178 MB | Xcode build output |
| `~/Developer/almira-personal/env.sh` | 4 KB | written by `verify.sh`; also reproduced in `MOVE.md` |
| `~/Developer/almira-personal/logs/` | 12 KB | emulator logs, disposable |
| Docker volume `almira-personal-redisdata` | 68 KB | OTP challenges and rate counters; ephemeral by design |
| Docker volume `almira-personal-documents` | 12 KB | empty apart from the `dev-kek` noted above |
| Docker image `almira-personal:dev` | 536 MB | `./dev-personal/up.sh` builds it |
| `backend/var/documents/` | empty | created on first run |
| `backend/build/`, `app/*/build/`, `.gradle/`, `.kotlin/` | — | build output |

### Toolchain — installed on the new machine, not copied

| | This machine | Needed |
|---|---|---|
| JDK | 21.0.11 (Homebrew) | 21 |
| Docker | 29.6.1 (Desktop) | any recent |
| macOS | 26.5.2 | only for iOS |
| Xcode | 26.x | only for iOS — App Store, manual, ~22 GB |

Backend, web and Android need **JDK 21 + Docker + git** and nothing else. Linux
is fine for all three. iOS is the only part that requires a Mac, and it is
deliberately outside the one command — see `MOVE.md`.

---

## NOT part of this project — do not touch on either machine

These share a prefix or a machine and belong to other work:

| | |
|---|---|
| Docker volumes `almira_dbdata`, `almira_redisdata` | the separate `almira` compose project in `infra/`, not `almira-personal` |
| Containers `bhrigu-postgres`, `bhrigu-kafka`, … | unrelated office stack |
| `~/.gradle`, `~/.m2`, `~/Library/Android` | untouched by this project on purpose; it is folder-local precisely so these stay clean |

---

## One defect this inventory found

**The Gradle wrapper JARs are not tracked in git**, so a fresh clone cannot run
`./gradlew` at all — which defeats the whole "clone, one command" goal.

```
$ git check-ignore -v app/gradle/wrapper/gradle-wrapper.jar
.gitignore:12:*.jar    app/gradle/wrapper/gradle-wrapper.jar
```

Line 12 is `*.jar`; line 13 is meant to rescue the wrapper but reads
`!gradle/wrapper/gradle-wrapper.jar`, which is **anchored to the repository
root** and therefore matches neither `app/gradle/...` nor `backend/gradle/...`.
Both jars are 47,505 bytes and exist on disk; neither is in the repository.

`verify.sh` works around this by bootstrapping the wrapper from the
distribution named in `gradle-wrapper.properties`, so a clean clone succeeds
today. The workaround costs a 130 MB download that should not be necessary.

**The one-line fix**, for whenever you want it — Gradle's own documentation says
the wrapper JAR is meant to be committed:

```gitignore
!**/gradle/wrapper/gradle-wrapper.jar
```

then `git add -f app/gradle/wrapper/gradle-wrapper.jar backend/gradle/wrapper/gradle-wrapper.jar`.
Left undone here because this item was "produce, do not execute", and changing
what the repository contains is a decision rather than a deliverable.
