-- =============================================================================
-- V18 · Estate, and the people who handle it.
-- Refs: docs/01 §10, docs/03 §8, docs/07 §1 "Estate", docs/10 Phase 3
--
-- Two tables of people and two of paper, and one idea holding them together:
-- **a nominee is not an heir.** In India a nominee is a receiver — the person
-- the institution pays — while the estate passes to heirs under a will or by
-- succession. Families discover the difference at the worst possible moment.
-- Almira records both and can therefore say, quietly and early, "this policy
-- pays your brother, but your will leaves it to your spouse".
--
-- Everything here is as private as the rest: an estate document names what
-- someone owns and who they have chosen, which is among the most sensitive
-- things this product holds.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Advisors and other people attached to records: the CA, the LIC agent, the
-- lawyer who holds the will, the banker who knows the locker.
--
-- These are contact cards, not users. The person who will actually help your
-- family is usually not going to install an app, and a registry that can only
-- hold app users would leave out everyone who matters here.
-- -----------------------------------------------------------------------------
create table contacts (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references households(id) on delete cascade,
  kind          text not null default 'other'
                  check (kind in ('ca','advisor','agent','lawyer','banker','broker',
                                  'doctor','executor','witness','other')),
  name          text not null,
  organisation  text,
  phone         text,
  email         text,
  address       text,
  notes         text,
  visibility    text not null default 'household'
                  check (visibility in ('private','household','scoped')),
  deleted_at    timestamptz,
  version       int not null default 1,
  created_by    uuid references users(id),
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  constraint contact_name_is_not_blank check (length(btrim(name)) > 0)
);
create index on contacts (household_id) where deleted_at is null;
create trigger contacts_touch before insert or update on contacts
  for each row execute function app.touch_row();

-- Which records a contact handles. Same shape as document_links, and for the
-- same reason: "who do I call about this?" is a question about one record.
create table contact_links (
  id          uuid primary key default gen_random_uuid(),
  contact_id  uuid not null references contacts(id) on delete cascade,
  entity_type text not null
                check (entity_type in ('investment','liability','account','estate_document','goal')),
  entity_id   uuid not null,
  role        text,
  created_at  timestamptz not null default now(),
  unique (contact_id, entity_type, entity_id)
);
create index on contact_links (entity_type, entity_id);

-- -----------------------------------------------------------------------------
-- The paper: wills, codicils, powers of attorney, trust deeds.
--
-- Almira does not draft or store legal instruments — it records that one exists,
-- where it is, who executed it and when, and (optionally) links the scan in the
-- vault. The most valuable field here is `location`: a will nobody can find is
-- a will that does not exist.
-- -----------------------------------------------------------------------------
create table estate_documents (
  id           uuid primary key default gen_random_uuid(),
  household_id uuid not null references households(id) on delete cascade,
  -- Whose instrument it is. A household has one will per adult, not one will.
  member_id    uuid not null references members(id) on delete cascade,
  kind         text not null
                 check (kind in ('will','codicil','poa','living_will','trust',
                                 'nomination_letter','succession_certificate','other')),
  title        text not null,
  executed_on  date,
  -- Where the physical original is. The single most useful line in this table.
  location     text,
  registered   boolean not null default false,
  status       text not null default 'executed'
                 check (status in ('draft','executed','superseded','revoked')),
  notes        text,
  -- The scan, if there is one. Documents carry their own privacy (V12).
  document_id  uuid references documents(id) on delete set null,
  visibility   text not null default 'private'
                 check (visibility in ('private','household','scoped')),
  deleted_at   timestamptz,
  version      int not null default 1,
  created_by   uuid references users(id),
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now(),
  constraint estate_title_is_not_blank check (length(btrim(title)) > 0)
);
create index on estate_documents (household_id) where deleted_at is null;
create index on estate_documents (member_id);
create trigger estate_documents_touch before insert or update on estate_documents
  for each row execute function app.touch_row();

-- Who does what under the instrument: executor, alternate, attorney, guardian.
-- Either a member, a contact, or a plain name — the executor is often the
-- family lawyer, who is a contact card, not a user.
create table estate_roles (
  id                 uuid primary key default gen_random_uuid(),
  estate_document_id uuid not null references estate_documents(id) on delete cascade,
  role               text not null
                       check (role in ('executor','alternate_executor','attorney',
                                       'guardian','witness','trustee')),
  member_id          uuid references members(id) on delete set null,
  contact_id         uuid references contacts(id) on delete set null,
  person_name        text,
  note               text,
  created_at         timestamptz not null default now(),
  constraint estate_role_names_someone
    check (member_id is not null or contact_id is not null
           or (person_name is not null and length(btrim(person_name)) > 0))
);
create index on estate_roles (estate_document_id);

-- Who inherits what. A row with no investment_id is a share of the residue —
-- "everything else, equally between the children" — which is how most wills
-- actually read.
create table estate_beneficiaries (
  id                 uuid primary key default gen_random_uuid(),
  estate_document_id uuid not null references estate_documents(id) on delete cascade,
  investment_id      uuid references investments(id) on delete cascade,
  liability_id       uuid references liabilities(id) on delete cascade,
  member_id          uuid references members(id) on delete set null,
  person_name        text,
  relationship       text,
  share_pct          numeric(5,2) not null default 100
                       check (share_pct > 0 and share_pct <= 100),
  note               text,
  created_at         timestamptz not null default now(),
  constraint beneficiary_names_someone
    check (member_id is not null
           or (person_name is not null and length(btrim(person_name)) > 0))
);
create index on estate_beneficiaries (estate_document_id);
create index on estate_beneficiaries (investment_id);

-- =============================================================================
-- Visibility.
-- =============================================================================

create or replace function app.owns_estate_document(p_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select exists (
    select 1 from estate_documents e
      join members m on m.id = e.member_id
    where e.id = p_id
      and m.deleted_at is null
      and (m.user_id = app.current_user_id() or e.created_by = app.current_user_id())
  )
$$;

create or replace function app.owns_contact(p_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select exists (
    select 1 from contacts c
    where c.id = p_id and c.created_by = app.current_user_id()
  )
$$;

alter table contacts enable row level security;

create policy contacts_read on contacts for select
  using (app.can_read_record(household_id, visibility, 'contact', id, app.owns_contact(id)));

create policy contacts_insert on contacts for insert
  with check (app.can_write_household(household_id) and created_by = app.current_user_id());

create policy contacts_update on contacts for update
  using (app.can_write_household(household_id)
         and app.can_read_record(household_id, visibility, 'contact', id, app.owns_contact(id)))
  with check (app.can_write_household(household_id));

create policy contacts_delete on contacts for delete
  using (app.can_write_household(household_id)
         and app.can_read_record(household_id, visibility, 'contact', id, app.owns_contact(id)));

-- A link is visible only when the contact is. The record at the other end
-- carries its own policy, and the service checks both — so "who handles this
-- holding" can never become a way to enumerate holdings.
alter table contact_links enable row level security;

create policy contact_links_read on contact_links for select
  using (exists (select 1 from contacts c where c.id = contact_id));

create policy contact_links_write on contact_links for all
  using (exists (select 1 from contacts c
                 where c.id = contact_id and app.can_write_household(c.household_id)))
  with check (exists (select 1 from contacts c
                      where c.id = contact_id and app.can_write_household(c.household_id)));

alter table estate_documents enable row level security;

create policy estate_documents_read on estate_documents for select
  using (app.can_read_record(household_id, visibility, 'estate_document', id,
                             app.owns_estate_document(id)));

create policy estate_documents_insert on estate_documents for insert
  with check (app.can_write_household(household_id) and created_by = app.current_user_id());

create policy estate_documents_update on estate_documents for update
  using (app.can_write_household(household_id) and app.owns_estate_document(id))
  with check (app.can_write_household(household_id));

create policy estate_documents_delete on estate_documents for delete
  using (app.can_write_household(household_id) and app.owns_estate_document(id));

-- Roles and beneficiaries inherit the instrument's visibility exactly. Reading
-- "who inherits the flat" is reading the will.
alter table estate_roles enable row level security;
create policy estate_roles_read on estate_roles for select
  using (exists (select 1 from estate_documents e where e.id = estate_document_id));
create policy estate_roles_write on estate_roles for all
  using (app.owns_estate_document(estate_document_id))
  with check (app.owns_estate_document(estate_document_id));

alter table estate_beneficiaries enable row level security;
create policy estate_beneficiaries_read on estate_beneficiaries for select
  using (exists (select 1 from estate_documents e where e.id = estate_document_id));
create policy estate_beneficiaries_write on estate_beneficiaries for all
  using (app.owns_estate_document(estate_document_id))
  with check (app.owns_estate_document(estate_document_id));

-- -----------------------------------------------------------------------------
-- The grant rule learns two more types. Same shape every time, and it still
-- raises for anything it has not been taught (V8, V14).
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
      return (select coalesce(array_agg(m.id), '{}')
              from goals g
              join members m
                on m.household_id = g.household_id
               and m.deleted_at is null
               and (m.id = g.member_id or (g.member_id is null and m.user_id = g.created_by))
              where g.id = p_record_id);
    when 'estate_document' then
      -- The member whose instrument it is. Not the creator: an admin may have
      -- typed it in, and that does not make the will theirs to share.
      return (select coalesce(array_agg(e.member_id), '{}')
              from estate_documents e where e.id = p_record_id);
    when 'contact' then
      return (select coalesce(array_agg(m.id), '{}')
              from contacts c join members m on m.user_id = c.created_by
              where c.id = p_record_id and m.household_id = c.household_id
                and m.deleted_at is null);
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
    when 'investment'      then return (select i.created_by  from investments i where i.id = p_record_id);
    when 'account'         then return (select a.created_by  from accounts    a where a.id = p_record_id);
    when 'liability'       then return (select l.created_by  from liabilities l where l.id = p_record_id);
    when 'document'        then return (select d.uploaded_by from documents   d where d.id = p_record_id);
    when 'goal'            then return (select g.created_by  from goals       g where g.id = p_record_id);
    when 'estate_document' then return (select e.created_by  from estate_documents e where e.id = p_record_id);
    when 'contact'         then return (select c.created_by  from contacts    c where c.id = p_record_id);
    else
      raise exception 'record type % has no creator rule; extend app.record_created_by',
        p_record_type using errcode = 'feature_not_supported';
  end case;
end $$;

-- -----------------------------------------------------------------------------
-- Nominee ≠ heir, made checkable.
--
-- One row per holding where a nominee and a will-beneficiary disagree, computed
-- through the caller's own RLS: you can only be shown a mismatch on a holding
-- you can see, named in a will you can see.
-- -----------------------------------------------------------------------------
create view nominee_will_mismatch with (security_invoker = true) as
with nominees as (
  select n.investment_id,
         array_agg(distinct coalesce(m.display_name, n.nominee_name)
                   order by coalesce(m.display_name, n.nominee_name)) as names
  from investment_nominees n
  left join members m on m.id = n.member_id
  group by n.investment_id
),
heirs as (
  select b.investment_id,
         array_agg(distinct coalesce(m.display_name, b.person_name)
                   order by coalesce(m.display_name, b.person_name)) as names,
         min(e.id::text)::uuid as estate_document_id
  from estate_beneficiaries b
  join estate_documents e on e.id = b.estate_document_id
  left join members m on m.id = b.member_id
  where b.investment_id is not null
    and e.deleted_at is null
    and e.status = 'executed'
  group by b.investment_id
)
select i.id                       as investment_id,
       i.household_id,
       i.title,
       nominees.names             as nominee_names,
       heirs.names                as heir_names,
       heirs.estate_document_id
from investments i
join nominees on nominees.investment_id = i.id
join heirs    on heirs.investment_id = i.id
where i.deleted_at is null
  and nominees.names is distinct from heirs.names;
