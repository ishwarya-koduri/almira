package tech.bhrigu.almira.support

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Where the tests get their Postgres and Redis.
 *
 * Two modes, and the choice is explicit rather than clever:
 *
 *  - EXTERNAL, when ALMIRA_TEST_DB_URL is set. CI points this at service
 *    containers; locally it points at `docker compose up` (see the README).
 *    Faster than Testcontainers, and it works in environments that restrict
 *    direct access to the Docker API — which is not unusual, and which
 *    Testcontainers cannot work around.
 *
 *  - TESTCONTAINERS otherwise, so a developer with Docker can clone the repo
 *    and run `./gradlew test` with no setup at all.
 *
 * Either way the database is created with the same two-role split as
 * production: tests run as the non-owner `almira_app`, so they exercise the
 * real row-level security boundary rather than a permissive stand-in.
 */
object TestInfra {

    private val externalDbUrl: String? = System.getenv("ALMIRA_TEST_DB_URL")

    val usingExternal: Boolean = externalDbUrl != null

    private val postgres: PostgreSQLContainer<*>? =
        if (usingExternal) null
        else PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("almira")
            .withUsername("almira")
            .withPassword("dev")
            .withInitScript("testcontainers-init.sql")
            .also { it.start() }

    private val redis: GenericContainer<*>? =
        if (usingExternal) null
        else GenericContainer(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .also { it.start() }

    val dbUrl: String = externalDbUrl ?: postgres!!.jdbcUrl
    val dbOwnerUser: String = System.getenv("ALMIRA_TEST_DB_OWNER_USER") ?: postgres?.username ?: "almira"
    val dbOwnerPassword: String = System.getenv("ALMIRA_TEST_DB_OWNER_PASSWORD") ?: postgres?.password ?: "dev"
    val dbAppUser: String = System.getenv("ALMIRA_TEST_DB_APP_USER") ?: "almira_app"
    val dbAppPassword: String = System.getenv("ALMIRA_TEST_DB_APP_PASSWORD") ?: "app_dev_password"

    val redisHost: String = System.getenv("ALMIRA_TEST_REDIS_HOST") ?: redis!!.host
    val redisPort: Int =
        System.getenv("ALMIRA_TEST_REDIS_PORT")?.toInt() ?: redis!!.getMappedPort(6379)

    init {
        // The test suite signs users up and deletes records. Running it against
        // a database called `almira` would mean running it against someone's
        // real data, so refuse rather than trust the caller got it right.
        require(!dbUrl.trimEnd('/').endsWith("/almira")) {
            "ALMIRA_TEST_DB_URL points at the development database ($dbUrl). " +
                "Use almira_test — the test suite writes and deletes data."
        }
    }
}
