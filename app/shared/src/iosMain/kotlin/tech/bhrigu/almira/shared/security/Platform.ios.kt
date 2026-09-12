package tech.bhrigu.almira.shared.security

import tech.bhrigu.almira.shared.api.TokenStore

/**
 * The iOS half of the seam, deliberately unimplemented.
 *
 * The shapes are here so the common code compiles for this target and so the
 * work is visible rather than imagined: what remains is a Keychain-backed
 * [TokenStore] (`kSecClassGenericPassword` with
 * `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`, and `SecAccessControl` with
 * `.biometryCurrentSet` to match the Android key's binding) and an [AppLock]
 * over `LAContext.evaluatePolicy(.deviceOwnerAuthentication)` — which, like
 * Android's pairing of BIOMETRIC_STRONG with DEVICE_CREDENTIAL, is Face ID or
 * Touch ID with the passcode behind it.
 *
 * Nothing above this file changes when they arrive. It has never been compiled:
 * this target needs the Kotlin/Native toolchain, which is not installed — see
 * docs/known-issues.md.
 */
actual class PlatformHost

actual fun createTokenStore(host: PlatformHost): TokenStore =
    error("The iOS Keychain store is not written yet — see docs/known-issues.md.")

actual fun createAppLock(host: PlatformHost): AppLock =
    error("The iOS LocalAuthentication lock is not written yet — see docs/known-issues.md.")
