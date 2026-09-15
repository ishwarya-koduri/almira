-- =============================================================================
-- V110 · A sign-in code by email is queued, whatever the address, and the
-- worker decides whether it goes.
-- Refs: docs/13 §5 "No enumeration", docs/known-issues.md "Sign-in emails go
--       through an outbox, and an unlisted address's is dropped there",
--       auth/SignInCodeOutbox.kt
--
-- Until now the request path sent a listed address's code to the provider in
-- the background and, for an address off the closed-alpha allowlist, made the
-- same provider call to a "decoy sink" mailbox, so that both followed the
-- provider as it was. Every probe of a stranger's address was a billed send
-- (owner's decision, 2026-09-15: not shipped).
--
-- Now every email sign-in request, listed or not, writes one row here through
-- the same function, and nothing on the request path talks to a provider. The
-- worker re-reads the allowlist when it gets to the row: a listed address is
-- sent its code, anything else is dropped with no provider call, and either
-- way the body is deleted in the statement that records the outcome.
--
-- The body holds no code. The code is derived when it is needed, from the
-- server's key, the request id and the address (QueuedEmailCodes), so neither
-- table nor a dump of it is a list of codes that work.
--
-- Only the owner connection reads or changes these tables: row-level security
-- on, no policy, nothing granted to the runtime role (R__grants). The request
-- path writes only through app.enqueue_sign_in_code_email.
-- =============================================================================

create table sign_in_code_emails (
  id              uuid primary key default gen_random_uuid(),
  created_at      timestamptz not null default now(),
  -- sent: the provider accepted it. failed: the provider did not (failure says
  -- how). dropped: the address was not on the allowlist when the worker got to
  -- it; no provider was called. unconfirmed: a worker stopped between starting
  -- the send and recording it; never sent again. expired: queued past the
  -- code's lifetime; not sent.
  status          text not null default 'queued'
                    check (status in ('queued', 'sent', 'failed', 'dropped', 'unconfirmed', 'expired')),
  failure         text,
  claim_token     uuid,
  claimed_until   timestamptz,
  -- Committed before the provider is called, as in outbound_messages (V32).
  send_started_at timestamptz,
  finished_at     timestamptz,
  constraint sign_in_code_emails_finished_when_not_queued
    check ((status = 'queued') = (finished_at is null))
);

create index sign_in_code_emails_queued on sign_in_code_emails (created_at) where status = 'queued';
create index sign_in_code_emails_finished on sign_in_code_emails (finished_at) where status <> 'queued';

comment on table sign_in_code_emails is
  'One row per email sign-in code request, listed address or not, and how it ended. No address, no code (V110).';

create table sign_in_code_email_bodies (
  message_id  uuid primary key references sign_in_code_emails(id) on delete cascade,
  address     text not null check (length(address) between 3 and 320),
  purpose     text not null check (purpose = 'login'),
  request_id  uuid not null,
  -- The length the code was issued at, so a restart with another length does
  -- not send a code the challenge will not match.
  code_length smallint not null check (code_length between 6 and 8)
);

comment on table sign_in_code_email_bodies is
  'Where a queued sign-in email goes, only until the worker records its outcome (V110).';

alter table sign_in_code_emails enable row level security;
alter table sign_in_code_email_bodies enable row level security;
-- Deliberately no policies.

-- The request path's only way in. The same statement for every address: the
-- function does not know, and must not be told, whether the address is listed.
create or replace function app.enqueue_sign_in_code_email(
    p_address text, p_purpose text, p_request_id uuid, p_code_length int)
  returns void language plpgsql security definer
  set search_path = public, app, pg_temp as $$
declare
  v_id uuid;
begin
  insert into sign_in_code_emails default values returning id into v_id;
  insert into sign_in_code_email_bodies (message_id, address, purpose, request_id, code_length)
  values (v_id, p_address, p_purpose, p_request_id, p_code_length);
end $$;

revoke all on function app.enqueue_sign_in_code_email(text, text, uuid, int) from public;
do $$
begin
  if exists (select 1 from pg_roles where rolname = 'almira_app') then
    execute 'grant execute on function app.enqueue_sign_in_code_email(text, text, uuid, int) to almira_app';
  end if;
end $$;
