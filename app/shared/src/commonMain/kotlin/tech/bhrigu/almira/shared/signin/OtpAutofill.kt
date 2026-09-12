package tech.bhrigu.almira.shared.signin

import tech.bhrigu.almira.shared.security.PlatformHost

/**
 * The code, without anyone having to read it off a notification and type it.
 *
 * The two platforms solve this so differently that only the shape can be
 * shared. Android listens: Play Services hands the app one specific SMS,
 * chosen because it ends with a hash of this app's signing certificate, and no
 * SMS permission is involved — the app can read that one message and nothing
 * else. iOS does not listen at all; the keyboard offers the code above it
 * because the field says `textContentType = .oneTimeCode`, and there is no
 * runtime API to call.
 *
 * So this interface is "tell me when a code arrives, if that is a thing here",
 * which is the only honest common denominator.
 */
interface OtpAutofill {

    /**
     * Suspend until a one-time code arrives, or return null if none does.
     *
     * Cancelling the caller stops listening. Returns null rather than throwing
     * on every ordinary disappointment — a timeout, no Play Services, a
     * message that did not match — because autofill failing is not an error,
     * it is a person typing six digits like they always have.
     */
    suspend fun awaitCode(length: Int): String?

    /**
     * The eleven characters the SMS must end with for this build to be given
     * it, or null where the platform has no such notion.
     *
     * Worth surfacing rather than hiding in a gradle task: it is derived from
     * the signing certificate, so debug, release and every re-signed build
     * have a different one, and an SMS template carrying the wrong one fails
     * silently — the message arrives, autofill simply never happens.
     */
    fun smsSignature(): String?
}

expect fun createOtpAutofill(host: PlatformHost): OtpAutofill
