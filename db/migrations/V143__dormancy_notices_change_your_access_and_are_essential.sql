-- =============================================================================
-- V143 · The dormancy notices are put to the owner's test, and pass it.
-- Refs: V120 (dormant households), V140 app.message_is_essential,
--       lifecycle/Dormancy.kt, provider/MessageTemplates.kt,
--       docs/23 "Notices that protect your account",
--       docs/known-issues.md "The dormancy notices have not been put to the
--       owner's test" (answered here)
--
-- V140 applied the test "does it change YOUR rights or access?" to the notices
-- the owner named. The V120 dormancy notices were added on a parallel branch and
-- were not among them. Each changes the recipient's rights or access, so each is
-- essential, as docs/05 §12.7 already described `lifecycle.household.dormant`:
--
--  * `lifecycle.household.dormant` (to the members with a login): nobody can
--    invite, remove or rename any more, and an adult may now take it on.
--  * `lifecycle.household.dormant.you` (to the owner whose going made it
--    dormant): their closure or departure is held until someone takes it on.
--  * `lifecycle.household.ownership_accepted` (to the rest of the household):
--    someone else now runs it — as `lifecycle.successor.claimed`, essential.
--  * `lifecycle.household.running_again` (to the household): the owner is back,
--    and the right to take it on has ended — as `lifecycle.memorial.reversed`.
--
-- Household news (someone else joined or left) stays under consent, unchanged.
-- The list is still one list: MessageTemplates.ESSENTIAL_TEMPLATES matches it and
-- MessagesConsentTest reads this definition back.
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
    'lifecycle.household.ownership_accepted'
  ), false)
$$;
