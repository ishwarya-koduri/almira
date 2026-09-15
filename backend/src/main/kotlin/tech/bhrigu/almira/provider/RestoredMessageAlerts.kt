package tech.bhrigu.almira.provider

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tech.bhrigu.almira.config.AlmiraProperties
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * The operator's alert for queued messages a restore left without a body
 * (V145; docs/13 "After a restore").
 *
 * Owner's decision, 2026-09-15: *alert on body_not_restored, once per restore
 * with a count, not per message. A permanently undeliverable queued message is
 * exactly the silent class.*
 *
 * Both workers — [NotificationOutbox] and the sign-in email outbox — call
 * [noticed] with how many such messages a claim finished. That adds to the
 * restore they belong to: the newest `restore_events` row noticed or restored in
 * the last [SAME_RESTORE] (scripts/restore.sh writes one per restore), or, when
 * there is none, a new row marked `detected` — a restore done some other way.
 * A body can only be missing after a restore, so everything found within that
 * window is that restore's.
 *
 * [raiseDue] then says it once: when a restore has lost messages, has not been
 * alerted, and nothing more has been found for [SETTLE] — long enough for both
 * workers to have drained what they had — it stamps `alerted_at` with the count
 * so far and logs one ERROR line, [ALERT_EVENT]. The stamp and the count are one
 * conditional update, so two servers never both say it. What is found after that
 * still counts (`not_restored`), and the operator's view of /health shows it
 * beside `alerted_count`; it raises no second alert.
 *
 * Nothing here names a message, an address or a person: a time, where the
 * restore row came from, the backup's name and counts.
 */
@Component
class RestoredMessageAlerts(
    @Qualifier("ownerDataSource") ownerDataSource: DataSource,
    private val props: AlmiraProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val jdbc = NamedParameterJdbcTemplate(ownerDataSource)
    private val transactions = TransactionTemplate(DataSourceTransactionManager(ownerDataSource))

    data class Raised(
        val restoreId: UUID,
        val restoredAt: Instant,
        val recordedBy: String,
        val backupName: String?,
        val count: Int,
    )

    data class Latest(
        val restoredAt: Instant,
        val recordedBy: String,
        val notRestored: Int,
        val alertedAt: Instant?,
        val alertedCount: Int?,
    )

    /** Adds [count] body_not_restored messages to the restore they belong to. */
    fun noticed(count: Int) {
        if (count <= 0) return
        transactions.executeWithoutResult {
            // One at a time across servers: two workers finding the first lost message
            // of an unrecorded restore at once must open one row, not two.
            jdbc.query("select pg_advisory_xact_lock(hashtext('restore_events'))", emptyMap<String, Any>()) { _, _ -> }
            val window = MapSqlParameterSource("window", SAME_RESTORE.seconds.toDouble())
            val existing = jdbc.query(
                """
                select id from restore_events
                 where coalesce(last_noticed_at, restored_at) >= now() - make_interval(secs => :window)
                 order by restored_at desc
                 limit 1
                """.trimIndent(),
                window,
            ) { rs, _ -> rs.getObject("id", UUID::class.java) }.firstOrNull()
            val id = existing ?: jdbc.queryForObject(
                "insert into restore_events (recorded_by) values ('detected') returning id",
                emptyMap<String, Any>(),
                UUID::class.java,
            )!!
            if (existing == null) {
                log.warn(
                    "restore alerts: queued messages have no body, and no restore was recorded in the last {} hours; " +
                        "counting them against a detected restore ({})",
                    SAME_RESTORE.toHours(), id,
                )
            }
            jdbc.update(
                """
                update restore_events
                   set not_restored = not_restored + :count,
                       first_noticed_at = coalesce(first_noticed_at, now()),
                       last_noticed_at = now()
                 where id = :id
                """.trimIndent(),
                MapSqlParameterSource().addValue("count", count).addValue("id", id),
            )
        }
    }

    /** The poll, on the outbox's cadence: says each settled restore's loss once. */
    @Scheduled(
        initialDelayString = "\${almira.outbox.poll-interval:PT2S}",
        fixedDelayString = "\${almira.outbox.poll-interval:PT2S}",
    )
    fun scheduled() {
        if (!props.outbox.background) return
        runCatching { raiseDue() }.onFailure { log.warn("restore alerts: check failed: {}", it.javaClass.simpleName) }
    }

    /**
     * Raises the alert for every restore that lost messages, has not been alerted,
     * and has had nothing new found for [settle]. Returns what it raised.
     */
    fun raiseDue(settle: Duration = SETTLE): List<Raised> {
        val raised = jdbc.query(
            """
            update restore_events
               set alerted_at = now(), alerted_count = not_restored
             where alerted_at is null and not_restored > 0
               and last_noticed_at <= now() - make_interval(secs => :settle)
            returning id, restored_at, recorded_by, backup_name, alerted_count
            """.trimIndent(),
            MapSqlParameterSource("settle", settle.toMillis() / 1000.0),
        ) { rs, _ ->
            Raised(
                restoreId = rs.getObject("id", UUID::class.java),
                restoredAt = rs.getTimestamp("restored_at").toInstant(),
                recordedBy = rs.getString("recorded_by"),
                backupName = rs.getString("backup_name"),
                count = rs.getInt("alerted_count"),
            )
        }
        raised.forEach {
            log.error(
                "{}: {} queued message(s) had no body after the restore of {} ({}{}) and will never be sent; " +
                    "each is recorded failed as body_not_restored. The people they were for were not told. " +
                    "Check outbound_messages and sign_in_code_emails where failure = 'body_not_restored' " +
                    "(docs/13 \"After a restore\"). Restore {}.",
                ALERT_EVENT, it.count, it.restoredAt,
                if (it.recordedBy == "detected") "not recorded by restore.sh; detected" else "recorded by restore.sh",
                it.backupName?.let { name -> ", backup $name" } ?: "",
                it.restoreId,
            )
        }
        return raised
    }

    /** The newest restore that lost messages, within [within], for the operator's view of /health. */
    fun latest(within: Duration = SHOWN_FOR): Latest? = jdbc.query(
        """
        select restored_at, recorded_by, not_restored, alerted_at, alerted_count
          from restore_events
         where not_restored > 0 and restored_at >= now() - make_interval(secs => :within)
         order by restored_at desc
         limit 1
        """.trimIndent(),
        MapSqlParameterSource("within", within.seconds.toDouble()),
    ) { rs, _ ->
        Latest(
            restoredAt = rs.getTimestamp("restored_at").toInstant(),
            recordedBy = rs.getString("recorded_by"),
            notRestored = rs.getInt("not_restored"),
            alertedAt = rs.getObject("alerted_at", Timestamp::class.java)?.toInstant(),
            alertedCount = (rs.getObject("alerted_count") as Number?)?.toInt(),
        )
    }.firstOrNull()

    companion object {
        /** The event name a log alert rule matches (docs/17 §8). */
        const val ALERT_EVENT = "MESSAGES LOST IN A RESTORE"
        /** How long a restore keeps collecting its lost messages. Queued messages drain in seconds. */
        val SAME_RESTORE: Duration = Duration.ofHours(24)
        /** Quiet time after the last one found, so the alert carries both workers' counts. */
        val SETTLE: Duration = Duration.ofMinutes(1)
        /** How long /health's operator view keeps showing a restore's loss. */
        val SHOWN_FOR: Duration = Duration.ofDays(7)
    }
}
