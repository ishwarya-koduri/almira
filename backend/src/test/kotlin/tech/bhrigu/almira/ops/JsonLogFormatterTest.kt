package tech.bhrigu.almira.ops

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.LoggingEvent
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.boot.SpringApplication
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.MapPropertySource

@DisplayName("JSON logs: one object a line, and nothing about a person in it")
class JsonLogFormatterTest {

    private val mapper = ObjectMapper()
    private val context = LoggerContext()

    private fun event(message: String, vararg args: Any?, error: Throwable? = null) =
        LoggingEvent(
            "fqcn", context.getLogger("tech.bhrigu.almira.auth.AuthService"),
            if (error == null) Level.INFO else Level.ERROR, message, error, args,
        )

    @Test
    fun `a line is one parseable object with the fields a collector needs`() {
        val out = JsonLogFormatter().format(event("login ok for {} (new={})", "******4321", true))
        assertThat(out).endsWith("\n")
        assertThat(out.trimEnd()).doesNotContain("\n")
        val json = mapper.readTree(out)
        assertThat(json.fieldNames().asSequence().toList())
            .containsExactly("ts", "level", "logger", "thread", "message")
        assertThat(json["level"].asText()).isEqualTo("INFO")
        assertThat(json["message"].asText()).isEqualTo("login ok for ******4321 (new=true)")
    }

    @Test
    fun `an exception is named by class and frames, never by its message`() {
        val cause = IllegalArgumentException("Key (phone)=(+919812345678) already exists")
        val error = RuntimeException("while saving ravi@example.com", cause)
        val json = mapper.readTree(JsonLogFormatter().format(event("request failed", error = error)))
        assertThat(json["error"].asText())
            .isEqualTo("java.lang.RuntimeException <- java.lang.IllegalArgumentException")
        assertThat(json["frames"].size()).isBetween(1, 12)
        val whole = json.toString()
        assertThat(whole).doesNotContain("9812345678").doesNotContain("ravi@").doesNotContain("already exists")
    }

    @Test
    fun `the MDC is not copied into the line`() {
        MDC.put("phone", "+919812345678")
        try {
            val event = event("hello")
            assertThat(JsonLogFormatter().format(event)).doesNotContain("9812345678").doesNotContain("phone")
        } finally {
            MDC.clear()
        }
    }

    @Test
    fun `emails and phone-length numbers are scrubbed, record ids are not`() {
        val id = "0b6f6b1e-2a4c-4c1d-9e1f-123456789012"
        assertThat(JsonLogFormatter.scrub("sent to asha.k+alpha@mail.co.in for $id"))
            .isEqualTo("sent to [email] for $id")
        assertThat(JsonLogFormatter.scrub("code for +919812345678 and 9812345678"))
            .isEqualTo("code for [number] and [number]")
        assertThat(JsonLogFormatter.scrub("took 1532 ms, 5 rows")).isEqualTo("took 1532 ms, 5 rows")
    }

    private fun selectorWith(value: String?): StandardEnvironment {
        val env = StandardEnvironment()
        if (value != null) env.propertySources.addFirst(MapPropertySource("t", mapOf("almira.log.format" to value)))
        LogFormatSelector().postProcessEnvironment(env, SpringApplication())
        return env
    }

    @Test
    fun `text is the default, json selects this formatter, anything else refuses`() {
        assertThat(selectorWith(null).getProperty(LogFormatSelector.STRUCTURED_CONSOLE)).isNull()
        assertThat(selectorWith("text").getProperty(LogFormatSelector.STRUCTURED_CONSOLE)).isNull()
        assertThat(selectorWith("JSON").getProperty(LogFormatSelector.STRUCTURED_CONSOLE))
            .isEqualTo(JsonLogFormatter::class.java.name)
        assertThatThrownBy { selectorWith("ecs") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("ALMIRA_LOG_FORMAT")
    }
}
