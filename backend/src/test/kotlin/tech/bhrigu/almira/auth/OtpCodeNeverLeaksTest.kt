package tech.bhrigu.almira.auth

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.AppenderBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.test.context.TestPropertySource
import org.springframework.web.client.RestTemplate
import tech.bhrigu.almira.support.ApiTestBase
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The code never reaches a log, a trace or an error payload.
 *
 * Driven over real HTTP through every path a code travels — request, resend
 * refused, wrong code, malformed bodies that CONTAIN the real code, the right
 * code, the code reused, and the same again for step-up — with every logger in
 * the application raised to DEBUG for the duration, because "not at INFO" is
 * not the claim. Then every captured log event (message, arguments, MDC, key
 * values and the whole exception chain) and every response body and header is
 * searched for every code that was actually sent.
 *
 * The sender here remembers codes and never echoes them, which is what a real
 * SMS sender is. The development echo is a separate, deliberate channel with
 * its own gate, tested in OtpServiceTest.
 *
 * Email is walked the same way in its own test, through the real email sender
 * and the sandbox email channel (which logs), for an allowlisted address and a
 * decoy, and outbound_messages is searched as well.
 *
 * Codes are eight digits in this test so that a chance appearance of the same
 * digits in a log line is a one-in-a-hundred-million event rather than a flake.
 */
@DisplayName("The one-time code never leaks")
@Import(OtpCodeNeverLeaksTest.Recording::class)
@TestPropertySource(properties = ["almira.otp.length=8", "almira.auth.sign-in-channels=phone,email"])
class OtpCodeNeverLeaksTest : ApiTestBase() {

    @TestConfiguration
    class Recording {
        @Bean @Primary
        fun recordingSender() = OtpServiceTest.RecordingSender()

        /**
         * Remembers each email code and then hands it to the REAL email sender,
         * so the code travels the same way it will in production — through
         * ChannelEmailOtpSender, ProviderCalls and the sandbox email channel,
         * which logs — and any of them writing it down is caught.
         */
        @Bean @Primary
        fun recordingEmailSender(real: ChannelEmailOtpSender) = object : EmailOtpSender {
            override val available get() = real.available
            override fun send(email: String, code: String) {
                real.send(email, code)
                // After, so a test that has seen the code knows the sandbox has logged.
                emailCodes += email to code
            }
        }
    }

    @Autowired private lateinit var sender: OtpServiceTest.RecordingSender

    /** Thread-safe: Tomcat's request threads log into it concurrently. */
    private class Capture(private val testThread: String) : AppenderBase<ILoggingEvent>() {
        val texts = ConcurrentLinkedQueue<String>()
        override fun append(e: ILoggingEvent) {
            // The test's own HTTP client logs what IT sends, on the test thread.
            // That is this harness talking, not the server; everything the
            // server logs happens on its request threads and is kept.
            if (e.threadName == testThread && e.loggerName.startsWith("org.springframework.web.client.")) return
            val parts = mutableListOf(e.level.toString(), e.loggerName, e.message ?: "", e.formattedMessage ?: "")
            e.argumentArray?.forEach { parts += it.toString() }
            e.mdcPropertyMap?.forEach { (k, v) -> parts += "$k=$v" }
            e.keyValuePairs?.forEach { parts += "${it.key}=${it.value}" }
            var t: IThrowableProxy? = e.throwableProxy
            while (t != null) {
                parts += "${t.className}: ${t.message}"
                t.suppressed?.forEach { parts += "${it.className}: ${it.message}" }
                t = t.cause
            }
            texts += parts.joinToString(" | ")
        }
    }

    private val raw = RestTemplate(org.springframework.http.client.JdkClientHttpRequestFactory()).apply {
        errorHandler = object : org.springframework.web.client.ResponseErrorHandler {
            override fun hasError(response: org.springframework.http.client.ClientHttpResponse) = false
            override fun handleError(response: org.springframework.http.client.ClientHttpResponse) = Unit
        }
    }

    /** A body exactly as typed, malformed or not. */
    private fun postRaw(path: String, body: String, token: String? = null): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            token?.let { setBearerAuth(it) }
        }
        return raw.exchange(url(path), HttpMethod.POST, HttpEntity(body, headers), String::class.java)
    }

    /** Every logger at DEBUG and every event kept, for the duration of [block]. */
    private fun captured(block: () -> Unit): Capture {
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        val capture = Capture(Thread.currentThread().name).apply { this.context = context; start() }
        val root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
        val saved = context.loggerList.associateWith { it.level }
        root.addAppender(capture)
        context.loggerList.forEach { if (it.level != null) it.level = Level.DEBUG }
        root.level = Level.DEBUG
        try {
            block()
        } finally {
            root.detachAppender(capture)
            saved.forEach { (logger, level) -> logger.level = level }
        }
        return capture
    }

    @Test
    fun `no code sent appears in any log event, response body or response header`() {
        val responses = mutableListOf<ResponseEntity<String>>()
        fun keep(r: ResponseEntity<String>) = r.also { responses += it }

        val capture = captured {
            val phone = uniquePhone()

            // Sign-in: request, resend refused, wrong, malformed, right, reused.
            val challenge = keep(post("/api/v1/auth/otp/request", body = mapOf("phone" to phone))).json()
            assertThat(challenge.path("requestId").asText()).isNotBlank()
            val code = sender.lastCode()
            assertThat(code).hasSize(8)
            keep(post("/api/v1/auth/otp/request", body = mapOf("phone" to phone)))
            keep(post("/api/v1/auth/otp/verify", body = mapOf("phone" to phone, "code" to wrongFor(code))))
            malformedBodiesCarrying(code, phone).forEach { keep(postRaw("/api/v1/auth/otp/verify", it)) }
            val login = keep(post("/api/v1/auth/otp/verify", body = mapOf("phone" to phone, "code" to code)))
            assertThat(login.statusCode.value()).describedAs(login.body).isEqualTo(200)
            val token = login.json().path("accessToken").asText()
            keep(post("/api/v1/auth/otp/verify", body = mapOf("phone" to phone, "code" to code)))

            // Step-up: the same journey behind a session.
            keep(post("/api/v1/auth/step-up/request", token))
            val stepUp = sender.lastCode()
            assertThat(stepUp).isNotEqualTo(code)
            keep(post("/api/v1/auth/step-up/verify", token, mapOf("code" to wrongFor(stepUp))))
            listOf(
                """{"code":"$stepUp""",
                """{"code":$stepUp""" + "x}",
                """{"code":["$stepUp"]}""",
                """{"code":"${stepUp}a"}""",
                """{"code":x$stepUp}""",
            ).forEach { keep(postRaw("/api/v1/auth/step-up/verify", it, token)) }
            val elevated = keep(post("/api/v1/auth/step-up/verify", token, mapOf("code" to stepUp)))
            assertThat(elevated.statusCode.value()).describedAs(elevated.body).isEqualTo(200)
            keep(post("/api/v1/auth/step-up/verify", token, mapOf("code" to stepUp)))
        }

        val codes = sender.sent.map { it.second }
        assertThat(codes).hasSize(2)
        // Sanity on the harness itself: DEBUG was really captured, and the
        // requests really went through the logged path, so "found nothing"
        // cannot mean "looked at nothing".
        assertThat(capture.texts).anyMatch { it.startsWith("DEBUG | org.springframework.web") }
        assertThat(capture.texts.size).isGreaterThan(20)
        // ...and the paths that used to leak were really walked: the unreadable
        // bodies reached the catch-all's ERROR log, and the validation failures
        // reached the exception resolver's DEBUG line. A change that stopped
        // them getting that far would otherwise pass this test by not trying.
        assertThat(capture.texts.count { it.startsWith("ERROR") && "HttpMessageNotReadableException" in it })
            .isEqualTo(9)
        assertThat(capture.texts.count { it.startsWith("DEBUG") && "Resolved [org.springframework.web.bind.MethodArgumentNotValidException" in it })
            .isEqualTo(2)
        assertThat(responses.map { it.statusCode.value() }).containsExactly(
            200, 429, 400, 500, 500, 500, 400, 500, 500, 200, 400,
            200, 400, 500, 500, 500, 400, 500, 200, 400,
        )

        for (c in codes) {
            val inLogs = capture.texts.filter { c in it }
            assertThat(inLogs).describedAs("log events containing a real code").isEmpty()
            responses.forEachIndexed { i, r ->
                assertThat(r.body.orEmpty()).describedAs("response #$i body").doesNotContain(c)
                r.headers.forEach { (name, values) ->
                    assertThat(values.joinToString()).describedAs("response #$i header $name").doesNotContain(c)
                }
            }
        }
    }

    @Test
    fun `there is no tracing on the classpath for a code to be recorded into`() {
        // A span attribute or an HTTP exchange recorder would be a place the
        // request body goes that no log appender sees. None is present; if one
        // is ever added, this fails and the leak test above needs extending to
        // cover it before it is trusted.
        listOf(
            "io.micrometer.tracing.Tracer",
            "io.opentelemetry.api.OpenTelemetry",
            "brave.Tracer",
            "org.springframework.boot.actuate.web.exchanges.HttpExchangeRepository",
            "io.sentry.Sentry",
        ).forEach { name ->
            assertThat(runCatching { Class.forName(name) }.isFailure).describedAs(name).isTrue()
        }
    }

    /**
     * The same journey by email, for an allowlisted address and one that is
     * not: request, resend refused, a decoy, wrong, malformed bodies carrying
     * the real code, right, reused — then step-up by email for the account that
     * creates. The code goes through the real email sender and the sandbox email
     * channel, which logs, and is additionally looked for in outbound_messages,
     * the table a notification would be recorded in.
     */
    @Test
    fun `no email code appears in any log event, response body, response header or outbound message`() {
        val responses = mutableListOf<ResponseEntity<String>>()
        fun keep(r: ResponseEntity<String>) = r.also { responses += it }
        val outsider = "not.listed.${System.nanoTime()}@example.test"

        val capture = captured {
            keep(post("/api/v1/auth/otp/email/request", body = mapOf("email" to "  ${listed.uppercase()} ")))
            val code = awaitEmailCode(1)
            assertThat(code).hasSize(8)
            keep(post("/api/v1/auth/otp/email/request", body = mapOf("email" to listed)))
            keep(post("/api/v1/auth/otp/email/request", body = mapOf("email" to outsider)))
            keep(post("/api/v1/auth/otp/email/verify", body = mapOf("email" to listed, "code" to wrongFor(code))))
            malformedBodiesCarrying(code, listed, "email").forEach { keep(postRaw("/api/v1/auth/otp/email/verify", it)) }
            val login = keep(post("/api/v1/auth/otp/email/verify", body = mapOf("email" to listed, "code" to code)))
            assertThat(login.statusCode.value()).describedAs(login.body).isEqualTo(200)
            val token = login.json().path("accessToken").asText()
            keep(post("/api/v1/auth/otp/email/verify", body = mapOf("email" to listed, "code" to code)))

            val challenge = keep(post("/api/v1/auth/step-up/request", token))
            assertThat(challenge.json().path("channel").asText()).describedAs(challenge.body).isEqualTo("email")
            val stepUp = awaitEmailCode(2)
            assertThat(stepUp).isNotEqualTo(code)
            keep(post("/api/v1/auth/step-up/verify", token, mapOf("code" to wrongFor(stepUp))))
            listOf(
                """{"code":"$stepUp""",
                """{"code":$stepUp""" + "x}",
                """{"code":["$stepUp"]}""",
                """{"code":"${stepUp}a"}""",
                """{"code":x$stepUp}""",
            ).forEach { keep(postRaw("/api/v1/auth/step-up/verify", it, token)) }
            val elevated = keep(post("/api/v1/auth/step-up/verify", token, mapOf("code" to stepUp)))
            assertThat(elevated.statusCode.value()).describedAs(elevated.body).isEqualTo(200)
            keep(post("/api/v1/auth/step-up/verify", token, mapOf("code" to stepUp)))
        }

        val codes = emailCodes.map { it.second }
        assertThat(emailCodes.map { it.first }).containsExactly(listed, listed)
        // The harness really walked the paths that log: the sandbox channel spoke
        // for both codes, the unreadable bodies reached the catch-all, the
        // validation failures reached the resolver.
        assertThat(capture.texts.count { "sandbox email: template=otp_email" in it }).isEqualTo(2)
        assertThat(capture.texts.count { it.startsWith("ERROR") && "HttpMessageNotReadableException" in it })
            .isEqualTo(9)
        assertThat(capture.texts.count { it.startsWith("DEBUG") && "Resolved [org.springframework.web.bind.MethodArgumentNotValidException" in it })
            .isEqualTo(2)
        assertThat(responses.map { it.statusCode.value() }).containsExactly(
            200, 429, 200, 400, 500, 500, 500, 400, 500, 500, 200, 400,
            200, 400, 500, 500, 500, 400, 500, 200, 400,
        )

        for (c in codes) {
            assertThat(capture.texts.filter { c in it }).describedAs("log events containing a real email code").isEmpty()
            responses.forEachIndexed { i, r ->
                assertThat(r.body.orEmpty()).describedAs("response #$i body").doesNotContain(c)
                r.headers.forEach { (name, values) ->
                    assertThat(values.joinToString()).describedAs("response #$i header $name").doesNotContain(c)
                }
            }
            assertThat(db.queryForObject("select count(*) from outbound_messages o where o::text like ?", Long::class.java, "%$c%"))
                .describedAs("outbound_messages rows containing a real email code").isZero()
            assertThat(db.queryForObject("select count(*) from outbound_message_bodies b where b::text like ?", Long::class.java, "%$c%"))
                .describedAs("queued message bodies containing a real email code").isZero()
        }
        assertThat(db.queryForObject("select count(*) from outbound_messages where template = 'otp_email'", Long::class.java))
            .describedAs("a sign-in code is not a notification and is not recorded as one").isZero()
    }

    private fun awaitEmailCode(nth: Int): String {
        val deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos()
        while (emailCodes.size < nth && System.nanoTime() < deadline) Thread.sleep(10)
        check(emailCodes.size >= nth) { "email code #$nth was never sent" }
        return emailCodes[nth - 1].second
    }

    private fun wrongFor(code: String) = if (code == "00000000") "11111111" else "00000000"

    private fun malformedBodiesCarrying(code: String, address: String, field: String = "phone") = listOf(
        """{"$field":"$address","code":"$code""",          // unterminated
        """{"$field":"$address","code":$code""" + "x}",   // bad token after the digits
        """{"$field":"$address","code":["$code"]}""",      // wrong type
        """{"$field":"$address","code":"${code}a"}""",     // fails validation
        """{"$field":"$address","code":"$code","requestId":7,,}""",
        """{"$field":"$address","code":x$code}""",           // an unquoted token Jackson quotes back
    )

    companion object {
        val emailCodes = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
        private val listed = "leak.test.${System.nanoTime()}@example.test"

        @JvmStatic
        @org.springframework.test.context.DynamicPropertySource
        fun allowlist(registry: org.springframework.test.context.DynamicPropertyRegistry) {
            registry.add("almira.auth.email-allowlist") { listed }
        }
    }
}
