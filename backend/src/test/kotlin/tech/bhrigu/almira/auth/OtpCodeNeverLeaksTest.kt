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
 * Codes are eight digits in this test so that a chance appearance of the same
 * digits in a log line is a one-in-a-hundred-million event rather than a flake.
 */
@DisplayName("The one-time code never leaks")
@Import(OtpCodeNeverLeaksTest.Recording::class)
@TestPropertySource(properties = ["almira.otp.length=8"])
class OtpCodeNeverLeaksTest : ApiTestBase() {

    @TestConfiguration
    class Recording {
        @Bean @Primary
        fun recordingSender() = OtpServiceTest.RecordingSender()
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

    @Test
    fun `no code sent appears in any log event, response body or response header`() {
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        val capture = Capture(Thread.currentThread().name).apply { this.context = context; start() }
        val root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
        val saved = context.loggerList.associateWith { it.level }
        val responses = mutableListOf<ResponseEntity<String>>()
        fun keep(r: ResponseEntity<String>) = r.also { responses += it }

        root.addAppender(capture)
        context.loggerList.forEach { if (it.level != null) it.level = Level.DEBUG }
        root.level = Level.DEBUG
        try {
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
        } finally {
            root.detachAppender(capture)
            saved.forEach { (logger, level) -> logger.level = level }
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

    private fun wrongFor(code: String) = if (code == "00000000") "11111111" else "00000000"

    private fun malformedBodiesCarrying(code: String, phone: String) = listOf(
        """{"phone":"$phone","code":"$code""",          // unterminated
        """{"phone":"$phone","code":$code""" + "x}",   // bad token after the digits
        """{"phone":"$phone","code":["$code"]}""",      // wrong type
        """{"phone":"$phone","code":"${code}a"}""",     // fails validation
        """{"phone":"$phone","code":"$code","requestId":7,,}""",
        """{"phone":"$phone","code":x$code}""",           // an unquoted token Jackson quotes back
    )
}
