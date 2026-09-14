package tech.bhrigu.almira.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tech.bhrigu.almira.support.ApiTestBase

/**
 * A refresh token presented twice ends the whole session, and the ending is on
 * the record.
 *
 * The audit half was the part that did not hold: `auth.refresh_reuse_detected`
 * was written in the refresh's own transaction, which the refusal that follows
 * rolls back (known-issues 14, "The refresh-reuse audit row"). Watched failing
 * on the audit assertion before the row moved into the revocation's transaction.
 */
@DisplayName("Refresh token reuse")
class RefreshReuseApiTest : ApiTestBase() {

    @Test
    fun `a rotated refresh token used again ends the session, and the reuse is audited`() {
        val phone = uniquePhone()
        val challenge = post("/api/v1/auth/otp/request", body = mapOf("phone" to phone)).json()
        val login = post(
            "/api/v1/auth/otp/verify",
            body = mapOf("phone" to phone, "code" to challenge.path("developmentCode").asText()),
        ).json()
        val first = login.path("refreshToken").asText()
        val userId = login.path("user").path("id").asText()

        val rotated = post("/api/v1/auth/refresh", body = mapOf("refreshToken" to first))
        assertThat(rotated.statusCode.value()).describedAs(rotated.body).isEqualTo(200)
        val second = rotated.json().path("refreshToken").asText()

        val reused = post("/api/v1/auth/refresh", body = mapOf("refreshToken" to first))
        assertThat(reused.statusCode.value()).isEqualTo(401)

        // The legitimate holder's newer token is burned with it.
        assertThat(post("/api/v1/auth/refresh", body = mapOf("refreshToken" to second)).statusCode.value())
            .isEqualTo(401)
        assertThat(
            db.queryForObject(
                "select revoked_reason from user_sessions where user_id = ?::uuid", String::class.java, userId,
            ),
        ).isEqualTo("refresh_token_reuse")
        assertThat(
            db.queryForObject(
                "select count(*) from activity_log where actor_user_id = ?::uuid and action = 'auth.refresh_reuse_detected'",
                Int::class.java, userId,
            ),
        ).describedAs("the reuse is on the record, not rolled back with the refusal").isEqualTo(1)
    }
}
