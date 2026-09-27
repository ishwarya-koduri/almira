package tech.almira.common

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.client.RestTemplate
import tech.almira.support.ApiTestBase
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * An `Accept` header the server cannot satisfy is a format problem, and must be
 * answered as one.
 *
 * It used to be answered as 401 unauthorized, with a session that was alive and
 * correct: the reply could not be written in the accepted type, the failure
 * escaped the dispatcher, and the container re-dispatched to /error, where the
 * JWT filter does not run. Of every wrong status this is the worst one: a client
 * that treats 401 as "signed out" would drop the session and the local data with
 * it, because of a request header. The same root cause made
 * `GET /api/v1/me/export` answer 500 when asked for as JSON, on the door DPDP
 * makes us keep open.
 *
 * So: 406, one INFO line, no ERROR, and never 401.
 */
@DisplayName("An Accept the server cannot satisfy is 406, never a dead session")
class AcceptHeaderTest : ApiTestBase() {

    private lateinit var token: String

    @BeforeEach
    fun signedIn() {
        token = signIn()
    }

    private val raw = RestTemplate(org.springframework.http.client.JdkClientHttpRequestFactory()).apply {
        errorHandler = object : org.springframework.web.client.ResponseErrorHandler {
            override fun hasError(response: org.springframework.http.client.ClientHttpResponse) = false
            override fun handleError(response: org.springframework.http.client.ClientHttpResponse) = Unit
        }
    }

    private fun send(
        method: HttpMethod,
        path: String,
        accept: String,
        body: String? = null,
    ): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            set(HttpHeaders.ACCEPT, accept)
            setBearerAuth(token)
            body?.let { contentType = MediaType.APPLICATION_JSON }
        }
        return raw.exchange(url(path), method, HttpEntity(body, headers), String::class.java)
    }

    private fun logged(block: () -> ResponseEntity<String>): Pair<ResponseEntity<String>, List<ILoggingEvent>> {
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        val events = ConcurrentLinkedQueue<ILoggingEvent>()
        val appender = object : AppenderBase<ILoggingEvent>() {
            override fun append(e: ILoggingEvent) { events += e }
        }.apply { this.context = context; start() }
        val root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
        root.addAppender(appender)
        try {
            return block() to events.toList()
        } finally {
            root.detachAppender(appender)
        }
    }

    /** The whole answer: 406, the envelope, readable, and nobody logged a fault. */
    private fun notAcceptable(response: ResponseEntity<String>, events: List<ILoggingEvent>) {
        assertThat(response.statusCode.value())
            .describedAs("401 here would sign a live session out over a header: ${response.body}")
            .isNotEqualTo(401)
        assertThat(response.statusCode.value()).describedAs(response.body).isEqualTo(406)
        assertThat(response.errorCode()).describedAs(response.body).isEqualTo("not_acceptable")
        assertThat(response.json().path("error").path("message").asText()).isNotBlank()
        // Sent as JSON whatever was asked for: a status with no readable reason
        // leaves a client guessing at a dead session all over again.
        assertThat(response.headers.contentType?.toString())
            .describedAs("the error is written, not negotiated away")
            .startsWith(MediaType.APPLICATION_JSON_VALUE)
        assertThat(events.filter { it.level == Level.ERROR }.map { "${it.loggerName} ${it.formattedMessage}" })
            .describedAs("a refusal we made on purpose is not a server fault")
            .isEmpty()
        val lines = events.filter { it.loggerName == ApiErrorHandler::class.java.name }
        assertThat(lines.map { "${it.level} ${it.formattedMessage}" })
            .singleElement()
            .satisfies({ assertThat(it).startsWith("INFO ").contains("HttpMediaTypeNotAcceptableException") })
    }

    @Test
    fun `a read with an Accept that excludes JSON is 406, not 401`() {
        listOf("text/html", "application/xml", "foo/bar").forEach { accept ->
            val (response, events) = logged { send(HttpMethod.GET, "/api/v1/me", accept) }
            assertThat(response.statusCode.value()).describedAs("$accept: ${response.body}").isEqualTo(406)
            notAcceptable(response, events)
        }
    }

    @Test
    fun `a write with an Accept that excludes JSON is 406, not 401`() {
        val (response, events) = logged {
            send(
                HttpMethod.POST, "/api/v1/households", "text/html",
                """{"name":"Accept test","defaultVisibility":"private","displayName":"Owner"}""",
            )
        }
        notAcceptable(response, events)
    }

    @Test
    fun `asking a zip endpoint for JSON is 406, not a server fault`() {
        val (response, events) = logged { send(HttpMethod.GET, "/api/v1/me/export", "application/json") }
        notAcceptable(response, events)
    }

    @Test
    fun `an Accept the endpoint can satisfy is served as before`() {
        listOf("application/json", "*/*", "text/html, application/json;q=0.9", "application/*").forEach { accept ->
            val response = send(HttpMethod.GET, "/api/v1/me", accept)
            assertThat(response.statusCode.value()).describedAs("$accept: ${response.body}").isEqualTo(200)
        }
    }

    @Test
    fun `no Accept header at all is served as before`() {
        val headers = HttpHeaders().apply { setBearerAuth(token) }
        val response = raw.exchange(
            url("/api/v1/me"), HttpMethod.GET, HttpEntity<String>(headers), String::class.java,
        )
        assertThat(response.statusCode.value()).describedAs(response.body).isEqualTo(200)
    }

    /**
     * The status has to be the format's, not the session's, even when the
     * session really is the problem: an expired token with a strange Accept is
     * still 401, and the client is right to act on it.
     */
    @Test
    fun `a real 401 still reads as 401 whatever was asked for`() {
        val headers = HttpHeaders().apply {
            set(HttpHeaders.ACCEPT, "text/html")
            setBearerAuth("not-a-token")
        }
        val response = raw.exchange(
            url("/api/v1/me"), HttpMethod.GET, HttpEntity<String>(headers), String::class.java,
        )
        assertThat(response.statusCode.value()).describedAs(response.body).isEqualTo(401)
        assertThat(response.errorCode()).isEqualTo("unauthorized")
    }
}
