-- =============================================================================
-- An address proved before anything is sent to it.
--
-- Emailing an export is being built behind `almira.exports.email.enabled`, off
-- by default. Its destination cannot be an address somebody typed into a box:
-- one transposed character mails a household's whole financial record to a
-- stranger, and there is no recalling an email. So an address becomes a
-- destination only after a one-time code sent to that address comes back —
-- exactly the proof a phone number needs before it can be the number on an
-- account (OtpService.PHONE_CHANGE), with the channel swapped.
--
-- **Deliberately not `users.email`.** That column is an identity: it is unique,
-- AuthRepository looks an account up by it, and an address written there is a
-- second front door into the account. Somewhere to send a file must never be a
-- way to sign in, so proved destinations live here and nothing in auth reads
-- this table.
--
-- **No unique constraint on the address alone**, which is the enumeration
-- answer rather than an oversight. A global unique would make "that address is
-- already in use" a way for anyone to ask whether a stranger has an Almira
-- account. Two accounts may prove the same address — one mailbox in a
-- household is a household, not a conflict — so the only uniqueness is per
-- account, and the request path can answer identically for every address
-- because there is nothing for it to find out.
--
-- How many an account may hold is enforced in the service, not here: counting
-- rows is not a check constraint, and the refusal wants a sentence a person
-- can read.
-- =============================================================================

create table verified_email_addresses (
  id           uuid primary key default gen_random_uuid(),
  user_id      uuid not null references users(id) on delete cascade,
  address      citext not null,
  -- When the code came back. There is no unverified row: an address that has
  -- not been proved is not in this table at all, so no reader has to remember
  -- to filter on a flag (docs/05 §3.6, the same reasoning as the export).
  verified_at  timestamptz not null default now(),
  -- When something was last sent to it, for the account screen. Never a count
  -- of what was sent, and never what was in it.
  last_sent_at timestamptz,
  created_at   timestamptz not null default now(),
  constraint verified_email_addresses_one_per_account unique (user_id, address)
);

create index verified_email_addresses_by_user
  on verified_email_addresses (user_id, verified_at desc);

comment on table verified_email_addresses is
  'Addresses an account has proved it receives mail at, by answering a one-time '
  'code sent there. Destinations only: never an identity, never read by sign-in '
  '(V153).';

alter table verified_email_addresses enable row level security;

-- Your own, and only yours. The account that proved an address is the only one
-- that can see it, add to it or take it away.
create policy verified_email_addresses_read on verified_email_addresses for select
  using (user_id = app.current_user_id());

create policy verified_email_addresses_insert on verified_email_addresses for insert
  with check (user_id = app.current_user_id());

create policy verified_email_addresses_update on verified_email_addresses for update
  using (user_id = app.current_user_id())
  with check (user_id = app.current_user_id());

create policy verified_email_addresses_delete on verified_email_addresses for delete
  using (user_id = app.current_user_id());
