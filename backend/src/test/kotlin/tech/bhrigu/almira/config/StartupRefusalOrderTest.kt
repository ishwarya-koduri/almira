package tech.bhrigu.almira.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.assertj.core.api.SoftAssertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.event.ApplicationStartedEvent
import org.springframework.boot.web.context.WebServerInitializedEvent
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ApplicationListener
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.support.GenericApplicationContext
import tech.bhrigu.almira.AlmiraApplication
import tech.bhrigu.almira.support.ApiTestBase
import tech.bhrigu.almira.support.TestInfra
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Supplier

/**
 * Nothing that refuses to start can land behind Flyway's migration again.
 *
 * Three parts, each failing on a different way of getting it wrong:
 *
 *  1. **In the running context**, every [StartupRefusal] bean is a dependency
 *     of the `flyway` bean — the structural guarantee that it is built and
 *     verified before `migrate`. Watched failing with KeyEncryptionKeyCheck put
 *     back to an InitializingBean that injected Flyway.
 *  2. **A new refusal needs no wiring.** A StartupRefusal registered from
 *     outside the application — nothing in DatabaseConfig names it — refuses a
 *     real start before `flyway` initialises and before a web server exists,
 *     whether it refuses in its constructor or in [StartupRefusal.verifyBeforeMigrating].
 *     Watched failing with the `refusals` parameter taken off `flyway`.
 *  3. **The source.** Every main class whose code says "Refusing to start" is a
 *     StartupRefusal, an EnvironmentPostProcessor registered in
 *     spring.factories, or a helper called from one of those; and every
 *     @ConfigurationProperties class that refuses in an init block is bound by
 *     [StartupSettingsCheck]. Watched failing with PlanProperties removed from
 *     StartupSettingsCheck. It cannot see a refusal worded some other way —
 *     that is what part 1 and code review are for.
 */
@DisplayName("Startup refusals are ordered before Flyway migrates, structurally")
class StartupRefusalOrderTest {

    @Nested
    @DisplayName("a refusal nothing names")
    inner class ANewRefusal {

        @TestFactory
        fun `refuses a real start before flyway initialises`(): List<DynamicTest> = listOf(
            DynamicTest.dynamicTest("refusing in verifyBeforeMigrating") {
                refusedBeforeFlyway { StartupRefusal { throw IllegalStateException("Refusing to start — test refusal in verify") } }
            },
            DynamicTest.dynamicTest("refusing in its constructor") {
                refusedBeforeFlyway { throw IllegalStateException("Refusing to start — test refusal in constructor") }
            },
        )

        private fun refusedBeforeFlyway(refusal: () -> StartupRefusal) {
            val initialised = CopyOnWriteArrayList<String>()
            val served = CopyOnWriteArrayList<String>()
            val properties = mapOf(
                "almira.db.url" to TestInfra.dbUrl,
                "almira.db.owner-user" to TestInfra.dbOwnerUser,
                "almira.db.owner-password" to TestInfra.dbOwnerPassword,
                "almira.db.app-user" to TestInfra.dbAppUser,
                "almira.db.app-password" to TestInfra.dbAppPassword,
                "almira.db.max-pool-size" to "1",
                "spring.data.redis.host" to TestInfra.redisHost,
                "spring.data.redis.port" to TestInfra.redisPort.toString(),
                "almira.otp.provider" to "log",
                "ALMIRA_ENV" to "development",
                "almira.jwt.secret" to "test-only-secret-that-is-long-enough-for-hmac256-signing",
                "almira.outbox.background" to "false",
                "server.port" to "0",
            )
            val failure = catchThrowable {
                SpringApplicationBuilder(AlmiraApplication::class.java)
                    .initializers(
                        ApplicationContextInitializer<ConfigurableApplicationContext> { context ->
                            (context as GenericApplicationContext)
                                .registerBean("aRefusalAddedLater", StartupRefusal::class.java, Supplier { refusal() })
                            context.beanFactory.addBeanPostProcessor(object : BeanPostProcessor {
                                override fun postProcessBeforeInitialization(bean: Any, beanName: String): Any {
                                    initialised += beanName
                                    return bean
                                }
                            })
                        },
                    )
                    .listeners(
                        ApplicationListener<WebServerInitializedEvent> { served += "web server" },
                        ApplicationListener<ApplicationStartedEvent> { served += "started" },
                    )
                    .run(*properties.map { (k, v) -> "--$k=$v" }.toTypedArray())
                    .close()
            }
            val softly = SoftAssertions()
            softly.assertThat(generateSequence(failure) { it.cause }.mapNotNull { it.message }.toList())
                .describedAs("the startup failure is the added refusal")
                .anyMatch { it.contains("test refusal") }
            softly.assertThat(initialised)
                .describedAs("beans initialised — `flyway` initialising is `migrate` running")
                .doesNotContain("flyway")
            softly.assertThat(served).describedAs("a web server started, or the application started").isEmpty()
            softly.assertAll()
        }
    }

    @Nested
    @DisplayName("in the source")
    inner class InTheSource {

        private val main = File("src/main/kotlin")
        private val factories = File("src/main/resources/META-INF/spring.factories").readText()
        private val settingsCheck = File(main, "tech/bhrigu/almira/config/StartupSettingsCheck.kt").readText()

        /**
         * Classes that refuse but are neither a StartupRefusal nor a registered
         * post-processor, with the file that calls them from one. Each caller is
         * checked to be one and to name the helper.
         */
        private val helpers = mapOf(
            "PageChecksumCheck" to "tech/bhrigu/almira/config/DatabaseConfig.kt",
            "RuntimeRoleCheck" to "tech/bhrigu/almira/config/DatabaseConfig.kt",
        )

        private fun code(file: File): String =
            Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(file.readText(), "")
                .lines().filterNot { it.trim().startsWith("//") }.joinToString("\n")

        /** The bodies of every `init { … }` block, braces matched. */
        private fun initBlocks(source: String): List<String> =
            Regex("\\binit\\s*\\{").findAll(source).map { match ->
                var depth = 0
                var end = match.range.last
                for (i in match.range.last until source.length) {
                    when (source[i]) {
                        '{' -> depth++
                        '}' -> if (--depth == 0) { end = i; break }
                    }
                }
                source.substring(match.range.last, end)
            }.toList()

        private fun kotlinFiles() = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

        @Test
        fun `every class that refuses to start runs before migration`() {
            assertThat(main).describedAs("run from backend/").isDirectory()
            val softly = SoftAssertions()
            val refusing = kotlinFiles().filter { "Refusing to start" in code(it) }
            assertThat(refusing).describedAs("files that refuse to start — the scan found none, so it is broken").isNotEmpty()
            refusing.forEach { file ->
                val source = code(file)
                val name = file.nameWithoutExtension
                val isRefusalBean = Regex(":[^{]*\\bStartupRefusal\\b").containsMatchIn(source)
                val isPostProcessor = "EnvironmentPostProcessor" in source && name in factories
                val caller = helpers[name]?.let { File(main, it) }
                val isHelperOfOne = caller != null && code(caller).let { c ->
                    "StartupRefusal" in c && Regex("\\b$name\\(").containsMatchIn(c)
                }
                softly.assertThat(isRefusalBean || isPostProcessor || isHelperOfOne)
                    .describedAs(
                        "$name says \"Refusing to start\" but is not a StartupRefusal, not an EnvironmentPostProcessor " +
                            "in spring.factories, and not a helper called from one — so nothing orders it before " +
                            "Flyway migrates. Implement StartupRefusal (see its KDoc).",
                    )
                    .isTrue()
            }
            softly.assertAll()
        }

        @Test
        fun `every properties class that refuses is bound before any context`() {
            val softly = SoftAssertions()
            val checked = kotlinFiles().mapNotNull { file ->
                val source = code(file)
                if ("@ConfigurationProperties" !in source) return@mapNotNull null
                if (initBlocks(source).none { Regex("\\b(require|check|error)\\(").containsMatchIn(it) }) return@mapNotNull null
                Regex("@ConfigurationProperties\\([^)]*\\)\\s*(?:data\\s+)?class\\s+(\\w+)").find(source)?.groupValues?.get(1)
            }
            assertThat(checked).describedAs("refusing properties classes — none found, so the scan is broken").isNotEmpty()
            checked.forEach { name ->
                softly.assertThat(settingsCheck)
                    .describedAs("$name refuses in its init block, so StartupSettingsCheck must bind it before any context")
                    .contains("$name::class.java")
            }
            softly.assertAll()
        }
    }
}

/** Part 1 of [StartupRefusalOrderTest], in a real context. */
@DisplayName("Startup refusals: every one is a dependency of flyway")
class StartupRefusalDependencyTest : ApiTestBase() {

    @Autowired private lateinit var context: ConfigurableApplicationContext

    @Test
    fun `every StartupRefusal bean is a dependency of flyway`() {
        val refusals = context.getBeanNamesForType(StartupRefusal::class.java).toList()
        val flywayDependsOn = context.beanFactory.getDependenciesForBean("flyway").toList()

        assertThat(refusals)
            .describedAs("the refusals this application has today")
            .contains("pageChecksumRefusal", "runtimeRoleRefusal", "keyEncryptionKeyCheck")
        assertThat(flywayDependsOn)
            .describedAs("beans flyway depends on — each refusal must be one, or it can run after migrate")
            .containsAll(refusals)
    }
}
