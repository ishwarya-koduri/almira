package tech.bhrigu.almira.shared.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which LocalAuthentication errors are a cancel and which are a failure.
 *
 * The distinction is the whole difference between a lock screen that waits and
 * one that tells somebody off. Android already treats three codes as a cancel;
 * iOS treated two, and the missing one - `LAErrorAppCancel`, -9 - is the one
 * that arrives when the person leaves for the home screen mid-prompt. So going
 * to the home screen and coming back painted a red message under the unlock
 * button for something nobody did wrong.
 *
 * Reachable here only because the mapping is a pure function. On a device it
 * would need a real prompt and a real cancel to reproduce, which is why it went
 * unnoticed.
 */
class AppleUnlockOutcomeTest {

    @Test
    fun `all three cancels are cancels - including the app cancel that was missed`() {
        assertEquals(
            UnlockResult.Cancelled, appleUnlockOutcome(-2L, "Canceled by user."),
            "LAErrorUserCancel (-2) must be a cancel: they tapped Cancel.",
        )
        assertEquals(
            UnlockResult.Cancelled, appleUnlockOutcome(-4L, "Canceled by system."),
            "LAErrorSystemCancel (-4) must be a cancel: iOS took the prompt away.",
        )
        assertEquals(
            UnlockResult.Cancelled, appleUnlockOutcome(-9L, "Canceled by application."),
            "LAErrorAppCancel (-9) must be a cancel. This is the one the first iOS mapping " +
                "missed: leaving for the home screen mid-prompt showed an error message for " +
                "something that is not a failure. Android maps all three of its cancels - see " +
                "AppLock.android.kt.",
        )
    }

    @Test
    fun `no error at all is a cancel rather than a blank message`() {
        assertEquals(
            UnlockResult.Cancelled, appleUnlockOutcome(null, null),
            "A false result with no error is nothing having been refused. Showing an empty " +
                "Failed message would be a red line with no words in it.",
        )
    }

    @Test
    fun `a real failure keeps the system's own wording`() {
        val result = appleUnlockOutcome(-8L, "Biometry is locked out.")

        assertEquals(
            UnlockResult.Failed("Biometry is locked out."), result,
            "LAErrorBiometryLockout (-8) is a failure, and the system's sentence says it " +
                "better than ours would.",
        )
    }

    @Test
    fun `an unknown failure with no message still says something short`() {
        val result = appleUnlockOutcome(-1000L, null)

        assertTrue(
            result is UnlockResult.Failed,
            "an unrecognised code is a failure, not a cancel: it is not a decision not to " +
                "answer, and swallowing it would leave the unlock button doing nothing.",
        )
        assertTrue(
            result.message.isNotBlank(),
            "the lock screen has a fixed-height line for this message, so it must not be blank.",
        )
        assertTrue(
            "—" !in result.message,
            "user-visible copy must not contain an em dash. It is '${result.message}'.",
        )
    }

    @Test
    fun `a blank message from the system is replaced rather than shown`() {
        val result = appleUnlockOutcome(-1000L, "   ")

        assertEquals(
            appleUnlockOutcome(-1000L, null), result,
            "whitespace is not a message. A blank one has to fall back to our own words, or " +
                "the lock screen shows a coloured line with nothing in it.",
        )
    }
}
