package tech.bhrigu.almira.shared.security

/**
 * What one LocalAuthentication failure means to the lock screen.
 *
 * Apple-shaped knowledge in common code, deliberately. The alternative is the
 * mapping living inside the `evaluatePolicy` callback in `Platform.ios.kt`,
 * where it is reachable only from a device with a face enrolled and a finger to
 * cancel with — which is how the previous version came to treat two of the
 * three cancels as cancels and the third as an error nobody could reproduce.
 * The codes are stable public API, the function is pure, and it is asserted
 * from `commonTest` on every run.
 *
 * The three [Cancelled][UnlockResult.Cancelled] codes are the counterpart of
 * Android's `ERROR_USER_CANCELED`, `ERROR_NEGATIVE_BUTTON` and `ERROR_CANCELED`
 * (see `AppLock.android.kt`). All three mean a decision not to answer rather
 * than a failure to, and showing a message under the unlock button for one is
 * telling somebody off for backgrounding the app.
 */
internal fun appleUnlockOutcome(code: Long?, message: String?): UnlockResult = when (code) {
    // No error alongside a false result: nothing was refused, so nothing to say.
    null -> UnlockResult.Cancelled

    LA_ERROR_USER_CANCEL, LA_ERROR_SYSTEM_CANCEL, LA_ERROR_APP_CANCEL ->
        UnlockResult.Cancelled

    // The system's own wording, which already says "too many attempts" better
    // than we would. Only when it is actually there to use.
    else -> UnlockResult.Failed(message?.takeIf { it.isNotBlank() } ?: GENERIC_FAILURE)
}

/** `LAErrorUserCancel`: they tapped Cancel on the prompt. */
private const val LA_ERROR_USER_CANCEL = -2L

/** `LAErrorSystemCancel`: iOS took the prompt away, typically a call or a lock. */
private const val LA_ERROR_SYSTEM_CANCEL = -4L

/**
 * `LAErrorAppCancel`: the app itself dropped the prompt, which here means the
 * person left for the home screen. The one the old mapping missed, and the one
 * that turned backgrounding into a red line under the unlock button.
 */
private const val LA_ERROR_APP_CANCEL = -9L

/** Short, because it sits in a fixed-height line on the lock screen. */
private const val GENERIC_FAILURE = "That did not work. Try again."
