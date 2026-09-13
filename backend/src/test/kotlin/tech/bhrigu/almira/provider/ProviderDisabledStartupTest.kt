package tech.bhrigu.almira.provider

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import tech.bhrigu.almira.AlmiraApplication
import tech.bhrigu.almira.support.TestInfra

/**
 * The whole application starts with any provider disabled — each one alone, all
 * of them together, and with nothing said at all.
 *
 * Not a mocked context and not the check on its own: the real
 * `AlmiraApplication`, with application.yml, every bean, a web server on a free
 * port and the test database. known-issues 11 was exactly the gap between the
 * two — `ProviderModeCheck` accepted `off`, and then the context failed on a
 * missing `AccountAggregatorClient` — so only starting the thing proves it.
 *
 * Each context is closed before the next starts; seven open at once would spend
 * the database's connections.
 */
@DisplayName("Providers, disabled: the real application starts")
class ProviderDisabledStartupTest {

    private fun start(modes: Map<String, String>): ConfigurableApplicationContext {
        val properties = mutableMapOf<String, Any>(
            "almira.db.url" to TestInfra.dbUrl,
            "almira.db.owner-user" to TestInfra.dbOwnerUser,
            "almira.db.owner-password" to TestInfra.dbOwnerPassword,
            "almira.db.app-user" to TestInfra.dbAppUser,
            "almira.db.app-password" to TestInfra.dbAppPassword,
            "almira.db.max-pool-size" to 2,
            "spring.data.redis.host" to TestInfra.redisHost,
            "spring.data.redis.port" to TestInfra.redisPort,
            "almira.otp.provider" to "log",
            // Chosen explicitly, as in ApiTestBase: the test databases have no
            // page checksums, which only an explicit development environment allows.
            "ALMIRA_ENV" to "development",
            "almira.jwt.secret" to "test-only-secret-that-is-long-enough-for-hmac256-signing",
            "server.port" to 0,
        )
        modes.forEach { (name, mode) -> properties["almira.providers.$name.mode"] = mode }
        // As command-line arguments, NOT SpringApplicationBuilder.properties():
        // those are default properties, the lowest precedence there is, so
        // application.yml wins over them. The first version of this test did
        // that, and every context it started ignored both the provider modes
        // and the database URL — and ran Flyway against application.yml's
        // development database instead of the test one.
        //
        // This test used to check the URL here, after run() — which is after
        // Flyway. The check is now TestDatabaseGuard (support/, registered for
        // the whole test classpath), which refuses before any connection is
        // opened; see TestDatabaseGuardTest.
        val args = properties.map { (key, value) -> "--$key=$value" }.toTypedArray()
        return SpringApplicationBuilder(AlmiraApplication::class.java).run(*args)
    }

    /** What the running context actually has, per provider name. */
    private fun modesIn(context: ConfigurableApplicationContext): Map<String, ProviderMode> {
        val channels = context.getBeansOfType(ChannelSender::class.java).values.associate { it.channel to it.mode }
        return mapOf(
            "sms" to (channels["sms"] ?: ProviderMode.DISABLED),
            "email" to (channels["email"] ?: ProviderMode.DISABLED),
            "push" to (channels["push"] ?: ProviderMode.DISABLED),
            "digilocker" to context.getBean(DocumentVaultProvider::class.java).mode,
            "aa" to context.getBean(AccountAggregatorClient::class.java).mode,
            "whatsapp" to context.getBean(WhatsAppGateway::class.java).mode,
        )
    }

    private fun startsWith(label: String, modes: Map<String, String>, expected: Map<String, ProviderMode>) =
        DynamicTest.dynamicTest(label) {
            start(modes).use { context ->
                assertThat(context.isActive).isTrue()
                assertThat(modesIn(context)).describedAs(label).isEqualTo(expected)
                // The status is served from the service that used to fail to be
                // built, so reaching it is part of "started".
                assertThat(context.getBean(ConnectService::class.java)).isNotNull()
            }
        }

    @TestFactory
    fun `each provider disabled on its own, all disabled together, and the defaults`(): List<DynamicTest> {
        val names = ProviderModeCheck.providerNames()
        val allSandbox = names.associateWith { "sandbox" }

        val oneAtATime = names.map { name ->
            startsWith(
                "only $name disabled",
                allSandbox + (name to "disabled"),
                names.associateWith { if (it == name) ProviderMode.DISABLED else ProviderMode.SANDBOX },
            )
        }
        val allAtOnce = startsWith(
            "every provider disabled",
            names.associateWith { "disabled" },
            names.associateWith { ProviderMode.DISABLED },
        )
        // Nothing set: application.yml's defaults, which cut Account Aggregator.
        val defaults = startsWith(
            "nothing set — Account Aggregator is disabled, the rest are sandbox",
            emptyMap(),
            names.associateWith { if (it == "aa") ProviderMode.DISABLED else ProviderMode.SANDBOX },
        )
        return oneAtATime + allAtOnce + defaults
    }
}
