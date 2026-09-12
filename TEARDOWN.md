# Undoing all of this

Written as the personal setup is built, not afterwards, so it says what is
actually true rather than what was intended. Two lists: what a folder delete and
a `down -v` really remove, and what genuinely is not folder-removable.

Last updated at the end of Stage 3 (Android emulator running).

---

## 1 · What comes out cleanly

### The running stack and its data

```bash
cd ~/Developer/almira
./dev-personal/down.sh
```

Removes the three `almira-personal-*` containers, the `almira-personal-net`
network and the three named volumes — the database, the Redis append-only file,
and the documents volume with the per-install development key inside it. The
script prints what is left of the project (should be "nothing") and what is
still running, which is the line worth reading: the office containers must still
be there.

The built image is left behind on purpose, because rebuilding it takes a couple
of minutes:

```bash
docker image rm almira-personal:dev
```

Base images pulled from Docker Hub — `eclipse-temurin:21-jdk-jammy`,
`21-jre-jammy` — are shared Docker state and may be in use by other projects.
Leave them unless you are sure:

```bash
docker image rm eclipse-temurin:21-jdk-jammy eclipse-temurin:21-jre-jammy
```

### Everything installed for the app

```bash
# Stop the emulator and the adb server first, or the folder will be busy.
adb emu kill; adb kill-server
rm -rf ~/Developer/almira-personal
```

That is 8.7 GB and it is the whole of the toolchain:

| | |
|---|---|
| `android-sdk/` | 5.6 GB — command-line tools, platform-tools, platform 35, build-tools, the emulator, and the arm64 system image |
| `android-user-home/` | 2.0 GB — the AVD, its disk images, SDK licences, CLI bundles |
| `gradle-home/` | 1.1 GB — Gradle's caches and every downloaded dependency |
| `konan/` | Kotlin/Native's toolchain, once anything is built for iOS |
| `env.sh` | the only thing that ever pointed at any of it |

Nothing here is on `PATH`, in a shell rc file, in a LaunchAgent, or in a login
item. It is inert unless `env.sh` is sourced in a terminal.

### The repository

`~/Developer/almira` is an ordinary git checkout. Deleting it removes the
working copy; the history is on the remote.

---

## 2 · What is **not** folder-removable

### `~/.android` — 24 KB, five files, created 12 Sep 2026

The one genuine leak, and it is worth being precise about because the rest of
the isolation held.

`ANDROID_USER_HOME` and `ANDROID_AVD_HOME` moved the bulk — the AVD and its 2 GB
of disk images went folder-local as intended. But adb and the emulator write a
handful of small files to a hard-coded `$HOME/.android` regardless:

| file | what it is |
|---|---|
| `analytics.settings` | an anonymous UUID and `"hasOptedIn": false` — written by the SDK tools on first run |
| `adbkey` | adb's RSA key, the one a physical device would be asked to trust |
| `emu-last-feature-flags.protobuf` | the emulator's feature flags from its last run |
| `emu-update-last-check.ini` | when it last looked for an update |
| `modem-nv-ram-5554` | simulated modem state for the running AVD |

**This directory did not exist on this machine before 12 Sep 2026**, so removing
it restores the machine exactly:

```bash
rm -rf ~/.android
```

Do that only if no other Android work is happening on this Mac. If any is, the
`adbkey` there may be authorising a real device and deleting it means
re-approving that device's USB debugging prompt.

`env.sh` now also sets `ANDROID_EMULATOR_HOME`, which per the emulator's own
`-help-environment` replaces `$HOME/.android` outright. It was added after the
first boot demonstrated the leak, so **a fresh setup following these
instructions should not produce this directory at all** — that has not been
re-tested here, because proving it means deleting the existing one, and deleting
something in a home directory is a decision for whoever owns the machine.

### `~/.konan` — pre-empted, not yet a problem

Kotlin/Native defaults its ~1 GB toolchain to `~/.konan`. `env.sh` sets
`KONAN_DATA_DIR` into the folder before anything was ever compiled for iOS, so
this directory does not exist. If you ever build iOS *without* sourcing `env.sh`,
it will appear, and then:

```bash
rm -rf ~/.konan
```

### Docker's own state

Image layers, the build cache and the daemon's storage are shared across every
project on the machine. `down -v` removes this project's containers, network and
volumes; it does not and should not touch the rest. To reclaim shared space —
**after checking what else uses it**:

```bash
docker builder prune          # build cache only
docker image prune            # dangling images only
```

Never `docker system prune -a` on this machine: it would take the office images
with it.

### Still ahead, and genuinely system-level

Not installed yet. Recorded here so the list is complete when they are:

| | |
|---|---|
| **Xcode** | `/Applications/Xcode.app`, 15–40 GB depending on the simulator runtimes. Installed from the App Store or a developer download, needs admin for the command-line tools, and is **not** folder-removable — uninstalling means deleting the app plus `~/Library/Developer/`. Required before anything compiles for iOS. |
| **Hypervisor** | Nothing to install. On Apple silicon the Android emulator runs as a normal user process against macOS's built-in Hypervisor framework — the `qemu-system-aarch64` binary carries the `com.apple.security.hypervisor` entitlement. No HAXM, no kernel extension, no admin. Verified on this machine. |

---

## 3 · Checking it worked

```bash
docker ps -a  --filter "name=almira-personal"    # expect nothing
docker volume ls --filter "name=almira-personal" # expect nothing
docker network ls --filter "name=almira-personal"# expect nothing
ls ~/Developer/almira-personal 2>&1              # expect "No such file or directory"
ls ~/.android ~/.konan 2>&1                      # expect the same
docker ps                                        # expect the office containers, untouched
```

And the things that should never have changed, worth confirming once:

```bash
ls -ld ~/.gradle ~/.m2                           # modification dates predate this work
```
