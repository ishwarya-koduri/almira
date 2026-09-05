-- =============================================================================
-- V5 · Derived value views.
--
-- Every view is declared `security_invoker = true` so it is evaluated with the
-- CALLING role's row-level security, not the view owner's. Without this flag a
-- view is a perfect privacy side-channel: the policies in V4 would apply to the
-- tables but not to anything selected through a view over them.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- investment_value — what one holding is worth, and how honestly we know it.
--
-- Precedence, per docs/07 §1 ("no live price -> manual snapshots, never
-- fabricated"):
--   1. valued        latest valuation snapshot
--   2. at_cost       the amount invested; truthful but not current
--   3. custom_field  a Universal record whose worth lives in a user-defined
--                    money field
--   4. unknown       we genuinely do not know; the UI says so and offers
--                    "add a valuation", rather than printing a zero
--
-- The custom-field lateral carries the docs/01 §5 guardrail: LIMIT 1 means a
-- custom money field joins value math EXACTLY once, even if the data is odd.
-- A record-level field wins over a type-level one.
-- -----------------------------------------------------------------------------
create view investment_value with (security_invoker = true) as
select
  i.id                as investment_id,
  i.household_id,
  i.currency,
  v.value             as valued_amount,
  v.as_of_date        as valued_on,
  i.invested_amount,
  cf.custom_value,
  coalesce(v.value, i.invested_amount, cf.custom_value) as effective_value,
  case
    when v.value           is not null then 'valued'
    when i.invested_amount is not null then 'at_cost'
    when cf.custom_value   is not null then 'custom_field'
    else 'unknown'
  end                 as value_basis
from investments i
left join lateral (
  select vl.value, vl.as_of_date
  from valuations vl
  where vl.investment_id = i.id
  order by vl.as_of_date desc, vl.created_at desc
  limit 1
) v on true
left join lateral (
  select (i.attributes ->> f.key)::numeric as custom_value
  from custom_fields f
  where f.counts_toward_value
    and ( (f.owner_type = 'record' and f.owner_id = i.id)
       or (f.owner_type = 'type'   and f.owner_id = i.type_id) )
    and i.attributes ? f.key
    -- Only a well-formed number participates; junk is ignored, never coerced.
    and (i.attributes ->> f.key) ~ '^-?[0-9]+(\.[0-9]+)?$'
  order by case f.owner_type when 'record' then 0 else 1 end, f.sort
  limit 1
) cf on true
where i.deleted_at is null;

comment on view investment_value is
  'Effective value per holding with an explicit basis. Never fabricates a number.';

-- -----------------------------------------------------------------------------
-- investment_owner_value — value attributed to each owner by their share.
--
-- This is what keeps joint holdings from double counting: a flat 50/50 asset
-- contributes half to each owner and its whole self exactly once to the
-- household (docs/07 §1).
-- -----------------------------------------------------------------------------
create view investment_owner_value with (security_invoker = true) as
select
  o.investment_id,
  o.member_id,
  o.holder_type,
  o.share_pct,
  iv.household_id,
  iv.currency,
  iv.value_basis,
  iv.effective_value,
  round(coalesce(iv.effective_value, 0) * o.share_pct / 100.0, 4) as attributed_value
from investment_ownerships o
join investment_value iv on iv.investment_id = o.investment_id;

comment on view investment_owner_value is
  'Per-owner attributed value (value x share). Sums here never double-count joint assets.';
