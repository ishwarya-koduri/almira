package tech.bhrigu.almira.support

import org.springframework.boot.context.event.ApplicationPreparedEvent
import org.springframework.context.ApplicationListener
import org.springframework.core.env.Environment

/**
 * Refuses to let any Spring application started from the test classpath reach a
 * database other than the test one — and refuses BEFORE anything connects.
 *
 * Why it exists: ProviderDisabledStartupTest once passed its database URL as
 * SpringApplicationBuilder.properties(), which are the lowest-precedence
 * properties there are. application.yml's development default
 * (localhost:55432/almira) won, and Flyway migrated the owner's real database.
 * The test did check the URL — after run(), which is after Flyway.
 *
 * Registered in src/test/resources/META-INF/spring.factories, so it applies to
 * every SpringApplication the tests start: @SpringBootTest contexts
 * (ApiTestBase) and hand-built ones alike, with nothing for a new test to
 * remember. Test sources only; the application jar never contains it.
 *
 * Why ApplicationPreparedEvent and not an earlier hook: it is the last moment
 * before refresh(), and nothing opens a connection until refresh() creates the
 * datasource beans. It is also the first moment the environment is final —
 * @DynamicPropertySource values (how ApiTestBase supplies the URL) are added by
 * a context customizer during context preparation, after the environment
 * events, so a guard on ApplicationEnvironmentPreparedEvent would see
 * application.yml's default and refuse every correct test.
 */
class TestDatabaseGuard : ApplicationListener<ApplicationPreparedEvent> {

    override fun onApplicationEvent(event: ApplicationPreparedEvent) {
        refuseUnlessTestDatabase(event.applicationContext.environment)
    }

    companion object {
        /** Every property through which this application, or Spring, could be given a database. */
        val URL_PROPERTIES = listOf("almira.db.url", "spring.datasource.url", "spring.flyway.url")

        fun refuseUnlessTestDatabase(environment: Environment) {
            val wrong = URL_PROPERTIES
                .mapNotNull { name -> environment.getProperty(name)?.let { name to it } }
                .filter { (_, url) -> url != TestInfra.dbUrl }
            if (wrong.isNotEmpty()) {
                throw NotTheTestDatabase(
                    "Refusing to start: this application, started from the tests, would connect to " +
                        wrong.joinToString { (name, url) -> "$name=$url" } +
                        " — not the test database (${TestInfra.dbUrl}). Nothing has connected yet. " +
                        "Pass the URL at a precedence that beats application.yml (command-line " +
                        "arguments or @DynamicPropertySource, not SpringApplicationBuilder.properties()).",
                )
            }
        }
    }

    class NotTheTestDatabase(message: String) : IllegalStateException(message)
}
