-- =============================================================================
-- V27 · Which way an outbound message failed, and after how many attempts.
-- Refs: docs/13 "When a provider fails"
--
-- outbound_messages.failure used to hold an exception's class name, which told
-- nobody anything. Every provider call now goes through one timeout-and-retry
-- policy that classifies the outcome, and the row records that classification:
--
--   timeout               no answer in time; retried; may still arrive
--   unavailable           the provider could not take it; retried
--   rejected              refused for this message (recipient, template); not retried
--   insufficient_balance  refused for our account; not retried; the operator's problem
--   error                 an adapter bug that classified nothing
--
-- Free text rather than a check constraint on purpose: rows written before this
-- migration hold class names, and a constraint would have to either rewrite
-- history or refuse it.
-- =============================================================================

alter table outbound_messages
  add column attempts int not null default 1 check (attempts >= 1);

-- A new overload rather than a replacement: `create or replace` cannot change a
-- signature, and the nine-argument version stays callable so nothing deployed
-- against V24 breaks mid-rollout. It records attempts = 1.
create or replace function app.record_outbound_message(
    p_household_id uuid, p_user_id uuid, p_channel text, p_provider text,
    p_template text, p_title text, p_recipient_hint text, p_status text, p_failure text,
    p_attempts int)
  returns void language sql security definer
  set search_path = public, app, pg_temp as $$
  insert into outbound_messages (household_id, user_id, channel, provider, template,
                                 title, recipient_hint, status, failure, attempts)
  values (p_household_id, p_user_id, p_channel, p_provider, p_template,
          p_title, p_recipient_hint, p_status, p_failure, greatest(coalesce(p_attempts, 1), 1));
$$;

-- No grant here: R__grants sets default privileges so every function created in
-- schema app is executable by almira_app, and a grant naming the role would fail
-- in a migrate-only context where that role does not exist.
