package tech.bhrigu.almira.shared.signin

import tech.bhrigu.almira.shared.api.OtpChallenge
import tech.bhrigu.almira.shared.api.OtpDeliveryStatus
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

    /** GET /auth/otp/email/delivery/{requestId}, as OtpService.emailDelivery answers it. */
    @Test
    fun anEmailThatCouldNotBeSentIsSaidPlainly() {
        fun status(state: String, failure: String? = null, message: String? = null) =
            OtpDeliveryStatus("r", state, failure, message, if (state == "failed" || state == "delayed") 0 else null)

        assertEquals(EmailDelivery.Pending, EmailDelivery.of(status("sending")))
        assertEquals(EmailDelivery.Sent, EmailDelivery.of(status("sent")))
        for (failure in listOf("otp_delivery_failed", "otp_provider_unavailable", "otp_service_unavailable")) {
            val said = EmailDelivery.of(status("failed", failure, "We couldn't send the code. Because $failure."))
            assertEquals(EmailDelivery.Failed("We couldn't send the code. Because $failure."), said)
        }
        assertEquals(
            EmailDelivery.Failed("We couldn't send the code."),
            EmailDelivery.of(status("failed", "otp_something_new", "")),
            "a failure with no readable sentence still says the code did not go",
        )
        assertTrue(EmailDelivery.of(status("delayed", message = "late")) is EmailDelivery.Delayed)
        assertEquals(EmailDelivery.Unknown, EmailDelivery.of(null))
        assertEquals(EmailDelivery.Unknown, EmailDelivery.of(status("queued")))
        // Once asking stops, only "sent" may say the code went.
        val late = EmailDelivery.Delayed(EmailDelivery.DELAYED)
        assertEquals(late, EmailDelivery.whenAskingStops(EmailDelivery.Pending))
        assertEquals(late, EmailDelivery.whenAskingStops(EmailDelivery.Unknown))
        assertEquals(late, EmailDelivery.whenAskingStops(null))
        assertEquals(EmailDelivery.Sent, EmailDelivery.whenAskingStops(EmailDelivery.Sent))
        assertEquals(EmailDelivery.Failed("x"), EmailDelivery.whenAskingStops(EmailDelivery.Failed("x")))

        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val decoded = json.decodeFromString(
            OtpDeliveryStatus.serializer(),
            """{"requestId":"r","status":"failed","failure":"otp_provider_unavailable","message":"We couldn't send the code. x","resendAfterSeconds":0}""",
        )
        assertEquals(EmailDelivery.Failed("We couldn't send the code. x"), EmailDelivery.of(decoded))
        assertNull(json.decodeFromString(OtpDeliveryStatus.serializer(), """{"requestId":"r","status":"sent"}""").failure)
    }
}
