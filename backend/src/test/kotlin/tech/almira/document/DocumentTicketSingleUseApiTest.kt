package tech.almira.document

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.springframework.test.annotation.DirtiesContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.io.ByteArrayResource
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestTemplate
import tech.almira.support.ApiTestBase
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A download ticket is spent by exactly one request, however they race.
 *
 * The race is made certain rather than hoped for: every plain GET of a ticket
 * key waits at a barrier for a second one, so two redemptions both read the
 * ticket before either can delete it — which is precisely the window a
 * read-then-delete leaves open. A redemption that takes and deletes the ticket
 * in one command never reaches the barrier.
 */
// A context of its own (a spy or a replaced bean), closed after this class so
// its connection pools do not stay open beside every cached context.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("A download ticket is single-use under concurrency")
@Import(DocumentTicketSingleUseApiTest.RacingRedis::class)
class DocumentTicketSingleUseApiTest : ApiTestBase() {

    @TestConfiguration
    class RacingRedis {
        @Bean
        fun stringRedisTemplate(factory: RedisConnectionFactory): StringRedisTemplate =
            object : StringRedisTemplate(factory) {
                private val barrier = CyclicBarrier(2)

                @Suppress("UNCHECKED_CAST")
                override fun opsForValue(): ValueOperations<String, String> {
                    val real = super.opsForValue()
                    return Proxy.newProxyInstance(
                        ValueOperations::class.java.classLoader, arrayOf(ValueOperations::class.java),
                    ) { _, method, args ->
                        val key = args?.firstOrNull() as? String
                        if (method.name == "get" && args.size == 1 && key?.startsWith("document:ticket:") == true) {
                            runCatching { barrier.await(3, TimeUnit.SECONDS) }
                        }
                        try {
                            method.invoke(real, *(args ?: emptyArray()))
                        } catch (e: InvocationTargetException) {
                            throw e.targetException
                        }
                    } as ValueOperations<String, String>
                }
            }
    }

    private val multipart = RestTemplate().apply {
        errorHandler = object : org.springframework.web.client.ResponseErrorHandler {
            override fun hasError(r: org.springframework.http.client.ClientHttpResponse) = false
            override fun handleError(r: org.springframework.http.client.ClientHttpResponse) = Unit
        }
    }

    @Test
    fun `two requests racing with one ticket - exactly one is served`() {
        val owner = signIn()
        val householdId = createHousehold(owner, "Koduri", "private", "Ishwarya").path("id").asText()
        val uploaded = multipart.exchange(
            url("/api/v1/households/$householdId/documents?docType=policy"),
            HttpMethod.POST,
            HttpEntity(
                LinkedMultiValueMap<String, Any>().apply {
                    add("file", object : ByteArrayResource("POLICY 5567123".toByteArray()) {
                        override fun getFilename() = "policy.pdf"
                    })
                },
                HttpHeaders().apply {
                    contentType = MediaType.MULTIPART_FORM_DATA
                    setBearerAuth(owner)
                },
            ),
            String::class.java,
        )
        val id = mapper.readTree(uploaded.body).path("id").asText()
        val challenge = post("/api/v1/auth/step-up/request", owner).json()
        post(
            "/api/v1/auth/step-up/verify", owner,
            mapOf("code" to challenge.path("developmentCode").asText(), "requestId" to challenge.path("requestId").asText()),
        )
        val token = post("/api/v1/households/$householdId/documents/$id/access", owner).json().path("token").asText()

        val pool = Executors.newFixedThreadPool(2)
        val answers = try {
            List(2) { pool.submit<HttpStatus> { HttpStatus.valueOf(get("/api/v1/documents/download?token=$token").statusCode.value()) } }
                .map { it.get(20, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertThat(answers).describedAs("answers to two concurrent redemptions of one ticket")
            .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.NOT_FOUND)
    }
}
