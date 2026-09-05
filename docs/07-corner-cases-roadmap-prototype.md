[‹ Index](README.md) · [‹ Prev: Backend, API & Stack](06-backend-api-and-stack.md) · [Next › Differentiation & Standout](08-differentiation-and-standout.md)

# 07 · Corner Cases, Roadmap & Prototype

## 1. Corner cases & edge scenarios
**Ownership & people** — joint holdings without double-counting; an owner who is also a nominee; joint **accounts** with multiple holders; minor-owned assets with a guardian that transitions at 18; a managed member later creating their own login (merge, don't duplicate); a member leaving with clean export; one real person represented once across roles.

**Privacy/visibility** — a private record must contribute nothing (not even amount) to another viewer's totals; a joint record can't be hidden from a co-owner; changing a record from household→private must retroactively hide it everywhere (reports, search, exports, cached totals bust); an admin must never see private records except via emergency unlock; scoped grants revoked mid-session end access immediately.

**Universal / custom** — a custom *money* field joins value math exactly once; other custom fields stay informational; definitions versioned so old records still render; a custom type promoted to the taxonomy with zero migration.

**Liabilities** — loan against an asset (encumbrance + net equity); loan with no linked asset; closed/foreclosed loans to history; part-prepayment; revolving card balances.

**Valuation & money** — unknown cost basis (marked "at cost/unknown"); units-not-rupees (grams/units/shares); no live price → manual snapshots, never fabricated; multi-currency stored native, converted only for display; partial redemption/early FD break; rollover without losing history; **corporate actions** (split/bonus/merger/buyback) adjusting quantity & basis; rounding to paise / 0.001 g; never floats.

**Returns & tax** — XIRR with irregular flows and open positions; realized vs unrealized separation; tax-lot method (FIFO vs average) affecting gains; STCG/LTCG threshold and holding-period edges; TDS captured; FY boundary mid-transaction.

**Goals** — investment mapped to multiple goals (allocation ≤ 100%); unallocated investments; goals with no funding; achieved/abandoned goals archived.

**Estate** — **nominee ≠ heir** mismatch flagged; multiple nominees with % splits; will absent; executor unreachable; emergency access requested while owner is merely travelling (veto window + notifications).

**Reminders & time** — time zones; recurring premiums/SIPs/EMIs with skipped/failed cycles; review/verify nudges for maturity-less assets; month-end/leap-day due dates (31st → Feb).

**Documents & data** — large scans, unsupported formats, low-confidence OCR (human review); statement **versioning**; expiring docs; orphan documents on delete; corrupt import rows → partial import with a clear error report, never silent all-or-nothing; duplicate detection on import/quick-add.

**Access & security** — lost-device session revoke + biometric lock; guest-link expiry/abuse; concurrent edits (optimistic concurrency); offline capture then sync conflict (idempotency + merge prompt).

## 2. Roadmap & phases
- **Phase 0 — Foundations (prototype):** auth + household + members; investments core with 5 types **+ universal/custom**; **per-record visibility**; manual capture; list + detail; dashboard. Replaces the spreadsheet.
- **Phase 1 — Complete balance sheet:** full taxonomy; institutions & accounts (co-holders) + linkage; ownership/joint + nominees; **liabilities + true net worth**; valuations + net-worth trend; reminders (incl. EMI) + cash-flow calendar; document vault; global search; **RLS privacy fully enforced**.
- **Phase 2 — Insight & delight:** goals; returns (XIRR/CAGR); tax layer; natural-language quick-add; OCR; spreadsheet import; templates/duplicate/rollover; reports; completeness/verification surface.
- **Phase 3 — Continuity & trust:** estate/legal (will/executor/POA); contacts/advisors; **Transmission Assistant**; scoped guest sharing; emergency access; E2E mode; **security whitepaper + audit**.
- **Phase 4 — Scale & rails:** mobile parity; **DigiLocker** + **Account-Aggregator** import; multi-currency; **WhatsApp capture + regional languages**; advisor/CA collaboration.

## 3. Prototype blueprint — minimal DDL (core + privacy + new tables)
```sql
create extension if not exists pgcrypto;
create extension if not exists citext;

create table households (
  id uuid primary key default gen_random_uuid(), name text not null,
  base_currency text default 'INR', default_visibility text default 'private',
  created_by uuid, created_at timestamptz default now(), updated_at timestamptz default now());

create table users (
  id uuid primary key default gen_random_uuid(), email citext unique not null,
  full_name text, password_hash text, auth_provider text default 'email',
  mfa_enabled boolean default false, created_at timestamptz default now());

create table members (
  id uuid primary key default gen_random_uuid(),
  household_id uuid not null references households(id) on delete cascade,
  user_id uuid references users(id), display_name text not null,
  relationship text, date_of_birth date, deleted_at timestamptz,
  created_at timestamptz default now());
create index on members(household_id) where deleted_at is null;

create table household_memberships (
  id uuid primary key default gen_random_uuid(),
  household_id uuid not null references households(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  role text not null check (role in ('owner','admin','editor','viewer','restricted')),
  status text default 'active', unique(household_id, user_id));

create table investment_types (
  id uuid primary key default gen_random_uuid(),
  category_code text not null, code text not null, label text not null,
  schema_key text not null, is_custom boolean default false,
  household_id uuid references households(id));

create table investments (
  id uuid primary key default gen_random_uuid(),
  household_id uuid not null references households(id) on delete cascade,
  type_id uuid not null references investment_types(id),
  account_id uuid, institution_id uuid, title text not null,
  status text default 'active' check (status in ('active','matured','closed','draft','archived')),
  invested_amount numeric(18,4), currency text default 'INR',
  quantity numeric(18,4), unit text, cost_basis_method text default 'fifo',
  start_date date, maturity_date date, storage_location text,
  attributes jsonb not null default '{}', notes text,
  is_in_continuity boolean default true,
  visibility text not null default 'private' check (visibility in ('private','household','scoped')),
  last_verified_at timestamptz, deleted_at timestamptz,
  created_by uuid references users(id), version int default 1,
  created_at timestamptz default now(), updated_at timestamptz default now());
create index on investments(household_id, status);
create index on investments(household_id, visibility);
create index on investments using gin (attributes);

create table investment_ownerships (
  id uuid primary key default gen_random_uuid(),
  investment_id uuid not null references investments(id) on delete cascade,
  member_id uuid not null references members(id),
  holder_type text default 'primary',
  share_pct numeric(5,2) not null check (share_pct between 0 and 100),
  unique(investment_id, member_id));

create table record_visibility_grants (
  id uuid primary key default gen_random_uuid(),
  household_id uuid not null references households(id) on delete cascade,
  record_type text not null, record_id uuid not null,
  member_id uuid not null references members(id),
  created_at timestamptz default now(),
  unique(record_type, record_id, member_id));
create index on record_visibility_grants(record_type, record_id);

create table liabilities (
  id uuid primary key default gen_random_uuid(),
  household_id uuid not null references households(id) on delete cascade,
  institution_id uuid, kind text not null, title text not null,
  principal numeric(18,4), outstanding numeric(18,4), interest_rate numeric(6,3),
  emi_amount numeric(18,4), emi_day int, start_date date, end_date date,
  status text default 'active',
  visibility text default 'private', attributes jsonb default '{}',
  deleted_at timestamptz, created_at timestamptz default now());

-- current value
create view investment_current as
select distinct on (investment_id) investment_id, value, quantity, as_of_date
from valuations order by investment_id, as_of_date desc;

-- RLS: visibility-aware read policy (the heart of the privacy model)
alter table investments enable row level security;
create policy inv_read on investments for select using (
  household_id in (select household_id from household_memberships
                   where user_id = current_setting('app.user_id')::uuid and status='active')
  and (
     exists (select 1 from investment_ownerships o join members m on m.id=o.member_id
             where o.investment_id = investments.id and m.user_id = current_setting('app.user_id')::uuid)
     or visibility = 'household'
     or (visibility = 'scoped' and exists (
           select 1 from record_visibility_grants g join members m on m.id=g.member_id
           where g.record_type='investment' and g.record_id=investments.id
             and m.user_id = current_setting('app.user_id')::uuid))
     -- OR emergency-access-unlocked AND is_in_continuity  (added by the continuity module)
  ));
```

## 4. Repo layout (monorepo)
```
almira/
├─ apps/ { web (Next.js), mobile (Expo), worker }
├─ packages/ { ui (design tokens + components from Doc 02), types (zod incl. attribute+custom-field schemas),
│              api-client, core (valuation, aggregation, XIRR, tax, visibility rules, share-sum) }
├─ db/ { migrations, seed (categories, types, institutions), policies (RLS) }
└─ infra/ { IaC, CI/CD }
```

## 5. First vertical slice
1) Supabase + DDL + RLS + seed types (incl. universal). 2) Auth + household + members + **default visibility**. 3) Type-aware capture for Gold/FD/MF/Stocks/Insurance **+ custom fields + visibility toggle**. 4) List + detail + dashboard on `investment_current` × ownerships **− liabilities**, filtered by visibility. 5) Import your spreadsheet to seed real data. 6) Add reminders + net-worth trend. Ship to yourself, then family.

[‹ Index](README.md) · [‹ Prev: Backend, API & Stack](06-backend-api-and-stack.md) · [Next › Differentiation & Standout](08-differentiation-and-standout.md)
