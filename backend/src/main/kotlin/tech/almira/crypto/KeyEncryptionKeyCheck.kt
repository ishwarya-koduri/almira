package tech.almira.crypto

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import tech.almira.config.PageChecksumCheck
import tech.almira.config.StartupRefusal
import javax.sql.DataSource

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
 * Two checks, before Flyway migrates — so before the web server accepts a
 * connection too. It used to wait for migrations (it injected Flyway so as to
 * read the table afterwards), which meant a server restored with the wrong key
 * applied every pending migration to the restored copy and only then refused.
 * It is a [StartupRefusal] now, so it runs first; a database with no
 * `encryption_keys` table yet — a fresh install, before its first migration —
 * has nothing to check, exactly like an empty table.
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
 * Reads through the owner pool, because `encryption_keys` is behind
 * row-level security and there is no user at startup.
 */
@Component
@Order(3)
class KeyEncryptionKeyCheck(
    // A provider, not the service: every StartupRefusal is built before any is
    // verified, and building the local key service can write a development key
    // file. Asked for here, after the page-checksum and runtime-role refusals.
    private val kmsProvider: ObjectProvider<KeyManagementService>,
    // The owner pool itself, not the systemJdbcBypassingRls template: Spring Boot
    // makes every JdbcTemplate bean depend on Flyway (database initialisation
    // ordering), so injecting one here would put Flyway in front of this again —
    // as a circular reference, since Flyway waits for this.
    @Qualifier("ownerDataSource") ownerDataSource: DataSource,
    private val environment: Environment,
) : StartupRefusal {

    private val log = LoggerFactory.getLogger(javaClass)
    private val system = JdbcTemplate(ownerDataSource)

    override fun verifyBeforeMigrating() {
        val kms = kmsProvider.getObject()
        val migrated = system.queryForObject(
            "select to_regclass('public.encryption_keys') is not null", Boolean::class.java,
        ) == true
        if (!migrated) {
            log.info("Key-encryption key {}: no encryption_keys table yet, nothing to check", kms.kekId)
            return
        }
        val rows = system.query(
            "select kek_id, count(*) as n from encryption_keys group by kek_id",
        ) { rs, _ -> rs.getString("kek_id") to rs.getLong("n") }.toMap()

        val sample = if (rows.containsKey(kms.kekId)) {
            system.query(
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
