package tech.bhrigu.almira.config

import org.assertj.core.api.SoftAssertions
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.event.ApplicationContextInitializedEvent
import org.springframework.context.ApplicationListener
import tech.bhrigu.almira.AlmiraApplication
import tech.bhrigu.almira.support.TestInfra
import java.nio.file.Files
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * A server configured wrongly is refused before anything acts on the
 * configuration — not merely refused.
 *
 * Each of these refusals used to be thrown from a bean's constructor, after the
 * Flyway bean had migrated the database and, in development, after a new
 * key-encryption key file had been written. So each case proves three things
 * did NOT happen when the refusal came:
 *
 *  - no application context was created, so no bean — no pool, no Flyway, no
 *    key file — could have run;
 *  - flyway_schema_history has exactly as many rows as before. On the suite's
 *    already-migrated database that cannot move; the watched-failing run was
 *    made against a new, empty throwaway database, where it went from nothing
 *    to 35 rows with the check moved back into the beans;
 *  - the development key file this start was told to use does not exist.
 *
 * The real application and application.yml, started the way
 * ProviderDisabledStartupTest starts it, so the check is proven to be reached
 * from spring.factories and not only when called by hand.
 */
@DisplayName("Startup settings are refused before the database is migrated or a key file written")
class StartupSettingsCheckTest {

    private data class Case(val label: String, val settings: Map<String, String>, val refusal: String)

    private val cases = listOf(
        Case("an unknown sign-in channel", mapOf("almira.auth.sign-in-channels" to "emial"), "which is not phone or email"),
        Case(
            "email sign-in with nobody on the allowlist",
            mapOf("almira.auth.sign-in-channels" to "email"),
            "almira.auth.email-allowlist (ALMIRA_ALPHA_EMAIL_ALLOWLIST) is empty",
        ),
        Case(
            "email sign-in with the email provider disabled",
            mapOf(
                "almira.auth.sign-in-channels" to "email",
                "almira.auth.email-allowlist" to "asha@example.com",
                "almira.providers.email.mode" to "disabled",
            ),
            "almira.providers.email.mode (ALMIRA_PROVIDER_EMAIL_MODE) is 'disabled'",
        ),
        Case(
            "a key-encryption key that is not 32 bytes",
            mapOf("almira.encryption.master-key" to "AAAA"),
            "must be 32 bytes (AES-256); got 3",
        ),
        Case(
            "no key-encryption key outside development",
            mapOf("ALMIRA_ENV" to "production", "almira.jwt.secret" to "K".repeat(48)),
            "ALMIRA_KMS_MASTER_KEY is not set",
        ),
        Case(
            "the development signing secret outside development",
            mapOf(
                "ALMIRA_ENV" to "production",
                "almira.jwt.secret" to "development-only-secret-please-override-in-every-real-environment",
            ),
            "ALMIRA_JWT_SECRET is still the development default",
        ),
        Case(
            "a provider allowed a hundred attempts",
            mapOf("almira.providers.sms.max-attempts" to "99"),
            "almira.providers.sms.max-attempts must be 1 to 10 (is 99)",
        ),
        Case(
            "a one-time-code send allowed a minute",
            mapOf("almira.otp.send-timeout" to "60s"),
            "almira.otp.send-timeout must be more than zero and at most PT15S (is PT1M)",
        ),
        // Properties classes that refuse in their own init blocks. These were
        // built when first injected, which was after Flyway.
        Case(
            "a grievance answered in 120 days",
            mapOf("almira.privacy.grievance.response-days" to "120"),
            "almira.privacy.grievance.response-days is 120; it must be between 1 and 90",
        ),
        Case(
            "a default plan that is not defined",
            mapOf("almira.plans.default-plan" to "gold"),
            "almira.plans.default-plan is 'gold', which is not one of almira.plans.definitions",
        ),
        Case(
            "a support channel nobody reads",
            mapOf("almira.support.channel" to "pigeon"),
            "almira.support.channel is 'pigeon'",
        ),
        Case(
            "continuity links over plain http",
            mapOf("almira.continuity.link-base-url" to "http://almira.example.in"),
            "almira.continuity.link-base-url must be an https address",
        ),
    )

    @TestFactory
    fun `each refusal comes before the context, the migration and the key file`(): List<DynamicTest> =
        cases.map { case -> DynamicTest.dynamicTest(case.label) { refusedBeforeAnything(case) } }

    private fun refusedBeforeAnything(case: Case) {
        val keyFile = Files.createTempDirectory("almira-startup-check").resolve("dev-kek")
        val properties = mutableMapOf(
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
            "almira.encryption.dev-key-file" to keyFile.toString(),
            "server.port" to "0",
        )
        properties.putAll(case.settings)
        // Command-line arguments, which beat application.yml (see ProviderDisabledStartupTest).
        val args = properties.map { (key, value) -> "--$key=$value" }.toTypedArray()

        val contexts = AtomicInteger()
        val migrationsBefore = appliedMigrations()

        val failure = catchThrowable {
            SpringApplicationBuilder(AlmiraApplication::class.java)
                .listeners(ApplicationListener<ApplicationContextInitializedEvent> { contexts.incrementAndGet() })
                .run(*args)
                .close()
        }

        // Soft, so a watched-failing run reports every one of these that happened.
        val softly = SoftAssertions()
        softly.assertThat(generateSequence(failure) { it.cause }.mapNotNull { it.message }.toList())
            .describedAs("the startup failure is this refusal")
            .anyMatch { it.contains(case.refusal) }
        softly.assertThat(contexts.get())
            .describedAs("application contexts created before the refusal — any one of them builds Flyway and the pools")
            .isZero()
        softly.assertThat(appliedMigrations())
            .describedAs("flyway_schema_history rows after the refusal")
            .isEqualTo(migrationsBefore)
        softly.assertThat(keyFile)
            .describedAs("the development key file, written by LocalKeyManagement if it was ever built")
            .doesNotExist()
        softly.assertAll()
    }

    /** Rows in flyway_schema_history, or 0 when there is no such table yet. */
    private fun appliedMigrations(): Int =
        DriverManager.getConnection(TestInfra.dbUrl, TestInfra.dbOwnerUser, TestInfra.dbOwnerPassword).use { c ->
            fun int(sql: String) = c.createStatement().use { s -> s.executeQuery(sql).use { rs -> rs.next(); rs.getInt(1) } }
            if (int("select (to_regclass('public.flyway_schema_history') is not null)::int") == 0) 0
            else int("select count(*) from public.flyway_schema_history")
        }
}
