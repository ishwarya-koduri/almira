-- =============================================================================
-- V30 — "Still true?" asks whoever recorded it when no owner can answer
--
-- V29 asked a record's owners and holders only. A parent's LIC policy recorded
-- by the adult child is owned by a member who never signs in, so nobody was
-- asked about it, ever — and that is the main case the feature is for.
--
-- The rule (docs/21 §4): the owners are asked when at least one of them could
-- answer — a live login with an active owner/admin/editor membership in the
-- household. When none can, the person who recorded it is asked instead,
-- provided they can still write in the household AND can see the record
-- without owning it (household visibility, or a scoped grant). A private
-- record for someone with no login is visible to nobody in the app, so it is
-- asked of nobody.
--
-- The sight check is explicit, not left to the view's security_invoker: the
-- sweep reads this view on the owner connection, which bypasses row-level
-- security, and must not count a record its recipient could not open.
-- =============================================================================

-- Whether anyone who owns or holds the record could answer for it. Security
-- definer: it reads other people's memberships, and returns only a boolean.
create or replace function app.still_true_owner_can_answer(p_record_type text, p_record_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select exists (
    select 1
    from (
      select o.member_id from investment_ownerships o
        where p_record_type = 'investment' and o.investment_id = p_record_id
      union all
      select h.member_id from liability_holders h
        where p_record_type = 'liability' and h.liability_id = p_record_id
      union all
      select h.member_id from account_holders h
        where p_record_type = 'account' and h.account_id = p_record_id
      union all
      select e.member_id from estate_documents e
        where p_record_type = 'estate_document' and e.id = p_record_id
    ) owners
    join members m on m.id = owners.member_id and m.deleted_at is null and m.user_id is not null
    join household_memberships hm on hm.user_id = m.user_id and hm.household_id = m.household_id
     and hm.status = 'active' and hm.role in ('owner', 'admin', 'editor')
  )
$$;

-- Whether the current user is the one asked because nobody who owns it can be.
create or replace function app.still_true_recorder_answers(p_record_type text, p_record_id uuid)
  returns boolean language sql stable security definer
  set search_path = public, app, pg_temp as $$
  select exists (
    select 1
    from (
      select household_id, visibility, created_by from investments
        where p_record_type = 'investment' and id = p_record_id
      union all
      select household_id, visibility, created_by from liabilities
        where p_record_type = 'liability' and id = p_record_id
      union all
      select household_id, visibility, created_by from accounts
        where p_record_type = 'account' and id = p_record_id
      union all
      select household_id, visibility, created_by from estate_documents
        where p_record_type = 'estate_document' and id = p_record_id
    ) r
    where r.created_by = app.current_user_id()
      and app.can_write_household(r.household_id)
      -- The read predicate with ownership set to false: sight the recorder has
      -- in their own right. Emergency reveals are deliberately not counted.
      and app.can_read_record(r.household_id, r.visibility, p_record_type, p_record_id, false)
      and not app.still_true_owner_can_answer(p_record_type, p_record_id)
  )
$$;

-- Same columns, same order: only the "who is asked" predicate changes.
create or replace view still_true_items with (security_invoker = true) as
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
  and ( case r.record_type
          when 'investment'      then app.owns_investment(r.record_id)
          when 'liability'       then app.owes_liability(r.record_id)
          when 'account'         then app.holds_account(r.record_id)
          when 'estate_document' then app.owns_estate_document(r.record_id)
          else false
        end
        or app.still_true_recorder_answers(r.record_type, r.record_id) );
