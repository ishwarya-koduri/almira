package tech.bhrigu.almira.config

import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.assertj.core.api.SoftAssertions
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.event.ApplicationStartedEvent
import org.springframework.boot.web.context.WebServerInitializedEvent
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ApplicationListener
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.mock.env.MockEnvironment
import tech.bhrigu.almira.AlmiraApplication
import tech.bhrigu.almira.config.RuntimeRoleCheck.Role
import tech.bhrigu.almira.config.RuntimeRoleCheck.Verdict
import tech.bhrigu.almira.support.TestInfra
import java.sql.DriverManager
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A runtime role that can get past row-level security refuses to start, before
 * Flyway migrates and before anything is served — in every environment,
 * development and this suite included.
 *
 * The startup cases prove the actions did NOT happen, not only that the start
 * failed:
 *
 *  - the `flyway` bean never reached initialisation, which is where its
 *    `migrate` runs (a BeanPostProcessor records every bean that does);
 *  - no web server was started and no application reached "started";
 *  - flyway_schema_history has as many rows as before.
 *
 * Watched failing with the check moved back behind the action: calling
 * `roleCheck.verify` only from the overridden `migrate`, after `super.migrate()`,
 * made every startup case report `flyway` as initialised; deleting the call
 * altogether made every one of them start a web server. See the commit.
 *
 * The roles are made here, as the owner, and dropped afterwards. Each can log
 * in; each is wrong in exactly one way.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("A runtime role that bypasses row-level security refuses to start, before migrating or serving")
class RuntimeRoleCheckTest {

    private val password = "role-guard-test"

    private data class Case(val label: String, val role: String, val refusal: String)

    private val cases = listOf(
        Case("a superuser", "almira_guard_super", "'almira_guard_super' (ALMIRA_DB_APP_USER) is a SUPERUSER"),
        Case("BYPASSRLS", "almira_guard_bypass", "'almira_guard_bypass' (ALMIRA_DB_APP_USER) has BYPASSRLS"),
        Case(
            "the owner of a table under row-level security",
            "almira_guard_owner",
            "'almira_guard_owner' (ALMIRA_DB_APP_USER) owns tables under row-level security (almira_guard.owned)",
        ),
        Case(
            "a member of a BYPASSRLS role, without inheriting it",
            "almira_guard_member",
            "'almira_guard_member' is a member of 'almira_guard_bypass' (so can SET ROLE to it), which has BYPASSRLS",
        ),
        Case("CREATEROLE", "almira_guard_createrole", "'almira_guard_createrole' (ALMIRA_DB_APP_USER) has CREATEROLE"),
        Case("the schema owner itself", TestInfra.dbOwnerUser, "is the schema owner (ALMIRA_DB_OWNER_USER)"),
    )

    @BeforeAll
    fun createRoles() {
        // Migrated first. Otherwise, on a fresh database, the table below would be
        // the only thing in the database when the positive case's Flyway looks,
        // and baselineOnMigrate would skip V1. The table lives in its own schema
        // so public stays exactly as the migrations left it.
        Flyway.configure().dataSource(TestInfra.dbUrl, TestInfra.dbOwnerUser, TestInfra.dbOwnerPassword)
            .locations("classpath:db/migration").baselineOnMigrate(true).load().migrate()
        dropRoles()
        owner(
            "create role almira_guard_super login superuser password '$password'",
            "create role almira_guard_bypass login bypassrls password '$password'",
            "create role almira_guard_owner login password '$password'",
            "create role almira_guard_member login noinherit password '$password'",
            "grant almira_guard_bypass to almira_guard_member",
            "create role almira_guard_createrole login createrole password '$password'",
            "create role almira_guard_late login password '$password'",
            "create schema almira_guard",
            "create table almira_guard.owned (id int)",
            "alter table almira_guard.owned enable row level security",
            "alter table almira_guard.owned owner to almira_guard_owner",
        )
    }

    @AfterAll
    fun dropRoles() {
        owner(
            "drop schema if exists almira_guard cascade",
            "drop role if exists almira_guard_member",
            "drop role if exists almira_guard_super",
            "drop role if exists almira_guard_bypass",
            "drop role if exists almira_guard_owner",
            "drop role if exists almira_guard_createrole",
            "drop role if exists almira_guard_late",
        )
    }

    @TestFactory
    fun `each refuses before Flyway migrates and before anything is served`(): List<DynamicTest> =
        cases.map { case -> DynamicTest.dynamicTest(case.label) { refusedBeforeMigrating(case) } }

    @Test
    fun `the suite's own two-role split starts, reading the catalogue as almira_app`() {
        start(TestInfra.dbAppUser, TestInfra.dbAppPassword).use { context ->
            assertThat(context.isActive).isTrue()
        }
    }

    private fun refusedBeforeMigrating(case: Case) {
        val initialised = CopyOnWriteArrayList<String>()
        val served = CopyOnWriteArrayList<String>()
        val migrationsBefore = appliedMigrations()
        val appPassword = if (case.role == TestInfra.dbOwnerUser) TestInfra.dbOwnerPassword else password

        val failure = catchThrowable {
            start(case.role, appPassword, initialised, served).close()
        }

        val softly = SoftAssertions()
        softly.assertThat(generateSequence(failure) { it.cause }.mapNotNull { it.message }.toList())
            .describedAs("the startup failure is this refusal, naming the role and the attribute")
            .anyMatch { it.contains("Refusing to start (before migrating)") && it.contains(case.refusal) }
        softly.assertThat(initialised)
            .describedAs("beans initialised before the refusal — `flyway` initialising is `migrate` running")
            .doesNotContain("flyway")
        softly.assertThat(served)
            .describedAs("a web server started, or the application reached started")
            .isEmpty()
        softly.assertThat(appliedMigrations())
            .describedAs("flyway_schema_history rows after the refusal")
            .isEqualTo(migrationsBefore)
        softly.assertAll()
    }

    private fun start(
        appUser: String,
        appPassword: String,
        initialised: MutableList<String> = mutableListOf(),
        served: MutableList<String> = mutableListOf(),
    ): ConfigurableApplicationContext {
        val properties = mapOf(
            "almira.db.url" to TestInfra.dbUrl,
            "almira.db.owner-user" to TestInfra.dbOwnerUser,
            "almira.db.owner-password" to TestInfra.dbOwnerPassword,
            "almira.db.app-user" to appUser,
            "almira.db.app-password" to appPassword,
            "almira.db.max-pool-size" to "1",
            "spring.data.redis.host" to TestInfra.redisHost,
            "spring.data.redis.port" to TestInfra.redisPort.toString(),
            "almira.otp.provider" to "log",
            // Development, chosen explicitly: the refusal must not relax there.
            "ALMIRA_ENV" to "development",
            "almira.jwt.secret" to "test-only-secret-that-is-long-enough-for-hmac256-signing",
            "almira.outbox.background" to "false",
            "server.port" to "0",
        )
        // Command-line arguments, which beat application.yml (see ProviderDisabledStartupTest).
        val args = properties.map { (key, value) -> "--$key=$value" }.toTypedArray()
        return SpringApplicationBuilder(AlmiraApplication::class.java)
            .initializers(
                ApplicationContextInitializer<ConfigurableApplicationContext> { context ->
                    context.beanFactory.addBeanPostProcessor(object : BeanPostProcessor {
                        override fun postProcessBeforeInitialization(bean: Any, beanName: String): Any {
                            initialised += beanName
                            return bean
                        }
                    })
                },
            )
            .listeners(
                ApplicationListener<WebServerInitializedEvent> { served += "web server on ${it.webServer.port}" },
                ApplicationListener<ApplicationStartedEvent> { served += "application started" },
            )
            .run(*args)
    }

    /**
     * A migration runs as the owner and could hand the runtime role what the
     * first check refused. Here the role is clean when the Flyway bean is built
     * and gains BYPASSRLS before `migrate` returns — the re-check refuses it,
     * which in a real start is before the web server is created.
     */
    @Test
    fun `a role that can bypass once migrations have run refuses before serving`() {
        val props = AlmiraProperties(
            db = AlmiraProperties.Db(
                TestInfra.dbUrl, TestInfra.dbOwnerUser, TestInfra.dbOwnerPassword, "almira_guard_late", password,
            ),
            jwt = AlmiraProperties.Jwt("test-only-secret-that-is-long-enough-for-hmac256-signing"),
            otp = AlmiraProperties.Otp(),
        )
        val environment = MockEnvironment().apply {
            setProperty("ALMIRA_ENV", "development")
            setProperty("almira.environment", "development")
        }
        val config = DatabaseConfig(props)
        config.ownerDataSource().use { ownerPool ->
            (config.dataSource() as HikariDataSource).use { runtimePool ->
                val flyway = config.flyway(ownerPool, runtimePool, environment)
                owner("alter role almira_guard_late bypassrls")
                assertThatThrownBy { flyway.migrate() }
                    .hasMessageContaining("Refusing to start (after migrating, before serving)")
                    .hasMessageContaining("'almira_guard_late' (ALMIRA_DB_APP_USER) has BYPASSRLS")
            }
        }
    }

    // --- the decision, without a database ------------------------------------

    private fun self(name: String = "almira_app", superuser: Boolean = false, bypass: Boolean = false) =
        Role(name, isSelf = true, superuser = superuser, bypassRls = bypass)

    @Test
    fun `a plain non-owner role is enforced`() {
        assertThat(RuntimeRoleCheck.decide("almira_app", "almira", listOf(self()))).isEqualTo(Verdict.Enforced)
    }

    @Test
    fun `an unreadable catalogue refuses`() {
        assertThat((RuntimeRoleCheck.decide("almira_app", "almira", null) as Verdict.Refuse).reason)
            .contains("could not be read")
    }

    @Test
    fun `a pool connected as someone other than the configured role refuses`() {
        assertThat((RuntimeRoleCheck.decide("almira_app", "almira", listOf(self("postgres"))) as Verdict.Refuse).reason)
            .contains("connected as 'postgres', not as ALMIRA_DB_APP_USER 'almira_app'")
    }

    @Test
    fun `every problem is named, not only the first`() {
        val verdict = RuntimeRoleCheck.decide(
            "almira_app", "almira",
            listOf(
                self(bypass = true),
                Role("almira", isSelf = false, superuser = true, ownedRlsTables = List(7) { "public.t$it" }),
            ),
        )
        assertThat((verdict as Verdict.Refuse).reason)
            .contains("'almira_app' (ALMIRA_DB_APP_USER) has BYPASSRLS")
            .contains("is a member of 'almira' (so can SET ROLE to it), which is a SUPERUSER")
            .contains("public.t0, public.t1, public.t2, public.t3, public.t4 and 2 more")
    }

    // --- helpers --------------------------------------------------------------

    private fun owner(vararg statements: String) =
        DriverManager.getConnection(TestInfra.dbUrl, TestInfra.dbOwnerUser, TestInfra.dbOwnerPassword).use { c ->
            statements.forEach { sql -> c.createStatement().use { it.execute(sql) } }
        }

    /** Rows in flyway_schema_history, or 0 when there is no such table yet. */
    private fun appliedMigrations(): Int =
        DriverManager.getConnection(TestInfra.dbUrl, TestInfra.dbOwnerUser, TestInfra.dbOwnerPassword).use { c ->
            fun int(sql: String) = c.createStatement().use { s -> s.executeQuery(sql).use { rs -> rs.next(); rs.getInt(1) } }
            if (int("select (to_regclass('public.flyway_schema_history') is not null)::int") == 0) 0
            else int("select count(*) from public.flyway_schema_history")
        }
}
