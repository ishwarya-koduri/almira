package tech.bhrigu.almira.shared.signin

import tech.bhrigu.almira.shared.api.OtpChallenge
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sign-in state's own decisions — which channels the server's answer
 * means, when an address is worth sending, what the code step says — with no
 * network and no Compose, so they hold identically on Android and iOS.
 */
class SignInStateTest {

    @Test
    fun theServersListIsReadInItsOrderAndAnythingUnknownOrEmptyMeansPhone() {
        assertEquals(listOf(SignInChannel.Email), SignInChannel.fromServer(listOf("email")))
        assertEquals(
            listOf(SignInChannel.Phone, SignInChannel.Email),
            SignInChannel.fromServer(listOf("phone", "email", "carrier-pigeon", "email")),
        )
        assertEquals(listOf(SignInChannel.Phone), SignInChannel.fromServer(emptyList()))
        assertEquals(listOf(SignInChannel.Phone), SignInChannel.fromServer(listOf("passkey")))
    }

    @Test
    fun anEmailOnlyServerOffersNoSwitch() {
        val alpha = SignInState(channel = SignInChannel.Email, channels = listOf(SignInChannel.Email))
        assertNull(alpha.otherChannel)
        val both = SignInState(channels = listOf(SignInChannel.Phone, SignInChannel.Email))
        assertEquals(SignInChannel.Email, both.otherChannel)
    }

    @Test
    fun theSendButtonFollowsTheChannelOnScreen() {
        val email = SignInState(channel = SignInChannel.Email, phone = "9876543210", email = "asha")
        assertFalse(email.addressIsPlausible, "a valid phone must not enable sending on the email step")
        assertTrue(email.copy(email = " Asha.Rao+alpha@Example.com ").addressIsPlausible)
        listOf("asha@", "@example.com", "asha@example", "a sha@example.com", "a@b@c.com").forEach {
            assertFalse(email.copy(email = it).emailIsPlausible, it)
        }
        assertTrue(SignInState(phone = "9876543210").addressIsPlausible)
    }

    @Test
    fun theCodeStepSaysWhereTheCodeWent() {
        assertEquals("+91 9876543210", SignInState(phone = "9876543210").sentTo)
        assertEquals(
            "asha@example.com",
            SignInState(channel = SignInChannel.Email, email = "asha@example.com ").sentTo,
        )
    }

    /** `channel` arrived after v1 froze: an older server's challenge must still decode. */
    @Test
    fun aChallengeWithoutAChannelStillDecodes() {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val old = json.decodeFromString<OtpChallenge>("""{"requestId":"r","expiresInSeconds":300,"resendAfterSeconds":30}""")
        assertNull(old.channel)
        val new = json.decodeFromString<OtpChallenge>(
            """{"requestId":"r","expiresInSeconds":300,"resendAfterSeconds":30,"channel":"email"}""",
        )
        assertEquals("email", new.channel)
    }
}
