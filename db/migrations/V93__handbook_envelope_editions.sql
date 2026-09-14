-- =============================================================================
-- V93 · The envelope edition of the family handbook.
-- Refs: docs/03 §8.2, docs/05 §7, catch-up plan P-28
--
-- A handbook printed for an envelope is numbered and dated, so a family holding
-- two can tell which is newer. Its QR code opens a guest link to the handbook
-- (V20) — scoped when it is printed, read-only, logged, withdrawable — and the
-- printed pages carry everything on their own, so the envelope still works if
-- the link, or Almira, is gone.
--
-- Printing a new edition withdraws the previous edition's link: an old envelope
-- left in a drawer stops opening anything the day a newer one exists.
-- The token itself is never stored, here or anywhere (V20).
-- =============================================================================

create table handbook_editions (
  id              uuid primary key default gen_random_uuid(),
  household_id    uuid not null references households(id) on delete cascade,
  created_by      uuid not null references users(id),
  edition         int not null check (edition >= 1),
  share_id        uuid references guest_shares(id) on delete set null,
  link_expires_at timestamptz not null,
  created_at      timestamptz not null default now(),
  unique (household_id, created_by, edition)
);

-- Each person's editions are theirs, like the links they make.
alter table handbook_editions enable row level security;

create policy handbook_editions_own on handbook_editions for all
  using (app.guest_share_id() is null
         and created_by = app.current_user_id()
         and app.is_household_member(household_id))
  with check (app.guest_share_id() is null
              and created_by = app.current_user_id()
              and app.is_household_member(household_id));
