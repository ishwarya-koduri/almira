package tech.bhrigu.almira.shared.security

import tech.bhrigu.almira.shared.api.TokenStore

/**
 * The seam every platform-owned security object passes through.
 *
 * Both of the things this file produces need something only the platform can
 * hand over — the Keystore needs a `Context`, and `BiometricPrompt` needs a
 * real `FragmentActivity` to attach its dialog to. Rather than smuggling those
 * through a global, the entry point on each platform builds one [PlatformHost]
 * and everything else is made from it.
 *
 * That is what makes iOS a target-add rather than a rewrite: `iosMain` declares
 * `actual class PlatformHost()` with nothing in it, returns a Keychain-backed
 * store and a LocalAuthentication lock, and not one line above this file moves.
 */
expect class PlatformHost

/**
 * Tokens at rest, encrypted by hardware the app cannot extract a key from.
 *
 * Android: AES-256-GCM under an `AndroidKeyStore` key that never leaves the
 * TEE or StrongBox. iOS: the Keychain, when that target arrives.
 */
expect fun createTokenStore(host: PlatformHost): TokenStore

/** The lock in front of the app. Android: BiometricPrompt. iOS: LocalAuthentication. */
expect fun createAppLock(host: PlatformHost): AppLock

/**
 * What stands between a stolen, already-unlocked phone and a family's balance
 * sheet.
 *
 * This is deliberately more than a curtain drawn over the UI. The Keystore key
 * that decrypts the refresh token is itself minted with
 * `setUserAuthenticationRequired`, so failing the lock does not hide the
 * session — it leaves the session mathematically unreadable. A curtain can be
 * lifted by anything that can draw over the app; this cannot.
 */
interface AppLock {
    /** What the device can actually do, which decides whether locking is offered at all. */
    fun availability(): LockAvailability

    /**
     * Show the prompt and suspend until the person answers it.
     *
     * Returns rather than throws, because every one of these outcomes is
     * ordinary and none of them is exceptional.
     */
    suspend fun unlock(title: String, subtitle: String): UnlockResult
}

enum class LockAvailability {
    /** A fingerprint or face is enrolled: the prompt will offer it. */
    Biometric,

    /** No biometric, but a PIN, pattern or password exists to fall back to. */
    DeviceCredentialOnly,

    /**
     * The device has no lock screen at all. There is nothing to gate with, and
     * inventing one would be theatre — so the app says so rather than pretending.
     */
    None,
}

sealed interface UnlockResult {
    data object Unlocked : UnlockResult

    /** Wrong finger, wrong PIN, or too many tries. Offer another go. */
    data class Failed(val message: String) : UnlockResult

    /** Back, cancel, or the prompt dismissed. Not an error; not an entry either. */
    data object Cancelled : UnlockResult

    /** Nothing to prompt with. The caller decides what an unlockable app means. */
    data object Unavailable : UnlockResult
}
