-- =============================================================================
-- V144 · One list again: the operator-repair notices and the successor's
-- "asked first" are essential alongside V140 and V143.
-- Refs: V135 (asked first), V137 (operator repair), V140, V143
--       app.message_is_essential, provider/MessageTemplates.kt,
--       docs/23 "Notices that protect your account"
--
-- V137 (dormant-ordered) redefined app.message_is_essential with the two
-- operator-repair notices, on a branch that had neither V140 nor V143. Migrated
-- in version order, V140 and V143 redefine the function after it and drop them,
-- so a fresh database would queue a repair warning only with consent while
-- MessageTemplates.ESSENTIAL_TEMPLATES says it always goes. This is V143's list
-- with V137's two added back.
--
-- `lifecycle.household.asked_first` (V135) is put to the owner's test as the
-- other dormancy notices were in V143: it tells the named successor that they
-- alone may take the household on until a date, which changes their rights, so
-- it is essential and worded as YOUR_PLACE.
--
-- app.never_stopped_by_memorial is untouched: V137's definition is the latest.
-- =============================================================================

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
    'lifecycle.household.repair_done'
  ), false)
$$;
