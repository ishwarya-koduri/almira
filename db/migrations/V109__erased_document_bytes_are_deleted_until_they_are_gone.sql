-- =============================================================================
-- V109 · Erased document bytes are deleted until they are gone.
-- Refs: docs/05 §8 (hard purge), docs/known-issues.md 66,
--       lifecycle/AccountPurge.kt, lifecycle/DepartureCompletion.kt
--
-- A purge or a departure deletes document rows in its transaction and the
-- stored bytes only after it commits. The storage keys lived in memory, so a
-- storage outage at that moment left the bytes in the bucket for good, with no
-- row naming them — and in a household that carries on, still under a data key
-- that exists.
--
-- The key is now written here in the same transaction that deletes the row.
-- The sweep deletes the bytes and removes the row only once storage has said
-- yes; a failure leaves the row for the next run.
--
-- Only the owner connection touches it: row-level security on, no policy, and
-- nothing granted to the runtime role (R__grants).
-- =============================================================================

create table pending_storage_deletions (
  storage_key     text primary key,
  queued_at       timestamptz not null default now(),
  attempts        int not null default 0,
  last_attempt_at timestamptz
);

comment on table pending_storage_deletions is
  'Stored document bytes whose rows an erasure or a move has deleted, kept until storage confirms the delete (V109).';

alter table pending_storage_deletions enable row level security;
-- Deliberately no policy.
