[‹ Index](README.md) · [‹ Prev: Security & Privacy](05-security-and-privacy.md) · [Next › Corner Cases, Roadmap & Prototype](07-corner-cases-roadmap-prototype.md)

# 06 · Backend, API & Stack

## 1. Architecture
A **modular monolith** (one deployable, clean module boundaries), splittable into services only if scale demands. Layers: API → application services → domain → Postgres, with a **background worker** for reminders, notifications, OCR, imports, emergency-access timers, and tax-pack generation. Modules: auth · households · members · institutions · accounts · investments · liabilities · goals · valuations · transactions · returns · tax · documents · reminders · reports · search · continuity · sharing · contacts · **privacy/visibility** · audit · notifications.

## 2. API surface (REST; GraphQL is a fine alternative)
All household-scoped: `/api/households/{hid}/…`, JWT access + rotating refresh. **Every read passes the [Doc 05 §3](05-security-and-privacy.md#3-the-intra-household-privacy-model) visibility predicate.**
```
POST /auth/{signup,login,refresh,mfa/verify}        GET /me
GET/POST/PATCH/DELETE .../members       POST .../invitations   POST /invitations/{token}/accept
GET/POST .../institutions               GET/POST/PATCH/DELETE .../accounts   POST .../accounts/{id}/holders
GET .../investments?type=&member=&institution=&status=&goal=&q=&group_by=
POST .../investments                    GET/PATCH/DELETE .../investments/{id}
PATCH .../investments/{id}/visibility   (private|household|scoped + grants[])
POST .../investments/{id}/{valuations,transactions,documents,reminders,tax-lots}
GET/POST .../liabilities                POST .../liabilities/{id}/link-asset
GET/POST .../goals                      POST .../goals/{id}/map-investment
GET  .../returns?scope=investment|member|portfolio        GET .../tax/{summary,pack}?fy=
POST .../capture/parse-text  ·  parse-document  ·  /import
GET  .../reports/{allocation,upcoming,net-worth-trend,cashflow,liquidity}
GET  .../search?q=            GET/POST .../contacts     POST .../contacts/{id}/link
GET  .../continuity/{summary,transmission,export}
POST/DELETE .../shares        POST .../trusted-contacts   POST .../access-grants (request/veto)
GET  .../trash    POST .../trash/{entity}/{id}/restore     GET .../activity
```

## 3. Key behaviors
- **Create investment/liability** is transactional (record + ownerships/holders + nominees + first valuation + reminders + goal maps + tax lots + visibility grants), validating share/allocation sums and the attribute/custom-field schema first.
- **Returns** computed on read (XIRR via Newton's method over `transactions` + latest valuation), cached in Redis with short TTL, busted on write.
- **Reminders/cash-flow** worker scans due items (incl. EMIs), emits notifications, advances recurrences, offers rollover drafts.
- **Emergency access** worker flips `access_grants` at `unlocks_at`, notifying throughout the window.
- **Idempotency keys** on all creates; **optimistic concurrency** via `version` check ([Doc 05 §10](05-security-and-privacy.md)).

## 4. Tech stack — fast MVP path (solo/small team)
| Layer | Choice | Why |
|---|---|---|
| DB + Auth + Storage + RLS | **Supabase** (managed Postgres) | Postgres + auth + **row-level security** + encrypted storage in one; RLS is exactly how the privacy model is enforced. |
| Backend logic | Supabase Edge Functions or a small **NestJS** service | Capture parsing, returns/tax math, reminders. |
| Web | **Next.js (React) + TS**, TanStack Query, Tailwind, shadcn/ui | Fast, beautiful, SSR; pairs with [Doc 02](02-ux-and-design-system.md) tokens. |
| Mobile | **React Native (Expo)** or **Flutter** | One codebase; capture is mobile-heavy. |
| Jobs | Supabase cron + Node worker / Upstash QStash | Reminders, OCR, imports, tax packs. |
| OCR | Google Vision / Textract, or on-device ML Kit | Document capture. |
| Notifications | FCM/APNs + Resend/SES (+ WhatsApp later) | Push + email. |
| Hosting | Vercel (web) + Supabase (data) + Fly.io/Render (worker) | Minimal ops. |

## 5. Tech stack — scale path
NestJS (Node/TS) or FastAPI (Python) modular monolith → selective services; managed Postgres + read replicas + PgBouncer; Redis (cache/queues via BullMQ); S3 (SSE-KMS) + pre-signed URLs; Postgres FTS/trigram before OpenSearch; Docker + Terraform; GitHub Actions CI/CD; OpenTelemetry + Grafana/Loki + Sentry.

## 6. Why Postgres (not NoSQL)
Deeply relational (ownership, linkage, aggregation, and especially **row-level-security-based privacy**) yet needs flexible per-type/custom attributes — Postgres gives relational integrity **and** JSONB, matching the universal engine. Optional India rails later: **DigiLocker** (official documents) and **Account Aggregator** (consent-based import).

[‹ Index](README.md) · [‹ Prev: Security & Privacy](05-security-and-privacy.md) · [Next › Corner Cases, Roadmap & Prototype](07-corner-cases-roadmap-prototype.md)
