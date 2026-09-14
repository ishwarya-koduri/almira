-- =============================================================================
-- V104 · Three references to a person that stopped their erasure.
-- Refs: V75, V92, V93, lifecycle/AccountPurge.kt, docs/05 §8
--
-- handbook_editions.created_by, lost_money_checks.created_by and
-- investment_fmv_2018.recorded_by pointed at users(id) with no delete action,
-- and the purge did not clear them. Someone who printed a handbook envelope,
-- recorded a lost-money check or entered a 2018 value in a household other
-- people still use could therefore never be erased: `delete from users` failed
-- on the foreign key, the whole purge rolled back, and the sweep retried it
-- every hour, failing the same way.
--
-- An edition is its maker's own (readable only by them, its link already gone
-- with their guest shares), so it goes with them. A lost-money check and a
-- 2018 value are the household's findings, so they stay, without the name.
-- The check is still readable by the member it is about (V92 policy).
-- =============================================================================

alter table handbook_editions drop constraint handbook_editions_created_by_fkey;
alter table handbook_editions add constraint handbook_editions_created_by_fkey
  foreign key (created_by) references users(id) on delete cascade;

alter table lost_money_checks alter column created_by drop not null;
alter table lost_money_checks drop constraint lost_money_checks_created_by_fkey;
alter table lost_money_checks add constraint lost_money_checks_created_by_fkey
  foreign key (created_by) references users(id) on delete set null;

alter table investment_fmv_2018 drop constraint investment_fmv_2018_recorded_by_fkey;
alter table investment_fmv_2018 add constraint investment_fmv_2018_recorded_by_fkey
  foreign key (recorded_by) references users(id) on delete set null;
