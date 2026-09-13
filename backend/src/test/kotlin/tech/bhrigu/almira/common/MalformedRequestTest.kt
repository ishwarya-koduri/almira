package tech.bhrigu.almira.common

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
import tech.bhrigu.almira.support.ApiTestBase
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A request the server cannot read is the caller's mistake, and is answered as
 * one: a 4xx with a plain code, the error envelope every other refusal uses,
 * one INFO line naming the exception type, and nothing the caller sent repeated
 * back — not in the response, not in a log line at INFO or above.
 *
 * Each case carries a marker in the part that is wrong, so "the value was not
 * echoed" is looked for rather than assumed. Before this, every one of these
 * answered 500 internal_error and wrote an ERROR (docs/api/README.md, changelog
 * 2026-09-13).
 */
@DisplayName("A request the server cannot read is a 4xx, not a server fault")
class MalformedRequestTest : ApiTestBase() {

    private lateinit var token: String
    private lateinit var householdId: String

    @BeforeEach
    fun household() {
        token = signIn()
        householdId = createHousehold(token).path("id").asText()
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
        body: String?,
        type: MediaType? = MediaType.APPLICATION_JSON,
        auth: Boolean = true,
    ): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            type?.let { contentType = it }
            if (auth) setBearerAuth(token)
        }
        return raw.exchange(url(path), method, HttpEntity(body, headers), String::class.java)
    }

    private class Capture : AppenderBase<ILoggingEvent>() {
        val events = ConcurrentLinkedQueue<ILoggingEvent>()
        override fun append(e: ILoggingEvent) { events += e }
        fun text(e: ILoggingEvent): String {
            val parts = mutableListOf(e.loggerName, e.formattedMessage ?: "")
            var t = e.throwableProxy
            while (t != null) { parts += "${t.className}: ${t.message}"; t = t.cause }
            return parts.joinToString(" | ")
        }
    }

    private fun captured(block: () -> ResponseEntity<String>): Pair<ResponseEntity<String>, Capture> {
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        val capture = Capture().apply { this.context = context; start() }
        val root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
        root.addAppender(capture)
        try {
            return block() to capture
        } finally {
            root.detachAppender(capture)
        }
    }

    /**
     * The common half of every case: the status and code, the envelope, no
     * ERROR from anyone, the one INFO line naming [exception], and [marker]
     * nowhere in the response or in any log event at INFO or above.
     */
    private fun refused(
        status: Int,
        code: String,
        exception: String,
        marker: String,
        request: () -> ResponseEntity<String>,
    ): ResponseEntity<String> {
        val (response, capture) = captured(request)
        assertThat(response.statusCode.value()).describedAs(response.body).isEqualTo(status)
        assertThat(response.errorCode()).describedAs(response.body).isEqualTo(code)
        val error = response.json().path("error")
        assertThat(error.fieldNames().asSequence().toSet()).isSubsetOf("code", "message", "details", "at")
        assertThat(error.path("message").asText()).isNotBlank()

        assertThat(response.body.orEmpty()).doesNotContain(marker)
        response.headers.forEach { (name, values) ->
            assertThat(values.joinToString()).describedAs("header $name").doesNotContain(marker)
        }
        val events = capture.events.toList()
        assertThat(events.filter { it.level == Level.ERROR }.map(capture::text))
            .describedAs("ERROR log events").isEmpty()
        assertThat(events.filter { it.level.isGreaterOrEqual(Level.INFO) }.map(capture::text).filter { marker in it })
            .describedAs("log events at INFO or above repeating what was sent").isEmpty()
        // The refusal was really logged, once, by the handler, at INFO and by type.
        val lines = events.filter { it.loggerName == ApiErrorHandler::class.java.name }
        assertThat(lines.map { "${it.level} ${it.formattedMessage}" })
            .describedAs("ApiErrorHandler log lines")
            .singleElement().satisfies({ assertThat(it).startsWith("INFO ").contains(exception) })
        return response
    }

    private fun marker() = "zq${UUID.randomUUID().toString().replace("-", "").take(12)}"

    // --- a required field is missing -------------------------------------------

    @Test
    fun `a missing required field is validation_failed naming the field and nothing else`() {
        val m = marker()
        val r = refused(400, "validation_failed", "HttpMessageNotReadableException", m) {
            send(HttpMethod.POST, "/api/v1/auth/otp/verify", """{"code":"$m"}""", auth = false)
        }
        assertThat(r.json().path("error").path("details").toString())
            .isEqualTo("""{"fields":{"phone":"This is required"}}""")
    }

    @Test
    fun `a required field sent as null is the same as missing`() {
        val m = marker()
        val r = refused(400, "validation_failed", "HttpMessageNotReadableException", m) {
            send(HttpMethod.POST, "/api/v1/households/$householdId/members", """{"displayName":null,"notes":"$m"}""")
        }
        assertThat(r.json().path("error").path("details").toString())
            .isEqualTo("""{"fields":{"displayName":"This is required"}}""")
    }

    @Test
    fun `a missing field inside a list is named by its path, the way field validation names it`() {
        val m = marker()
        val r = refused(400, "validation_failed", "HttpMessageNotReadableException", m) {
            send(
                HttpMethod.POST, "/api/v1/households/$householdId/investments",
                """{"typeId":"${UUID.randomUUID()}","title":"$m","owners":[{"sharePct":100}]}""",
            )
        }
        assertThat(r.json().path("error").path("details").toString())
            .isEqualTo("""{"fields":{"owners[0].memberId":"This is required"}}""")
    }

    // --- the body cannot be read -------------------------------------------------

    @Test
    fun `JSON that does not parse is malformed_request`() {
        val m = marker()
        val r = refused(400, "malformed_request", "HttpMessageNotReadableException", m) {
            send(HttpMethod.POST, "/api/v1/auth/otp/verify", """{"phone":"9876543210","code":$m}""", auth = false)
        }
        assertThat(r.json().path("error").path("message").asText()).isEqualTo("We couldn't read that request.")
        assertThat(r.json().path("error").has("details")).isFalse()
    }

    @Test
    fun `an unterminated body is malformed_request`() {
        val m = marker()
        refused(400, "malformed_request", "HttpMessageNotReadableException", m) {
            send(HttpMethod.POST, "/api/v1/households/$householdId/members", """{"displayName":"$m""")
        }
    }

    @Test
    fun `a field of the wrong type is malformed_request`() {
        val m = marker()
        refused(400, "malformed_request", "HttpMessageNotReadableException", m) {
            send(HttpMethod.POST, "/api/v1/households/$householdId/members", """{"displayName":["$m"]}""")
        }
    }

    @Test
    fun `a value that is not a UUID where a UUID belongs is malformed_request`() {
        val m = marker()
        refused(400, "malformed_request", "HttpMessageNotReadableException", m) {
            send(HttpMethod.POST, "/api/v1/households/$householdId/investments", """{"typeId":"$m","title":"x"}""")
        }
    }

    @Test
    fun `an empty body is malformed_request`() {
        refused(400, "malformed_request", "HttpMessageNotReadableException", marker()) {
            send(HttpMethod.POST, "/api/v1/households/$householdId/members", null)
        }
    }

    // --- the body is not JSON at all ----------------------------------------------

    @Test
    fun `a body in a content type the endpoint does not read is 415 unsupported_media_type`() {
        val m = marker()
        refused(415, "unsupported_media_type", "HttpMediaTypeNotSupportedException", m) {
            send(HttpMethod.POST, "/api/v1/auth/otp/verify", "phone=9876543210&code=$m", MediaType.TEXT_PLAIN, auth = false)
        }
    }

    @Test
    fun `JSON sent to an upload endpoint is 415 unsupported_media_type`() {
        val m = marker()
        refused(415, "unsupported_media_type", "HttpMediaTypeNotSupportedException", m) {
            send(HttpMethod.POST, "/api/v1/households/$householdId/documents", """{"file":"$m"}""")
        }
    }

    // --- the URL cannot be read -----------------------------------------------------

    @Test
    fun `a path variable that is not a UUID is malformed_request naming the parameter`() {
        val m = marker()
        val r = refused(400, "malformed_request", "MethodArgumentTypeMismatchException", m) {
            send(HttpMethod.GET, "/api/v1/households/$m/search?q=gold", null, type = null)
        }
        assertThat(r.json().path("error").path("details").toString()).isEqualTo("""{"parameter":"householdId"}""")
    }

    @Test
    fun `a query parameter of the wrong type is malformed_request naming the parameter`() {
        val m = marker()
        val r = refused(400, "malformed_request", "MethodArgumentTypeMismatchException", m) {
            send(HttpMethod.GET, "/api/v1/households/$householdId/search?q=gold&limit=$m", null, type = null)
        }
        assertThat(r.json().path("error").path("details").toString()).isEqualTo("""{"parameter":"limit"}""")
    }

    @Test
    fun `a missing required query parameter is malformed_request naming the parameter`() {
        val m = marker()
        val r = refused(400, "malformed_request", "MissingServletRequestParameterException", m) {
            send(HttpMethod.GET, "/api/v1/households/$householdId/search?limit=3&other=$m", null, type = null)
        }
        assertThat(r.json().path("error").path("details").toString()).isEqualTo("""{"parameter":"q"}""")
    }

    @Test
    fun `an upload without its file part is malformed_request naming the part`() {
        val m = marker()
        val boundary = "b${UUID.randomUUID().toString().replace("-", "")}"
        val body = "--$boundary\r\nContent-Disposition: form-data; name=\"notes\"\r\n\r\n$m\r\n--$boundary--\r\n"
        val r = refused(400, "malformed_request", "MissingServletRequestPartException", m) {
            send(
                HttpMethod.POST, "/api/v1/households/$householdId/documents", body,
                MediaType.parseMediaType("multipart/form-data; boundary=$boundary"),
            )
        }
        assertThat(r.json().path("error").path("details").toString()).isEqualTo("""{"parameter":"file"}""")
    }
}
