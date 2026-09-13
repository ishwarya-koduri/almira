package tech.bhrigu.almira.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource

/**
 * The hole was not in JwtService or LocalKeyManagement — both were correct for
 * the environment they were given. It was one line of application.yml:
 * `environment: ${ALMIRA_ENV:development}`, which gave them "development"
 * whenever nobody had said anything.
 *
 * So this reads the packaged application.yml itself, with no operating-system
 * environment and no system properties in the way, and asks what an unset
 * ALMIRA_ENV resolves to. A unit test that passes "" to a constructor would
 * have passed while the default was still there.
 */
@DisplayName("An unset environment resolves to nothing, not to development")
class EnvironmentDefaultTest {

    @Test
    fun `the packaged configuration does not default the environment to development`() {
        val environment = StandardEnvironment().apply {
            // Only the packaged file. If the test JVM happens to have ALMIRA_ENV
            // set, it must not answer this question for the YAML.
            propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
            propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
            YamlPropertySourceLoader()
                .load("application.yml", ClassPathResource("application.yml"))
                .forEach(propertySources::addLast)
        }

        val resolved = environment.getProperty("almira.environment")

        assertThat(resolved)
            .describedAs(
                "with ALMIRA_ENV unset, almira.environment must not resolve to development. " +
                    "When it did, a jar started without the variable accepted the JWT secret " +
                    "published in this repository — docs/17 §3.",
            )
            .isNotEqualToIgnoringCase("development")
        assertThat(resolved).isEmpty()
    }
}
