package tech.bhrigu.almira.ops

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.logging.structured.StructuredLogFormatter

/**
 * One JSON object per log line, for a collector to index and alert on, and
 * built to carry as little about people as a log line can.
 *
 * Written here rather than using Spring Boot's `ecs` or `logstash` formats,
 * because both copy everything they are given — the MDC, key-value pairs and
 * every exception's message — and an exception message is exactly where
 * personal data escapes: a unique-constraint violation quotes the phone number
 * that collided, a parse error quotes the input. So:
 *
 *  - fields: `ts`, `level`, `logger`, `thread`, `message`, and for a throwable
 *    `error` (class names of the chain) and `frames` (the top frames of the
 *    outermost one). Nothing else, and in particular **no MDC and no
 *    exception messages**;
 *  - the message is scrubbed as defence in depth, though every call site
 *    already masks what it logs (PhoneNumber.mask, EmailAddress.mask): an email
 *    address becomes `[email]` and a run of ten or more digits becomes
 *    `[number]`. A UUID is left alone — record ids are how an incident is
 *    traced, and they name nobody.
 *
 * Selected with `ALMIRA_LOG_FORMAT=json` (see [LogFormatSelector]); the
 * default stays the readable text format, for a developer's terminal.
 */
class JsonLogFormatter : StructuredLogFormatter<ILoggingEvent> {

    override fun format(event: ILoggingEvent): String {
        val line = linkedMapOf<String, Any>(
            "ts" to event.instant.toString(),
            "level" to event.level.toString(),
            "logger" to event.loggerName,
            "thread" to event.threadName,
            "message" to scrub(event.formattedMessage ?: ""),
        )
        event.throwableProxy?.let { proxy ->
            line["error"] = chain(proxy).joinToString(" <- ") { it.className }
            line["frames"] = proxy.stackTraceElementProxyArray
                .take(MAX_FRAMES)
                .map { it.stackTraceElement.toString() }
        }
        return MAPPER.writeValueAsString(line) + "\n"
    }

    private fun chain(proxy: IThrowableProxy): List<IThrowableProxy> =
        generateSequence(proxy) { it.cause }.take(MAX_CAUSES).toList()

    companion object {
        private val MAPPER = ObjectMapper()
        private const val MAX_FRAMES = 12
        private const val MAX_CAUSES = 8

        private val EMAIL = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")

        // Ten or more digits, optionally with a leading +, not inside a word or a
        // hyphenated token — so a phone number goes and a UUID's last group stays.
        private val LONG_NUMBER = Regex("""(?<![\w\-])\+?\d{10,}(?![\w\-])""")

        fun scrub(message: String): String =
            LONG_NUMBER.replace(EMAIL.replace(message, "[email]"), "[number]")
    }
}
