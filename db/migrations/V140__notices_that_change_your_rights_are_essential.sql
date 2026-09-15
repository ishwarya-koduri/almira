-- =============================================================================
-- V140 · A notice that changes your own rights or obligations is essential.
-- Refs: V125 app.message_is_essential, provider/MessageTemplates.kt,
--       docs/23 "Notices that protect your account", docs/13 "Pacing",
--       docs/known-issues.md "Consent to messages is asked for on the web only,
--       and some reminders are never asked about" (the classification question)
--
-- V125 made consent to messages opt-in and kept, as essential, only the notices
-- that protect an account or let a person stop something done in their name.
-- The informational notices that had ridden on the `emergency.`/`lifecycle.`
-- prefixes went under consent, and whether any of them should come back was
-- left to the owner. The owner's answer is a test, asked of the person who
-- receives the notice:
--
--     "Does it change YOUR rights or obligations?"
--
--  * Being named an emergency contact places a duty on you     -> essential
--    (`emergency.named`, sent only to the person named).
--  * "You've left the household" changes your access           -> essential
--    (`lifecycle.departure.completed.you`, sent only to the one who left).
--  * A memorial reversed is a major change of state            -> essential
--    (`lifecycle.memorial.reversed`, sent to whoever marked it).
--  * A successor named matters to the successor                -> essential
--    (`lifecycle.successor.named`, sent only to the successor; nobody else is
--    told by message, so "essential to the successor only" is the template).
--  * A child coming of age changes that child's rights: essential to the
--    child. No message about it goes to the child today — a child is noticed
--    only while they have no login, and is welcomed in the app when they first
--    sign in. The notes to the adults who run the household
--    (`lifecycle.coming_of_age.guardian`, `.welcomed`) are about someone else,
--    so they stay under consent.
--  * Someone else joined or left is household news              -> consent
--    (`lifecycle.departure.started`, `.completed`, `.cancelled`,
--    `lifecycle.coming_of_age.welcomed`).
--
-- Still one list. Queueing, the worker's re-check and pacing all ask this
-- function, so each notice added here is at once sent without consent and not
-- held by quiet hours or the daily limit. MessageTemplates.ESSENTIAL_TEMPLATES
-- is the same list, and MessagesConsentTest reads this definition and fails if
-- they differ.
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
    'lifecycle.successor.named'
  ), false)
$$;
