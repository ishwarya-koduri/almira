-- =============================================================================
-- V14 · Goals, and what funds them.
-- Refs: docs/01 §7, docs/04 §6, docs/10 Epic 2.1
--
-- A goal turns a pile of holdings into an answer to the question people actually
-- ask: "will there be enough for Aarav's degree in 2039?" One SIP can fund two
-- goals, so the mapping carries an allocation rather than being exclusive.
-- =============================================================================

create table goals (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references households(id) on delete cascade,
  name          text not null,
  target_amount numeric(18,4) not null check (target_amount > 0),
  target_date   date,
  priority      int not null default 2 check (priority between 1 and 3),
  -- Whose goal it is. NULL means the household's — a shared emergency fund
  -- belongs to nobody in particular.
  member_id     uuid references members(id) on delete set null,
  icon          text,
  notes         text,
  status        text not null default 'active'
                  check (status in ('active','achieved','archived')),
  visibility    text not null default 'private'
                  check (visibility in ('private','household','scoped')),
  deleted_at    timestamptz,
  version       int not null default 1,
  created_by    uuid references users(id),
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  constraint target_date_is_not_in_the_past_at_creation
    check (target_date is null or target_date > date '2000-01-01')
);
create index on goals (household_id, status) where deleted_at is null;
create index on goals (member_id);
create trigger goals_touch before insert or update on goals
  for each row execute function app.touch_row();

-- -----------------------------------------------------------------------------
-- What funds what. Many-to-many with a share, because one SIP genuinely can be
-- half a house deposit and half a retirement (docs/01 §7).
-- -----------------------------------------------------------------------------
create table investment_goals (
  id             uuid primary key default gen_random_uuid(),
  goal_id        uuid not null references goals(id) on delete cascade,
  investment_id  uuid not null references investments(id) on delete cascade,
  allocation_pct numeric(5,2) not null check (allocation_pct > 0 and allocation_pct <= 100),
  created_at     timestamptz not null default now(),
  unique (goal_id, investment_id)
);
create index on investment_goals (investment_id);

-- A holding cannot be more than fully allocated. Unlike ownership this need not
-- total 100 — an unallocated holding is perfectly normal, and is surfaced rather
-- than forced (docs/10 Epic 2.1).
create or replace function app.assert_allocation_within_100() returns trigger
  language plpgsql as $$
declare
  v_investment_id uuid := coalesce(new.investment_id, old.investment_id);
  v_total numeric(8,2);
begin
  if not exists (select 1 from investments where id = v_investment_id) then return null; end if;

  select coalesce(sum(allocation_pct), 0) into v_total
    from investment_goals where investment_id = v_investment_id;

  if v_total > 100 then
    raise exception
      'holding % is allocated %%% across goals, which is more than it is', v_investment_id, v_total
      using errcode = 'check_violation';
  end if;
  return null;
end $$;

create constraint trigger investment_goals_within_100
  after insert or update or delete on investment_goals
  deferrable initially deferred
  for each row execute function app.assert_allocation_within_100();

-- =============================================================================
-- Visibility. A goal names something private about a person — what they are
-- saving for, and how far short they are — so it carries the same three levels
-- as everything else.
-- =============================================================================

create or replace function app.owns_goal(p_goal_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select exists (
    select 1 from goals g
      left join members m on m.id = g.member_id
    where g.id = p_goal_id
      and ( (m.id is not null and m.user_id = app.current_user_id() and m.deleted_at is null)
         or g.created_by = app.current_user_id() )
  )
$$;

create or replace function app.can_modify_goal(p_goal_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select exists (
    select 1 from goals g
    where g.id = p_goal_id
      and g.deleted_at is null
      and app.can_write_household(g.household_id)
      and app.can_read_record(g.household_id, g.visibility, 'goal', g.id, app.owns_goal(g.id))
  )
$$;

alter table goals enable row level security;

create policy goals_read on goals for select
  using (app.can_read_record(household_id, visibility, 'goal', id, app.owns_goal(id)));

create policy goals_insert on goals for insert
  with check (app.can_write_household(household_id) and created_by = app.current_user_id());

create policy goals_update on goals for update
  using (app.can_write_household(household_id)
         and app.can_read_record(household_id, visibility, 'goal', id, app.owns_goal(id)))
  with check (app.can_write_household(household_id));

create policy goals_delete on goals for delete
  using (app.can_write_household(household_id)
         and app.can_read_record(household_id, visibility, 'goal', id, app.owns_goal(id)));

-- A mapping is visible only when BOTH ends are. Otherwise a shared holding
-- would reveal that a private goal exists, and roughly how much of that holding
-- is pointed at it.
alter table investment_goals enable row level security;

create policy investment_goals_read on investment_goals for select
  using (exists (select 1 from goals g where g.id = goal_id)
         and exists (select 1 from investments i where i.id = investment_id));

create policy investment_goals_write on investment_goals for all
  using (app.can_modify_goal(goal_id)
         and exists (select 1 from investments i where i.id = investment_id))
  with check (app.can_modify_goal(goal_id)
              and exists (select 1 from investments i where i.id = investment_id));

-- -----------------------------------------------------------------------------
-- The grant rule learns its fifth type. Same shape every time: one branch, and
-- it still raises for anything it has not been taught.
-- -----------------------------------------------------------------------------
create or replace function app.record_holder_member_ids(p_record_type text, p_record_id uuid)
  returns uuid[] language plpgsql stable security definer
  set search_path = public, app, pg_temp as $$
begin
  case p_record_type
    when 'investment' then
      return (select coalesce(array_agg(o.member_id), '{}')
              from investment_ownerships o where o.investment_id = p_record_id);
    when 'account' then
      return (select coalesce(array_agg(h.member_id), '{}')
              from account_holders h where h.account_id = p_record_id);
    when 'liability' then
      return (select coalesce(array_agg(h.member_id), '{}')
              from liability_holders h where h.liability_id = p_record_id);
    when 'document' then
      return (select coalesce(array_agg(m.id), '{}')
              from documents d join members m on m.user_id = d.uploaded_by
              where d.id = p_record_id and m.household_id = d.household_id
                and m.deleted_at is null);
    when 'goal' then
      -- The member it is for, or whoever created it when it belongs to the
      -- household rather than to a person.
      return (select coalesce(array_agg(m.id), '{}')
              from goals g
              join members m
                on m.household_id = g.household_id
               and m.deleted_at is null
               and (m.id = g.member_id or (g.member_id is null and m.user_id = g.created_by))
              where g.id = p_record_id);
    else
      raise exception 'record type % has no holder rule; extend app.record_holder_member_ids',
        p_record_type using errcode = 'feature_not_supported';
  end case;
end $$;

create or replace function app.record_created_by(p_record_type text, p_record_id uuid)
  returns uuid language plpgsql stable security definer
  set search_path = public, app, pg_temp as $$
begin
  case p_record_type
    when 'investment' then return (select i.created_by  from investments i where i.id = p_record_id);
    when 'account'    then return (select a.created_by  from accounts    a where a.id = p_record_id);
    when 'liability'  then return (select l.created_by  from liabilities l where l.id = p_record_id);
    when 'document'   then return (select d.uploaded_by from documents   d where d.id = p_record_id);
    when 'goal'       then return (select g.created_by  from goals       g where g.id = p_record_id);
    else
      raise exception 'record type % has no creator rule; extend app.record_created_by',
        p_record_type using errcode = 'feature_not_supported';
  end case;
end $$;

-- -----------------------------------------------------------------------------
-- What a goal is worth today. security_invoker, so a member sees only the
-- holdings they may see contributing to it — which means two members can read
-- different progress on a shared goal, and both are right.
-- -----------------------------------------------------------------------------
create view goal_funding with (security_invoker = true) as
select
  g.id as goal_id,
  g.household_id,
  coalesce(sum(round(coalesce(iv.effective_value, 0) * ig.allocation_pct / 100.0, 4)), 0) as funded,
  count(ig.investment_id) as holding_count
from goals g
left join investment_goals ig on ig.goal_id = g.id
left join investment_value iv on iv.investment_id = ig.investment_id
where g.deleted_at is null
group by g.id, g.household_id;
