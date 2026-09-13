package tech.bhrigu.almira.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.mock.env.MockEnvironment
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tech.bhrigu.almira.config.PageChecksumCheck.Verdict
import tech.bhrigu.almira.support.TestInfra
import javax.sql.DataSource

/**
 * Page checksums: refuse without them, and relax only on a development
 * environment somebody chose. docs/17 §3.
 *
 * Watched failing before being trusted (docs/19): make [PageChecksumCheck.decide]
 * return Protected for "off", or drop the explicit-variable half of
 * `developmentChosen`, and the matching tests below go red.
 */
@DisplayName("Page checksums: a missing or mistyped environment refuses")
class PageChecksumCheckTest {

    private val refusesWith = { v: Verdict -> (v as Verdict.Refuse).reason }

    @Test
    fun `on passes in any environment, including none`() {
        assertThat(PageChecksumCheck.decide("on", null, null)).isEqualTo(Verdict.Protected)
        assertThat(PageChecksumCheck.decide("on", "production", "production")).isEqualTo(Verdict.Protected)
    }

    @Test
    fun `off with development chosen explicitly warns and starts`() {
        assertThat(PageChecksumCheck.decide("off", "development", "development"))
            .isEqualTo(Verdict.DevelopmentWarning)
    }

    @Test
    fun `off in production refuses and says how to fix it`() {
        val reason = refusesWith(PageChecksumCheck.decide("off", "production", "production"))
        assertThat(reason)
            .contains("data_checksums is off")
            .contains("ALMIRA_ENV is 'production'")
            .contains("--data-checksums")
            .contains("docs/17 §3")
    }

    /**
     * The case the check exists for. application.yml resolves
     * almira.environment to "development" when ALMIRA_ENV is missing, so
     * reading only the resolved property would relax on an omission.
     */
    @Test
    fun `a missing ALMIRA_ENV refuses even though the property defaulted to development`() {
        val reason = refusesWith(PageChecksumCheck.decide("off", null, "development"))
        assertThat(reason).contains("ALMIRA_ENV is not set")
    }

    @Test
    fun `a mistyped environment refuses rather than guessing`() {
        assertThat(PageChecksumCheck.decide("off", "develpment", "develpment"))
            .isInstanceOf(Verdict.Refuse::class.java)
        assertThat(PageChecksumCheck.decide("off", "", "development"))
            .isInstanceOf(Verdict.Refuse::class.java)
    }

    @Test
    fun `the variable and the property must agree before anything relaxes`() {
        val reason = refusesWith(PageChecksumCheck.decide("off", "development", "production"))
        assertThat(reason).contains("must agree")
    }

    @Test
    fun `an unreadable or unrecognised value counts as unprotected`() {
        assertThat(refusesWith(PageChecksumCheck.decide(null, "production", "production")))
            .contains("could not be read")
        assertThat(refusesWith(PageChecksumCheck.decide("ON ", "production", "production")))
            .contains("which is not 'on'")
    }

    // ---- against a real server: the query reads what Postgres actually says ----

    private fun env(explicit: String?, resolved: String) = MockEnvironment().apply {
        explicit?.let { setProperty("ALMIRA_ENV", it) }
        setProperty("almira.environment", resolved)
    }

    private fun ownerDataSourceOnTestDb(): DataSource =
        DriverManagerDataSource(TestInfra.dbUrl, TestInfra.dbOwnerUser, TestInfra.dbOwnerPassword)

    /** The test databases are created without checksums, like the dev one. */
    @Test
    fun `a real database without checksums refuses when ALMIRA_ENV is missing`() {
        assertThatThrownBy {
            PageChecksumCheck(env(null, "development")).verify(ownerDataSourceOnTestDb())
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("data_checksums is off")
    }

    @Test
    fun `a real database without checksums starts when development was chosen`() {
        PageChecksumCheck(env("development", "development")).verify(ownerDataSourceOnTestDb())
    }

    /**
     * Needs a server created with checksums on. ALMIRA_TEST_CHECKSUMS_DB_URL
     * (owner credentials in the URL) points at one; otherwise Testcontainers
     * starts one. If neither is possible this skips — and a skip here means
     * the passing half of the check is unproven on that run, not proven.
     */
    @Test
    fun `a real database initialised with --data-checksums starts in production`() {
        val external = System.getenv("ALMIRA_TEST_CHECKSUMS_DB_URL")
        if (external != null) {
            PageChecksumCheck(env("production", "production")).verify(DriverManagerDataSource(external))
            return
        }
        assumeTrue(DockerClientFactory.instance().isDockerAvailable, "needs Docker or ALMIRA_TEST_CHECKSUMS_DB_URL")
        PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
            .withEnv("POSTGRES_INITDB_ARGS", "--data-checksums")
            .use { pg ->
                pg.start()
                val ds = DriverManagerDataSource(pg.jdbcUrl, pg.username, pg.password)
                PageChecksumCheck(env("production", "production")).verify(ds)
            }
    }

    @Test
    fun `an unreachable database refuses outside development`() {
        val unreachable = DriverManagerDataSource(
            "jdbc:postgresql://127.0.0.1:1/nothing?connectTimeout=2", "nobody", "nothing",
        )
        assertThatThrownBy {
            PageChecksumCheck(env("production", "production")).verify(unreachable)
        }.hasMessageContaining("could not be read")
    }
}
