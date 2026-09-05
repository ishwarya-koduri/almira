[‹ Index](README.md) · [‹ Prev: Build & Launch Plan](09-build-and-launch-plan.md) · [Next › Getting Started Runbook](11-getting-started-runbook.md)

# 10 · Phases, User Stories, Acceptance Criteria & Definition of Done

The delivery backlog for **Almira**, organised by the phases in [Doc 07 §2](07-corner-cases-roadmap-prototype.md#2-roadmap--phases). Each phase lists its goal, epics, **user stories** (`As a <persona>, I want <capability> so that <benefit>`), **acceptance criteria** (AC — testable, checkbox form), and a phase **Definition of Done (DoD)**.

**Personas** (from [Doc 01 §3](01-product-and-scope.md#3-personas)): **Organizer** (primary), **Family CFO**, **Heir/Nominee**, **Elder**, **Privacy Hawk**, plus the **Admin** role.

## How to read this
- **Definition of Ready (DoR)** — a story is ready to build when: it has clear ACs, a design reference ([Doc 02](02-ux-and-design-system.md)/[Doc 03](03-screens-and-flows.md)), any data-model impact is identified ([Doc 04](04-data-model.md)), and its privacy/visibility behaviour is specified ([Doc 05](05-security-and-privacy.md)).
- **Universal Definition of Done (applies to EVERY story)** — listed once below; phase DoDs add release-level gates on top.

### Universal Definition of Done (every story)
- [ ] Merged via PR with green CI (build, unit + integration tests, lint, security scan).
- [ ] Tests: unit + integration (Testcontainers for anything touching the DB); critical flows covered by E2E.
- [ ] **Privacy proven:** the feature respects per-record visibility and household scoping — an automated test confirms no cross-member/cross-household leakage via API, list, detail, search, reports, or export ([Doc 05](05-security-and-privacy.md)).
- [ ] Input validated; errors handled with kind, plain-language messages; no sensitive data in logs/analytics.
- [ ] Money & quantity use `numeric`; amounts render in Indian grouping (₹1,76,875) with tabular figures.
- [ ] Runs on **Android + iOS**, **phone + tablet**; supports dynamic type; meets **WCAG AA**; dark mode; Simple Mode not broken.
- [ ] Offline-safe where capture is involved; optimistic concurrency respected on edits.
- [ ] Audit-log entry where the action is sensitive; opt-in analytics event where useful.
- [ ] Behind a feature flag / staged-rollout-ready; changelog + affected docs updated.
- [ ] Product owner accepts the story against its ACs on a real device.

---

## Phase 0 — Foundations (the MVP that replaces the spreadsheet)
**Goal:** sign in with OTP, add *any* asset (with owner + visibility), and see a dashboard — on real devices. **Exit:** you + ≥2 family members using it daily instead of the sheet.

### Epic 0.1 — Account & OTP auth
**0.1.1 Phone OTP sign-in** — *As a new user, I want to sign in with my phone number + an OTP so I don't need a password.*
- [ ] Requesting an OTP delivers a 6-digit code (SMS) within ~30s; UI shows a resend cooldown.
- [ ] Correct code within 5 minutes logs me in and issues an access token + rotating refresh token.
- [ ] Wrong code shows a clear error; after 5 failed attempts the code is invalidated and I must request a new one.
- [ ] Rate limits enforced (≈1 request/30s, 5/hour per phone; per-IP too); abuse triggers cooldown/CAPTCHA.
- [ ] OTP autofills on Android (SMS Retriever) and iOS one-time-code.
- [ ] A new number routes to onboarding; a known number routes to Home.

**0.1.2 Biometric app-lock** — *As any user, I want the app to lock behind Face/fingerprint so a lost phone doesn't expose my data.*
- [ ] On cold start and on resume from background, the app requires biometric (device-passcode fallback).
- [ ] Toggle in Settings, default ON; content stays hidden until unlocked.

**0.1.3 Sessions & logout** — *As a user, I want to see and revoke my sessions and log out.*
- [ ] Settings lists sessions (device, last active); revoking one invalidates its refresh token immediately.
- [ ] Logout clears tokens locally and requires OTP again.

### Epic 0.2 — Household & members
**0.2.1 Create household** — *As an Organizer, I want to choose "Just me" or "Me + family" so the app fits my situation.*
- [ ] The choice creates a household; "family" prompts adding members; the mode is changeable later.

**0.2.2 Add members (with or without a login)** — *As a Family CFO, I want to add people, some without their own login, so I can track their assets.*
- [ ] Add a member with name + relationship (+ optional DOB → auto minor flag).
- [ ] A **managed** member needs no login; I can later invite them to claim their own login, which merges (no duplicate).

**0.2.3 Default visibility** — *As a Privacy Hawk, I want to set a default (Private/Household) for my new records.*
- [ ] Onboarding captures my default; it's stored per user and applied to new captures; the household also has a default.

### Epic 0.3 — Capture (core + record-anything)
**0.3.1 Add an investment (type-aware form)** — *As an Organizer, I want a form that asks only what the type needs so capture is fast.*
- [ ] Choosing a type reshapes the form; ≤5 essentials visible, the rest under "More details".
- [ ] Required fields validate on blur with inline errors; Save persists and the record appears in the list at once.
- [ ] "Save & add another" keeps context (institution, date); money shows ₹ + Indian grouping + amount-in-words helper.
- [ ] A draft autosaves, survives an app kill, works offline, and syncs later (idempotent).

**0.3.2 Record anything (universal type + custom fields)** — *As an Organizer, I want to record an asset the app doesn't predefine, with my own fields.*
- [ ] A "Custom/Other" type accepts the common fields and is countable in totals.
- [ ] I can add a typed custom field (text/number/money/date/percent/bool/select) with a label/unit; it renders in capture + detail.
- [ ] A custom **money** field is counted exactly once in totals; non-money custom fields never affect totals.
- [ ] I can save a reusable **custom type**; it then behaves like any built-in type.

**0.3.3 Ownership & visibility at capture** — *As a Family CFO, I want to set who owns a record (incl. joint shares) and who can see it.*
- [ ] Owner defaults to Me; I can add joint owners; shares must total 100% (UI enforces).
- [ ] Visibility is Private / Household / Scoped; Scoped lets me pick specific members.
- [ ] A joint owner always sees a jointly-owned record regardless of visibility.

### Epic 0.4 — List & detail
**0.4.1 Investment list** — *As an Organizer, I want a searchable, filterable list so I find things fast.*
- [ ] Rows show type, owner avatar(s), value, next date, status; filter by type/member/status; group-by works; empty state guides adding.

**0.4.2 Investment detail** — *As an Organizer, I want a detail view with everything about a holding.*
- [ ] Shows value, linkage, nominee, visibility, notes, custom fields; supports edit and archive (soft-delete → Trash).

### Epic 0.5 — Dashboard (basic)
**0.5.1 Total assets + breakdown** — *As an Organizer, I want a home screen with my total and a simple breakdown.*
- [ ] Shows **Total Assets** (Σ my visible records × share) with amount-in-words and a count-up.
- [ ] Scope switcher (Me/Household/Member) changes the figure; each viewer sees only records they're permitted to.
- [ ] Allocation donut by category and a bar by member. *(True net worth incl. liabilities arrives in Phase 1.)*

### Epic 0.6 — Privacy foundation
**0.6.1 Enforce per-record visibility** — *As a Privacy Hawk, I want my private records invisible to others, even admins.*
- [ ] Via API + RLS, a member cannot read another member's Private record through list, detail, search, or export.
- [ ] The Owner/Admin **role does not** bypass visibility.
- [ ] A viewer's household total excludes records they can't see — no amount leaks.
- [ ] Covered by automated tests that would fail on any leakage.

### ✅ Phase 0 Definition of Done
- [ ] OTP login, biometric lock, household + members, capture (incl. universal/custom), list/detail, basic dashboard, and per-record visibility all work on Android + iOS (phone + tablet).
- [ ] RLS privacy proven by tests (no cross-member leakage).
- [ ] Real data can be entered manually (bulk import is Phase 2).
- [ ] **Internal testing** build live on both stores; you + ≥2 family members using it on real devices.
- [ ] Universal DoD met for every included story.

---

## Phase 1 — The complete balance sheet
**Goal:** true net worth (assets − liabilities), full asset coverage, linkage, nominees, reminders + cash-flow, documents, and search — privacy fully enforced. **Exit:** the app is a trustworthy single source of truth for the whole household.

### Epic 1.1 — Full taxonomy
**1.1.1 All common Indian asset types** — *As an Organizer, I want templates for FD, MF, stocks, gold, insurance, PPF/EPF/NPS, SSY, bonds, property, chit, etc., so every holding fits.*
- [ ] Each type exposes its relevant fields (per [Appendix](01-product-and-scope.md)); the type picker is searchable; category dots/icons are consistent.

### Epic 1.2 — Institutions, accounts & linkage
**1.2.1 Institutions & accounts** — *As a Family CFO, I want to record banks/AMCs/brokers/insurers and their accounts (savings/demat/folio).*
- [ ] Add an institution (logo where known) and an account with a **masked** number; accounts support **co-holders**.

**1.2.2 Link an investment to an account** — *As an Organizer, I want to know which bank funds which SIP.*
- [ ] Capture/detail lets me pick a linked account; the Institutions view groups records by institution/account; **unlinked records are flagged**.

### Epic 1.3 — Nominees
**1.3.1 Record nominees** — *As an owner, I want nominee(s) with % splits so beneficiaries are clear.*
- [ ] Add nominee (a member or free text) with shares; shown in detail; nominee is distinct from owner.

### Epic 1.4 — Liabilities & true net worth
**1.4.1 Record liabilities** — *As a Family CFO, I want to record loans with EMI and outstanding.*
- [ ] Add a liability (lender, principal, outstanding, rate, EMI amount/day, tenure); an EMI creates a recurring reminder; visibility behaves like investments.

**1.4.2 Asset–liability linkage & net worth** — *As an Organizer, I want net worth = assets − liabilities and to know which asset a loan is against.*
- [ ] Link a loan to the asset it secures; the asset detail shows "encumbered — ₹X outstanding".
- [ ] Home shows **True Net Worth** with **Total Assets** and **Total Liabilities** as sub-figures; per-viewer visibility respected.

### Epic 1.5 — Valuations & trend
**1.5.1 Value snapshots & net-worth trend** — *As an Organizer, I want to record values over time and see the trend.*
- [ ] Add a valuation (date, value, optional qty); current value = latest; a trend chart is drawn from snapshots; records with no valuation are marked "at cost / unknown" (never fabricated).

### Epic 1.6 — Reminders & cash-flow
**1.6.1 Reminders + cash-flow calendar** — *As any user, I want alerts before maturities/premiums/EMIs/SIPs and a money in/out view.*
- [ ] Reminders fire at (due − lead days) correctly across time zones; snooze/mark-done; a done maturity offers a **rollover draft**.
- [ ] A calendar/timeline shows projected outflows (SIP/premium/EMI) and inflows (interest/rent/maturity).

### Epic 1.7 — Document vault
**1.7.1 Attach & find proofs** — *As an Heir/Organizer, I want to attach proofs and retrieve them.*
- [ ] Attach an **encrypted** document to a record; view after re-auth via a short-lived signed URL; documents are searchable; a "missing proof" checklist exists; access respects visibility.

### Epic 1.8 — Global search
**1.8.1 Search everything** — *As an Organizer, I want one search across all my data.*
- [ ] Results are grouped (investments, liabilities, accounts, contacts, documents), **respect visibility**, and deep-link to the record; recent/suggested shown when empty.

### ✅ Phase 1 Definition of Done
- [ ] True net worth is accurate incl. joint shares and liabilities; all core asset types capture with linkage; nominees recorded.
- [ ] Reminders + cash-flow working; documents encrypted and retrievable; global search live.
- [ ] RLS enforced across every new surface (list/detail/search/reports/exports) — proven by tests.
- [ ] **Closed testing** build on both stores; the **Google Play 12-testers / 14-day** gate started (recruit testers now).
- [ ] Universal DoD met for every story.

---

## Phase 2 — Insight & delight
**Goal:** turn the registry into insight (goals, returns, tax) and make capture effortless (quick-add, OCR, import). **Exit:** the app tells you how you're doing and importing the old sheet is one step.

### Epic 2.1 — Goals
**2.1.1 Goals & mapping** — *As an Organizer, I want goals and to map investments to them.*
- [ ] Create a goal (name, target, date, priority, optional member); map investments with allocation % (≤100% per investment; over-allocation warned).
- [ ] A progress ring shows funded ÷ target and on-track/behind (informational); a "by goal" dashboard lens exists; unallocated investments are flagged.

### Epic 2.2 — Returns
**2.2.1 Returns (XIRR/CAGR, realized/unrealized)** — *As an Organizer, I want to see performance where the data allows.*
- [ ] Absolute + CAGR for lump sums; **XIRR** for multi-cash-flow (SIPs); realized vs unrealized derived from transactions.
- [ ] Shown per investment/category/member/portfolio; where data is insufficient, the UI prompts "add a valuation" instead of showing a fabricated number.

### Epic 2.3 — Tax (India) — *informational, not tax advice*
**2.3.1 Deduction meters** — *As an Organizer, I want to see 80C/80D/80CCD usage.*
- [ ] Meters auto-derive from tagged records; show used vs limit; FY selector; the "not tax advice" note is shown.

**2.3.2 Capital gains via tax lots** — *As an Organizer, I want realized gains classified.*
- [ ] Tax lots track cost basis; holding period → STCG/LTCG with equity/debt rules; an FY realized-gains summary and an unrealized view exist; disclaimer shown.

### Epic 2.4 — Frictionless capture
**2.4.1 Natural-language quick add** — *As an Organizer, I want to type shorthand and confirm.*
- [ ] "1L gold 6.3g at ICICI Aug 3" parses into editable chips (type/amount/qty/institution/date); nothing saves until I confirm; unparsed parts are left blank.

**2.4.2 Document OCR** — *As an Organizer, I want to snap a certificate and prefill fields.*
- [ ] OCR extracts candidate fields; low-confidence ones are highlighted; the original is stored as an encrypted proof; I confirm before save.

**2.4.3 Bulk import from spreadsheet** — *As an Organizer migrating, I want to import my sheet.*
- [ ] Upload CSV/XLSX; map columns; preview + **dedupe** (folio/policy/receipt); **partial import with a clear error report** (never silent all-or-nothing); imported records seed the dashboard.

**2.4.4 Templates / duplicate / rollover** — *As an Organizer, I want to avoid re-entry.*
- [ ] Save a template; duplicate any record; a done maturity reminder can spawn a pre-filled renewal.

### Epic 2.5 — Reports & completeness
**2.5.1 Reports + completeness score** — *As an Organizer, I want allocation/concentration/liquidity views and to know what's missing.*
- [ ] Reports by class/member/institution/goal; liquidity buckets; export PDF/CSV/XLSX.
- [ ] A **completeness score** (nominee + linkage + proof + fresh valuation) with one-tap "fix" links; all reports respect visibility and carry the "informational, not advice" note.

### ✅ Phase 2 Definition of Done
- [ ] Goals, returns, tax meters + capital gains, quick-add, OCR, import, templates, reports, and completeness all work and respect visibility.
- [ ] Import validated end-to-end on your real spreadsheet.
- [ ] **Open/closed testing** expanded on both stores.
- [ ] Universal DoD met for every story.

---

## Phase 3 — Continuity & trust
**Goal:** the "For My Family" promise, made real and safe — plus the trust proofs that let people commit their whole financial life. **Exit:** public launch on both stores.

### Epic 3.1 — Estate & legal
**3.1.1 Will / executor / POA + key docs** — *As an owner, I want to record my will, executor, POA, and personal documents.*
- [ ] Per member: has-will, will location, executor, POA holder; PAN/masked-Aadhaar/deeds stored in the vault.
- [ ] A **nominee ↔ will mismatch** is detected and surfaced as an "attention" item.

### Epic 3.2 — Contacts / advisors
**3.2.1 Advisor directory** — *As a Family CFO, I want CA/agent/lawyer/banker contacts linked to the records they handle.*
- [ ] Add a contact (role, phone, email, firm); link it to records; it appears in detail and in continuity.

### Epic 3.3 — Transmission Assistant (heir mode)
**3.3.1 "How your family claims this"** — *As an Heir, I want step-by-step claim guidance per asset.*
- [ ] For each continuity-included asset, show the claim/transmission steps (LIC, EPF, bank FD, demat via NSDL/CDSL, property mutation) with the linked contact and document.
- [ ] A printable/exportable **family handbook** is produced.

### Epic 3.4 — Scoped sharing
**3.4.1 Time-boxed guest links** — *As an Organizer, I want to share a slice (e.g., the tax pack) with my CA for a limited time.*
- [ ] Create a read-only link with a defined scope + expiry; revocable; sensitive documents can be excluded; access is logged; expired/revoked links are dead immediately and leak nothing outside their scope.

### Epic 3.5 — Emergency access
**3.5.1 Time-delayed, vetoable continuity access** — *As an owner, I want a trusted contact to reach my continuity summary if I'm unreachable.*
- [ ] Set a trusted contact + inactivity window; a request starts a visible countdown with notifications to both parties.
- [ ] The owner can **veto** any time during the window; on unlock, the contact gets **read-only** access to continuity-included records (including private ones marked for continuity); every step is audited.

### Epic 3.6 — End-to-end encryption (optional)
**3.6.1 Zero-knowledge mode** — *As a Privacy Hawk, I want sensitive fields/documents encrypted so even the server can't read them.*
- [ ] Opt-in; sensitive fields/docs encrypted with a passphrase-derived key; server stores only ciphertext.
- [ ] Clear warnings shown (no server-side OCR/search on those fields; no password-reset recovery of that data).

### Epic 3.7 — Trust proofs
**3.7.1 Whitepaper, audit, data rights** — *As any user, I want evidence the app is trustworthy.*
- [ ] A published **security whitepaper**; a completed third-party penetration test with findings resolved.
- [ ] In-app **account deletion** and **data export** available and tested.

### ✅ Phase 3 Definition of Done
- [ ] Estate/legal, contacts, Transmission Assistant, scoped sharing, emergency access, optional E2E, and trust proofs all shipped and visibility-safe.
- [ ] **Google Play** production access granted (12-testers/14-day gate cleared); **App Store** review passed (demo login provided to reviewers).
- [ ] Public **production launch** achieved on both stores (staged rollout → 100%).
- [ ] Universal DoD met for every story.

---

## Phase 4 — Scale & rails
**Goal:** remove the last of the manual work and reach the whole of India. **Exit:** integrations live, multi-language, advisor collaboration.

### Epic 4.1 — DigiLocker
**4.1.1 Import official documents** — *As an Organizer, I want to pull PAN/insurance/property docs from DigiLocker.*
- [ ] Consent-based DigiLocker link; imported docs land in the vault, mapped to records; I confirm before saving.

### Epic 4.2 — Account Aggregator
**4.2.1 Consent-based data import (no passwords)** — *As an Organizer, I want optional read-only import of bank/deposit/MF data.*
- [ ] AA consent flow; imported data creates/updates records with `source = AA`; **no credentials are ever stored**; I review before committing.

### Epic 4.3 — Multi-currency
**4.3.1 Foreign assets** — *As an NRI/family with foreign holdings, I want native currency, converted for display.*
- [ ] Per-record currency; `exchange_rates` power display conversion; the household total shows in base currency; native amounts are preserved.

### Epic 4.4 — Bharat reach
**4.4.1 WhatsApp capture & reminders** — *As an Elder/on-the-go user, I want to add entries and get reminders on WhatsApp.*
- [ ] A WhatsApp channel captures a quick entry (parsed → confirm) and delivers reminders; opt-in; respects authentication.

**4.4.2 Regional languages (Telugu, Hindi)** — *As an Indian user, I want the app in my language.*
- [ ] Full localization (UI, numbers, dates) for Telugu + Hindi; switchable; the framework is RTL-ready for future languages.

### Epic 4.5 — Advisor collaboration
**4.5.1 Ongoing scoped advisor access** — *As a Family CFO, I want my CA to have continuing, limited access.*
- [ ] Invite an advisor with a scoped role; they see only permitted slices; access is logged and revocable.

### ✅ Phase 4 Definition of Done
- [ ] DigiLocker + Account Aggregator imports, multi-currency, WhatsApp + Telugu/Hindi, and advisor collaboration all shipped.
- [ ] Performance/scale targets met (load, cost, crash-free rate); no privacy regressions.
- [ ] Universal DoD met for every story.

---

## Release mapping (quick reference)
| Phase | Ships | Store stage |
|---|---|---|
| 0 | OTP, household, capture (+custom), list/detail, basic dashboard, privacy foundation | Internal testing |
| 1 | Full taxonomy, accounts+linkage, nominees, liabilities+true net worth, reminders+cash-flow, vault, search | Closed testing (start 12-tester gate) |
| 2 | Goals, returns, tax, quick-add, OCR, import, reports+completeness | Open/closed testing |
| 3 | Estate, transmission, sharing, emergency access, E2E, whitepaper+audit | **Production launch** |
| 4 | DigiLocker, AA, multi-currency, WhatsApp+regional, advisor collab | Post-launch releases |

[‹ Index](README.md) · [‹ Prev: Build & Launch Plan](09-build-and-launch-plan.md) · [Next › Getting Started Runbook](11-getting-started-runbook.md)
