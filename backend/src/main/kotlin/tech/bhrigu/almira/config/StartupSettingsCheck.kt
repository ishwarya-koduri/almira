package tech.bhrigu.almira.config

import org.springframework.boot.SpringApplication
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment
import tech.bhrigu.almira.auth.OtpService
import tech.bhrigu.almira.auth.SignInChannels
import tech.bhrigu.almira.crypto.LocalKeyManagement
import tech.bhrigu.almira.provider.ProviderCalls
import tech.bhrigu.almira.security.JwtService

/**
 * Every configuration refusal that used to wait for its bean, run before the
 * application context exists.
 *
 * The rule (docs/known-issues.md, "A guard runs before the action it guards"):
 * each of these checks lived in a bean's constructor, and beans are built after
 * the Flyway bean has migrated the database — because the web server builds
 * JwtAuthFilter first, and that filter needs the database. So a server started
 * with `ALMIRA_SIGN_IN_CHANNELS=emial`, a mangled `ALMIRA_KMS_MASTER_KEY`, or
 * `almira.providers.sms.max-attempts=99` applied every pending migration —
 * column drops included — and then refused. In development it also wrote a new
 * key-encryption key file first. Seen on the real jar, not inferred.
 *
 * An [EnvironmentPostProcessor], like ProviderModeCheck, because that runs
 * before any bean: before the pools, before Flyway, before a key file. It binds
 * [AlmiraProperties] the way the context will and calls the same functions the
 * beans call, so the two cannot disagree about what is refused or what the
 * sentence says. The beans keep calling them too, for anything that builds them
 * directly.
 *
 * Registered in META-INF/spring.factories. See the comment there for why not a
 * `.imports` file.
 *
 * What it cannot see: values a test adds with @DynamicPropertySource, which
 * arrive after this runs. Those are still checked by the beans, as before.
 */
class StartupSettingsCheck : EnvironmentPostProcessor {

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        val props = Binder.get(environment)
            .bind("almira", Bindable.of(AlmiraProperties::class.java))
            .orElse(null)
            ?: return

        // The order the beans used to be built in, so that a server with two
        // problems names the same one first as it did before.
        JwtService.checkSecret(props)
        SignInChannels.check(props)
        if (props.encryption.provider.equals("local", ignoreCase = true)) {
            LocalKeyManagement.configuredKey(props)
        }
        OtpService.checkBounds(props.otp)
        ProviderCalls.checkBounds(props.providers)
    }
}
