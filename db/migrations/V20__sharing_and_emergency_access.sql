-- =============================================================================
-- V20 · Two ways someone outside the usual rules gets to see something:
--       a scoped guest link, and emergency access.
-- Refs: docs/05 §6-7, docs/03 §8, docs/10 Phase 3
--
-- Both are exceptions to the privacy model, so both are built as *narrowings*
-- of it rather than bypasses. Neither adds a code path that can read more than
-- the person who created it could; both are enforced by the same policies as
-- everything else, and both are audited.
--
--   A guest link  — a CA who needs the tax pack for a week. The scope is
--                   materialised as explicit rows when the link is made, under
--                   the sharer's own RLS, so a link can never contain something
--                   the sharer could not see. Inside a guest session every read
--                   policy additionally requires the row to be in that list.
--
--   Emergency     — a trusted person, after a delay the owner can veto, sees
--   access          records marked for continuity and nothing else. Privacy is
--                   for life; continuity is for after (docs/05 §3.4).
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Guest links
-- -----------------------------------------------------------------------------
create table guest_shares (
  id                uuid primary key default gen_random_uuid(),
  household_id      uuid not null references households(id) on delete cascade,
  label             text not null,
  scope             text not null
                      check (scope in ('tax_pack','handbook','records')),
  -- The financial year, for a tax-pack share.
  scope_detail      text,
  -- Only the hash. A link that can be reconstructed from a database dump is not
  -- a link, it is a password stored in plaintext.
  token_hash        text not null unique,
  recipient_hint    text,
  note              text,
  include_documents boolean not null default false,
  expires_at        timestamptz not null,
  revoked_at        timestamptz,
  max_views         int,
  view_count        int not null default 0,
  created_by        uuid not null references users(id),
  created_at        timestamptz not null default now(),
  constraint share_expires_in_the_future check (expires_at > created_at),
  constraint share_label_is_not_blank check (length(btrim(label)) > 0)
);
create index on guest_shares (household_id) where revoked_at is null;

-- The scope, resolved at creation time rather than at open time. Resolving it
-- later would mean a link quietly widening as the household adds records — and
-- a share of "my tax pack" would eventually include holdings that did not exist
-- when it was sent.
create table guest_share_items (
  share_id    uuid not null references guest_shares(id) on delete cascade,
  record_type text not null
                check (record_type in ('investment','liability','account','document',
                                       'goal','contact','estate_document')),
  record_id   uuid not null,
  primary key (share_id, record_type, record_id)
);

create table guest_share_views (
  id         uuid primary key default gen_random_uuid(),
  share_id   uuid not null references guest_shares(id) on delete cascade,
  viewed_at  timestamptz not null default now(),
  -- Hashed, not stored: enough to spot a link being passed around, not enough
  -- to track anyone (docs/05 §5).
  ip_hash    text,
  user_agent text
);
create index on guest_share_views (share_id, viewed_at desc);

-- A share is its creator's business. An admin who could list other members'
-- shares would learn that a private record exists from the fact it was shared.
alter table guest_shares enable row level security;

create policy guest_shares_read on guest_shares for select
  using (app.is_household_member(household_id) and created_by = app.current_user_id());

create policy guest_shares_insert on guest_shares for insert
  with check (app.can_write_household(household_id) and created_by = app.current_user_id());

create policy guest_shares_update on guest_shares for update
  using (created_by = app.current_user_id())
  with check (created_by = app.current_user_id());

create policy guest_shares_delete on guest_shares for delete
  using (created_by = app.current_user_id());

alter table guest_share_items enable row level security;
create policy guest_share_items_all on guest_share_items for all
  using (exists (select 1 from guest_shares s where s.id = share_id))
  with check (exists (select 1 from guest_shares s where s.id = share_id));

alter table guest_share_views enable row level security;
create policy guest_share_views_read on guest_share_views for select
  using (exists (select 1 from guest_shares s where s.id = share_id));
-- The view is written by the guest, who is not the share's creator, so the
-- insert is made through app.record_guest_view() below rather than by policy.

-- -----------------------------------------------------------------------------
-- The guest clamp.
--
-- Outside a guest session this returns true and changes nothing. Inside one it
-- is the whole of a guest's reach: every read policy below gains
-- `and app.guest_scope_allows(...)`, so even a bug in an endpoint cannot
-- return a row the link does not name.
-- -----------------------------------------------------------------------------
create or replace function app.guest_share_id() returns uuid
  language sql stable as $$
  select nullif(current_setting('app.guest_share_id', true), '')::uuid
$$;

create or replace function app.guest_scope_allows(p_record_type text, p_record_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select case
    when app.guest_share_id() is null then true
    else exists (
      select 1 from guest_share_items i
      where i.share_id = app.guest_share_id()
        and i.record_type = p_record_type
        and i.record_id = p_record_id)
  end
$$;

-- Opening a link happens with no signed-in user at all, so the lookup cannot go
-- through the policy above — there is nobody to be the creator of. This is the
-- one definer-rights read in the flow, it is keyed by the token hash alone, and
-- it returns only what the opener is about to be told anyway.
create or replace function app.resolve_guest_share(p_token_hash text)
  returns table (
    id uuid, household_id uuid, label text, scope text, scope_detail text,
    recipient_hint text, note text, include_documents boolean,
    expires_at timestamptz, revoked_at timestamptz, max_views int, view_count int,
    created_by uuid, created_at timestamptz, item_count bigint,
    household_name text, shared_by text
  )
  language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select s.id, s.household_id, s.label, s.scope, s.scope_detail,
         s.recipient_hint, s.note, s.include_documents,
         s.expires_at, s.revoked_at, s.max_views, s.view_count,
         s.created_by, s.created_at,
         (select count(*) from guest_share_items i where i.share_id = s.id),
         h.name,
         coalesce(u.full_name, 'Someone')
  from guest_shares s
  join households h on h.id = s.household_id
  join users u on u.id = s.created_by
  where s.token_hash = p_token_hash
$$;

-- Recording a view is a write a guest must be able to make, and the only one.
create or replace function app.record_guest_view(
    p_share_id uuid, p_ip_hash text, p_user_agent text)
  returns int language plpgsql security definer
  set search_path = public, app, pg_temp as $$
declare v_count int;
begin
  insert into guest_share_views (share_id, ip_hash, user_agent)
    values (p_share_id, p_ip_hash, p_user_agent);
  update guest_shares set view_count = view_count + 1
    where id = p_share_id returning view_count into v_count;
  return v_count;
end $$;

-- -----------------------------------------------------------------------------
-- Emergency access
-- -----------------------------------------------------------------------------
create table emergency_contacts (
  id                uuid primary key default gen_random_uuid(),
  household_id      uuid not null references households(id) on delete cascade,
  -- Whose records this contact may eventually reach.
  member_id         uuid not null references members(id) on delete cascade,
  -- The person who may ask. A member of the household, because reaching the
  -- records requires a login the household already trusts.
  trusted_member_id uuid not null references members(id) on delete cascade,
  -- How long the owner has to say no. Humane by design: long enough to notice,
  -- short enough to matter.
  wait_days         int not null default 14 check (wait_days between 1 and 90),
  note              text,
  created_by        uuid references users(id),
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now(),
  unique (household_id, member_id, trusted_member_id),
  constraint trusted_contact_is_someone_else check (member_id <> trusted_member_id)
);
create trigger emergency_contacts_touch before insert or update on emergency_contacts
  for each row execute function app.touch_row();

create table emergency_requests (
  id                 uuid primary key default gen_random_uuid(),
  household_id       uuid not null references households(id) on delete cascade,
  subject_member_id  uuid not null references members(id) on delete cascade,
  requested_by       uuid not null references users(id),
  reason             text,
  requested_at       timestamptz not null default now(),
  -- Set from the trusted contact's wait_days. The delay is the safeguard: it
  -- gives an owner who is merely travelling time to say no.
  unlock_at          timestamptz not null,
  access_expires_at  timestamptz not null,
  vetoed_at          timestamptz,
  vetoed_by          uuid references users(id),
  revoked_at         timestamptz,
  acknowledged_at    timestamptz,
  created_at         timestamptz not null default now(),
  constraint unlock_is_after_the_request check (unlock_at >= requested_at),
  constraint access_ends_after_it_starts check (access_expires_at > unlock_at)
);
create index on emergency_requests (household_id, subject_member_id);

alter table emergency_contacts enable row level security;

-- Who you have named, and who has named you. Both sides need to see it: being
-- someone's emergency contact is a responsibility, not a secret from them.
create policy emergency_contacts_read on emergency_contacts for select
  using (app.is_household_member(household_id)
         and (member_id = any(app.current_member_ids(household_id))
           or trusted_member_id = any(app.current_member_ids(household_id))));

create policy emergency_contacts_write on emergency_contacts for all
  using (member_id = any(app.current_member_ids(household_id)))
  with check (member_id = any(app.current_member_ids(household_id))
              and app.can_write_household(household_id));

alter table emergency_requests enable row level security;

-- The subject must see a request against them — the veto is worthless
-- otherwise — and the requester must see their own.
create policy emergency_requests_read on emergency_requests for select
  using (app.is_household_member(household_id)
         and (requested_by = app.current_user_id()
           or subject_member_id = any(app.current_member_ids(household_id))));

create policy emergency_requests_insert on emergency_requests for insert
  with check (app.is_household_member(household_id)
              and requested_by = app.current_user_id()
              and exists (
                select 1 from emergency_contacts c
                where c.household_id = emergency_requests.household_id
                  and c.member_id = emergency_requests.subject_member_id
                  and c.trusted_member_id = any(app.current_member_ids(household_id))));

-- The subject vetoes; the requester may withdraw. Both are updates.
create policy emergency_requests_update on emergency_requests for update
  using (app.is_household_member(household_id)
         and (requested_by = app.current_user_id()
           or subject_member_id = any(app.current_member_ids(household_id))))
  with check (app.is_household_member(household_id));

-- -----------------------------------------------------------------------------
-- What an unlock reveals.
--
-- Only records marked for continuity, only while the window is open, only to
-- the person who asked — and the state is derived from timestamps rather than
-- from a status column, so an unlock cannot be left switched on by a worker
-- that failed to run.
-- -----------------------------------------------------------------------------
create or replace function app.emergency_reveals(p_household_id uuid, p_in_continuity boolean)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select coalesce(p_in_continuity, false) and exists (
    select 1 from emergency_requests r
    where r.household_id = p_household_id
      and r.requested_by = app.current_user_id()
      and r.vetoed_at is null
      and r.revoked_at is null
      and now() >= r.unlock_at
      and now() <  r.access_expires_at
  )
$$;

-- Debts are part of continuity by default: a family that inherits an asset and
-- not the loan against it has been told half the truth.
alter table liabilities add column is_in_continuity boolean not null default true;

-- -----------------------------------------------------------------------------
-- The policies gain both clauses. Same shape everywhere:
--   ( the ordinary rule OR an emergency unlock ) AND within the guest's scope
-- -----------------------------------------------------------------------------
alter policy investments_read on investments using (
  ( app.can_read_record(household_id, visibility, 'investment', id, app.owns_investment(id))
    or app.emergency_reveals(household_id, is_in_continuity) )
  and app.guest_scope_allows('investment', id)
);

alter policy liabilities_read on liabilities using (
  ( app.can_read_record(household_id, visibility, 'liability', id, app.owes_liability(id))
    or app.emergency_reveals(household_id, is_in_continuity) )
  and app.guest_scope_allows('liability', id)
);

alter policy accounts_read on accounts using (
  app.can_read_record(household_id, visibility, 'account', id, app.holds_account(id))
  and app.guest_scope_allows('account', id)
);

alter policy goals_read on goals using (
  app.can_read_record(household_id, visibility, 'goal', id, app.owns_goal(id))
  and app.guest_scope_allows('goal', id)
);

alter policy contacts_read on contacts using (
  app.can_read_record(household_id, visibility, 'contact', id, app.owns_contact(id))
  and app.guest_scope_allows('contact', id)
);

-- The will is the point of an emergency unlock, so it is revealed like a
-- continuity record — an instrument nobody can read is an instrument nobody
-- can act on.
alter policy estate_documents_read on estate_documents using (
  ( app.can_read_record(household_id, visibility, 'estate_document', id,
                        app.owns_estate_document(id))
    or app.emergency_reveals(household_id, true) )
  and app.guest_scope_allows('estate_document', id)
);

-- Documents keep inheriting the privacy of what they are attached to, which
-- means an emergency unlock reaches a receipt exactly when it reaches the
-- holding — no separate rule to keep in step.
alter policy documents_read on documents using (
  app.is_household_member(household_id)
  and (
    uploaded_by = app.current_user_id()
    or exists (
      select 1 from app.document_links_of(documents.id) dl
      where app.linked_record_visible(dl.entity_type, dl.entity_id)
    )
    or (
      not exists (select 1 from app.document_links_of(documents.id))
      and ( visibility = 'household'
         or (visibility = 'scoped' and app.has_visibility_grant('document', documents.id)) )
    )
  )
  and app.guest_scope_allows('document', documents.id)
);
