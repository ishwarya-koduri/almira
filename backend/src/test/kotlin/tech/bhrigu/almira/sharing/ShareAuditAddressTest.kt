package tech.bhrigu.almira.sharing

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.test.context.TestPropertySource
import org.springframework.web.client.RestTemplate
import tech.bhrigu.almira.support.ApiTestBase
import java.math.BigDecimal
import java.security.MessageDigest
import kotlin.random.Random

/**
 * The address a guest-link view is recorded against — read back from the
 * stored row, since the row is what anyone investigating would trust.
 */
@DisplayName("The address a guest-link view records")
class ShareAuditAddressTest {

    abstract class Base : ApiTestBase() {
        private val rest = RestTemplate(org.springframework.http.client.JdkClientHttpRequestFactory()).apply {
            errorHandler = object : org.springframework.web.client.ResponseErrorHandler {
                override fun hasError(response: org.springframework.http.client.ClientHttpResponse) = false
                override fun handleError(response: org.springframework.http.client.ClientHttpResponse) = Unit
            }
        }

        fun hash(ip: String) = MessageDigest.getInstance("SHA-256").digest(ip.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)

        /** Opens a fresh link with the given header and returns the stored ip_hash. */
        fun recordedHashWhenOpenedWith(forwardedFor: String): String {
            val owner = signIn()
            val householdId = createHousehold(owner).path("id").asText()
            capture(
                owner, householdId, "ppf", "PPF, SBI", BigDecimal("150000"),
                visibility = "household",
                attributes = mapOf("account_no" to "123456789", "yearly_contribution" to 150000),
            )
            val created = post(
                "/api/v1/households/$householdId/shares", owner,
                mapOf("label" to "Audit", "scope" to "tax_pack", "financialYear" to "2026-27", "expiresInDays" to 1),
            ).json()
            assertThat(created.path("url").asText()).describedAs(created.toString()).contains("/share/")
            val token = created.path("url").asText().substringAfterLast('/')
            val headers = HttpHeaders().apply { set("X-Forwarded-For", forwardedFor) }
            val status = rest.exchange(url("/api/v1/share/$token"), HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
                .statusCode.value()
            assertThat(status).isEqualTo(200)
            return db.queryForList(
                "select ip_hash from guest_share_views where share_id = ?::uuid",
                String::class.java, created.path("id").asText(),
            ).single()
        }
    }

    @Nested
    @TestPropertySource(properties = ["server.tomcat.remoteip.internal-proxies=192\\.0\\.2\\.254"])
    inner class FromAnUntrustedPeer : Base() {
        @Test
        fun `a forged X-Forwarded-For is not what the view log records`() {
            val forged = "198.51.100.${Random.nextInt(1, 255)}"
            val recorded = recordedHashWhenOpenedWith(forged)
            assertThat(recorded).isNotEqualTo(hash(forged))
            assertThat(recorded).isEqualTo(hash("127.0.0.1"))
        }
    }

    @Nested
    inner class ThroughATrustedProxy : Base() {
        @Test
        fun `the proxy-appended address is recorded, not the one the guest wrote first`() {
            val forged = "198.51.100.${Random.nextInt(1, 255)}"
            val real = "203.0.113.${Random.nextInt(1, 255)}"
            val recorded = recordedHashWhenOpenedWith("$forged, $real")
            assertThat(recorded).isEqualTo(hash(real))
        }
    }
}
