package tech.bhrigu.almira.auth

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpStatus
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.common.EmailAddress
import tech.bhrigu.almira.config.AlmiraProperties
import java.nio.file.Files
import java.nio.file.Path

/**
 * The sign-in channel switch and the alpha allowlist, as configuration: what
 * the packaged defaults are, what refuses to start, and how an address is
 * spelled before it is compared with anything.
 */
@DisplayName("Sign-in channels and the email allowlist")
class SignInChannelsTest {

    private fun props(
        channels: List<String>,
        allowlist: List<String> = emptyList(),
        emailMode: String = "sandbox",
    ) = AlmiraProperties(
        db = AlmiraProperties.Db("jdbc:postgresql://x/y", "u", "p", "u2", "p2"),
        jwt = AlmiraProperties.Jwt("test-only-secret-that-is-long-enough-for-hmac256-signing"),
        otp = AlmiraProperties.Otp(),
        auth = AlmiraProperties.Auth(channels, allowlist),
        providers = AlmiraProperties.Providers(email = AlmiraProperties.Provider(mode = emailMode)),
    )

    // --- the packaged defaults ----------------------------------------------

    /** Read from application.yml itself, with no environment in the way, as EnvironmentDefaultTest does. */
    private fun packaged(): AlmiraProperties.Auth {
        val environment = StandardEnvironment().apply {
            propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
            propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
            YamlPropertySourceLoader()
                .load("application.yml", ClassPathResource("application.yml"))
                .forEach(propertySources::addLast)
        }
        return Binder(ConfigurationPropertySources.get(environment), PropertySourcesPlaceholdersResolver(environment))
            .bind("almira.auth", Bindable.of(AlmiraProperties.Auth::class.java))
            .orElseThrow { AssertionError("almira.auth is not in application.yml") }
    }

    @Test
    fun `unset, a server signs in by phone only and lists nobody, so development tools are unchanged`() {
        val auth = packaged()
        assertThat(auth.signInChannels).containsExactly("phone")
        assertThat(auth.emailAllowlist).isEmpty()
        assertThat(SignInChannels(props(auth.signInChannels, auth.emailAllowlist)).enabled)
            .containsExactly(OtpChannel.PHONE)
    }

    @Test
    fun `both variables are named in application yml and in the production example`() {
        val yml = ClassPathResource("application.yml").inputStream.readAllBytes().decodeToString()
        val example = Files.readString(Path.of("..", ".env.production.example"))
        listOf("ALMIRA_SIGN_IN_CHANNELS", "ALMIRA_ALPHA_EMAIL_ALLOWLIST").forEach {
            assertThat(yml).describedAs("application.yml").contains("\${$it:")
            assertThat(example).describedAs(".env.production.example").contains("# $it=")
        }
    }

    // --- what refuses to start ----------------------------------------------

    @Test
    fun `a channel that is not phone or email refuses rather than guessing`() {
        assertThatThrownBy { SignInChannels(props(listOf("phone", "emial"))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("'emial'")
    }

    @Test
    fun `no channels at all refuses`() {
        assertThatThrownBy { SignInChannels(props(listOf(" "))) }
            .hasMessageContaining("nobody could sign in")
    }

    @Test
    fun `email with an empty allowlist refuses, because nobody could sign in`() {
        assertThatThrownBy { SignInChannels(props(listOf("email"))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("ALMIRA_ALPHA_EMAIL_ALLOWLIST")
        // Phone alone does not need one.
        SignInChannels(props(listOf("phone")))
    }

    /**
     * Email codes are sent through the email provider, so offering email
     * sign-in with that provider disabled is two settings that contradict each
     * other. Refused by name rather than started with email silently left out
     * of /auth/otp/channels, which would end the email alpha — and sign every
     * email-only tester out — as a side effect of a provider switch.
     */
    @Test
    fun `email sign-in with the email provider disabled refuses, naming both settings`() {
        listOf("disabled", "DISABLED", " Disabled ").forEach { mode ->
            assertThatThrownBy { SignInChannels(props(listOf("email"), listOf("a@b.co"), emailMode = mode)) }
                .describedAs("email mode '$mode'")
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("almira.auth.sign-in-channels")
                .hasMessageContaining("almira.providers.email.mode")
                .hasMessageContaining("'disabled'")
            assertThatThrownBy { SignInChannels(props(listOf("phone", "email"), listOf("a@b.co"), emailMode = mode)) }
                .hasMessageContaining("almira.providers.email.mode")
        }
        // Email disabled on its own is a normal state: a phone-only server starts.
        assertThat(SignInChannels(props(listOf("phone"), emailMode = "disabled")).enabled)
            .containsExactly(OtpChannel.PHONE)
        // And email sign-in with the provider on starts, sandbox or live.
        listOf("sandbox", "live").forEach { mode ->
            assertThat(SignInChannels(props(listOf("email"), listOf("a@b.co"), emailMode = mode)).enabled)
                .containsExactly(OtpChannel.EMAIL)
        }
    }

    @Test
    fun `a mistyped allowlist entry refuses, naming its position and not its contents`() {
        assertThatThrownBy {
            SignInChannels(props(listOf("email"), listOf("asha@example.com", "ravi-at-example.com")))
        }
            .hasMessageContaining("#2")
            .satisfies({ assertThat(it.message).doesNotContain("ravi") })
    }

    // --- the allowlist ------------------------------------------------------

    @Test
    fun `the allowlist is compared in the canonical spelling, and nothing is folded together`() {
        val channels = SignInChannels(
            props(listOf("email", "phone"), listOf("  Asha.Rao+alpha@Example.COM ", "", "ravi@example.com")),
        )
        assertThat(channels.enabled).containsExactly(OtpChannel.PHONE, OtpChannel.EMAIL)
        assertThat(channels.isAllowed(EmailAddress.normalize("ASHA.RAO+ALPHA@example.com"))).isTrue()
        // Dots and tags are part of the address, not decoration.
        assertThat(channels.isAllowed(EmailAddress.normalize("asharao+alpha@example.com"))).isFalse()
        assertThat(channels.isAllowed(EmailAddress.normalize("asha.rao@example.com"))).isFalse()
    }

    @Test
    fun `a disabled channel is refused with its own code, and says which channels exist`() {
        val e = runCatching { SignInChannels(props(listOf("email"), listOf("a@b.co"))).requireEnabled(OtpChannel.PHONE) }
            .exceptionOrNull() as ApiException
        assertThat(e.status).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(e.code).isEqualTo("sign_in_channel_disabled")
        assertThat(e.details).containsEntry("channel", "phone").containsEntry("enabledChannels", listOf("email"))
    }

    @Test
    fun `an address is trimmed and lower-cased, and a non-address is refused`() {
        assertThat(EmailAddress.normalize("  Priya.S+Tax@GMail.com\t")).isEqualTo("priya.s+tax@gmail.com")
        listOf("", "priya", "priya@", "@gmail.com", "priya@gmail", "pri ya@gmail.com", "a@b@c.com", "a".repeat(250) + "@b.co")
            .forEach { raw ->
                val e = runCatching { EmailAddress.normalize(raw) }.exceptionOrNull() as? ApiException
                assertThat(e?.code).describedAs("'$raw'").isEqualTo("email_invalid")
            }
        assertThat(EmailAddress.mask("priya.s@gmail.com")).isEqualTo("p····@gmail.com")
    }
}
