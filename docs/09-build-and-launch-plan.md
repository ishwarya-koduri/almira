[‹ Index](README.md) · [‹ Prev: Differentiation & Standout](08-differentiation-and-standout.md) · [Next › Phases & DoD](10-phases-user-stories-and-dod.md)

# 09 · Build & Launch Plan (end to end)

*From an empty GitHub repo to a live app on the Play Store and App Store — every account, tool, decision, and step.*

## 0. Your questions, answered directly
- **Is it mobile/tablet only, or web too?** **Mobile-first for launch** — one app that runs on **phone and tablet** (Android + iOS), because capture is a phone activity (photos, OTP, on-the-go) and your end goal is app-store publishing. **Web is a later, optional companion** (a read-and-report dashboard), not part of v1. This refines the earlier "web + mobile" to a shippable scope.
- **Java or Kotlin?** **Kotlin — everywhere.** Not Java (Kotlin is the modern, null-safe, official choice; Java is legacy for this). And it pairs perfectly with your **IntelliJ IDEA**. Kotlin now covers *both* backend **and** cross-platform mobile:
  - **Backend:** Kotlin + **Spring Boot** (Gradle) in IntelliJ.
  - **Mobile:** **Kotlin Multiplatform (KMP) + Compose Multiplatform** — one Kotlin codebase for Android *and* iOS. Compose Multiplatform for iOS went **stable in May 2025** and is production-ready in 2026 (used by Netflix, Cash App). So "all-Kotlin" is a real, first-class option now.
  - *(Alternative if you'd rather have the largest cross-platform UI ecosystem: **Flutter** for the app + the same Kotlin/Spring backend. Both are documented below; pick one.)*
- **Login via OTP — can we?** **Yes.** Phone-number OTP is the primary login, implemented cleanly (see [§9](#9-otp-authentication-in-detail)). We orchestrate OTP through an SMS provider and issue our own tokens — so identity stays in *our* control (good for the privacy stance), with biometric app-lock as the local second gate.

## 1. Recommended stack at a glance
| Layer | Choice | Notes |
|---|---|---|
| Language | **Kotlin** (backend + mobile) | IntelliJ-native; one language end to end |
| Mobile app | **Kotlin Multiplatform + Compose Multiplatform** | Android + iOS from one codebase; *(Flutter = alternative)* |
| Backend | **Spring Boot (Kotlin, Gradle)** | REST API, security, jobs; *(Ktor = lighter alternative)* |
| Database | **PostgreSQL** | relational + JSONB + **row-level security** (powers [privacy](05-security-and-privacy.md)) |
| Migrations | **Flyway** | versioned SQL in the repo |
| Caching / OTP store / rate-limit / queues | **Redis** | short-lived OTPs, sessions, throttling, background jobs |
| Object storage (documents) | **S3-compatible** (AWS S3 / GCS / Cloudflare R2) | encrypted blobs, signed URLs |
| Key management | **Cloud KMS** (AWS KMS / GCP KMS) | envelope encryption keys ([Doc 05 §4](05-security-and-privacy.md)) |
| Auth (OTP delivery) | **MSG91** (India) or **Twilio Verify**; email OTP fallback | *(Firebase Phone Auth = managed alternative)* |
| Containerization | **Docker** + **docker-compose** (local), containers in prod | |
| CI/CD | **GitHub Actions** | build/test/deploy backend + mobile |
| Hosting (backend) | **Fly.io / Render / Railway** (simple) or **AWS/GCP** (scale) | container deploy |
| Push notifications | **FCM** (Android + iOS via APNs) | reminders |
| Crash / errors | **Sentry** | backend + mobile |
| Analytics (privacy-respecting) | **PostHog** (self-hostable) | opt-in, no PII |

**Do we need a DB and caching?** Yes — **PostgreSQL** is the system of record; **Redis** is the cache + OTP/rate-limit/queue layer. Both run in Docker locally and as managed services in production.

## 2. Accounts & services to create (with costs)
| Account | Purpose | Cost (2026) |
|---|---|---|
| **GitHub** | source control, Actions CI/CD, issues | Free (Pro optional) |
| **JetBrains** IntelliJ IDEA | backend + KMP IDE | Community free; **Ultimate** (Spring support) paid |
| **Android Studio** | Android build/emulator, KMP tooling | Free |
| **Xcode on a Mac** | **required** to build/sign/submit iOS | Free (needs macOS hardware or a cloud Mac) |
| **Apple Developer Program** | publish to App Store, TestFlight | **$99 / year** |
| **Google Play Console** | publish to Play Store | **$25 one-time** |
| **SMS provider** (MSG91 / Twilio) | OTP delivery | pay-per-SMS |
| **India DLT registration** | required for SMS sender/template in India | one-time registration |
| **Cloud host** (Fly/Render/AWS/GCP) | backend, DB, Redis | usage-based |
| **Object storage + KMS** | documents + encryption keys | usage-based |
| **Sentry / PostHog** | monitoring / analytics | free tiers |
| **Domain + email** | website, deep links, transactional email | small annual |

> **iOS reality:** building and submitting iOS apps **requires macOS + Xcode**. If you don't have a Mac, use a **cloud Mac CI** (Codemagic, Bitrise, GitHub Actions macOS runners, or MacStadium). Plan for this early — it's the most common surprise.

## 3. Local machine setup (one-time)
1. Install **Git** and sign in to GitHub; set up SSH keys.
2. Install **JDK 21** (Temurin) and **Gradle** (or use the Gradle wrapper).
3. Install **IntelliJ IDEA Ultimate** (Spring + database tools) with the **Kotlin Multiplatform** plugin.
4. Install **Android Studio** + Android SDK + an emulator; enable KMP.
5. (iOS) On a Mac: install **Xcode** + command-line tools + an iOS Simulator.
6. Install **Docker Desktop**.
7. Install **kdoctor** (KMP environment checker) and run it until all checks pass.
8. Clone the repo, copy `.env.example` → `.env`, run `docker compose up` (Postgres + Redis), then run the backend and app from IntelliJ/Android Studio.

## 4. Repository & Git workflow
**Monorepo** (recommended for a solo/small team — atomic changes across app + backend):
```
almira/
├─ backend/            # Spring Boot (Kotlin, Gradle)
├─ app/                # Kotlin Multiplatform + Compose Multiplatform
│  ├─ shared/          # shared Kotlin (domain, api-client, models, validation)
│  ├─ androidApp/
│  └─ iosApp/
├─ db/                 # Flyway migrations + RLS policies + seed
├─ infra/              # docker-compose, IaC, deploy configs
├─ docs/               # this documentation set
└─ .github/workflows/  # CI/CD pipelines
```
**Branching:** trunk-based — short-lived feature branches → PR → CI must pass → squash-merge to `main`. Protect `main` (required checks, review). **Conventional Commits** (`feat:`, `fix:`) to auto-generate changelogs. Tag releases (`v0.1.0`).

## 5. Environments & configuration
Three environments — **dev** (local Docker), **staging** (cloud, test data), **prod** (cloud, real data). Config via environment variables; **never commit secrets**. Secrets live in GitHub Actions secrets + the cloud secret manager; local secrets in an untracked `.env`. Each environment has its own DB, Redis, storage bucket, and KMS key.

## 6. Local infrastructure (Docker Compose)
`infra/docker-compose.yml` brings up everything the app needs locally:
```yaml
services:
  db:
    image: postgres:16
    environment: { POSTGRES_DB: almira, POSTGRES_PASSWORD: dev }
    ports: ["5432:5432"]
    volumes: ["dbdata:/var/lib/postgresql/data"]
  redis:
    image: redis:7
    ports: ["6379:6379"]
  # optional: minio (S3-compatible) + mailhog (email OTP testing)
volumes: { dbdata: {} }
```
Backend connects to these; Flyway applies migrations on startup. This mirrors production (Postgres + Redis) so "works on my machine" also works in the cloud.

## 7. Backend build plan (Spring Boot, Kotlin)
- **Modules/packages** mirror [Doc 06](06-backend-api-and-stack.md): auth, households, members, institutions, accounts, investments, liabilities, goals, valuations, transactions, returns, tax, documents, reminders, reports, search, continuity, sharing, contacts, privacy, audit, notifications.
- **Persistence:** Spring Data JPA or JDBC; **Flyway** migrations in `db/migrations`; **Postgres row-level security** policies applied as migrations and toggled per request by setting `app.user_id` on the connection — this is the authoritative enforcement of the [privacy model](05-security-and-privacy.md#3-the-intra-household-privacy-model).
- **API:** REST controllers per [Doc 06 §2](06-backend-api-and-stack.md); request/response validation; consistent error envelope; idempotency keys on creates; optimistic concurrency via `version`.
- **Background jobs:** a scheduler (Spring `@Scheduled` or a Redis-backed queue) for reminders/cash-flow, emergency-access timers, OCR, imports, tax-pack generation.
- **Security:** Spring Security with JWT (access + refresh), method-level authorization for role capabilities, field-level encryption for sensitive columns via KMS envelope keys.
- **Testing:** JUnit + Testcontainers (real Postgres/Redis in tests) so RLS and migrations are tested for real.

## 8. Mobile build plan (Kotlin Multiplatform + Compose Multiplatform)
- **`shared/`** holds domain models, validation (the same rules as backend where possible), the typed API client, and the design tokens from [Doc 02](02-ux-and-design-system.md) expressed as a Compose theme (colors, type scale, spacing, shape).
- **UI** in Compose Multiplatform — the component library ([Doc 02 §6](02-ux-and-design-system.md)) built once as reusable composables (buttons, fields, selects/bottom-sheets, choice cards, segmented controls, toggles, chips, pickers, cards). Android and iOS render the same UI; platform-specific bits (biometrics, camera/OCR, secure storage) use KMP `expect/actual`.
- **Local storage/offline:** an offline-first cache (SQLDelight or Room-KMP) so capture works offline and syncs later with idempotency keys.
- **Navigation:** Compose Multiplatform navigation with deep-link support (for OTP links, share links, reminders).
- **iOS specifics:** the `iosApp/` wraps the shared UI in a SwiftUI/UIViewController host; native APIs (Keychain, LocalAuthentication/FaceID, APNs) via `actual` implementations. Build/sign on a Mac or cloud-Mac CI.
- *(Flutter alternative: `app/` is a single Flutter project (Dart) calling the same Kotlin/Spring API; recreate the Doc 02 design system as a Flutter theme + widget library. Choose this only if you prefer Flutter's UI ecosystem over staying all-Kotlin.)*

## 9. OTP authentication in detail
**Goal:** phone-number OTP as the primary factor, our backend in control, provider only for delivery.

### 9.1 Flow (sequence)
```
App                     Backend                      Redis            SMS provider
 │  POST /auth/otp/request {phone}                     │                    │
 │ ─────────────────────▶ │ rate-limit check ─────────▶│                    │
 │                        │ generate 6-digit OTP        │                    │
 │                        │ store hash+expiry(5m)+tries▶│ (key: otp:{phone}) │
 │                        │ send OTP ──────────────────────────────────────▶│  SMS
 │ ◀───────── 200 (requestId, cooldown) ───────────────┤                    │
 │  POST /auth/otp/verify {phone, code, requestId}      │                    │
 │ ─────────────────────▶ │ fetch hash ───────────────▶│                    │
 │                        │ compare + attempts++         │                    │
 │                        │ if ok: find/create user      │                    │
 │                        │ issue access JWT (15m) +     │                    │
 │                        │ refresh token (rotating) ────│ store refresh      │
 │ ◀──── 200 {access, refresh, isNewUser} ─────────────┤                    │
 │  (new user → onboarding; else → home)                │                    │
```

### 9.2 Rules that make OTP safe
- **6-digit** code, **5-minute** expiry, stored **hashed** in Redis (never plaintext), **max 5 verify attempts** then invalidate.
- **Rate limits:** per phone (e.g., 1 request / 30s, 5 / hour) and per IP; exponential cooldown; **CAPTCHA** after abuse.
- **Resend** with cooldown; **auto-read** the OTP on Android (SMS Retriever API) and iOS (one-time-code autofill) for a smooth UX.
- **Rotating refresh tokens** with reuse detection; short-lived access JWT; device/session list with remote revoke.
- **Biometric app-lock** (FaceID/fingerprint) as a local second gate on every open — pairs with OTP for strong, passwordless security.
- **Email OTP fallback** (for users without reliable SMS) and an optional **WhatsApp OTP** channel later (aligns with the Bharat-reach goal).

### 9.3 What to use
- **India:** **MSG91** (DLT-compliant OTP, autofill support) or **Twilio Verify** (handles OTP generation, delivery, retries across SMS/WhatsApp/email — less code for us).
- **Managed alternative:** **Firebase Phone Auth** — the client runs the OTP flow, the backend verifies the Firebase ID token. Fastest to build; trade-off is Google dependency for identity.
- **Recommendation:** start with **Twilio Verify** (or MSG91 in India) driven by our Spring backend issuing our own JWTs — keeps identity ours, satisfies the privacy stance, and lets us add email/WhatsApp channels without changing the app.
- **India note:** SMS OTP requires **DLT registration** of sender ID and message template (TRAI). Providers guide this; budget a few days.

## 10. CI/CD (GitHub Actions)
- **Backend pipeline:** on PR → build, unit + Testcontainers tests, lint (ktlint/detekt), security scan; on merge to `main` → build Docker image, push to registry, deploy to **staging**; manual approve → **prod**.
- **Android pipeline:** build AAB, run tests, upload to **Play Console** internal/closed track via the Play Developer API (Gradle Play Publisher).
- **iOS pipeline:** on a **macOS runner** — build, sign (Fastlane match for certs), upload to **TestFlight** via App Store Connect API.
- Secrets in GitHub Actions secrets; signing keys in encrypted secrets/Fastlane match. Cache Gradle for speed.

## 11. Cloud infrastructure & deployment
- **Backend:** container on **Fly.io/Render/Railway** (simple) or **AWS ECS/GCP Cloud Run** (scale); autoscaling, health checks.
- **Database:** managed **PostgreSQL** (Supabase/Neon/RDS/Cloud SQL) with automated encrypted backups, PITR, and a read replica when needed; PgBouncer for pooling at scale.
- **Redis:** managed (Upstash/Elasticache/Memorystore).
- **Storage:** S3/GCS/R2 bucket (private, encrypted) with signed URLs; **KMS** for envelope keys.
- **CDN + TLS:** Cloudflare in front; HTTPS everywhere; deep-link/associated-domain files hosted for iOS/Android app links.
- **IaC:** Terraform in `infra/` so environments are reproducible.

## 12. Security & compliance setup (build-time)
Ties to [Doc 05](05-security-and-privacy.md). Before any real user data: TLS + HSTS; field-level encryption via KMS; RLS policies live and tested; secrets in a manager; dependency/secret scanning in CI; rate limiting + WAF; audit logging on; encrypted backups + a tested restore. **India DPDP:** a privacy policy, consent capture (esp. for storing another adult's data and guardian basis for minors), and data export/delete endpoints. **DLT** registration for SMS. Draft the **security whitepaper** early — it's also marketing ([Doc 08](08-differentiation-and-standout.md)).

## 13. Testing strategy
- **Backend:** unit tests; **Testcontainers** integration tests (real Postgres/Redis, RLS verified); contract tests for the API.
- **Shared (KMP):** unit tests for domain/validation run on both targets.
- **UI:** Compose UI tests; screenshot tests for the design system; manual device matrix (a few Android phones/tablets + iPhone/iPad sizes).
- **E2E:** critical journeys (onboarding → OTP → capture → dashboard; continuity unlock) automated.
- **Beta:** internal → closed testing on both stores before production (see §14/§15).

## 14. Publishing to Google Play (step by step)
1. Pay the **$25 one-time** fee; create the Play Console account (Individual, or **Organization** with a D-U-N-S number to skip the tester rule below — org verification takes ~2–4 weeks).
2. Create the app; complete **store listing** (name, descriptions, screenshots for phone + tablet, feature graphic, icon).
3. Complete **Data safety** form (what data is collected, encryption, deletion) and the content-rating questionnaire; add the **privacy policy** URL.
4. Set up **app signing** (Google Play App Signing) and upload the AAB.
5. **Internal testing** track (up to 100 testers, instant) for your own QA.
6. **Closed testing** — **required for new personal accounts (created after 13 Nov 2023): at least 12 testers opted in for 14 continuous days** before you can apply for production access. Recruit 12+ real testers early (friends/family/community). *(Organization accounts are exempt.)*
7. Apply for **production access**, answer the readiness questions, then roll out (staged rollout %, then 100%).
8. Updates after production don't need the tester gate again.

> **Plan for the 12-testers/14-days gate on Android — it's the most common launch delay for new accounts.** Line up testers during Phase 1–2, not at the end.

## 15. Publishing to the App Store (step by step)
1. Enroll in the **Apple Developer Program** (**$99/year**; Individual or Organization).
2. On a **Mac with Xcode**: create the App ID, certificates, and provisioning profiles (automate with **Fastlane match**).
3. In **App Store Connect**: create the app record; fill metadata, screenshots (iPhone + iPad sizes), and the **privacy "nutrition labels"** (data types collected/linked/tracked).
4. Upload a build (Xcode/Fastlane) → distribute via **TestFlight** (up to 100 internal / 10,000 external testers) for beta.
5. Submit for **App Store review**; be ready for guideline checks (account deletion in-app is required if you offer sign-up; justify permissions; OTP/login must be testable — provide a demo login for reviewers).
6. On approval, **release** (manual or automatic, phased rollout available).

> **iOS needs a Mac** at build/sign/submit time. No Mac → use a cloud-Mac CI (Codemagic/Bitrise/GitHub macOS runners).

## 16. Post-launch operations
- **Monitor:** Sentry (crashes/errors), uptime, DB/Redis metrics, OTP delivery success, cost.
- **Support:** in-app feedback, help docs, a support inbox.
- **Iterate:** ship updates behind the CI/CD pipeline; watch the completeness/freshness and activation metrics ([Doc 08](08-differentiation-and-standout.md)); staged rollouts to catch regressions.
- **Compliance upkeep:** honor export/delete requests; rotate keys; renew the Apple membership annually; keep store data-safety/privacy labels accurate as features change.

## 17. Cost summary (indicative, 2026)
| Item | Cost |
|---|---|
| Google Play (one-time) | $25 |
| Apple Developer (annual) | $99/yr |
| GitHub / Sentry / PostHog | free tiers to start |
| SMS OTP | pay-per-message (₹ per SMS) |
| Cloud (backend + Postgres + Redis + storage) | usage-based; small at MVP scale |
| Mac for iOS (if none) | hardware or cloud-Mac subscription |
| Domain + transactional email | small annual |

## 18. End-to-end timeline (maps to the [roadmap](07-corner-cases-roadmap-prototype.md#2-roadmap--phases))
1. **Setup (days):** accounts, machine, repo, Docker, CI skeleton.
2. **Phase 0 (weeks):** auth (OTP), household/members, core capture + universal type, dashboard. Create the Play/Apple accounts now and **start recruiting 12 Android testers.**
3. **Phase 1:** full balance sheet + liabilities + reminders + search + RLS privacy; internal testing on both stores.
4. **Phase 2:** goals, returns, tax, import, OCR; closed testing (**14-day Android gate**), TestFlight beta.
5. **Phase 3:** continuity/transmission, sharing, E2E, security whitepaper; apply for production access; **App Store review**.
6. **Launch:** staged rollout on both stores → 100%.
7. **Post-launch:** monitor, support, iterate; web companion later if desired.

## 19. Definition of done (launch checklist)
- [ ] OTP login works on real devices (SMS autofill, rate limits, refresh rotation, biometric lock).
- [ ] RLS privacy verified: a member cannot see another's private records via API, search, reports, or exports.
- [ ] Encryption at rest + field-level for sensitive columns; documents encrypted; backups restore-tested.
- [ ] Reminders/EMI/cash-flow fire correctly across time zones.
- [ ] Import from spreadsheet seeds real data cleanly.
- [ ] Store listings, screenshots (phone + tablet), privacy policy, data-safety/nutrition labels complete.
- [ ] Android: 12 testers × 14 days done, production access granted.
- [ ] iOS: TestFlight beta passed, review approved (demo login provided).
- [ ] Sentry, analytics (opt-in), uptime monitoring live.
- [ ] Account deletion + data export available in-app.

[‹ Index](README.md) · [‹ Prev: Differentiation & Standout](08-differentiation-and-standout.md) · [Next › Phases & DoD](10-phases-user-stories-and-dod.md)
