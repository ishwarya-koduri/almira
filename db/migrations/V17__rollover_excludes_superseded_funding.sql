-- =============================================================================
-- V17 · A renewed holding funds its goal through its successor, not twice.
-- Refs: docs/01 §7, docs/07 §1 ("rollover without losing history")
--
-- V16 gave a renewal a pointer back to the record it replaced, and the renewal
-- inherits the goal mappings. Both records are then real — the old one is kept
-- deliberately, because the history is the point — but only one of them holds
-- the money, so only one of them may fund the goal.
--
-- security_invoker is preserved: a viewer who cannot see the renewal still sees
-- the original funding the goal, which is the truthful answer for them.
-- =============================================================================

create or replace view goal_funding with (security_invoker = true) as
select
  g.id as goal_id,
  g.household_id,
  coalesce(sum(round(coalesce(iv.effective_value, 0) * ig.allocation_pct / 100.0, 4)), 0) as funded,
  count(ig.investment_id) as holding_count
from goals g
left join investment_goals ig
  on ig.goal_id = g.id
 and not exists (select 1 from investments s
                 where s.rolled_from_id = ig.investment_id and s.deleted_at is null)
left join investment_value iv on iv.investment_id = ig.investment_id
where g.deleted_at is null
group by g.id, g.household_id;
