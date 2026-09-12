package tech.bhrigu.almira.shared.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import tech.bhrigu.almira.shared.api.TokenStore
import kotlin.coroutines.resume

/**
 * Everything the Android side needs that only an Activity can provide.
 *
 * The store takes the application context out of it and keeps nothing else;
 * the prompt keeps the activity, because that is what it attaches its dialog
 * to. Holding the activity here rather than in a global is what makes the
 * lifetime obvious.
 */
actual class PlatformHost(internal val activity: FragmentActivity)

actual fun createTokenStore(host: PlatformHost): TokenStore =
    KeystoreTokenStore(host.activity)

actual fun createAppLock(host: PlatformHost): AppLock = AndroidAppLock(host.activity)

/**
 * BiometricPrompt, with the device's PIN, pattern or password behind it.
 *
 * `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` is one prompt, not two: the person
 * sees a fingerprint on a phone that has one and a keypad on a phone that does
 * not, and a fingerprint that will not read falls through to the keypad without
 * anyone having to find a button. It is also the exact pair the Keystore key is
 * minted against, so passing this prompt is what makes the session decryptable
 * — the dialog and the cryptography are asking the same question.
 */
internal class AndroidAppLock(private val activity: FragmentActivity) : AppLock {

    private val manager = BiometricManager.from(activity)

    override fun availability(): LockAvailability = when {
        manager.canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS ->
            LockAvailability.Biometric

        manager.canAuthenticate(DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS ->
            LockAvailability.DeviceCredentialOnly

        else -> LockAvailability.None
    }

    override suspend fun unlock(title: String, subtitle: String): UnlockResult {
        if (availability() == LockAvailability.None) return UnlockResult.Unavailable

        return suspendCancellableCoroutine { continuation ->
            // One resume, whatever the prompt does. BiometricPrompt is
            // perfectly willing to call onAuthenticationFailed and then
            // onAuthenticationError, and resuming twice crashes the coroutine.
            var answered = false
            fun answer(result: UnlockResult) {
                if (answered) return
                answered = true
                continuation.resume(result)
            }

            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        answer(UnlockResult.Unlocked)
                    }

                    override fun onAuthenticationError(code: Int, message: CharSequence) {
                        answer(
                            when (code) {
                                BiometricPrompt.ERROR_USER_CANCELED,
                                BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                                BiometricPrompt.ERROR_CANCELED,
                                -> UnlockResult.Cancelled

                                BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
                                BiometricPrompt.ERROR_HW_NOT_PRESENT,
                                BiometricPrompt.ERROR_HW_UNAVAILABLE,
                                -> UnlockResult.Unavailable

                                // The system's own wording, which already says
                                // "too many attempts, try again in 30 seconds"
                                // better than we would.
                                else -> UnlockResult.Failed(message.toString())
                            },
                        )
                    }

                    // Deliberately not answering: a finger that did not read is
                    // not a decision. The prompt stays up and lets them try
                    // again, which is what every other app on the phone does.
                    override fun onAuthenticationFailed() = Unit
                },
            )

            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                // No negative button is allowed alongside DEVICE_CREDENTIAL —
                // the system supplies "Use PIN" itself and refuses to build the
                // prompt if we add one.
                .setConfirmationRequired(false)
                .build()

            prompt.authenticate(info)
            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        }
    }
}
