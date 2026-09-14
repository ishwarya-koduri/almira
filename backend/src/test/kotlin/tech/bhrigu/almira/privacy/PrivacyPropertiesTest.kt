package tech.bhrigu.almira.privacy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

/** The grievance contact and its period are configuration; a period over 90 days is not allowed to start. */
@DisplayName("Grievance contact configuration")
class PrivacyPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(PrivacyProperties::class)
    class Config

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration::class.java))
        .withUserConfiguration(Config::class.java)

    @Test
    fun `a period beyond ninety days refuses to start`() {
        runner.withPropertyValues("almira.privacy.grievance.response-days=91").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).rootCause().hasMessageContaining("Rule 14(3)")
        }
        runner.withPropertyValues("almira.privacy.grievance.response-days=0").run { context ->
            assertThat(context).hasFailed()
        }
    }

    @Test
    fun `ninety days is the ceiling, not beyond it`() {
        runner.withPropertyValues("almira.privacy.grievance.response-days=90").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(PrivacyProperties::class.java).grievance.responseDays).isEqualTo(90)
        }
    }

    @Test
    fun `a named contact is configured only with both a name and an address`() {
        runner.run { context ->
            val grievance = context.getBean(PrivacyProperties::class.java).grievance
            assertThat(grievance.configured).isFalse()
            assertThat(grievance.responseDays).isEqualTo(30)
        }
        runner.withPropertyValues(
            "almira.privacy.grievance.name=Grievance Officer",
            "almira.privacy.grievance.email=grievance@example.invalid",
        ).run { context ->
            assertThat(context.getBean(PrivacyProperties::class.java).grievance.configured).isTrue()
        }
    }
}
