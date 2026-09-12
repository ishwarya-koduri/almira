# Almira — Documentation Set

> **Almira** — the *almirah*, the cupboard where an Indian family keeps its gold, papers, and passbooks; now the place they keep the record of it all. *Every rupee your family has invested or owes — physical, digital, listed, unlisted, insured, or one‑of‑a‑kind — in one calm, private place, so nothing is ever forgotten and no one is ever left guessing.*

This is the **build-ready documentation set**, split into interlinked docs for convenience. It supersedes the earlier single-file blueprint. Start here, then follow the links.

## Status
- **Domain, scope, data model:** build-ready.
- **UX & design system:** deeply specified in this revision (Doc 02).
- **Security & privacy (incl. intra-household privacy):** now a first-class, priority document (Doc 05).
- **Still open:** a few product decisions — see the end of each relevant doc and the decision log below.

## The documents
| # | Doc | What's inside |
|---|---|---|
| 01 | [Product & Scope](01-product-and-scope.md) | Problem, philosophy, personas, full asset taxonomy, the *record-anything* universal engine, liabilities, goals, returns, tax, estate/continuity, information architecture (every tab). |
| 02 | [UX & Design System](02-ux-and-design-system.md) | The look & feel: restrained palette, typography, spacing, motion, and the full interactive component library (dropdowns, radios, toggles, chips, pickers, forms…). |
| 03 | [Screens & Flows](03-screens-and-flows.md) | Wireframes and the key journeys: onboarding, capture, first-open, continuity/heir mode. |
| 04 | [Data Model](04-data-model.md) | Every table, column, index — including the **visibility** columns that power intra-household privacy. |
| 05 | [Security & Privacy](05-security-and-privacy.md) | **Priority.** Threat model, auth, encryption/E2E, and the **intra-household privacy & visibility model**. Compliance, sharing, concurrency. |
| 06 | [Backend, API & Stack](06-backend-api-and-stack.md) | Architecture, API surface, and the recommended tech stack (MVP + scale). |
| 07 | [Corner Cases, Roadmap & Prototype](07-corner-cases-roadmap-prototype.md) | Edge scenarios, phased roadmap, prototype DDL, repo layout, first vertical slice. |
| 08 | [Differentiation & Standout](08-differentiation-and-standout.md) | Competitor comparison, what makes Almira unforgettable, and the risks to watch. |
| 09 | [Build & Launch Plan](09-build-and-launch-plan.md) | **End to end:** accounts, tooling, Kotlin stack, Docker, DB/cache, OTP login, CI/CD, and publishing to Google Play + App Store. |
| 10 | [Phases, Stories & Definition of Done](10-phases-user-stories-and-dod.md) | The delivery backlog: every phase with **user stories, acceptance criteria, and a Definition of Done**. |
| 11 | [Getting Started Runbook](11-getting-started-runbook.md) | **How to actually build it, step by step from zero** — accounts, tools, repo, first running slice, then how to iterate to launch. |
| 12 | [Zero-knowledge encryption](12-end-to-end-encryption.md) | The scheme, completely: KDF parameters, envelope layout, AAD, rotation, and what it does *not* defend against — written so a second client implements the same one. |
| 13 | [Providers & going live](13-providers-and-going-live.md) | DigiLocker, Account Aggregator, WhatsApp, SMS, email, push: what is built, what the sandbox does, and exactly which credential or registration flips each one live. |
| 14 | [Language](14-localization.md) | English, Telugu, Hindi — what is translated, what is not, and what finishing would take. |
| 15 | [Security whitepaper](15-security-whitepaper.md) | The public document: how the privacy model is enforced, what is encrypted and how, and a plain section on what none of it defends against. |
| 16 | [Controls & threat model](16-controls-and-threat-model.md) | The auditor's companion: trust boundaries, assets against adversaries, STRIDE, a control catalogue naming the file to read, and the known gaps. |
| 17 | [Deploying](17-deploying.md) | Bare host to running instance: the compose stack, the runtime role, the key and what losing it means, backups, the installable web client — and why your first testers cannot sign in by email yet. |
| 18 | [Handover](18-handover.md) | What exists, what is deliberately not built, the order to do the rest in, what has never been verified, and how to pick the repository up on another machine. |
| — | [Known issues](known-issues.md) | A running log of things found while building and deliberately **not** fixed in the change that found them: what it is, which behaviour is the correct one, why it is still there, and when to pick it up. Entries are deleted when fixed. |

## How the docs interlink
Each doc has a top nav bar (**‹ Index · Prev · Next ›**) and cross-links inline. The dependency order for building: **01 → 04 → 05 → 06 → 02/03 → 07 → 08 → 09**.

## Decision log
**Decided**
- **Name** — **Almira** (the family's digital *almirah* — where everything valuable is kept).
- **Platform** — **mobile-first (iOS + Android), tablet-responsive; web companion later.** ([Doc 09 §1](09-build-and-launch-plan.md))
- **Stack** — **Kotlin everywhere: Spring Boot backend + Kotlin Multiplatform/Compose app** + PostgreSQL + Redis (Flutter is the documented app-framework alternative). ([Doc 09 §1–2](09-build-and-launch-plan.md))
- **Login** — **phone OTP** (Twilio Verify / MSG91) + biometric app-lock. ([Doc 09 §9](09-build-and-launch-plan.md))

**Decided since, in the build**
- **Privacy depth** — both. Server-side envelope encryption for everything sensitive, *plus* optional zero-knowledge fields the server cannot read at all ([Doc 12](12-end-to-end-encryption.md)).
- **Web companion** — built alongside the backend rather than later; it is how every phase was exercised, and several bugs were only ever going to be found by looking at a screen.
- **Providers** — adapters with working sandboxes, built before the accounts exist ([Doc 13](13-providers-and-going-live.md)).

**Still pending your call**
1. **App framework** — Flutter (best UI) vs Kotlin Multiplatform + Compose (one language, IntelliJ-native)?
3. **Household default visibility** — "transparent by default" or "private by default" (see [Doc 05 §3](05-security-and-privacy.md#3-the-intra-household-privacy-model))?
4. **Continuity in v1** — full Transmission Assistant + emergency access, or a simple shareable summary first?
5. **Tax depth in v1** — full 80C + capital gains, or deduction meters only?
6. **Next artifact** — a clickable prototype (dashboard + capture) or a runnable schema + starter codebase?

*Living documents — every section is meant to be challenged and refined as the product takes shape.*
