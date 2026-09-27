-- =============================================================================
-- An export leaves as a link, not as a file in an inbox.
--
-- Stage three of emailing an export (`almira.exports.email.enabled`, still off
-- by default). Two changes, both small, and both in service of one decision:
-- what is emailed is a link that expires, never the file itself. A file sent
-- to an inbox is there forever, in the recipient's mail and in every relay it
-- passed through, and no expiry, revocation or view limit can reach it.
--
-- 1. `guest_shares.scope` gains `export`.
--
--    The link is an ordinary guest share — the same hashed token, the same
--    `expires_at`, `max_views`, `revoked_at` and view log, the same admission
--    checks and the same rate limit. Nothing new was written to hold an
--    emailed export, because everything it needs already exists and is already
--    tested.
--
--    An export share names its records in `guest_share_items` like any other,
--    which means the guest clamp (V20, `app.guest_scope_allows`) does
--    something worth having for free: the file behind the link holds exactly
--    the records that existed when the link was made. A holding added
--    tomorrow does not appear in a link sent today, and no code had to
--    remember to make that true.
--
--    `scope_detail` carries the format — csv, xlsx or pdf — where a tax pack
--    carries its financial year.
--
-- 2. `app.message_is_essential` gains `export.link`.
--
--    The email that carries the link is not a notice and not a campaign: it is
--    the answer to something the person did a moment ago, sent to an address
--    they proved. Under consent to messages it would be possible to ask for an
--    export and simply be sent nothing, with nowhere for the app to say why.
--    That is the same reasoning that put `otp_email` on this list, and like
--    `otp_email` it never goes through the outbox — it is sent on the request
--    path and its outcome is reported in the response.
--
--    MessagesConsentTest asserts this list and MessageTemplates.ESSENTIAL_TEMPLATES
--    are the same set, so the Kotlin copy changes in the same commit.
-- =============================================================================

-- Every value, not the ones this change is about. The first draft of this
-- migration rebuilt the list from V20 and left out `heir_help`, which V90 had
-- added, so heir mode began answering 400 to every attempt to hand a task to a
-- relative. The suite caught it; a careful reading of this file would not have,
-- because nothing in it was wrong — it was what it did not say.
--
-- This is the same failure as V150 dropping V135's dormancy guard: a thing
-- replaced whole loses every line the new text forgets. A CHECK constraint is
-- exactly that kind of thing, and the list below is the union of V20's three,
-- V90's `heir_help`, and this migration's `export`.
alter table guest_shares drop constraint if exists guest_shares_scope_check;

alter table guest_shares
  add constraint guest_shares_scope_check
  check (scope in ('tax_pack', 'handbook', 'records', 'heir_help', 'export'));

comment on column guest_shares.scope_detail is
  'The financial year for a tax pack; the file format for an export (V154).';

create or replace function app.message_is_essential(p_template text)
  returns boolean language sql immutable
  set search_path = public, app, pg_temp as $$
  select coalesce(p_template in (
    'otp_email',
    'auth.new_sign_in',
    'auth.phone_changed',
    'auth.authenticator_added',
    'auth.authenticator_removed',
    'auth.passkey_added',
    'auth.passkey_removed',
    'auth.recovery_codes_replaced',
    'auth.recovery_code_used',
    'emergency.check_in',
    'emergency.requested',
    'emergency.raised',
    'emergency.vetoed',
    'emergency.named',
    'lifecycle.closure.requested',
    'lifecycle.closure.cancelled',
    'lifecycle.memorial.marked',
    'lifecycle.successor.claimed',
    'lifecycle.departure.asked',
    'lifecycle.departure.completed.you',
    'lifecycle.memorial.reversed',
    'lifecycle.successor.named',
    'lifecycle.household.dormant',
    'lifecycle.household.dormant.you',
    'lifecycle.household.running_again',
    'lifecycle.household.ownership_accepted',
    'lifecycle.household.asked_first',
    'lifecycle.household.repair_requested',
    'lifecycle.household.repair_done',
    'lifecycle.coming_of_age.you',
    -- Nobody took your household on in the app, so only an operator can now (V147):
    -- the right to take it on ends for everyone told.
    'lifecycle.household.routed_to_repair',
    -- The export you just asked for, with the link to it (V154). Listed for the
    -- same reason 'otp_email' is: it is a direct answer to something the person
    -- did a moment ago, sent to an address they proved, and it never goes
    -- through the outbox. Under consent it would be possible to ask for an
    -- export and be sent nothing, with nowhere for the app to say why.
    'export.link'
  ), false)
$$;
