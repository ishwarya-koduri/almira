package tech.bhrigu.almira.common

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tech.bhrigu.almira.support.ApiTestBase
import java.net.Socket

/**
 * Even the refusals Spring never sees are the API's own.
 *
 * Tomcat rejects some URLs before the filter chain and used to answer with its
 * own HTML page: a styled document headed "HTTP Status 400", naming the
 * container, where every other path on the API answers with an envelope. A
 * client that parses errors as JSON got a parse failure instead of a reason,
 * and an unauthenticated caller got told what we run.
 *
 * Sent down a bare socket on purpose: any HTTP client worth the name normalises
 * `%2e%2e%2f` away before it reaches the wire, and the whole point is the path
 * that does not.
 */
@DisplayName("A refusal from the container is the same envelope as any other")
class ContainerErrorTest : ApiTestBase() {

    private fun rawRequest(line: String): String =
        Socket("localhost", port).use { socket ->
            socket.getOutputStream().write(
                "$line HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray(),
            )
            socket.getInputStream().readBytes().decodeToString()
        }

    /** The envelope out of a raw response, past the chunk framing around it. */
    private fun envelopeIn(response: String) = mapper.readTree(
        response.substringAfter("\r\n\r\n").substringAfter('{').let { "{$it" }.substringBeforeLast('}') + "}",
    )

    @Test
    fun `a path the container rejects answers the error envelope, not an HTML page`() {
        val response = rawRequest("GET /api/v1/share/%2e%2e%2f")

        assertThat(response).describedAs(response).startsWith("HTTP/1.1 400")
        assertThat(response).describedAs("content type").contains("application/json")
        val error = envelopeIn(response).path("error")
        assertThat(error.path("code").asText()).describedAs(response).isEqualTo("malformed_request")
        assertThat(error.path("message").asText()).describedAs(response).isNotBlank()
        assertThat(error.path("at").asText()).describedAs(response).isNotBlank()
    }

    @Test
    fun `a refusal from the container does not say what we run`() {
        val response = rawRequest("GET /api/v1/share/%2e%2e%2f")

        assertThat(response.lowercase())
            .describedAs("the container names itself to anyone who asks: $response")
            .doesNotContain("tomcat")
            .doesNotContain("apache")
            .doesNotContain("<html")
            .doesNotContain("http status")
    }
}
