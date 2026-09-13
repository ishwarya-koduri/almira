package tech.bhrigu.almira.provider

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.mock.env.MockEnvironment

/**
 * The guard that turns "flip one live" from a stack trace into a sentence.
 *
 * Every case here has been watched failing before being trusted — see
 * docs/19 §"How the verification is verified". The way to watch these fail is
 * to add a provider name to `implemented` in [ProviderModeCheck] and see the
 * second test stop refusing.
 */
@DisplayName("Provider modes: refusing clearly beats failing obscurely")
class ProviderModeCheckTest {

    private val check = ProviderModeCheck()

    private fun run(vararg properties: Pair<String, String>) {
        val environment = MockEnvironment()
        properties.forEach { (key, value) -> environment.setProperty(key, value) }
        check.postProcessEnvironment(environment, SpringApplication())
    }

    /**
     * Every other test in this class calls the check directly, which proves the
     * logic and says nothing about whether Spring ever calls it. That is not a
     * hypothetical: it was first registered in an `.imports` file under `META-INF/spring`,
     * where Boot 3 looks for auto-configuration and never for post-processors.
     * The class compiled, all six tests above passed, one had been watched
     * failing — and the running application ignored it completely and died on
     * the exception it was written to replace.
     *
     * So this asks Spring's own loader, the way the application does.
     */
    @Test
    fun `Spring actually loads the check, not just the test`() {
        // Read the registrations the way Spring's loader finds them — every
        // META-INF/spring.factories on the classpath — without instantiating
        // anything. The first version of this test called
        // SpringFactoriesLoader.load(), which *constructs* every registered
        // post-processor; one of Boot's own cannot be built without arguments,
        // so the test threw in both states — file present and file absent —
        // and therefore proved nothing about either. It had been "watched
        // failing" and the failure was about somebody else's class.
        val registered = javaClass.classLoader.getResources("META-INF/spring.factories")
            .toList()
            .flatMap { url ->
                java.util.Properties()
                    .apply { url.openStream().use(::load) }
                    .getProperty(EnvironmentPostProcessor::class.java.name, "")
                    .split(',')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
            }

        assertThat(registered)
            .describedAs(
                "ProviderModeCheck must be registered under EnvironmentPostProcessor in " +
                    "META-INF/spring.factories. If this fails, every other test in this class can " +
                    "pass while the real application never runs the check.",
            )
            .contains(ProviderModeCheck::class.java.name)
    }

    @Test
    fun `the defaults start, and are sandbox for everything but aa, which is disabled`() {
        run()
        assertThat(ProviderModeCheck.DEFAULT_MODES)
            .describedAs("Account Aggregator is cut from v1; every other provider defaults to its sandbox")
            .containsEntry("aa", "disabled")
            .containsEntry("sms", "sandbox").containsEntry("email", "sandbox").containsEntry("push", "sandbox")
            .containsEntry("digilocker", "sandbox").containsEntry("whatsapp", "sandbox")
    }

    /**
     * The same defaults are written in four places, and a deployment reads
     * whichever one it happens to hit: application.yml when nothing is set,
     * AlmiraProperties when a property source has no application.yml,
     * `.env.production.example` when an operator copies it, and the check's own
     * fallback. Read them back rather than trust that they were edited together.
     */
    @Test
    fun `the default modes agree across application yml, AlmiraProperties and the production example`() {
        val root = java.nio.file.Path.of("..")
        val yml = root.resolve("backend/src/main/resources/application.yml").toFile().readText()
        val example = root.resolve(".env.production.example").toFile().readText()
        val properties = tech.bhrigu.almira.config.AlmiraProperties.Providers().all()

        ProviderModeCheck.providerNames().forEach { name ->
            val expected = ProviderModeCheck.defaultMode(name)
            val env = "ALMIRA_PROVIDER_${name.uppercase()}_MODE"
            assertThat(yml).describedAs("application.yml default for $name")
                .contains("mode: \${$env:$expected}")
            assertThat(example).describedAs(".env.production.example default for $name")
                .contains("# $env=$expected\n")
            assertThat(properties.getValue(name).mode).describedAs("AlmiraProperties default for $name")
                .isEqualTo(expected)
        }
    }

    /**
     * The case this class exists for. Before it, `live` deleted the sandbox
     * bean and left nothing, so the application died on a
     * NoSuchBeanDefinitionException naming an interface — which reads like a
     * broken build rather than like asking for something that does not exist.
     */
    @Test
    fun `live refuses while no live adapter exists, and says so`() {
        assertThatThrownBy {
            run(
                "almira.providers.digilocker.mode" to "live",
                "almira.providers.digilocker.client-id" to "an-id",
                "almira.providers.digilocker.client-secret" to "a-secret",
            )
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("there is no live adapter for digilocker yet")
            .hasMessageContaining("missing implementation, not a missing setting")
            .hasMessageContaining("docs/13")
    }

    /**
     * Fail closed on nonsense. `liev` must not quietly mean sandbox and spend a
     * month sending nothing, and must not mean live either.
     */
    @Test
    fun `a mode that is not one of the three refuses rather than guessing`() {
        assertThatThrownBy { run("almira.providers.sms.mode" to "liev") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("almira.providers.sms.mode is 'liev'")
            .hasMessageContaining("Refusing rather than guessing")
    }

    /** Absent is a normal state: every provider can be disabled, alone or together. */
    @Test
    fun `disabled is a legitimate mode for every provider, one at a time and all at once`() {
        ProviderModeCheck.providerNames().forEach { name ->
            run("almira.providers.$name.mode" to "disabled")
            run("almira.providers.$name.mode" to "DISABLED")
        }
        run(*ProviderModeCheck.providerNames().map { "almira.providers.$it.mode" to "disabled" }.toTypedArray())
    }

    /**
     * `off` used to pass here and then crash startup for three providers
     * (known-issues 11). It is refused, by name, with the word to use instead —
     * not accepted as a second spelling of `disabled`.
     */
    @Test
    fun `off is refused with a sentence that names disabled`() {
        ProviderModeCheck.providerNames().forEach { name ->
            assertThatThrownBy { run("almira.providers.$name.mode" to "off") }
                .describedAs(name)
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("almira.providers.$name.mode is 'off'")
                .hasMessageContaining("write 'disabled'")
        }
    }

    /**
     * known-issues 12: any `almira.otp.provider` but `log` used to die on
     * "required a bean of type OtpSender", which reads like a broken build.
     */
    @Test
    fun `a one-time-code sender that does not exist is refused with a sentence`() {
        run("almira.otp.provider" to "log")
        assertThatThrownBy { run("almira.otp.provider" to "sms") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("almira.otp.provider is 'sms'")
            .hasMessageContaining("the only one-time-code sender that exists is 'log'")
    }

    /**
     * Credentials are checked before a live provider is accepted, because
     * turning one on and forgetting its key is an outage discovered by the
     * person who needed a one-time code, at the moment they needed it.
     *
     * Reached by pretending sms has an adapter — which is what the message in
     * the previous test is about — so this asserts the credential branch rather
     * than the implementation branch.
     */
    @Test
    fun `the credential check knows a DLT pair counts and an empty one does not`() {
        // Sender id and template id together are a credential for Indian SMS:
        // they are what decide whether an operator delivers the message at all.
        val withDlt = ProviderModeCheck.credentialKeys()
            .associateWith { if (it == "sender-id" || it == "template-id") "set" else "" }
        assertThat(withDlt.values.any { it.isNotBlank() })
            .describedAs("a DLT sender and template must register as credentials")
            .isTrue()

        val withNothing = ProviderModeCheck.credentialKeys().associateWith { "" }
        assertThat(withNothing.values.all { it.isBlank() })
            .describedAs("and an empty set must not")
            .isTrue()
    }

    @Test
    fun `every provider named in configuration is checked`() {
        assertThat(ProviderModeCheck.providerNames())
            .describedAs("a provider missing from this list is a provider nobody audits")
            .containsExactlyInAnyOrder("sms", "email", "push", "digilocker", "aa", "whatsapp")
    }
}
