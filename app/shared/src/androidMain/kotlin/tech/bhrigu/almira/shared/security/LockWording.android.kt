package tech.bhrigu.almira.shared.security

internal actual fun lockPrompt(availability: LockAvailability): String = when (availability) {
    LockAvailability.Biometric -> "Unlock with your fingerprint, or your screen lock."
    LockAvailability.DeviceCredentialOnly -> "Unlock with your PIN, pattern or password."
    // Reached only if the lock disappeared between launch and now — the device
    // lock was removed while the app was open.
    LockAvailability.None -> "This phone has no screen lock, so there is nothing to unlock with."
}
