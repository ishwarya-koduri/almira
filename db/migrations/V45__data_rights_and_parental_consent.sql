-- =============================================================================
-- V45 · Data rights: consent records, notice versions, rights requests,
--       nominations, and a parent's consent for a child's records.
-- Refs: docs/23-privacy-notice.md §"Your data rights", docs/05 §6
--
-- The Digital Personal Data Protection Act 2023 and its Rules (2025) give a
-- person things to DO, not just things to read: give and withdraw consent purpose
-- by purpose (s.6, Rule 3), ask what is held and who it went to (s.11), ask for
-- a correction (s.12), complain to a named person who replies within a published
-- period (s.13, Rules 9 and 14), and nominate someone to do all of that for them
-- after death or incapacity (s.14). A child's data needs a parent's verifiable
-- consent (s.9, Rule 10). The core obligations commence on 13 May 2027.
--
-- Nothing in this file is a legal conclusion. It is the record the product keeps
-- so that those things can be done at all, and shown back to the person.
--
--   privacy_notice_versions     one row per published notice. Read-only here.
--   privacy_notice_acceptances  who accepted which version, when.
--   consent_events              append-only: given / withdrawn, per purpose.
--                               The current choice is the latest event; the
--                               history IS the table.
--   data_rights_requests        a correction or a grievance, with the date we
--                               reply by. Never more than 90 days out.
--   data_rights_nominees        s.14 nominations. Not the emergency contact.
--   parental_consents           a signed-in adult's consent, as a child's parent
--                               or lawful guardian, for that child's records.
--
-- Every table is readable only by the person it is about (or, for a child's
-- consent line, by exactly the people who can already see that child on the
-- roster), and no table lets the application rewrite history: withdrawals go
-- through two narrow functions rather than an UPDATE policy.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Notice versions
-- -----------------------------------------------------------------------------
create table privacy_notice_versions (
  -- A date, because a notice is "the one from 14 September", not "v3".
  version          text primary key check (version ~ '^\d{4}-\d{2}-\d{2}$'),
  published_on     date not null,
  summary          text not null,
  -- False until counsel has read it. The app shows the draft banner while false.
  legally_reviewed boolean not null default false,
  created_at       timestamptz not null default now()
);

alter table privacy_notice_versions enable row level security;

-- Anyone may read what the notice said. No write policy: a new version is a
-- migration, reviewed like one, never something a request can publish.
create policy privacy_notice_versions_read on privacy_notice_versions for select
  using (true);

insert into privacy_notice_versions (version, published_on, summary, legally_reviewed)
values ('2026-09-14', date '2026-09-14',
        'First notice with itemised consent, data rights, a grievance contact and parental consent. A draft; not legally reviewed.',
        false);

-- The notice in force: the latest one already published.
create or replace function app.current_privacy_notice_version()
  returns text language sql stable
  set search_path = public, pg_temp as $$
  select version from privacy_notice_versions
   where published_on <= current_date
   order by published_on desc, version desc
   limit 1
$$;

create table privacy_notice_acceptances (
  user_id        uuid not null references users(id) on delete cascade,
  notice_version text not null references privacy_notice_versions(version),
  accepted_at    timestamptz not null default now(),
  primary key (user_id, notice_version)
);

alter table privacy_notice_acceptances enable row level security;

create policy privacy_notice_acceptances_read on privacy_notice_acceptances for select
  using (user_id = app.current_user_id());

create policy privacy_notice_acceptances_insert on privacy_notice_acceptances for insert
  with check (user_id = app.current_user_id());

-- -----------------------------------------------------------------------------
-- Consent, purpose by purpose
-- -----------------------------------------------------------------------------
-- The purposes are the two things Almira actually does with a person's data that
-- they can choose about:
--
--   records   keeping what they record and showing it to the people they
--             choose. Without it there is no service; withdrawing it is closing
--             the account, and the app says so rather than pretending otherwise.
--   messages  reminders and "still true?" nudges sent by email or text. In-app
--             messages and security messages (sign-in codes, an emergency-access
--             request against you) are not under this consent: the first leave
--             nothing outside Almira, and suppressing the second could cost the
--             person their chance to say no.
create table consent_events (
  id             uuid primary key default gen_random_uuid(),
  user_id        uuid not null references users(id) on delete cascade,
  purpose        text not null check (purpose in ('records', 'messages')),
  action         text not null check (action in ('given', 'withdrawn')),
  notice_version text not null references privacy_notice_versions(version),
  created_at     timestamptz not null default now(),
  -- The order events happened in. Two in one transaction share created_at, and
  -- "given, then withdrawn" must never read as "withdrawn, then given".
  seq            bigint generated always as identity
);
create index on consent_events (user_id, purpose, seq desc);

alter table consent_events enable row level security;

-- Your own history, and only yours. No UPDATE or DELETE policy: a consent record
-- that the service writing it could rewrite would prove nothing.
create policy consent_events_read on consent_events for select
  using (user_id = app.current_user_id());

create policy consent_events_insert on consent_events for insert
  with check (user_id = app.current_user_id());

-- Whether a person has withdrawn consent to messages. Asked by the notifier on
-- behalf of a recipient who is usually not the caller, so it is a definer
-- function that answers one boolean and nothing else.
--
-- No event at all is "not withdrawn": everyone who signed up before this notice
-- was receiving reminders, and silently stopping them would be its own harm.
-- docs/known-issues.md ("Consent to messages is assumed until someone withdraws
-- it") records that this default needs a decision before 13 May 2027.
create or replace function app.messages_consent_withdrawn(p_user_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, pg_temp as $$
  select coalesce((
    select e.action = 'withdrawn' from consent_events e
     where e.user_id = p_user_id and e.purpose = 'messages'
     order by e.seq desc
     limit 1
  ), false)
$$;

-- -----------------------------------------------------------------------------
-- Rights requests: correction and grievance
-- -----------------------------------------------------------------------------
-- "See" is answered on the spot and "Erase" is the account-closure flow, so
-- neither needs a queue. What does is the request a person cannot do for
-- themselves — correcting something they cannot edit — and a complaint.
create table data_rights_requests (
  id           uuid primary key default gen_random_uuid(),
  user_id      uuid not null references users(id) on delete cascade,
  kind         text not null check (kind in ('correction', 'grievance')),
  details      text not null check (length(btrim(details)) between 1 and 2000),
  status       text not null default 'open'
                 check (status in ('open', 'answered', 'withdrawn')),
  -- Set from almira.privacy.grievance.response-days when the request is made,
  -- so a later change of that setting does not move a date already promised.
  respond_by   date not null,
  response     text,
  answered_at  timestamptz,
  withdrawn_at timestamptz,
  created_at   timestamptz not null default now(),
  -- Rule 14(3): the published period may not exceed ninety days. Held here as
  -- well as in configuration, so no setting can promise a later date.
  constraint respond_within_ninety_days
    check (respond_by >= (created_at at time zone 'Asia/Kolkata')::date
           and respond_by <= (created_at at time zone 'Asia/Kolkata')::date + 90)
);
create index on data_rights_requests (user_id, created_at desc);
create index on data_rights_requests (respond_by) where status = 'open';

alter table data_rights_requests enable row level security;

create policy data_rights_requests_read on data_rights_requests for select
  using (user_id = app.current_user_id());

-- A request arrives open and unanswered. Answers are written by whoever handles
-- grievances, on the owner connection; a request cannot answer itself.
create policy data_rights_requests_insert on data_rights_requests for insert
  with check (user_id = app.current_user_id()
              and status = 'open' and response is null
              and answered_at is null and withdrawn_at is null);

-- Withdrawing is the only change a person makes to their own request, and it
-- changes nothing else about it.
create or replace function app.withdraw_data_rights_request(p_id uuid)
  returns boolean language plpgsql security definer
  set search_path = public, app, pg_temp as $$
declare n int;
begin
  update data_rights_requests
     set status = 'withdrawn', withdrawn_at = now()
   where id = p_id and user_id = app.current_user_id() and status = 'open';
  get diagnostics n = row_count;
  return n = 1;
end $$;

-- -----------------------------------------------------------------------------
-- Nominees (s.14)
-- -----------------------------------------------------------------------------
-- Someone who may exercise this person's data rights if they die or cannot act.
-- Deliberately a separate thing from emergency_contacts: an emergency contact is
-- a household member who can ask to READ marked records after a wait; a nominee
-- need not use Almira at all, and acts on the person's rights — seeing what is
-- held, correcting it, erasing it — not on the family's records.
create table data_rights_nominees (
  id           uuid primary key default gen_random_uuid(),
  user_id      uuid not null references users(id) on delete cascade,
  full_name    text not null check (length(btrim(full_name)) between 1 and 120),
  relationship text check (relationship is null or length(relationship) <= 40),
  -- How the nominee is reached when they come forward: a phone number or an
  -- email address. Needed in the clear, because the whole point is that the
  -- person who could unseal anything is no longer able to.
  contact      text not null check (length(btrim(contact)) between 3 and 254),
  created_at   timestamptz not null default now(),
  revoked_at   timestamptz
);
create index on data_rights_nominees (user_id) where revoked_at is null;

alter table data_rights_nominees enable row level security;

create policy data_rights_nominees_read on data_rights_nominees for select
  using (user_id = app.current_user_id());

create policy data_rights_nominees_insert on data_rights_nominees for insert
  with check (user_id = app.current_user_id() and revoked_at is null);

-- Revoking is the one change: a nominee is never edited into somebody else.
create policy data_rights_nominees_update on data_rights_nominees for update
  using (user_id = app.current_user_id() and revoked_at is null)
  with check (user_id = app.current_user_id() and revoked_at is not null);

-- -----------------------------------------------------------------------------
-- A parent's consent for a child's records (s.9, Rule 10)
-- -----------------------------------------------------------------------------
create table parental_consents (
  id             uuid primary key default gen_random_uuid(),
  household_id   uuid not null references households(id) on delete cascade,
  member_id      uuid not null references members(id) on delete cascade,
  given_by       uuid not null references users(id) on delete cascade,
  capacity       text not null check (capacity in ('parent', 'lawful_guardian')),
  -- How the adult was checked, once, at the moment of consent. Only one way
  -- today: a fresh code to the adult's own sign-in address, with a declaration
  -- that they are an adult and the child's parent or guardian. A DigiLocker
  -- age token is the stronger check Rule 10 points at (docs/known-issues.md,
  -- "Parental consent checks the adult's sign-in, not their age").
  verification   text not null check (verification in ('step_up_code')),
  notice_version text not null references privacy_notice_versions(version),
  given_at       timestamptz not null default now(),
  withdrawn_at   timestamptz
);
-- One live consent per child. A second parent may give theirs after the first
-- is withdrawn; two at once would make "who consented" ambiguous.
create unique index parental_consents_one_live on parental_consents (member_id)
  where withdrawn_at is null;
create index on parental_consents (household_id);

alter table parental_consents enable row level security;

-- Exactly the people who can see the child on the roster: the subquery runs
-- under the caller's own members policy.
create policy parental_consents_read on parental_consents for select
  using (exists (select 1 from members m
                  where m.id = parental_consents.member_id
                    and m.household_id = parental_consents.household_id));

-- Given by the signed-in adult themselves, in a household they can write to,
-- for a child with no login of their own who is a minor by date of birth.
create policy parental_consents_insert on parental_consents for insert
  with check (given_by = app.current_user_id()
              and withdrawn_at is null
              and app.can_write_household(household_id)
              and exists (select 1 from members m
                           where m.id = parental_consents.member_id
                             and m.household_id = parental_consents.household_id
                             and m.deleted_at is null
                             and m.user_id is null
                             and app.is_minor(m.date_of_birth)));

-- Only the adult who gave it withdraws it.
create or replace function app.withdraw_parental_consent(p_id uuid)
  returns boolean language plpgsql security definer
  set search_path = public, app, pg_temp as $$
declare n int;
begin
  update parental_consents
     set withdrawn_at = now()
   where id = p_id and given_by = app.current_user_id() and withdrawn_at is null;
  get diagnostics n = row_count;
  return n = 1;
end $$;
