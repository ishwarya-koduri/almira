-- =============================================================================
-- V29 · "Still true?" — records nobody has confirmed in a while come back.
-- Refs: docs/21-still-true.md
--
-- A registry nobody updates becomes wrong, then abandoned (docs/08). The fix is
-- not a bigger form, it is asking one small question at the right moment: is
-- this policy still in force, is this loan still running, is this bank account
-- still open, is this still the will that counts.
--
-- Three pieces:
--
--   record_confirmations        one row per record that someone has confirmed
--                               or snoozed. Generic across record types, the
--                               same way sealed_values and visibility grants are.
--   record_confirmation_nudges  per person, per record: when we last told them.
--                               This is what stops the sweep nagging.
--   still_true_records / still_true_items
--                               when each record falls due, computed in one
--                               place so the list endpoint and the sweep can
--                               never disagree about it.
--
-- The periods live in app.still_true_period_months, and docs/21 §2 argues each
-- one. StillTrueDocTest reads the table in docs/21 back out of this function.
-- =============================================================================

create table record_confirmations (
  record_type   text not null
                  check (record_type in ('investment','liability','account','estate_document')),
  record_id     uuid not null,
  household_id  uuid not null references households(id) on delete cascade,
  -- "Yes, this is still true." Null when the row exists only for a snooze.
  confirmed_at  timestamptz,
  confirmed_by  uuid references users(id) on delete set null,
  -- "Ask me later." Not a confirmation: it moves the question, not the anchor.
  snoozed_until date,
  snoozed_by    uuid references users(id) on delete set null,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  primary key (record_type, record_id)
);
create index on record_confirmations (household_id);

alter table record_confirmations enable row level security;

-- The row follows the record. linked_record_visible is invoker-rights, so each
-- check runs under the caller's own policies: a confirmation on a record you
-- cannot see is a row you cannot see, and cannot write.
create policy record_confirmations_read on record_confirmations for select
  using (app.linked_record_visible(record_type, record_id));

create policy record_confirmations_insert on record_confirmations for insert
  with check (app.can_write_household(household_id)
              and app.linked_record_visible(record_type, record_id));

create policy record_confirmations_update on record_confirmations for update
  using (app.can_write_household(household_id)
         and app.linked_record_visible(record_type, record_id))
  with check (app.can_write_household(household_id)
              and app.linked_record_visible(record_type, record_id));

-- Per person, because two co-owners are two people: nudging one must not count
-- as having nudged the other.
create table record_confirmation_nudges (
  user_id      uuid not null references users(id) on delete cascade,
  record_type  text not null
                 check (record_type in ('investment','liability','account','estate_document')),
  record_id    uuid not null,
  household_id uuid not null references households(id) on delete cascade,
  nudged_at    timestamptz not null default now(),
  primary key (user_id, record_type, record_id)
);

alter table record_confirmation_nudges enable row level security;

-- Readable by the person it is about. No write policy at all: only the sweep
-- writes here, on the owner connection, and a request has no business doing so.
create policy record_confirmation_nudges_read on record_confirmation_nudges for select
  using (user_id = app.current_user_id());

-- An attribute date typed into a form can be anything. A cast that throws would
-- take the whole list down for one bad value, so an unreadable date is no date.
create or replace function app.try_date(p_value text)
  returns date language plpgsql stable
  set search_path = pg_catalog, pg_temp as $$
begin
  if p_value is null or p_value !~ '^\d{4}-\d{2}-\d{2}$' then
    return null;
  end if;
  return p_value::date;
exception when others then
  return null;
end $$;

-- How long a confirmation holds, in months. docs/21 §2 argues each line; where
-- there was no argument the answer is the default, 12.
create or replace function app.still_true_period_months(p_record_type text, p_subtype text)
  returns int language sql immutable
  set search_path = pg_catalog, pg_temp as $$
  select case
    -- Cash is a balance, and a balance is wrong within a season.
    when p_record_type = 'investment' and p_subtype = 'cash' then 3
    -- A card's outstanding changes every statement.
    when p_record_type = 'liability' and p_subtype = 'credit_card' then 3
    -- A loan's outstanding drops with every EMI, and nothing here amortises it.
    when p_record_type = 'liability' then 6
    -- A will or a power of attorney changes with a life event, not a calendar.
    when p_record_type = 'estate_document' then 24
    else 12
  end
$$;

-- Every record that can be asked about, with when it falls due. Security
-- invoker: through a request it shows exactly the records the caller can see.
create view still_true_records with (security_invoker = true) as
with base as (
  select 'investment'::text as record_type, i.id as record_id, i.household_id,
         i.title, c.code as subtype,
         -- A value refresh is a confirmation: addValuation already stamps
         -- last_verified_at, because it means someone looked at a statement.
         greatest(i.created_at, i.last_verified_at) as refreshed_at,
         i.maturity_date as key_date_a,
         app.try_date(i.attributes ->> 'renewal_date') as key_date_b
  from investments i
  join investment_types t on t.id = i.type_id
  join asset_categories c on c.id = t.category_id
  where i.deleted_at is null
    -- A matured FD still sitting there is exactly the one to ask about.
    and i.status in ('active', 'matured')
  union all
  select 'liability', l.id, l.household_id, l.title, l.kind,
         -- Recording a balance is the liability's value refresh.
         greatest(l.created_at,
                  (select max(b.created_at) from liability_balances b
                   where b.liability_id = l.id)),
         l.end_date, null::date
  from liabilities l
  where l.deleted_at is null and l.status = 'active'
  union all
  select 'account', a.id, a.household_id, a.label, a.account_kind,
         a.created_at, null::date, null::date
  from accounts a
  where a.deleted_at is null
  union all
  select 'estate_document', e.id, e.household_id, e.title, e.kind,
         e.created_at, null::date, null::date
  from estate_documents e
  where e.deleted_at is null and e.status in ('draft', 'executed')
),
anchored as (
  select b.record_type, b.record_id, b.household_id, b.title, b.subtype,
         b.key_date_a, b.key_date_b, h.time_zone,
         app.still_true_period_months(b.record_type, b.subtype) as period_months,
         greatest(b.refreshed_at, rc.confirmed_at) as last_confirmed_at,
         (greatest(b.refreshed_at, rc.confirmed_at) at time zone h.time_zone)::date as anchor_date,
         rc.snoozed_until,
         (now() at time zone h.time_zone)::date as today
  from base b
  join households h on h.id = b.household_id and h.deleted_at is null
  left join record_confirmations rc
    on rc.record_type = b.record_type and rc.record_id = b.record_id
   and rc.household_id = b.household_id
),
dated as (
  select a.*,
         -- A maturity or renewal that has come since the last confirmation is
         -- the moment the answer may have changed. One from before it was
         -- already answered.
         least(case when a.key_date_a > a.anchor_date then a.key_date_a end,
               case when a.key_date_b > a.anchor_date then a.key_date_b end) as key_date,
         (a.anchor_date + make_interval(months => a.period_months))::date as period_due_on
  from anchored a
)
select d.record_type, d.record_id, d.household_id, d.title, d.subtype, d.time_zone,
       d.period_months, d.last_confirmed_at, d.key_date,
       -- A week after the date: long enough for the proceeds or the renewal
       -- receipt to have arrived, soon enough to still remember.
       least(d.period_due_on, d.key_date + 7) as due_on,
       d.snoozed_until,
       greatest(least(d.period_due_on, d.key_date + 7), d.snoozed_until) as effective_due_on,
       greatest(least(d.period_due_on, d.key_date + 7), d.snoozed_until) <= d.today as is_due,
       d.today
from dated d;

-- The records a person is asked about: the ones they own, hold, owe or whose
-- will it is, in a household where they can write. The helpers are the very
-- functions the read policies use for ownership, and ownership always grants
-- sight, so nobody is asked about a record they cannot see.
--
-- The helpers read app.user_id. Through a request that is the caller. In the
-- sweep, which runs on the owner connection, it is set per person, per
-- transaction — without it this view is empty, which is the point.
create view still_true_items with (security_invoker = true) as
select r.*,
       n.nudged_at,
       r.is_due and (
         n.nudged_at is null
         -- Confirmed or snoozed since the last nudge: this is a new question.
         or (n.nudged_at at time zone r.time_zone)::date < r.effective_due_on
         -- Ignored: ask again, but not more than once a month.
         or n.nudged_at < now() - interval '30 days'
       ) as nudge_eligible
from still_true_records r
left join record_confirmation_nudges n
  on n.user_id = app.current_user_id()
 and n.record_type = r.record_type and n.record_id = r.record_id
where app.can_write_household(r.household_id)
  and case r.record_type
        when 'investment'      then app.owns_investment(r.record_id)
        when 'liability'       then app.owes_liability(r.record_id)
        when 'account'         then app.holds_account(r.record_id)
        when 'estate_document' then app.owns_estate_document(r.record_id)
        else false
      end;
