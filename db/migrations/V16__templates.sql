-- =============================================================================
-- V16 · Capture templates.
-- Refs: docs/01 §5, docs/07 §2 Phase 2, docs/10 Epic 2.4.4
--
-- A template is not a record. It holds no money and belongs to no member — it is
-- a saved shape of a capture form, so that the fourth FD of the year is typed
-- once rather than four times.
--
-- It still needs its own privacy, because of where templates come from: someone
-- saving "LIC term plan" as a template puts the policy's own details into it.
-- So a template is private to whoever made it until they deliberately share it
-- with the household — the same act as granting visibility on a record, and for
-- the same reason.
-- =============================================================================

create table investment_templates (
  id             uuid primary key default gen_random_uuid(),
  household_id   uuid not null references households(id) on delete cascade,
  name           text not null,
  type_id        uuid not null references investment_types(id),
  institution_id uuid references institutions(id) on delete set null,
  account_id     uuid references accounts(id) on delete set null,
  title          text,
  invested_amount numeric(18,4),
  currency       text not null default 'INR',
  quantity       numeric(18,4),
  unit           text,
  storage_location text,
  attributes     jsonb not null default '{}',
  notes          text,
  -- Two levels only. A template has no owner to be "scoped" to.
  visibility     text not null default 'private'
                   check (visibility in ('private','household')),
  -- The visibility of the record this was saved from, when it was saved from
  -- one. A template made out of someone's Private holding carries that
  -- holding's details, so it can never be shared more widely than the record
  -- it came from — the check below says so in the database rather than only in
  -- the service, because that is where the rest of the privacy model lives.
  source_visibility text
                   check (source_visibility in ('private','household','scoped')),
  use_count      int not null default 0,
  deleted_at     timestamptz,
  version        int not null default 1,
  created_by     uuid not null references users(id),
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now(),
  constraint template_name_is_not_blank check (length(btrim(name)) > 0),
  constraint template_cannot_outgrow_its_source
    check (visibility = 'private'
           or source_visibility is null
           or source_visibility = 'household')
);
create index on investment_templates (household_id) where deleted_at is null;
create unique index investment_templates_unique_name
  on investment_templates (household_id, created_by, lower(btrim(name)))
  where deleted_at is null;
create trigger investment_templates_touch before insert or update on investment_templates
  for each row execute function app.touch_row();

alter table investment_templates enable row level security;

create policy investment_templates_read on investment_templates for select
  using (app.is_household_member(household_id)
         and (created_by = app.current_user_id() or visibility = 'household'));

-- Only your own. A shared template is readable by the household but stays the
-- creator's to change — otherwise "shared" would quietly mean "editable by
-- anyone", and a template someone relies on could be rewritten under them.
create policy investment_templates_insert on investment_templates for insert
  with check (app.can_write_household(household_id) and created_by = app.current_user_id());

create policy investment_templates_update on investment_templates for update
  using (app.can_write_household(household_id) and created_by = app.current_user_id())
  with check (app.can_write_household(household_id) and created_by = app.current_user_id());

create policy investment_templates_delete on investment_templates for delete
  using (app.can_write_household(household_id) and created_by = app.current_user_id());

-- -----------------------------------------------------------------------------
-- Rollover leaves a trail. When a matured FD is renewed, the new record points
-- back at the old one so the history survives the renewal (docs/07 §1
-- "rollover without losing history") — and the old one can say what became of
-- it rather than simply going quiet.
-- -----------------------------------------------------------------------------
alter table investments add column rolled_from_id uuid references investments(id);
create index on investments (rolled_from_id) where rolled_from_id is not null;

-- -----------------------------------------------------------------------------
-- Using a shared template is not editing it. The update policy deliberately
-- keeps a template in its creator's hands, which would otherwise mean the use
-- count only ever counted its creator's own uses. This counts everyone who may
-- read it, and touches nothing else.
-- -----------------------------------------------------------------------------
create or replace function app.record_template_use(p_template_id uuid)
  returns int language plpgsql security definer
  set search_path = public, app, pg_temp as $$
declare
  v_household uuid;
  v_visibility text;
  v_created_by uuid;
  v_count int;
begin
  select household_id, visibility, created_by
    into v_household, v_visibility, v_created_by
    from investment_templates
   where id = p_template_id and deleted_at is null;

  if v_household is null then return null; end if;
  if not app.is_household_member(v_household) then return null; end if;
  if v_visibility <> 'household' and v_created_by <> app.current_user_id() then return null; end if;

  update investment_templates set use_count = use_count + 1 where id = p_template_id
    returning use_count into v_count;
  return v_count;
end $$;
