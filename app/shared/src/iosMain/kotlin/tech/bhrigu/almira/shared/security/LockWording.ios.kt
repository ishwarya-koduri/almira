package tech.bhrigu.almira.shared.security

import platform.LocalAuthentication.LABiometryTypeFaceID
import platform.LocalAuthentication.LABiometryTypeTouchID
import platform.LocalAuthentication.LAContext

internal actual fun lockPrompt(availability: LockAvailability): String = when (availability) {
    // Named exactly, because iOS tells us which one this device has and a
    // person reading "biometrics" on an iPhone has to translate it themselves.
    LockAvailability.Biometric -> when (LAContext().biometryType) {
        LABiometryTypeFaceID -> "Unlock with Face ID, or your passcode."
        LABiometryTypeTouchID -> "Unlock with Touch ID, or your passcode."
        // Optic ID, or something Apple ships after this was written. Still true.
        else -> "Unlock with your biometrics, or your passcode."
    }
    LockAvailability.DeviceCredentialOnly -> "Unlock with your passcode."
    LockAvailability.None -> "This iPhone has no passcode, so there is nothing to unlock with."
}
