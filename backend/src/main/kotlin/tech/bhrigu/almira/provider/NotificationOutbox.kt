package tech.bhrigu.almira.provider

import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tech.bhrigu.almira.config.AlmiraProperties
import tech.bhrigu.almira.reminder.OutboundNotification
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import javax.sql.DataSource
import kotlin.concurrent.withLock

/** What one drain did. Counts only. */
data class OutboxDrainResult(
    val sent: Int = 0,
    val failed: Int = 0,
    /** Started by a worker that stopped before recording, on an at-most-once channel: marked, not re-sent. */
    val unconfirmed: Int = 0,
    /** Started by a worker that stopped, on a channel whose provider honours keys: sent again, same key. */
    val resent: Int = 0,
    /** The channel is no longer configured on this server. */
    val skipped: Int = 0,
) {
    operator fun plus(o: OutboxDrainResult) = OutboxDrainResult(
        sent + o.sent, failed + o.failed, unconfirmed + o.unconfirmed, resent + o.resent, skipped + o.skipped,
    )
    val touched get() = sent + failed + unconfirmed + skipped
}

/**
 * The worker that sends queued notifications (docs/13 "Interactive and background").
 *
 * Nobody is waiting for a reminder, a still-true nudge or an emergency-access
 * notice, so none of them is sent inside the request or sweep that caused it.
 * [RecordingNotifier] writes a `queued` row per channel with an idempotency key;
 * this sends it with the provider's own timeout and retry policy, passes the key
 * to the adapter, and records the outcome and the attempts on the same row.
 *
 * **Which connection, and why it is named.** A worker has no user, so on the
 * runtime pool row-level security shows it none of `outbound_messages` and no
 * `outbound_message_bodies` at all — and a worker that finds nothing looks
 * exactly like a worker with nothing to do. So it takes the OWNER data source by
 * explicit qualifier, as StillTrueSweep does. NotificationOutboxTest fails if it
 * is ever given the runtime pool.
 *
 * **Never the same key twice.** Each row is claimed in a committed transaction
 * that also stamps `send_started_at` BEFORE the provider is called, under a
 * lease long enough for the provider's whole retry budget. A worker that dies
 * after the provider accepted a message and before recording it leaves a row
 * that is still queued, with `send_started_at` set and its lease run out. What
 * the next worker does with it depends on the channel's provider:
 *
 *  - it honours idempotency keys ([ChannelSender.honoursIdempotencyKey]) —
 *    **at-least-once**: sent again with the same key, and the provider delivers
 *    it once. Timeouts are retried within `max-attempts` for the same reason.
 *  - it does not — **at-most-once**: not sent again. It is recorded `failed`
 *    with `failure = timeout` ("not confirmed; it may still arrive"), and a
 *    timeout is never retried. The cost is that a message the dying worker had
 *    not actually handed over is lost; the in-app row still has it.
 *
 * Rows are claimed `for update skip locked`, so two servers never claim the
 * same row, and one in-process lock means a drain here never overlaps another.
 */
@Component
class NotificationOutbox(
    @Qualifier("ownerDataSource") dataSource: DataSource,
    channels: List<ChannelSender>,
    private val calls: ProviderCalls,
    private val props: AlmiraProperties,
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(javaClass)
    private val jdbc = NamedParameterJdbcTemplate(dataSource)
    private val transactions = TransactionTemplate(DataSourceTransactionManager(dataSource))
    private val byChannel: Map<String, ChannelSender> = channels.associateBy { it.channel }
    private val lock = ReentrantLock()
    private val waker: ExecutorService = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("outbox-wake").factory())
    private val wakePending = AtomicBoolean(false)

    /**
     * Test seam: runs after the provider call and before the outcome is recorded.
     * A test throws from it to be the worker that died in exactly that gap.
     */
    @Volatile internal var afterSendBeforeRecord: ((UUID) -> Unit)? = null

    /** The poll: picks up whatever a wake missed, and rows left by a stopped worker. */
    @Scheduled(
        initialDelayString = "\${almira.outbox.poll-interval:PT2S}",
        fixedDelayString = "\${almira.outbox.poll-interval:PT2S}",
    )
    fun scheduled() {
        runCatching { drain() }.onFailure { log.warn("notification outbox drain failed: {}", it.javaClass.simpleName) }
    }

    /** Asks for a drain soon, on the worker's own thread. Many wakes while one is pending are one drain. */
    fun wake() {
        if (!wakePending.compareAndSet(false, true)) return
        runCatching {
            waker.execute {
                wakePending.set(false)
                scheduled()
            }
        }.onFailure { wakePending.set(false) }
    }

    /** Sends everything that is due, and returns when it has. Blocks while another drain here runs. */
    fun drain(): OutboxDrainResult = lock.withLock {
        var total = OutboxDrainResult()
        repeat(MAX_ROUNDS) {
            val (finished, claimed) = claim()
            var round = finished
            claimed.forEach { round += send(it) }
            total += round
            if (claimed.isEmpty() && finished.touched == 0) return@withLock total
        }
        total
    }

    private data class Claimed(
        val id: UUID,
        val token: UUID,
        val sender: ChannelSender,
        val notification: OutboundNotification,
        val key: String,
        val resend: Boolean,
    )

    private data class Candidate(
        val id: UUID,
        val householdId: UUID?,
        val userId: UUID,
        val channel: String,
        val template: String,
        val title: String?,
        val key: String,
        val started: Boolean,
        val body: String?,
    )

    /** One transaction: finishes what cannot be sent, claims the rest, commits before any provider call. */
    private fun claim(): Pair<OutboxDrainResult, List<Claimed>> = transactions.execute {
        val candidates = jdbc.query(
            """
            select o.id, o.household_id, o.user_id, o.channel, o.template, o.title, o.idempotency_key,
                   o.send_started_at is not null as started, b.body
            from outbound_messages o
            left join outbound_message_bodies b on b.message_id = o.id
            where o.status = 'queued'
              and o.idempotency_key is not null
              and (o.claimed_until is null or o.claimed_until < now())
            order by o.created_at
            limit :batch
            for update of o skip locked
            """.trimIndent(),
            mapOf("batch" to props.outbox.batchSize),
        ) { rs, _ ->
            Candidate(
                id = rs.getObject("id", UUID::class.java),
                householdId = rs.getObject("household_id", UUID::class.java),
                userId = rs.getObject("user_id", UUID::class.java),
                channel = rs.getString("channel"),
                template = rs.getString("template"),
                title = rs.getString("title"),
                key = rs.getString("idempotency_key"),
                started = rs.getBoolean("started"),
                body = rs.getString("body"),
            )
        }

        var finished = OutboxDrainResult()
        val claimed = mutableListOf<Claimed>()
        candidates.forEach { row ->
            val sender = byChannel[row.channel]
            when {
                sender == null -> {
                    finishUnsent(row.id, "skipped", null, addAttempt = false)
                    finished += OutboxDrainResult(skipped = 1)
                }
                row.started && !sender.honoursIdempotencyKey -> {
                    // At-most-once: it may have gone. Say so, and never ask again.
                    finishUnsent(row.id, "failed", FailureKind.TIMEOUT.code, addAttempt = true)
                    log.warn(
                        "outbox: a {} send was started by a worker that stopped before recording it; " +
                            "marked unconfirmed, not re-sent (message {})",
                        row.channel, row.id,
                    )
                    finished += OutboxDrainResult(unconfirmed = 1)
                }
                row.body == null -> {
                    finishUnsent(row.id, "failed", RecordingNotifier.UNCLASSIFIED, addAttempt = false)
                    finished += OutboxDrainResult(failed = 1)
                }
                else -> {
                    val token = UUID.randomUUID()
                    jdbc.update(
                        """
                        update outbound_messages
                           set claim_token = :token,
                               claimed_until = now() + make_interval(secs => :lease),
                               send_started_at = now(),
                               -- the attempt a stopped worker made, when this is a re-send
                               attempts = attempts + case when :resend then 1 else 0 end
                         where id = :id
                        """.trimIndent(),
                        MapSqlParameterSource()
                            .addValue("token", token)
                            .addValue("lease", lease(sender).seconds.toDouble())
                            .addValue("resend", row.started)
                            .addValue("id", row.id),
                    )
                    claimed += Claimed(
                        id = row.id, token = token, sender = sender, key = row.key, resend = row.started,
                        notification = OutboundNotification(
                            userId = row.userId, householdId = row.householdId, reminderId = null,
                            template = row.template, title = row.title.orEmpty(), body = row.body,
                            idempotencyKey = row.key,
                        ),
                    )
                }
            }
        }
        finished to claimed.toList()
    } ?: (OutboxDrainResult() to emptyList())

    private fun send(row: Claimed): OutboxDrainResult {
        val sender = row.sender
        var status = "failed"
        var provider = "unknown"
        var failure: String? = null
        var attempts = 1
        try {
            val sent = calls.execute(sender.channel, "notify", idempotent = sender.honoursIdempotencyKey) {
                sender.send(row.notification, null, row.key)
            }
            status = "sent"; provider = sent.value; attempts = sent.attempts
        } catch (e: ProviderCallFailed) {
            failure = e.kind.code; attempts = e.attempts
        } catch (e: Exception) {
            log.warn("outbox: {} send failed: {}", sender.channel, e.javaClass.simpleName)
            failure = RecordingNotifier.UNCLASSIFIED
        }
        afterSendBeforeRecord?.invoke(row.id)
        val recorded = jdbc.update(
            """
            update outbound_messages
               set status = :status, provider = :provider, failure = :failure,
                   attempts = attempts + :attempts, finished_at = now(), claimed_until = null
             where id = :id and claim_token = :token
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("status", status).addValue("provider", provider).addValue("failure", failure)
                .addValue("attempts", attempts).addValue("id", row.id).addValue("token", row.token),
        )
        if (recorded == 1) dropBody(row.id)
        val resent = if (row.resend) 1 else 0
        return if (status == "sent") OutboxDrainResult(sent = 1, resent = resent) else OutboxDrainResult(failed = 1, resent = resent)
    }

    private fun finishUnsent(id: UUID, status: String, failure: String?, addAttempt: Boolean) {
        jdbc.update(
            """
            update outbound_messages
               set status = :status, failure = :failure, finished_at = now(), claimed_until = null,
                   attempts = attempts + case when :add then 1 else 0 end
             where id = :id
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("status", status).addValue("failure", failure)
                .addValue("add", addAttempt).addValue("id", id),
        )
        dropBody(id)
    }

    /** The body is only kept while the message is on its way. */
    private fun dropBody(id: UUID) {
        jdbc.update("delete from outbound_message_bodies where message_id = :id", mapOf("id" to id))
    }

    /**
     * Longer than the provider's whole retry budget, so a live worker's claim
     * never runs out under it and a second worker cannot mistake it for dead.
     */
    internal fun lease(sender: ChannelSender): Duration {
        val p = props.providers.all().getValue(sender.channel)
        return p.timeout.multipliedBy(p.maxAttempts.toLong())
            .plus(ProviderCalls.MAX_BACKOFF.multipliedBy((p.maxAttempts - 1).toLong()))
            .plus(LEASE_MARGIN)
    }

    /** Test seam: holds this worker's lock, so nothing here drains until [block] returns. */
    internal fun <T> whilePaused(block: () -> T): T = lock.withLock(block)

    /** Which pool, and which database role, this worker really has. For the test that pins it. */
    internal fun poolName(): String? = (jdbc.jdbcTemplate.dataSource as? HikariDataSource)?.poolName

    internal fun databaseRole(): String? =
        jdbc.queryForObject("select current_user", emptyMap<String, Any>(), String::class.java)

    override fun close() {
        waker.shutdownNow()
    }

    private companion object {
        const val MAX_ROUNDS = 100
        val LEASE_MARGIN: Duration = Duration.ofMinutes(1)
    }
}
