package tech.bhrigu.almira.ops

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

/**
 * `ALMIRA_LOG_FORMAT` — `text` (the default) or `json`.
 *
 * `json` points Spring Boot's structured console logging at [JsonLogFormatter].
 * An operator writes one short word rather than a class name, and a typo
 * refuses to start instead of quietly logging text into a collector that
 * expects JSON and alerts on nothing.
 *
 * An [EnvironmentPostProcessor] because logging is configured from the
 * environment before any bean exists; Spring Boot runs post-processors first.
 */
class LogFormatSelector : EnvironmentPostProcessor {

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        val raw = environment.getProperty(PROPERTY, "text").trim()
        when (raw.lowercase()) {
            "", "text" -> Unit
            "json" -> environment.propertySources.addFirst(
                MapPropertySource(
                    "almiraLogFormat",
                    mapOf(STRUCTURED_CONSOLE to JsonLogFormatter::class.java.name),
                ),
            )
            else -> throw IllegalStateException(
                "Refusing to start — almira.log.format (ALMIRA_LOG_FORMAT) is '$raw', " +
                    "which is not 'text' or 'json'.",
            )
        }
    }

    companion object {
        const val PROPERTY = "almira.log.format"
        const val STRUCTURED_CONSOLE = "logging.structured.format.console"
    }
}
