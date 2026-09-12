package tech.bhrigu.almira.shared.security

/**
 * What the lock screen calls the thing it is asking for.
 *
 * The words are the one part of this feature that cannot be shared, because
 * they name hardware. "Unlock with your PIN, pattern or password" is exactly
 * right on Android and simply wrong on an iPhone, which has none of those three
 * things — it has a passcode, and Face ID or Touch ID in front of it.
 *
 * Found by reading the lock screen on the simulator rather than by thinking
 * about it: the copy had been written once, for Android, and carried over
 * silently when the iOS target arrived.
 *
 * Kept as a tiny `expect`/`actual` rather than a runtime `if (platform)` so
 * that adding a third platform means writing its words, not editing a branch
 * somebody else owns.
 */
internal expect fun lockPrompt(availability: LockAvailability): String
