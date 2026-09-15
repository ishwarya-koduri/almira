-- =============================================================================
-- V130 · A sign-in email that a listed address did not get alerts the operator.
-- Refs: docs/13 §5 "When a listed address's code is not delivered",
--       docs/17 §8, docs/known-issues.md "Sign-in emails go through an outbox,
--       and an unlisted address's is dropped there", auth/SignInCodeOutbox.kt
--
-- Owner's decision, 2026-09-15: a tester whose code fails to send is still
-- shown "sent" (the alpha trade-off stands), but "the silence must not extend
-- to the operator". When the worker ends a message for an address that is on
-- the allowlist at decision time in any way but `sent` — the provider failed or
-- refused, a stopped worker left it unconfirmed, it expired in the queue, or it
-- was dropped because email sign-in was switched off — it raises an operator
-- alert: an ERROR line `SIGN-IN EMAIL NOT DELIVERED` with no address and no
-- code, and this flag on the record, which the operator's view of /health
-- counts.
--
-- An unlisted address's dropped message, and a tester taken off the list while
-- their message waited, are intended drops and never set it.
--
-- The flag is on the record the worker already keeps for 30 days (V110), so it
-- carries no address and lives no longer. Like the rest of the table it is
-- readable only on the owner connection: nothing is granted to the runtime
-- role (R__grants revokes all on this table), and no client can see it.
-- =============================================================================

alter table sign_in_code_emails
  add column operator_alert boolean not null default false;

alter table sign_in_code_emails
  add constraint sign_in_code_emails_alert_only_when_not_sent
    check (not operator_alert or status in ('failed', 'unconfirmed', 'expired', 'dropped'));

create index sign_in_code_emails_operator_alerts
  on sign_in_code_emails (finished_at) where operator_alert;

comment on column sign_in_code_emails.operator_alert is
  'The address was on the allowlist when the worker decided, and the message was not sent: the operator is alerted (V130).';
