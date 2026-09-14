-- =============================================================================
-- V106 · A key-holder ask carries a record's title only to someone who may see it.
-- Refs: V95 key_holder_asks, V81 app.member_would_see, docs/27 §4
--
-- V95's insert policy asked one thing about the record: can the ASKER see it.
-- Sight is not ownership. An advisor with a grant, a member a record is scoped
-- to, or whoever holds an open emergency window can all read a record they do
-- not hold — and could then ask about it, putting its title in front of a
-- member the owner never shared it with (stored in `thing`, sent in the
-- notification, readable under key_holder_asks_read).
--
-- Now the asker must hold the record themselves (the owner chooses whom to
-- ask, and may show their own title to anyone they choose), or the person
-- asked must already read it by ordinary sight (app.member_would_see).
-- =============================================================================

-- Does the caller hold this record: own, owe, hold, file, or upload it?
create or replace function app.holds_askable_record(p_record_type text, p_record_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select case p_record_type
    when 'investment'      then app.owns_investment(p_record_id)
    when 'liability'       then app.owes_liability(p_record_id)
    when 'account'         then app.holds_account(p_record_id)
    when 'estate_document' then app.owns_estate_document(p_record_id)
    when 'document'        then exists (select 1 from documents d
                                         where d.id = p_record_id and d.uploaded_by = app.current_user_id())
    else false
  end
$$;

revoke all on function app.holds_askable_record(text, uuid) from public;
do $$
begin
  if exists (select 1 from pg_roles where rolname = 'almira_app') then
    execute 'grant execute on function app.holds_askable_record(text, uuid) to almira_app';
  end if;
end $$;

drop policy key_holder_asks_insert on key_holder_asks;

-- About a record the asker can see, to someone else in the household, and only
-- if the asker holds the record or the person asked already sees it.
create policy key_holder_asks_insert on key_holder_asks for insert
  with check (app.guest_share_id() is null
              and app.is_household_member(household_id)
              and asked_by = app.current_user_id()
              and not (asked_member_id = any(app.current_member_ids(household_id)))
              and exists (select 1 from members m
                          where m.id = asked_member_id and m.household_id = key_holder_asks.household_id
                            and m.deleted_at is null and m.user_id is not null)
              and app.linked_record_visible(record_type, record_id)
              and (app.holds_askable_record(record_type, record_id)
                   or app.member_would_see(asked_member_id, record_type, record_id)));
