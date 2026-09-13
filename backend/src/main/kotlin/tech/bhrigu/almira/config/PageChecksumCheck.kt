package tech.bhrigu.almira.config

import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import java.sql.SQLException
import javax.sql.DataSource

/**
 * Refuses to start on a database whose pages carry no checksums.
 *
 * The reasoning is in docs/17 §3 and is specific to what this stores: a
 * zero-knowledge ciphertext is the one kind of data where the server cannot
 * tell it has been corrupted, because reading it is exactly what the server
 * cannot do. Without `data_checksums` a flipped bit on disk is served as though
 * it were fine, and is found by the owner on the day the field fails to open.
 *
 * **It runs before migrations, against the database the application actually
 * uses.** That is why it is called from [DatabaseConfig] rather than being an
 * `EnvironmentPostProcessor` like the provider check. A post-processor runs
 * before every property source is attached, so it would have to open its own
 * connection from whatever `almira.db.url` said at that moment — and in the
 * test suite, where the URL arrives later, that is the development database on
 * this machine rather than the one under test. A safety check that silently
 * inspects a different database from the one it protects is worse than none.
 * Running before Flyway means a refused boot has written nothing.
 *
 * **It fails closed, twice.**
 *
 *  - Only an exact `on` passes. `off`, anything unrecognised, or a value that
 *    could not be read at all counts as unprotected.
 *  - It relaxes only when development was *chosen*: `ALMIRA_ENV` is set to
 *    `development` and `almira.environment` resolves to `development`. The
 *    packaged application.yml defaults `almira.environment` to `development`
 *    when `ALMIRA_ENV` is missing, so reading that property alone would relax
 *    on a missing variable — which is the one thing docs/17 §3 rules out. A
 *    typo or an omission in a deployment has to fail towards refusing.
 *
 * In development it warns instead, because enabling checksums on an existing
 * volume means recreating it, and the development database stays as it is.
 */
class PageChecksumCheck(private val environment: Environment) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun verify(dataSource: DataSource) {
        val (reading, readError) = try {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("show data_checksums").use { rs ->
                        (if (rs.next()) rs.getString(1) else null) to null
                    }
                }
            }
        } catch (e: SQLException) {
            null to e
        }

        val verdict = decide(
            checksums = reading,
            explicitEnv = environment.getProperty(ENV_VARIABLE),
            resolvedEnv = environment.getProperty("almira.environment"),
        )

        when (verdict) {
            Verdict.Protected -> log.info("Page checksums: on")

            is Verdict.DevelopmentWarning -> log.warn(
                "Page checksums are {} on this database. Allowed only because " +
                    "{}=development was set explicitly; a deployment with this " +
                    "database would refuse to start. See docs/17 §3.",
                reading ?: "unreadable (${readError?.message})",
                ENV_VARIABLE,
            )

            is Verdict.Refuse -> throw IllegalStateException(
                "Refusing to start — ${verdict.reason}",
                readError,
            )
        }
    }

    sealed interface Verdict {
        data object Protected : Verdict
        data object DevelopmentWarning : Verdict
        data class Refuse(val reason: String) : Verdict
    }

    companion object {
        const val ENV_VARIABLE = "ALMIRA_ENV"

        /**
         * Development was CHOSEN: the variable says so and the property agrees.
         * Missing, empty, mistyped or disagreeing all mean no. Shared with the
         * other startup checks that relax in development, so there is one rule.
         */
        fun developmentChosen(explicitEnv: String?, resolvedEnv: String?): Boolean =
            explicitEnv.equals("development", ignoreCase = true) &&
                resolvedEnv.equals("development", ignoreCase = true)

        /**
         * The whole decision, with no database and no Spring, so every branch
         * can be tested directly. [checksums] is the raw `show data_checksums`
         * value, or null if it could not be read.
         */
        fun decide(checksums: String?, explicitEnv: String?, resolvedEnv: String?): Verdict {
            if (checksums == "on") return Verdict.Protected

            if (developmentChosen(explicitEnv, resolvedEnv)) return Verdict.DevelopmentWarning

            val state = when (checksums) {
                null -> "could not be read"
                "off" -> "is off"
                else -> "is '$checksums', which is not 'on'"
            }
            val why = when {
                explicitEnv == null ->
                    "$ENV_VARIABLE is not set, and a missing value is never treated as development"
                !explicitEnv.equals("development", ignoreCase = true) ->
                    "$ENV_VARIABLE is '$explicitEnv'"
                else ->
                    "$ENV_VARIABLE is 'development' but almira.environment resolves to " +
                        "'$resolvedEnv', and the two must agree before anything is relaxed"
            }
            return Verdict.Refuse(
                "Postgres data_checksums $state, and $why. Without page checksums a " +
                    "corrupted zero-knowledge ciphertext is served as if it were intact. " +
                    "Create the database with `initdb --data-checksums` " +
                    "(POSTGRES_INITDB_ARGS=--data-checksums), or enable it on a stopped " +
                    "server with `pg_checksums --enable`. See docs/17 §3.",
            )
        }
    }
}
