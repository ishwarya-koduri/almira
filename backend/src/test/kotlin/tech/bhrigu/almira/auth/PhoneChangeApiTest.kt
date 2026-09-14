package tech.bhrigu.almira.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Changing the number on an account (T-05): only from a signed-in session that
 * has just confirmed it's you, only to a number whose texts you receive, and
 * with every device told.
 */
@DisplayName("Changing the phone number")
class PhoneChangeApiTest : SignInApiTestBase() {

    private fun requestChange(account: Account, phone: String) =
        redis.delete("otp:cooldown:phone_change:$phone").let {
            post("/api/v1/auth/phone/request", account.token, mapOf("phone" to phone))
        }

    @Test
    fun `needs a session - and one that has just confirmed it's you`() {
        val anonymous = post("/api/v1/auth/phone/request", body = mapOf("phone" to uniquePhone()))
        assertThat(anonymous.statusCode.value()).isEqualTo(401)

        val account = signUp()
        val notConfirmed = requestChange(account, uniquePhone())
        assertThat(notConfirmed.statusCode.value()).isEqualTo(403)
        assertThat(notConfirmed.errorCode()).isEqualTo("step_up_required")
        assertThat(notConfirmed.json().path("error").path("message").asText()).contains("changing your phone number")

        val verifyWithout = post(
            "/api/v1/auth/phone/verify", account.token,
            mapOf("phone" to uniquePhone(), "code" to "123456"),
        )
        assertThat(verifyWithout.statusCode.value()).isEqualTo(403)
    }

    @Test
    fun `a code to the new number moves the account to it, and the old number no longer opens it`() {
        val account = signUp()
        stepUpByCode(account)
        val newPhone = uniquePhone()

        val challenge = requestChange(account, newPhone)
        assertThat(challenge.statusCode.value()).describedAs(challenge.body).isEqualTo(200)
        val wrong = post(
            "/api/v1/auth/phone/verify", account.token,
            mapOf("phone" to newPhone, "code" to "000000", "requestId" to challenge.json().path("requestId").asText()),
        )
        assertThat(wrong.errorCode()).isEqualTo("otp_invalid")

        val changed = post(
            "/api/v1/auth/phone/verify", account.token,
            mapOf(
                "phone" to newPhone,
                "code" to challenge.json().path("developmentCode").asText(),
                "requestId" to challenge.json().path("requestId").asText(),
            ),
        )
        assertThat(changed.statusCode.value()).describedAs(changed.body).isEqualTo(200)
        assertThat(changed.json().path("phone").asText()).isEqualTo(newPhone)
        assertThat(get("/api/v1/me", account.token).json().path("phone").asText()).isEqualTo(newPhone)

        // The new number signs in to this account; the old one is a stranger's now.
        val viaNew = otpSignIn(newPhone).json()
        assertThat(viaNew.path("user").path("id").asText()).isEqualTo(account.userId)
        val viaOld = otpSignIn(account.phone).json()
        assertThat(viaOld.path("isNewUser").asBoolean()).isTrue()
        assertThat(viaOld.path("user").path("id").asText()).isNotEqualTo(account.userId)

        // Audited with masked numbers only, the elevation spent, and the devices told.
        val diff = db.queryForObject(
            "select diff::text from activity_log where actor_user_id = ?::uuid and action = 'auth.phone_changed'",
            String::class.java, account.userId,
        )!!
        assertThat(diff).contains(newPhone.takeLast(4)).doesNotContain(newPhone, account.phone)
        assertThat(requestChange(account, uniquePhone()).errorCode()).isEqualTo("step_up_required")
        assertThat(
            db.queryForObject(
                "select count(*) from outbound_messages where user_id = ?::uuid and template = 'auth.phone_changed' and channel = 'in_app'",
                Int::class.java, account.userId,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `a number that belongs to another account is refused, and only after its code is proven`() {
        val taken = signUp()
        val account = signUp()
        stepUpByCode(account)

        val challenge = requestChange(account, taken.phone)
        assertThat(challenge.statusCode.value()).describedAs("asking reveals nothing").isEqualTo(200)
        val refused = post(
            "/api/v1/auth/phone/verify", account.token,
            mapOf(
                "phone" to taken.phone,
                "code" to challenge.json().path("developmentCode").asText(),
                "requestId" to challenge.json().path("requestId").asText(),
            ),
        )
        assertThat(refused.statusCode.value()).describedAs(refused.body).isEqualTo(409)
        assertThat(refused.errorCode()).isEqualTo("phone_in_use")
        assertThat(get("/api/v1/me", account.token).json().path("phone").asText()).isEqualTo(account.phone)
    }

    @Test
    fun `the number already on the account is not a change`() {
        val account = signUp()
        stepUpByCode(account)
        assertThat(requestChange(account, account.phone).errorCode()).isEqualTo("phone_unchanged")
    }

    @Test
    fun `an account with an authenticator can confirm the change with it instead of the old number`() {
        val account = signUp()
        stepUpByCode(account)
        val secret = Base32.decode(post("/api/v1/auth/authenticator", account.token).json().path("secret").asText())!!
        val step = Totp.step(java.time.Instant.now())
        val recovery = post("/api/v1/auth/authenticator/confirm", account.token, mapOf("code" to Totp.code(secret, step)))
            .json().path("recoveryCodes").first().asText()

        // A new session on another device: nothing on it has been confirmed by a text.
        val pending = otpSignIn(account.phone).json().path("error").path("details").path("secondFactorToken").asText()
        val fresh = post(
            "/api/v1/auth/second-factor/authenticator",
            body = mapOf("secondFactorToken" to pending, "code" to Totp.code(secret, step + 1)),
        ).json().path("accessToken").asText()
        val onFresh = Account(account.phone, fresh, account.userId)
        assertThat(requestChange(onFresh, uniquePhone()).errorCode()).isEqualTo("step_up_required")

        val elevated = post("/api/v1/auth/step-up/recovery-code", fresh, mapOf("code" to recovery))
        assertThat(elevated.statusCode.value()).describedAs(elevated.body).isEqualTo(200)
        assertThat(requestChange(onFresh, uniquePhone()).statusCode.value()).isEqualTo(200)
    }
}
