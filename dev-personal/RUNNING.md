# Running Almira on this Mac

Written for someone who has never opened Xcode or Android Studio — and you
don't need to open either. Everything here is typed into Terminal, and the app
appears in a window you can click.

**Opening Terminal:** press `⌘ Space`, type `Terminal`, press Return. A window
with a text prompt appears. That is where every command below goes: paste one
line, press Return, wait for it to finish, then paste the next.

---

## The one line to run first, every time

Open a new Terminal window and paste this:

```bash
source ~/Developer/almira-personal/env.sh
```

It prints two lines and looks like it did nothing. It did: it points the build
tools at `~/Developer/almira-personal` so that nothing gets written into your
home folder or your other projects. **A Terminal window that has not run this
line will build the wrong thing or fail.** It only lasts for that one window —
open a new tab, run it again.

---

## Step 1 — Start the backend (needed by all three apps)

```bash
cd ~/Developer/almira && ./dev-personal/up.sh
```

First run takes a few minutes; after that, seconds. When it finishes it prints

```
Ready.
  Web app    http://localhost:18080
```

That is the server. Leave it running — it stays up until you stop it, even if
you close Terminal.

**Is it already running?** This tells you:

```bash
curl -s http://localhost:18080/health
```

---

## Step 2 — The web app (the easy one)

Open <http://localhost:18080> in Safari or Chrome. That's it — no build step.

---

## Step 3 — The iPhone app

**Show the iPhone on your screen first:**

```bash
open -a Simulator
```

A window shaped like an iPhone appears. It may take a minute the first time,
and it may open a different iPhone than the one below — that's fine, the next
command picks the right one regardless.

**Build and install the app onto it:**

```bash
cd ~/Developer/almira/app/iosApp && source ~/Developer/almira-personal/env.sh && xcodebuild -project iosApp.xcodeproj -scheme iosApp -configuration Debug -destination 'platform=iOS Simulator,name=iPhone 17 Pro' -derivedDataPath ~/Developer/almira-personal/xcode-derived build
```

Lots of text scrolls past. You are looking for **`** BUILD SUCCEEDED **`** at
the end. Then:

```bash
xcrun simctl boot "iPhone 17 Pro" 2>/dev/null; xcrun simctl install "iPhone 17 Pro" ~/Developer/almira-personal/xcode-derived/Build/Products/Debug-iphonesimulator/Almira.app && xcrun simctl launch "iPhone 17 Pro" tech.bhrigu.almira
```

Almira opens in the iPhone window. You can tap it with your mouse and type with
your keyboard.

*You do not need Xcode's own window for any of this.* If you ever do want it:
`open ~/Developer/almira/app/iosApp/iosApp.xcodeproj`, then press the ▶ Play
button at the top left. Everything above does the same thing without waiting for
Xcode's interface to load.

---

## Step 4 — The Android app

**Show the phone on your screen:**

```bash
source ~/Developer/almira-personal/env.sh && emulator -avd almira-personal-api35 &
```

The `&` at the end lets you keep typing while it boots. Wait until you see an
Android home screen — the first boot is slow.

**Build and install:**

```bash
cd ~/Developer/almira/app && source ~/Developer/almira-personal/env.sh && ./gradlew :androidApp:installDebug
```

Look for `BUILD SUCCESSFUL`. Then open **Almira** from the emulator's app list,
or launch it from Terminal:

```bash
adb shell monkey -p tech.bhrigu.almira -c android.intent.category.LAUNCHER 1
```

---

## Signing in — the part you cannot guess

There are two seeded people in the demo household "Koduri":

| Who | Phone to type | Sees |
|---|---|---|
| **Ishwarya** | `9889190735` | ₹33,35,000 — including her ₹1,50,000 private buffer |
| **Ravi** | `8889190742` | ₹31,85,000 — the buffer is invisible to him |

Type the 10 digits only; the `+91` is already in the field.

**No real SMS is sent.** The six-digit code is printed in the server's log. After
you tap **Send code**, run this in Terminal:

```bash
docker logs almira-personal-app --tail 40 2>&1 | grep "DEV OTP"
```

The last line looks like `=== DEV OTP for +91····0735 : 614163 ===`. The six
digits at the end are your code. Type them into the app.

**"Too many requests"** means you asked for more than 5 codes in an hour for
that number. Clear the counter:

```bash
docker exec almira-personal-redis sh -c 'redis-cli --scan --pattern "otp:rate:*" | xargs -r redis-cli del'
```

**Face ID on the iPhone simulator.** Almira asks for Face ID when it reopens. The
simulator has no real face, so you tell it the face matched: in the Simulator
menu bar, **Features → Face ID → Matching Face**. (If Face ID is greyed out,
first tick **Features → Face ID → Enrolled**.)

---

## Stopping things

| To stop | Command |
|---|---|
| The iPhone window | Quit the Simulator app (`⌘Q`) |
| The Android phone | Close the emulator window |
| The backend, keeping your data | `docker compose -p almira-personal -f ~/Developer/almira/dev-personal/docker-compose.personal.yml stop` |
| The backend **and delete its data** | `cd ~/Developer/almira && ./dev-personal/down.sh` |

`down.sh` deletes the demo household and both people. Re-create them with
`./dev-personal/up.sh --seed`.

Nothing here touches the `bhrigu-*` containers your other work uses. `down.sh`
prints the list of what is still running so you can see that for yourself.

---

## When something goes wrong

**"command not found: gradlew" / "emulator"** — you are in the wrong folder, or
you skipped `source ~/Developer/almira-personal/env.sh` in this window. Do both.

**"Couldn't reach Almira. Check your connection"** in the app — the backend is
not running. `curl -s http://localhost:18080/health` to check, then
`./dev-personal/up.sh`.

**`** BUILD FAILED **` or `BUILD FAILED`** — scroll up to the first line
starting `error:`. That line is the real problem; everything after it is noise.

**The app looks like an old version** — the build step was skipped or failed.
Re-run the build command and check for `BUILD SUCCEEDED` before installing.
