[‹ Index](README.md) · Prev — · [Next › UX & Design System](02-ux-and-design-system.md)

# 01 · Product & Scope

## 1. The problem
People don't forget *that* they invest — they forget the specifics that make an investment usable later: which bank funds which SIP, where the certificate lives, who the nominee is, when an FD matures or a premium is due, which broker holds the unlisted shares, and — crucially — *whether the family even knows it exists.* Aggregators optimize the liquid, syncable slice (listed stocks, MFs) and are blind to the long tail where forgetting is expensive: physical gold, jewelry, unlisted shares, chit funds, insurance, PPF/EPF/NPS, post-office schemes, property, FDs across banks — and the **loans** against them.

## 2. Philosophy (six principles)
1. **Data, not money.** Never moves money, holds funds, or stores banking passwords. It's a *record* — which is why it can track things transactional apps can't.
2. **Capture-first.** Getting data in is the hardest problem in the category; every decision serves fast, pleasant capture.
3. **Manual-first, integration-optional.** Works for *everything* on day one; integrations are convenience, not foundation.
4. **Record anything.** If it has a name, a value, and maybe a date, it belongs here — including types we never predefined (§5).
5. **Continuity by design.** Assumes the owner won't always be around to explain things.
6. **Private by default.** Encryption, masking, minimal collection, optional zero-knowledge — and privacy *within* the family (see [Doc 05](05-security-and-privacy.md)).

*Framing:* the headline number is **true net worth = assets − liabilities** ([§6](#6-liabilities--true-net-worth)). Anything showing only assets is labeled "Total Assets." Allocation/insight views carry an **"informational, not financial advice"** note.

## 3. Personas
- **Organizer (primary — Ishwarya):** financially active, design-literate; wants one beautiful source of truth and hates re-entering data.
- **Family CFO:** manages spouse, minor children (SSY), ageing parent; needs multi-person tracking with clean separation and easy aggregation.
- **Heir / Nominee:** may open it only when needed; must understand "what exists, where, and what to do next" with zero training.
- **Elder:** paper-heavy holdings, data entered *for* them; needs a large-type **Simple Mode**.
- **Privacy Hawk:** uses it only if data is encrypted, private within the family, and exportable.

## 4. Asset taxonomy
An **asset** = category + type + common fields + type-specific fields. The **Universal type** (§5) means this list is a starting point, never a ceiling. Liabilities (§6) are a parallel record.

| Category | Example types | Notable fields |
|---|---|---|
| Gold & Metals | physical, jewelry, digital gold, ETF, SGB, silver | weight(g), purity, making charges, storage, SGB series/maturity |
| Bank Deposits | FD, RD, tax-saver FD | linked account, principal, rate, tenure, maturity, payout, receipt no. |
| Mutual Funds | equity/debt/index/ELSS, SIP/lumpsum | AMC, scheme, folio, **linked account**, SIP amount/date, plan |
| Equity | listed, unlisted, ESOP/RSU | broker, demat, ISIN, qty, avg cost, certificate location, vesting |
| IPO | application, allotment, rights | applied/allotted qty, ref, listing date |
| Bonds | govt, corporate, NCD, T-bill | issuer, coupon, face value, maturity, ISIN |
| Retirement & Small Savings | PPF, EPF, NPS, NSC, KVP, SCSS, SSY | account/PRAN, cadence, maturity, minor beneficiary |
| Insurance | term, endowment, ULIP, health, annuity | policy no., sum assured, premium, **due date**, maturity, **nominee**, agent |
| Real Estate | land, flat, commercial, REIT | address, ownership share, valuation, deed location, **loan linkage**, rent |
| Alternatives | chit, P2P, crypto, art, foreign, business equity | operator/exchange, custody, jurisdiction, currency |
| Cash & Misc | savings buffer, cash, loans given | institution, purpose, return date |
| **Universal** | *anything else* | user-defined fields |

Three cross-cutting concepts on **every** record: **Linkage** (which account/demat/folio), **Proof** (document location + scan), **Continuity** (nominees, joint holders, inclusion in family summary).

## 5. Record Anything — the universal engine
The taxonomy is a layer of *smart templates* over a **fully generic asset record**. Anyone with an asset that doesn't fit — a stake in a friend's startup, a peer loan, farmland, an obscure bond, an NFT, a vintage watch — can still record it with structure.
- **Universal type** accepts common fields (title, value, quantity/unit, dates, owner, nominee, institution, document, notes) — enough to make anything first-class and countable.
- **Custom fields** on any record: typed (text, number, money, date, percent, bool, select) with label/unit, stored in the record's `attributes` JSONB, auto-rendered.
- **Custom types & categories:** save a reusable type ("Angel Investment", "Farmland") with its own icon, color, and fields; behaves like any built-in.
- **Promotion path:** popular custom types can graduate into the official taxonomy with no migration.
- **Guardrail:** a custom *money* field joins value math exactly once; other custom fields stay informational. Definitions are versioned so old records still render.

This is a moat: every competitor is a closed list; here the answer to "can it track my ___?" is always **yes**.

## 6. Liabilities & true net worth
Debts are a first-class, parallel record: home/car/personal/education/gold loans, credit-card outstanding, loan-against-property/FD/insurance/securities, family loans, custom. Fields: lender, principal, outstanding, rate, EMI amount & date, tenure, and **the asset it's secured against**. A loan links to its asset → the asset shows "encumbered — ₹X outstanding," and reports show **gross assets, total liabilities, and net equity**. `True Net Worth = Σ(asset value × share) − Σ(liability outstanding × responsibility)`. Every EMI creates a recurring reminder and appears in the cash-flow calendar.

## 7. Goals & planning
A **goal** has name, target amount, target date, priority, optional member ("Aarav's UG — 2039"). Investments map to goals many-to-many with an allocation % (one SIP can fund two goals). Progress = mapped value ÷ target, with a neutral on-track/behind indicator. Templates: Retirement, Child Education, Home, Emergency Fund, Wedding, Car. The dashboard gains a "by goal" lens.

## 8. Returns & performance
From invested amount + transactions + valuations: **absolute return, CAGR** (lump sums), **XIRR** (SIPs/irregular flows), **realized vs unrealized** gains, and **cost basis via tax lots** (FIFO/average) feeding tax. Shown per investment/category/member/portfolio — only where enough data exists; otherwise "add a valuation to see returns," never a fabricated number. **No forecasts, no buy/sell signals.**

## 9. Tax layer (India) — *informational, not tax advice*
- **Deduction meters:** 80C (ELSS/PPF/EPF/LIC/NSC/SSY/home-loan principal/tax-saver FD), 80D (health), 80CCD(1B) (NPS +₹50k), 24(b) (home-loan interest) — auto-derived from tagged records.
- **Capital gains:** per tax lot → STCG/LTCG with holding-period + equity/debt rules; realized FY summary; unrealized view for planning.
- **TDS on FD interest**; yearly interest-income summary; optional **AIS/26AS** reconciliation later.
- **FY tax pack** export (deductions, interest income, realized gains) for the user or their CA (via scoped sharing).

## 10. Estate, legal, contacts & continuity
- **Nominee ≠ heir:** in India a nominee is a *custodian/receiver*, not the legal owner; assets pass to **heirs** per will/succession. Almira records both and **flags mismatches** ("policy nominee is your brother, but your will leaves it to your spouse").
- **Will, executor, POA** and key personal docs (PAN, masked Aadhaar, deeds, locker agreement) in the Vault.
- **Contacts/advisors** directory (CA, agent, lawyer, banker) linkable to the records they handle.
- **Transmission Assistant (heir mode):** step-by-step "how your family claims this" per asset (LIC, EPF, bank FD, demat transmission, property mutation) with the right contact and document.
- **Scoped external sharing:** time-boxed, read-only guest links (e.g., tax pack to a CA for 7 days). See [Doc 05](05-security-and-privacy.md).
- **Emergency access:** trusted contact + time-delayed inactivity unlock, vetoable by the owner, fully audited.

## 11. Information architecture — every tab & section
Primary nav stays small. **Mobile:** bottom bar (max 5) + central **➕ Capture**. **Web:** left rail. Primary: **Home · Investments · ➕ Capture · Family · More**. Global **Search** everywhere.

- **Home:** true net worth (assets − liabilities), amount-in-words, delta; **scope switcher** (Me/Household/Member) and **lens switcher** (class/member/institution/goal); allocation donut, member/institution bars, assets-vs-liabilities bar; **Upcoming** (maturities, premium/EMI dues, SIPs); **Attention needed** (missing nominee/linkage/proof, stale data, nominee↔will mismatch).
- **Investments:** searchable list; filter/group by type, member, institution, status, tag, goal; inline actions.
- **Capture:** the hero (see [Doc 03](03-screens-and-flows.md)).
- **Family:** roster (people, incl. no-login members), roles & permissions, invites, joint-holding & nominee managers, "who can see whom."
- **More hub:** Institutions & Accounts (with co-holders; orphan-linkage flags) · Liabilities · Goals · Reminders & **Cash-flow Calendar** · Vault (versioned, expiry-aware) · Reports & Insights (+ returns) · Tax · Continuity · Sharing · **Trash/Restore** · Settings (security, **Simple Mode**, currency/units, theme, export/delete).

[‹ Index](README.md) · Prev — · [Next › UX & Design System](02-ux-and-design-system.md)
