package tech.bhrigu.almira.shared.signin

import tech.bhrigu.almira.shared.security.PlatformHost

/**
 * iOS has nothing to start, and that is not a gap.
 *
 * One-time-code autofill on iOS is declarative: a `UITextField` with
 * `textContentType = .oneTimeCode` makes the keyboard offer the code from the
 * most recent message, and no code runs in the app to make it happen. So the
 * honest implementation here waits for a code that will never be delivered
 * *through this interface* — the platform will have filled the field directly.
 *
 * Deliberately not `error(...)` like the Keychain and LocalAuthentication
 * stubs: those are unwritten work, and throwing is how they stay visible. This
 * is finished work that happens to be zero lines, and crashing a future iOS
 * build over it would be a bug we invented for ourselves. What does remain is
 * one attribute on the code field when the iOS stage opens — see
 * docs/known-issues.md.
 */
private object NoRuntimeAutofill : OtpAutofill {
    override suspend fun awaitCode(length: Int): String? = null
    override fun smsSignature(): String? = null
}

actual fun createOtpAutofill(host: PlatformHost): OtpAutofill = NoRuntimeAutofill
