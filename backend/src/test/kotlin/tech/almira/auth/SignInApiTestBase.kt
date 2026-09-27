package tech.almira.auth

import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.ResponseEntity
import tech.almira.support.ApiTestBase

/** Signing up, signing in again, and confirming it's you by a code, for the sign-in security suites. */
abstract class SignInApiTestBase : ApiTestBase() {

    protected class Account(val phone: String, val token: String, val userId: String)

    protected fun signUp(phone: String = uniquePhone()): Account {
        val login = otpSignIn(phone)
        assertThat(login.statusCode.value()).describedAs(login.body).isEqualTo(200)
        val json = login.json()
        return Account(phone, json.path("accessToken").asText(), json.path("user").path("id").asText())
    }

    @Autowired protected lateinit var redis: StringRedisTemplate

    /** Signing in again within the 30-second resend cooldown is refused; these tests are about what comes after. */
    protected fun otpSignIn(phone: String, deviceName: String? = null): ResponseEntity<String> {
        redis.delete("otp:cooldown:login:$phone")
        val challenge = post("/api/v1/auth/otp/request", body = mapOf("phone" to phone)).json()
        return post(
            "/api/v1/auth/otp/verify",
            body = buildMap {
                put("phone", phone)
                put("code", challenge.path("developmentCode").asText())
                put("requestId", challenge.path("requestId").asText())
                deviceName?.let { put("deviceName", it) }
            },
        )
    }

    protected fun stepUpByCode(account: Account) {
        redis.delete("otp:cooldown:step_up:${account.phone}")
        val token = account.token
        val challenge = post("/api/v1/auth/step-up/request", token).json()
        val verified = post(
            "/api/v1/auth/step-up/verify", token,
            mapOf("code" to challenge.path("developmentCode").asText(), "requestId" to challenge.path("requestId").asText()),
        )
        assertThat(verified.statusCode.value()).describedAs(verified.body).isEqualTo(200)
    }
}
