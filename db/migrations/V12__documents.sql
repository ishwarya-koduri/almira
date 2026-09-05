-- =============================================================================
-- V12 · The document vault (docs/01 §4 "Proof", docs/10 Epic 1.7).
--
-- Knowing you own something is half of it. Knowing where the certificate is —
-- and being able to hand it to whoever needs it — is the other half, and it is
-- the half families discover is missing at the worst possible moment.
--
-- Bytes never live in this table. They are encrypted with the household's data
-- key and written to object storage; this row holds only what is needed to find
-- and describe them.
-- =============================================================================

create table documents (
  id            uuid primary key default gen_random_uuid(),
  household_id  uuid not null references households(id) on delete cascade,
  -- Where the ciphertext lives. Opaque to everything but the storage adapter.
  storage_key   text not null unique,
  file_name     text not null,
  mime_type     text not null default 'application/octet-stream',
  size_bytes    bigint not null check (size_bytes >= 0),
  -- Of the PLAINTEXT, so a re-upload of the same file is recognisable without
  -- decrypting anything. Encryption is randomised, so ciphertext never repeats.
  content_sha256 text,
  doc_type      text not null default 'other'
                  check (doc_type in ('certificate','statement','receipt','policy',
                                      'deed','photo','kyc','will','other')),
  version       int not null default 1,
  supersedes_id uuid references documents(id) on delete set null,
  expires_on    date,
  visibility    text not null default 'private'
                  check (visibility in ('private','household','scoped')),
  notes         text,
  uploaded_by   uuid references users(id),
  deleted_at    timestamptz,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);
create index on documents (household_id) where deleted_at is null;
create index on documents (doc_type);
create index on documents (expires_on) where expires_on is not null and deleted_at is null;
create index documents_file_name_trgm on documents using gin (file_name gin_trgm_ops);

-- One statement can cover several holdings; one holding can have many proofs.
create table document_links (
  id          uuid primary key default gen_random_uuid(),
  document_id uuid not null references documents(id) on delete cascade,
  entity_type text not null
                check (entity_type in ('investment','liability','account','member','estate')),
  entity_id   uuid not null,
  created_at  timestamptz not null default now(),
  unique (document_id, entity_type, entity_id)
);
create index on document_links (entity_type, entity_id);

-- =============================================================================
-- Visibility.
--
-- A document INHERITS the privacy of what it is attached to. Attach a receipt to
-- a shared fixed deposit and everyone who can see the deposit can see the
-- receipt; attach it to a private one and only its owner can. That is what
-- people expect, and it means a proof can never be more visible than the thing
-- it proves.
--
-- A document attached to nothing — a will, a PAN card — carries its own
-- visibility, because there is nothing to inherit from.
-- =============================================================================

-- Plain SQL, deliberately NOT security definer: it must be evaluated with the
-- caller's own row-level security so that "can I see the linked record?" means
-- exactly what it says. No recursion is possible because none of these tables'
-- policies reference documents.
create or replace function app.linked_record_visible(p_entity_type text, p_entity_id uuid)
  returns boolean language sql stable
  set search_path = public, app, pg_temp as $$
  select case p_entity_type
    when 'investment' then exists (select 1 from investments  i where i.id = p_entity_id)
    when 'liability'  then exists (select 1 from liabilities  l where l.id = p_entity_id)
    when 'account'    then exists (select 1 from accounts     a where a.id = p_entity_id)
    when 'member'     then exists (select 1 from members      m where m.id = p_entity_id)
    else false
  end
$$;

-- The links of a document, WITHOUT row-level security.
--
-- This is security definer for one specific reason: the documents policy needs
-- to know what a document is attached to, and the document_links policy decides
-- that by asking whether the document is readable. Each policy would consult the
-- other and PostgreSQL rejects the query outright with "infinite recursion
-- detected in policy".
--
-- Reading the raw link rows here is safe because it decides nothing: the caller
-- still has to pass app.linked_record_visible on each one, and THAT runs with
-- the caller's own RLS. This function reveals which ids a document points at,
-- to a policy that is about to check whether the caller may see them.
create or replace function app.document_links_of(p_document_id uuid)
  returns table (entity_type text, entity_id uuid)
  language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select dl.entity_type, dl.entity_id
  from document_links dl
  where dl.document_id = p_document_id
$$;

alter table documents enable row level security;

create policy documents_read on documents for select
  using (
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
  );

create policy documents_insert on documents for insert
  with check (app.can_write_household(household_id)
              and uploaded_by = app.current_user_id());

create policy documents_update on documents for update
  using (app.can_write_household(household_id) and uploaded_by = app.current_user_id())
  with check (app.can_write_household(household_id));

create policy documents_delete on documents for delete
  using (app.can_write_household(household_id) and uploaded_by = app.current_user_id());

alter table document_links enable row level security;
create policy document_links_read on document_links for select
  using (exists (select 1 from documents d where d.id = document_id));
create policy document_links_write on document_links for all
  using (exists (select 1 from documents d
                 where d.id = document_id and d.uploaded_by = app.current_user_id()))
  with check (exists (select 1 from documents d
                      where d.id = document_id and d.uploaded_by = app.current_user_id())
              and app.linked_record_visible(entity_type, entity_id));

-- -----------------------------------------------------------------------------
-- The grant rule learns one more type. Same pattern as liabilities in V10: a
-- branch, not a special case — and it still raises for anything unknown.
--
-- A document's holder is whoever uploaded it. Sharing a proof is the uploader's
-- call; sharing what it proves is a separate decision made on the record itself.
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
    else
      raise exception 'record type % has no creator rule; extend app.record_created_by',
        p_record_type using errcode = 'feature_not_supported';
  end case;
end $$;
