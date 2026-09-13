package tech.bhrigu.almira.crypto

import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import tech.bhrigu.almira.config.PageChecksumCheck

/**
 * Refuses to start with a key-encryption key that does not open this database.
 *
 * Found by the restore drill (docs/17 §6): a restored copy started with the
 * wrong ALMIRA_KMS_MASTER_KEY booted, reported ready on /health/ready, and
 * failed only when somebody revealed an account number or opened a document —
 * as a 500 that says "something went wrong on our side". That is the most
 * likely mistake in a restore (the key lives somewhere else, on purpose) and it
 * looked exactly like a healthy deployment.
 *
 * Two checks, before the web server accepts a connection:
 *
 *  1. **The fingerprint.** Every wrapped household key records the `kek_id` of
 *     the KEK that wrapped it. If household keys exist and not one of them names
 *     the loaded KEK, this is the wrong key. KEK rotation is not implemented, so
 *     there is no legitimate reason for that.
 *  2. **An actual unwrap** of one key that does name it. A matching fingerprint
 *     is 48 bits of a hash; an unwrap is proof. It uses the key and discards the
 *     result.
 *
 * Keys wrapped by some other KEK alongside matching ones are logged as a
 * warning, not refused: those households' encrypted fields are unreadable, which
 * someone needs to know, but refusing would take every other household down too.
 *
 * An empty table passes — a fresh install has nothing to check against.
 *
 * Relaxes to a warning only when development was explicitly chosen, by the same
 * rule as [PageChecksumCheck]: a development key regenerated on a laptop orphans
 * that laptop's data, which is worth a warning and not worth a failing test suite.
 *
 * Reads through the owner connection, because `encryption_keys` is behind
 * row-level security and there is no user at startup.
 */
@Component
class KeyEncryptionKeyCheck(
    private val kms: KeyManagementService,
    @Qualifier("systemJdbcBypassingRls") private val system: NamedParameterJdbcTemplate,
    private val environment: Environment,
    // Injected only so migrations have run before this reads the table.
    @Suppress("unused") private val flyway: Flyway,
) : InitializingBean {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun afterPropertiesSet() {
        val rows = system.jdbcTemplate.query(
            "select kek_id, count(*) as n from encryption_keys group by kek_id",
        ) { rs, _ -> rs.getString("kek_id") to rs.getLong("n") }.toMap()

        val sample = if (rows.containsKey(kms.kekId)) {
            system.jdbcTemplate.query(
                "select wrapped_dek from encryption_keys where kek_id = ? limit 1",
                { rs, _ -> rs.getBytes(1) }, kms.kekId,
            ).firstOrNull()
        } else {
            null
        }
        val unwraps = sample?.let { runCatching { kms.unwrap(it) }.isSuccess }

        when (val verdict = decide(kms.kekId, rows, unwraps)) {
            is Verdict.Ok -> log.info("Key-encryption key {}: {}", kms.kekId, verdict.summary)
            is Verdict.Warn -> log.warn("Key-encryption key {}: {}", kms.kekId, verdict.message)
            is Verdict.Refuse -> {
                val developmentChosen = PageChecksumCheck.developmentChosen(
                    environment.getProperty(PageChecksumCheck.ENV_VARIABLE),
                    environment.getProperty("almira.environment"),
                )
                if (developmentChosen) {
                    log.warn("Key-encryption key {}: {} (allowed only because development was chosen)",
                        kms.kekId, verdict.reason)
                } else {
                    throw IllegalStateException("Refusing to start — ${verdict.reason}")
                }
            }
        }
    }

    sealed interface Verdict {
        data class Ok(val summary: String) : Verdict
        data class Warn(val message: String) : Verdict
        data class Refuse(val reason: String) : Verdict
    }

    companion object {
        /**
         * [rows] is household-key count by `kek_id`; [unwraps] is whether a key
         * naming [loaded] unwrapped, or null if none was tried.
         */
        fun decide(loaded: String, rows: Map<String, Long>, unwraps: Boolean?): Verdict {
            val total = rows.values.sum()
            if (total == 0L) return Verdict.Ok("no household keys yet")

            val matching = rows[loaded] ?: 0L
            val others = rows.filterKeys { it != loaded }
            if (matching == 0L) {
                return Verdict.Refuse(
                    "ALMIRA_KMS_MASTER_KEY is not the key this database was encrypted with. " +
                        "It is $loaded; all $total household key(s) here were wrapped by " +
                        others.keys.sorted().joinToString(", ") + ". Account numbers and documents " +
                        "would fail to open for everyone. After a restore, use the key that was in " +
                        "use when the backup was taken (docs/17 §4, §6).",
                )
            }
            if (unwraps == false) {
                return Verdict.Refuse(
                    "a household key names $loaded but does not unwrap with it. Either the key " +
                        "material differs from the one that wrote it despite the matching " +
                        "fingerprint, or the stored key is damaged (docs/17 §6).",
                )
            }
            if (others.isNotEmpty()) {
                return Verdict.Warn(
                    "${others.values.sum()} of $total household key(s) were wrapped by a different " +
                        "KEK (${others.keys.sorted().joinToString(", ")}) and cannot be opened with this one.",
                )
            }
            return Verdict.Ok("opens all $total household key(s)")
        }
    }
}
