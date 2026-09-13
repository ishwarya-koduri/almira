package tech.bhrigu.almira.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.web.client.RestTemplate
import tech.bhrigu.almira.support.ApiTestBase
import kotlin.random.Random

/**
 * Which address the per-network sign-in limit counts.
 *
 * It used to be the first X-Forwarded-For entry, from anyone — so an attacker
 * rotating that header had no per-network limit at all. Observed through the
 * Redis counter the limit actually increments, not through a helper that
 * might disagree with it.
 */
@DisplayName("The address the sign-in limit counts")
class ClientAddressTest {

    abstract class Base : ApiTestBase() {
        @Autowired lateinit var redis: StringRedisTemplate

        private val rest = RestTemplate(org.springframework.http.client.JdkClientHttpRequestFactory()).apply {
            errorHandler = object : org.springframework.web.client.ResponseErrorHandler {
                override fun hasError(response: org.springframework.http.client.ClientHttpResponse) = false
                override fun handleError(response: org.springframework.http.client.ClientHttpResponse) = Unit
            }
        }

        fun requestWithForwardedFor(value: String): Int {
            val headers = HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                set("X-Forwarded-For", value)
            }
            val body = mapper.writeValueAsString(mapOf("phone" to uniquePhone()))
            return rest.exchange(url("/api/v1/auth/otp/request"), HttpMethod.POST, HttpEntity(body, headers), String::class.java)
                .statusCode.value()
        }

        fun count(address: String) = redis.opsForValue().get("otp:rate:ip:$address")?.toLong() ?: 0L

        fun documentationAddress() = "203.0.113.${Random.nextInt(1, 255)}"
    }

    /** A caller that is not a proxy: whatever it writes in the header is ignored. */
    @Nested
    @TestPropertySource(properties = ["server.tomcat.remoteip.internal-proxies=192\\.0\\.2\\.254"])
    inner class FromAnUntrustedPeer : Base() {
        @Test
        fun `a forged X-Forwarded-For is not counted, the real peer is`() {
            val before = count("127.0.0.1")
            val forged = (1..3).map { "198.51.100.${Random.nextInt(1, 255)}" }
            forged.forEach { assertThat(requestWithForwardedFor(it)).isEqualTo(200) }
            assertThat(count("127.0.0.1") - before).isEqualTo(3)
            forged.forEach { assertThat(count(it)).describedAs(it).isZero() }
        }
    }

    /** Through a trusted proxy that appends, as nginx and Caddy do. */
    @Nested
    inner class ThroughATrustedProxy : Base() {
        @Test
        fun `the proxy-appended address counts, not the one the client wrote first`() {
            val forgedByClient = "198.51.100.${Random.nextInt(1, 255)}"
            val addedByProxy = documentationAddress()
            val before = count(addedByProxy)
            assertThat(requestWithForwardedFor("$forgedByClient, $addedByProxy")).isEqualTo(200)
            assertThat(count(addedByProxy) - before).isEqualTo(1)
            assertThat(count(forgedByClient)).isZero()
        }
    }
}
