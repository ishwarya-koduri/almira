-- =============================================================================
-- V146 · A child who comes of age is told themselves, and the household is told
--        when the child still needs telling.
-- Refs: lifecycle/ComingOfAge.kt, provider/MessageTemplates.kt,
--       docs/05 §3.5, docs/23 "Notices that protect your account",
--       docs/known-issues.md "No message tells a child they have come of age"
--
-- Owner's decision, 2026-09-15: *a child-only notice — their rights are the
-- ones changing. With no channel to them, show it in-app at their next sign-in
-- and tell the guardian the child still needs telling.*
--
-- The monthly sweep now notices a child turning eighteen whether or not they
-- have a login of their own. A child who has one is sent
-- `lifecycle.coming_of_age.you` at once — essential, so it is not held by
-- consent or pacing, and its in-app copy is there when they next open Almira.
-- A child who has none cannot be reached by anything, so the adults who run the
-- household are told the child still needs telling, and the same notice is
-- given to the child the moment they first sign in as themselves (an accepted
-- invitation that claims their member row).
--
-- child_told_at records that the child was given it, so neither path gives it
-- twice. Written only on the owner connection (the sweep, and the step that
-- runs after an invitation is accepted); members read it through the table's
-- existing policy.
-- =============================================================================

alter table coming_of_age_notices add column child_told_at timestamptz;

comment on column coming_of_age_notices.child_told_at is
  'When the young adult was given lifecycle.coming_of_age.you: by the sweep if they had a login, or at their first sign-in (V146).';

create or replace function app.message_is_essential(p_template text)
  returns boolean language sql immutable
  set search_path = public, app, pg_temp as $$
  select coalesce(p_template in (
    -- A sign-in code by email (auth/EmailOtpSender.kt). Sent at sign-in, not
    -- through the outbox, and listed so the classification is complete.
    'otp_email',
    -- A takeover of the account itself (auth/AccountNotices.kt).
    'auth.new_sign_in',
    'auth.phone_changed',
    'auth.authenticator_added',
    'auth.authenticator_removed',
    'auth.passkey_added',
    'auth.passkey_removed',
    'auth.recovery_codes_replaced',
    'auth.recovery_code_used',
    -- Emergency access: the question before it begins, the request against
    -- you (your chance to veto), a request raised in your name, and the veto.
    'emergency.check_in',
    'emergency.requested',
    'emergency.raised',
    'emergency.vetoed',
    -- You were named the person who may ask for someone's records: a duty (V140).
    'emergency.named',
    -- Your account or your place in a household is being ended or taken.
    'lifecycle.closure.requested',
    'lifecycle.closure.cancelled',
    'lifecycle.memorial.marked',
    'lifecycle.successor.claimed',
    'lifecycle.departure.asked',
    -- Your place in a household changed (V140): you left it, the passed-away
    -- label you gave was taken away, or you were named to carry it on.
    'lifecycle.departure.completed.you',
    'lifecycle.memorial.reversed',
    'lifecycle.successor.named',
    -- The household you belong to has nobody running it, or has someone again
    -- (V143): while it is dormant nobody can invite or remove people and an
    -- adult may take it on; when it ends, that right ends and someone runs it.
    -- The owner whose going made it dormant is told their going waits.
    'lifecycle.household.dormant',
    'lifecycle.household.dormant.you',
    'lifecycle.household.running_again',
    'lifecycle.household.ownership_accepted',
    -- The named successor is asked first, and alone may take the household on
    -- until their window ends (V135, V144): a right given to them, as
    -- `lifecycle.successor.named`.
    'lifecycle.household.asked_first',
    -- An operator is asked to make someone owner of your household, and did
    -- (V137, scripts/dormancy-repair.sh): the household's only warning before
    -- its owner is chosen from outside the app.
    'lifecycle.household.repair_requested',
    'lifecycle.household.repair_done',
    -- You came of age (V146): what is held in your name is yours to manage now.
    -- Sent only to the young adult, whose rights are the ones changing.
    'lifecycle.coming_of_age.you'
  ), false)
$$;
