[‹ Index](README.md) · [‹ Prev: Phases & DoD](10-phases-user-stories-and-dod.md) · Next —

# 11 · Getting Started — Step-by-Step Execution Runbook

*How we actually build Almira, from zero. Everything in order, including the basics. Follow the stages top to bottom; each ends with a ✅ checkpoint so you always know it's working before moving on.*

> **The golden rule:** don't build all of Phase 0 at once. First get **one thin thread working end to end** (log in with OTP → add one investment → see it on Home). That proves the whole toolchain. Then widen it story by story using [Doc 10](10-phases-user-stories-and-dod.md). Building breadth before you have one working thread is the most common way projects stall.
>
> **A realistic note:** this is a substantial app. If you're learning as you go, that's completely fine — go slow, one stage at a time, and lean on an AI pair-programmer (e.g., **Claude Code**) plus official docs for Kotlin, Spring Boot, and Compose Multiplatform. You do not need to know everything before you start; you need Stage 7 working.

---

## Stage 0 — Get oriented (before touching code)
1. Read the docs in this order: [README](README.md) → [01 Product](01-product-and-scope.md) → [04 Data Model](04-data-model.md) → [05 Security & Privacy](05-security-and-privacy.md) → [09 Build & Launch](09-build-and-launch-plan.md) → [10 Phases & DoD](10-phases-user-stories-and-dod.md).
2. Confirm the decisions (already recommended): **mobile-first** (Android + iOS, phone + tablet), **Kotlin everywhere** (Spring Boot backend + Kotlin Multiplatform/Compose app), **phone OTP** login.
3. Accept the golden rule above: **thin slice first.**

✅ *You know what you're building and in what order.*

---

## Stage 1 — Create your accounts
Do the free/needed-now ones first; defer the paid store accounts until Phase 1 (but not later — the Play tester gate needs lead time).

1. **GitHub** — sign up, verify email, **turn on 2FA**. (Create a private repo later in Stage 3.)
2. **JetBrains / IntelliJ IDEA** — create an account; IntelliJ **Community** is free and fine to start; **Ultimate** adds full Spring support (30-day free trial).
3. **SMS/OTP provider** — create a **Twilio** account (global, easy **Verify** API) or **MSG91** (India). *India note:* start **DLT registration** now — it takes a few days.
4. **Cloud host** (can defer to first deploy) — **Supabase** (Postgres + auth + storage in one) or **Fly.io/Render** for a container.
5. **Google Play Console** ($25 one-time) and **Apple Developer Program** ($99/year) — **create during Phase 1**, because the Play closed-test gate needs ≥12 testers for 14 continuous days before production.

✅ *GitHub + IntelliJ + an OTP provider account exist.*

---

## Stage 2 — Set up your computer
Run these once. (macOS/Linux shell shown; on Windows use PowerShell/WSL equivalents.)

1. **Git** — install, then set identity and an SSH key:
   ```bash
   git --version                      # confirm installed
   git config --global user.name  "Your Name"
   git config --global user.email "you@example.com"
   ssh-keygen -t ed25519 -C "you@example.com"   # press enter through prompts
   cat ~/.ssh/id_ed25519.pub          # copy this, add to GitHub → Settings → SSH keys
   ```
2. **JDK 21** (Temurin) — install; confirm `java -version` shows 21.
3. **IntelliJ IDEA** — install; in *Settings → Plugins* add **Kotlin Multiplatform**.
4. **Android Studio** — install; open *SDK Manager* (install a recent SDK) and *Device Manager* (create one emulator).
5. **Xcode** — *(Mac only; required for iOS)* install from the App Store; open it once to install components; add an iOS Simulator. *No Mac?* You can build/run Android now and add iOS later via a **cloud-Mac CI** (Codemagic/Bitrise).
6. **Docker Desktop** — install; confirm:
   ```bash
   docker run hello-world
   ```
7. **kdoctor** (KMP environment checker) — install and run `kdoctor` until every check is green.

✅ *`java -version` = 21, an Android emulator boots, Docker runs hello-world, kdoctor is green.*

---

## Stage 3 — Create the repo & project skeleton
1. On GitHub: **New repository** → name `almira` → **Private** → add a README, a `.gitignore` (choose *Kotlin* / *Gradle* / *Android* templates), and a license.
2. Clone and create the monorepo folders (matches [Doc 09 §4](09-build-and-launch-plan.md)):
   ```bash
   git clone git@github.com:<you>/almira.git
   cd almira
   mkdir -p backend app db/migrations infra docs .github/workflows
   cp -r <these-docs> docs/           # keep this doc set in the repo
   git add . && git commit -m "chore: scaffold monorepo" && git push
   ```
3. On GitHub: **Settings → Branches → protect `main`** (require a PR + green checks before merge).

✅ *A private `almira` repo with the folder skeleton and the docs, pushed to GitHub.*

---

## Stage 4 — Bring up local infrastructure (Docker)
1. Create `infra/docker-compose.yml` (Postgres + Redis — from [Doc 09 §6](09-build-and-launch-plan.md)).
2. Start it and confirm:
   ```bash
   docker compose -f infra/docker-compose.yml up -d
   docker ps                      # postgres + redis should be running
   ```
3. Connect with IntelliJ's Database tool (or `psql`) to `localhost:5432`, db `almira`.

✅ *Postgres and Redis are running locally and you can connect to the database.*

---

## Stage 5 — Backend skeleton (Spring Boot, Kotlin)
1. Generate the project at **start.spring.io** (or IntelliJ *New Project → Spring*): language **Kotlin**, build **Gradle (Kotlin DSL)**, Java **21**, dependencies: *Spring Web, Spring Data JDBC (or JPA), PostgreSQL Driver, Flyway Migration, Validation, Spring Security, Spring Data Redis.* Put it in `backend/`.
2. Configure `backend/src/main/resources/application.yml` with the local Postgres + Redis URLs.
3. Write the **first migration** `db/migrations/V1__init.sql` — create `households`, `users`, `members`, `household_memberships` (copy from [Doc 07 §3 DDL](07-corner-cases-roadmap-prototype.md)). Point Flyway at `db/migrations`.
4. Add a trivial health endpoint and run:
   ```bash
   cd backend && ./gradlew bootRun
   curl http://localhost:8080/health      # expect 200 OK
   ```
   On start, Flyway should apply V1 and the tables should appear in the DB.

✅ *The backend starts, applies the migration, and answers `/health`.*

---

## Stage 6 — Mobile skeleton (Kotlin Multiplatform + Compose)
1. Create the app with the **Kotlin Multiplatform wizard** (or IntelliJ/Android Studio *New Project → Kotlin Multiplatform*): produces `app/shared`, `app/androidApp`, `app/iosApp`.
2. Run it:
   - **Android:** select the emulator, press Run.
   - **iOS (Mac):** open `app/iosApp` in Xcode (or run the iOS target), pick a Simulator, Run.
3. Add the **design tokens** from [Doc 02](02-ux-and-design-system.md) (colors, type scale, spacing, shape) as a Compose `MaterialTheme` in `shared`.

✅ *A themed "Hello Almira" screen runs on the Android emulator (and iOS Simulator if on a Mac).*

---

## Stage 7 — The first vertical slice ⭐ (the one that matters)
Build **one thread end to end** — a small subset of Phase 0 — so the whole stack is proven before you widen it. Keep everything minimal.

1. **Backend — OTP:** implement `POST /auth/otp/request {phone}` and `POST /auth/otp/verify {phone, code}`. To start, you can log the code to the console (no SMS yet) or use Twilio Verify test credentials. On verify, create the user if new and return an access JWT + refresh token. Store the OTP **hashed in Redis** with a 5-minute expiry.
2. **App — login:** a phone-number screen → an OTP screen → on success store the tokens (in the platform secure store) and route to Home.
3. **Backend — create household + one investment:** `POST /households` (on first login) and a minimal `POST /investments {title, type, amount, visibility}` writing to Postgres (with an ownership row = Me, 100%).
4. **App — capture + see it:** a simple form (title, amount, type) → Save → a list screen shows it → Home shows a **Total** = sum of your visible records.
5. Wire the app to the backend through a small typed API client in `app/shared`.

✅ **Checkpoint (the big one):** on a real device/emulator you can *log in with an OTP, add one investment, and see it counted on Home.* The entire toolchain — app ↔ backend ↔ Postgres/Redis ↔ auth — now works. 🎉

---

## Stage 8 — Version-control workflow (adopt from here on)
- One **branch per story** (`feat/otp-login`), open a **PR**, let CI run, **squash-merge** to `main`.
- Use **Conventional Commits** (`feat:`, `fix:`, `chore:`) so a changelog can be generated.
- **Tag** milestones (`v0.1.0` at the end of the slice).

✅ *Every change now flows through a PR with review + green checks.*

---

## Stage 9 — Basic CI (GitHub Actions)
1. Add `.github/workflows/backend.yml`: on PR → set up JDK 21, cache Gradle, run `./gradlew test` (spin up Postgres/Redis as service containers so tests are real).
2. Add `.github/workflows/android.yml`: build the Android app and run unit tests.
3. Make these checks **required** for merging to `main`.

✅ *PRs can't merge unless the build and tests pass.*

---

## Stage 10 — Iterate through Phase 0, then 1, 2… (using Doc 10)
Now widen the slice, one story at a time, from [Doc 10](10-phases-user-stories-and-dod.md). For **each** story: read its acceptance criteria → build on a branch → write tests (incl. a **privacy/RLS test** — a member must never see another's private record) → open a PR → meet the **Universal DoD** → merge.

Phase 0 order that works well:
1. Real **OTP** via Twilio/MSG91 + resend/rate-limits + SMS autofill.
2. **Biometric app-lock** + sessions/logout.
3. **Household + members** (incl. managed members) + **default visibility**.
4. **Type-aware capture** + **universal/custom** type & fields.
5. **Ownership/joint + per-record visibility**, enforced by **Postgres Row-Level Security** ([Doc 07 §3 policy](07-corner-cases-roadmap-prototype.md)) — turn RLS on *now*, not later.
6. **List + detail**, then the **basic dashboard**.

Then deploy the backend to **staging** (a cloud host + managed Postgres/Redis), point a build at it, and keep going into Phase 1 (liabilities, true net worth, reminders, vault, search).

✅ *Phase 0 done = you and ≥2 family members use it daily instead of the spreadsheet (its phase DoD in Doc 10).*

---

## Stage 11 — Store prep (start during Phase 1, not at the end)
1. Create the **Google Play Console** ($25) and **Apple Developer** ($99/yr) accounts.
2. Set up **internal testing** on both, and — critically — **recruit ≥12 Android testers** and get them opted in, because a new personal Play account needs **12 testers for 14 continuous days** before production. Start this early.
3. Prepare a **privacy policy** URL and the store **data-safety / privacy-nutrition** answers as features settle.

✅ *Both store accounts exist; the 12-tester clock is ticking.*

---

## Stage 12 — Toward launch (Phase 3)
1. **Closed testing** on Play (clear the 14-day gate) and **TestFlight** beta on iOS.
2. Finish store listings (phone + tablet screenshots), privacy forms, and in-app **account deletion + data export** (both stores require these).
3. Submit for **App Store review** (provide a demo OTP login for reviewers) and apply for **Play production access**.
4. **Staged rollout** → 100% on both stores. 🚀
5. Post-launch: watch Sentry (crashes), OTP delivery success, and the completeness/activation metrics; ship updates through the same pipeline.

✅ *Almira is live on Google Play and the App Store.*

---

## Quick reference — the whole path in one breath
**Accounts → install tools → make the repo → `docker compose up` → backend runs `/health` → app shows "Hello Almira" → the OTP-login-add-one-investment slice works → PR/CI workflow → iterate Phase 0 with RLS on → deploy to staging → Phases 1–2 → store accounts + 12 testers → closed/TestFlight beta → review → staged production launch → iterate Phases 3–4.**

If you get stuck at any stage, that stage's ✅ checkpoint is the thing to fix before moving on — and I can help you through any single step in detail (commands, config, or code) whenever you want.

[‹ Index](README.md) · [‹ Prev: Phases & DoD](10-phases-user-stories-and-dod.md) · Next —
