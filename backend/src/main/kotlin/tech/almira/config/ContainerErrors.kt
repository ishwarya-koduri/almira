package tech.almira.config

import org.apache.catalina.connector.Request
import org.apache.catalina.connector.Response
import org.apache.catalina.core.StandardHost
import org.apache.catalina.valves.ErrorReportValve
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * The few refusals the container makes on its own, in the API's own envelope.
 *
 * A handful of requests never reach Spring at all: Tomcat rejects the URL
 * before the filter chain and writes its own page. `GET /api/v1/share/%2e%2e%2f`
 * answered with an HTML document headed "HTTP Status 400", styled and
 * server-named, where every other path on the API answers `{"error":{...}}`.
 * Two things wrong with it: a client parsing errors as JSON gets a parse
 * failure instead of a reason, and the page tells an unauthenticated caller
 * which container we run and which version, which is the first thing anyone
 * looking for a known hole would like to know.
 *
 * So the standard report is replaced with the same envelope, for the same
 * statuses. Nothing from the request is put in it: the code and message are
 * chosen from our own table by status, and are the ones the API already uses
 * for those statuses.
 */
class ApiErrorReportValve : ErrorReportValve() {

    override fun report(request: Request, response: Response, throwable: Throwable?) {
        val status = response.status
        // The base class's own guards: nothing to report on a success, nothing
        // to add once a body has been written, and nothing to say twice.
        if (status < 400 || response.contentWritten > 0 || !response.setErrorReported()) return

        val (code, message) = when {
            status == 400 -> "malformed_request" to "We couldn't read that request."
            status == 404 -> "not_found" to "We couldn't find that."
            status == 405 -> "method_not_allowed" to "That isn't something you can do here."
            status < 500 -> "request_refused" to "We couldn't accept that request."
            else -> "internal_error" to "Something went wrong on our side. Your data is safe. Please try again."
        }

        try {
            response.setContentType("application/json")
            response.setCharacterEncoding("UTF-8")
            response.writer.write("""{"error":{"code":"$code","message":"$message","at":"${Instant.now()}"}}""")
            response.writer.flush()
        } catch (e: Exception) {
            // The connection is already in trouble if this fails. Tomcat's own
            // report swallows the same case; there is nowhere left to write to.
        }
    }
}

/**
 * Tomcat builds the host's error valve from this class name at startup, so it
 * is named before the host starts. The context's parent is the host by the time
 * a context customizer runs.
 */
@Component
class ContainerErrorConfig : WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    override fun customize(factory: TomcatServletWebServerFactory) {
        factory.addContextCustomizers({ context ->
            (context.parent as? StandardHost)?.errorReportValveClass = ApiErrorReportValve::class.java.name
        })
    }
}
