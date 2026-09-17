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

    /**
     * A number that cannot work says why. The send button is disabled until the
     * address could work, which on its own leaves someone tapping a dead button
     * with no way to find out what is wrong (seen on Android, 2026-09-16).
     */
    @Test
    fun aNumberThatCannotWorkSaysSo() {
        val typing = SignInState(phone = "5432")
        assertNull(typing.addressHint, "nobody is corrected mid-word")

        val wrongStart = SignInState(phone = "5432109876")
        assertEquals("An Indian mobile number starts with 6, 7, 8 or 9.", wrongStart.addressHint)
        assertEquals(false, wrongStart.addressIsPlausible)

        assertNull(SignInState(phone = "9876543210").addressHint, "a number that works says nothing")

        val halfAnAddress = SignInState(channel = SignInChannel.Email, email = "ishwarya@")
        assertEquals("That address looks incomplete.", halfAnAddress.addressHint)
        assertNull(SignInState(channel = SignInChannel.Email, email = "ishwarya@example.com").addressHint)
    }

    /**
     * The owner's ruling (2026-09-17): hint only, never auto-correct while
     * typing, validate at submit. So a number is read however somebody writes
     * it, and nothing they typed is taken away from them.
     */
    @Test
    fun aNumberIsReadHoweverItIsWritten() {
        listOf(
            "9876543210",
            "98765 43210",
            "+91 98765 43210",
            "+91-98765-43210",
            "(98765) 43210",
            "919876543210",
        ).forEach { written ->
            val state = SignInState(phone = written)
            assertEquals("9876543210", state.phoneDigits, written)
            assertEquals(true, state.phoneIsPlausible, written)
            assertNull(state.addressHint, written)
            assertNull(state.addressProblem, written)
            assertEquals("+91 9876543210", state.sentTo, written)
        }
    }

    @Test
    fun theSubmitIsWhatRefuses() {
        // Nothing typed: the button still presses, and this is what it says.
        assertEquals("Enter your phone number.", SignInState().addressProblem)
        // Too few digits is only said at the submit; the hint stays quiet mid-word.
        val half = SignInState(phone = "98765")
        assertNull(half.addressHint)
        assertEquals("An Indian mobile number is ten digits.", half.addressProblem)
        // What the hint says, the submit says too: the words do not change.
        val wrongStart = SignInState(phone = "5432109876")
        assertEquals(wrongStart.addressHint, wrongStart.addressProblem)
        // Eleven digits is too many, and says so both ways.
        val tooMany = SignInState(phone = "98765432109")
        assertEquals("That is more than ten digits.", tooMany.addressHint)
        assertEquals(tooMany.addressHint, tooMany.addressProblem)

        assertNull(SignInState(phone = "+91 98765 43210").addressProblem, "a number that works is not refused")
        assertEquals("Enter your email address.", SignInState(channel = SignInChannel.Email).addressProblem)
    }

    /** Only a verdict on the code rattles the lock; a failure to reach one does not call the key wrong. */
    @Test
    fun aRefusedKeyIsOneTheServerJudged() {
        listOf(400, 401, 410, 422, 429).forEach { assertEquals(UnlockPhase.Refused, UnlockPhase.afterFailure(it), "$it") }
        listOf(0, 500, 502, 503).forEach { assertEquals(UnlockPhase.Interrupted, UnlockPhase.afterFailure(it), "$it") }
    }
}
