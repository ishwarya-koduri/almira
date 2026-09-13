-- =============================================================================
-- V28 · Where the original is, and who holds the key.
-- Refs: docs/20-where-and-who.md, docs/12
--
-- "Original in the steel almirah, second shelf." "Locker at SBI Ameerpet, key
-- with Amma." These are the most damaging sentences this database could hold:
-- they tell a burglar or a hostile relative exactly where to go. So they are
-- not columns. They are two sealed values per record, under the zero-knowledge
-- scheme that already exists (V22), with the fixed field keys
-- `original_location` and `key_holder`, and the server cannot read either.
--
-- This migration only widens where a sealed value may live. Nothing new is
-- stored in plaintext, and there is deliberately no plaintext fallback column.
-- =============================================================================

-- A scanned document has a physical original too — the deed, the share
-- certificate, the policy bond — and it was the one record kind with a
-- visibility model that a sealed value could not attach to.
alter table sealed_values drop constraint sealed_values_record_type_check;
alter table sealed_values add constraint sealed_values_record_type_check
  check (record_type in ('investment','liability','account','member','estate_document','document'));

-- The smallest legal envelope is 33 bytes (docs/12 §3): 1 version + 4 key
-- version + 12 iv + 16 tag. In base64 without padding that is 44 characters.
-- The service refuses anything shorter first, with a sentence; this is the
-- floor underneath it for any write that does not go through the service.
--
-- NOT VALID on purpose: it binds every new and updated row, and does not make
-- the migration fail on a development database holding a short test value
-- from before the floor was raised. No legitimate client ever wrote one.
alter table sealed_values add constraint ciphertext_is_at_least_an_envelope
  check (length(ciphertext) >= 44) not valid;

-- Visibility is the record's own, exactly as for every other sealed value.
-- The function is invoker-rights (not security definer), so each `exists`
-- runs under the caller's row-level security: a sealed value on a record you
-- cannot see is a row you cannot see. `document` is the only new branch;
-- the others are V22's, unchanged.
create or replace function app.linked_record_visible(p_entity_type text, p_entity_id uuid)
  returns boolean language sql stable
  set search_path = public, app, pg_temp as $$
  select case p_entity_type
    when 'investment'      then exists (select 1 from investments      i where i.id = p_entity_id)
    when 'liability'       then exists (select 1 from liabilities      l where l.id = p_entity_id)
    when 'account'         then exists (select 1 from accounts         a where a.id = p_entity_id)
    when 'member'          then exists (select 1 from members          m where m.id = p_entity_id)
    when 'estate_document' then exists (select 1 from estate_documents e where e.id = p_entity_id)
    when 'document'        then exists (select 1 from documents        d where d.id = p_entity_id)
    else false
  end
$$;
