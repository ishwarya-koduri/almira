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
    /**
     * Not sent, on purpose: the channel is no longer configured on this server, the
     * person switched it off, a live channel has no address for them, or it waited
     * out the daily limit for a week (docs/13 "Pacing").
     */
    val skipped: Int = 0,
    /** Held back until a quiet window ends or tomorrow's allowance: still queued, with `not_before`. */
    val deferred: Int = 0,
) {
    operator fun plus(o: OutboxDrainResult) = OutboxDrainResult(
        sent + o.sent, failed + o.failed, unconfirmed + o.unconfirmed, resent + o.resent, skipped + o.skipped,
        deferred + o.deferred,
    )
    val touched get() = sent + failed + unconfirmed + skipped + deferred
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
 * **Never the same key twice.** Rows are claimed in a committed transaction
 * (token and a lease only). Then, row by row, `send_started_at` is stamped and
 * committed immediately BEFORE that row's provider call, and the lease is
 * renewed from that moment for the provider's whole retry budget — so a row the
 * worker never reached is never taken for one that may have gone, and a slow
 * batch cannot run out the lease of the row being sent. A worker that dies
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
 *
 * **Who, and when** (docs/13 "Who a message is for" and "Pacing"). As it claims
 * a row the worker asks [DeliveryPacing] whether it may go now: a channel the
 * person switched off is skipped, and on a live channel a message that would land
 * in their quiet hours, or be their second non-essential message of the day,
 * waits (`not_before`). As it sends, it looks the person up in
 * [DeliveryDirectory] — their number, their address, their devices — and words
 * the message with [MessageTemplates], which adds why it came. A live channel
 * with no address for the person records `skipped`, `no_recipient`.
 */
@Component
class NotificationOutbox(
    @Qualifier("ownerDataSource") dataSource: DataSource,
    channels: List<ChannelSender>,
    private val calls: ProviderCalls,
    private val props: AlmiraProperties,
    private val directory: DeliveryDirectory,
    private val pacing: DeliveryPacing,
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

    /**
     * Test seam: runs for each claimed row before its send is stamped as started.
     * A test blocks in it to be the slow worker whose claim runs out under it.
     */
    @Volatile internal var beforeSendStarts: ((UUID) -> Unit)? = null

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
            // Deferred rows are not claimable again until their time, so a round that only
            // deferred has nothing more to find.
            if (claimed.isEmpty() && finished.touched == finished.deferred) return@withLock total
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
        /** Fixed when queued (V108); null means "look the person up now". */
        val address: String?,
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
        val address: String?,
        val timeZone: String?,
        val createdAt: java.time.Instant,
    )

    /**
     * One transaction: finishes what cannot be sent, claims the rest, commits before any provider call.
     * A claim does not mark a row as started; [send] does that for each row just before its own call.
     */
    private fun claim(): Pair<OutboxDrainResult, List<Claimed>> = transactions.execute {
        val candidates = jdbc.query(
            """
            select o.id, o.household_id, o.user_id, o.channel, o.template, o.title, o.idempotency_key,
                   o.send_started_at is not null as started, b.body, b.address, h.time_zone, o.created_at
            from outbound_messages o
            left join outbound_message_bodies b on b.message_id = o.id
            left join households h on h.id = o.household_id
            where o.status = 'queued'
              and o.idempotency_key is not null
              and (o.claimed_until is null or o.claimed_until < now())
              and (o.not_before is null or o.not_before <= :now)
            order by coalesce(o.not_before, o.created_at)
            limit :batch
            for update of o skip locked
            """.trimIndent(),
            mapOf("batch" to props.outbox.batchSize, "now" to java.sql.Timestamp.from(pacing.now())),
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
                address = rs.getString("address"),
                timeZone = rs.getString("time_zone"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
            )
        }

        var finished = OutboxDrainResult()
        val claimed = mutableListOf<Claimed>()
        val chosenToday = mutableMapOf<UUID, String>()
        candidates.forEach { row ->
            val sender = byChannel[row.channel]
            // Asked when the row was queued, and again now: a row can wait days for quiet hours
            // or the daily limit, and a withdrawal or a memorial made in between stops it.
            val stopped = if (row.started) null else stoppedFor(row)
            // A row already started is past pacing (and past the stops above): it was allowed once,
            // and whatever happens to it now is the idempotency rule's, not the calendar's.
            // A stopped row is not paced either, so it cannot take the day's one message.
            val decision = if (sender == null || row.started || stopped != null) {
                DeliveryPacing.Decision.Send
            } else {
                pacing.decide(
                    DeliveryPacing.Queued(
                        userId = row.userId, channel = row.channel, template = row.template,
                        logicalKey = DeliveryPacing.logicalKey(row.key, row.channel),
                        timeZone = zoneOf(row.timeZone), createdAt = row.createdAt,
                    ),
                    sender, chosenToday,
                )
            }
            when {
                stopped != null -> {
                    finishUnsent(row.id, "skipped", stopped, addAttempt = false)
                    finished += OutboxDrainResult(skipped = 1)
                }
                decision is DeliveryPacing.Decision.Skip -> {
                    finishUnsent(row.id, "skipped", decision.reason, addAttempt = false)
                    finished += OutboxDrainResult(skipped = 1)
                }
                decision is DeliveryPacing.Decision.Defer -> {
                    jdbc.update(
                        "update outbound_messages set not_before = :until, deferred_for = :reason where id = :id",
                        MapSqlParameterSource()
                            .addValue("until", java.sql.Timestamp.from(decision.until))
                            .addValue("reason", decision.reason).addValue("id", row.id),
                    )
                    finished += OutboxDrainResult(deferred = 1)
                }
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
                    // Only the claim here. The send is stamped by send(), just before it starts,
                    // so a row this worker never reached is not taken for one that may have gone.
                    val token = UUID.randomUUID()
                    jdbc.update(
                        """
                        update outbound_messages
                           set claim_token = :token,
                               claimed_until = now() + make_interval(secs => :lease)
                         where id = :id
                        """.trimIndent(),
                        MapSqlParameterSource()
                            .addValue("token", token)
                            .addValue("lease", lease(sender).seconds.toDouble())
                            .addValue("id", row.id),
                    )
                    claimed += Claimed(
                        id = row.id, token = token, sender = sender, key = row.key, resend = row.started, address = row.address,
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
        beforeSendStarts?.invoke(row.id)
        // Committed before the provider is called, row by row: this is what says "it may have gone",
        // and the lease runs from here, not from when the batch was claimed. If the claim is no longer
        // ours (a slow batch let it run out and another worker took the row), this worker leaves it.
        val started = jdbc.update(
            """
            update outbound_messages
               set send_started_at = now(),
                   claimed_until = now() + make_interval(secs => :lease),
                   -- the attempt a stopped worker made, when this is a re-send
                   attempts = attempts + case when :resend then 1 else 0 end
             where id = :id and claim_token = :token and status = 'queued'
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("lease", lease(sender).seconds.toDouble())
                .addValue("resend", row.resend)
                .addValue("id", row.id).addValue("token", row.token),
        )
        if (started != 1) return OutboxDrainResult()
        try {
            val notification = row.notification
            val composed = MessageTemplates.compose(
                notification.template, notification.title, notification.body, sender.channel,
                directory.locale(notification.userId),
            )
            val worded = notification.copy(title = composed.subject, body = composed.text)
            val recipients = row.address?.let { listOf(Recipient(it)) }
                ?: directory.recipients(notification.userId, sender.channel)
            when {
                // A sandbox reaches nobody, with or without an address; it is still called, as before.
                recipients.isEmpty() && sender.mode != ProviderMode.LIVE -> {
                    val sent = calls.execute(sender.channel, "notify", idempotent = sender.honoursIdempotencyKey) {
                        sender.send(worded, null, row.key)
                    }
                    status = "sent"; provider = sent.value; attempts = sent.attempts
                }
                recipients.isEmpty() -> {
                    status = "skipped"; failure = DeliveryPacing.NO_RECIPIENT; attempts = 0
                }
                else -> {
                    val outcome = sendToEach(sender, worded, row.key, recipients)
                    status = outcome.status; provider = outcome.provider
                    failure = outcome.failure; attempts = outcome.attempts
                }
            }
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
        return when (status) {
            "sent" -> OutboxDrainResult(sent = 1, resent = resent)
            "skipped" -> OutboxDrainResult(skipped = 1, resent = resent)
            else -> OutboxDrainResult(failed = 1, resent = resent)
        }
    }

    private data class Outcome(val status: String, val provider: String, val failure: String?, val attempts: Int)

    /**
     * One provider call per address. For SMS and email that is one call; for push it
     * is one per device, and one dead token must not fail the others. The row is
     * `sent` when any address took it. A push token the platform refused is
     * forgotten, so the next reminder does not try it again. Each call has its own
     * key — the row's, plus the installation — so a provider that de-duplicates
     * does not mistake the second phone for a repeat of the first.
     */
    private fun sendToEach(
        sender: ChannelSender,
        notification: OutboundNotification,
        key: String,
        recipients: List<Recipient>,
    ): Outcome {
        var provider = "unknown"
        var lastFailure: ProviderCallFailed? = null
        var attempts = 0
        var anySent = false
        recipients.forEach { recipient ->
            val callKey = recipient.installationId?.let { "$key:$it" } ?: key
            try {
                val sent = calls.execute(sender.channel, "notify", idempotent = sender.honoursIdempotencyKey) {
                    sender.send(notification, recipient.address, callKey)
                }
                anySent = true; provider = sent.value; attempts = maxOf(attempts, sent.attempts)
            } catch (e: ProviderCallFailed) {
                lastFailure = e
                attempts = maxOf(attempts, e.attempts)
                if (e.kind == FailureKind.REJECTED && recipient.installationId != null) {
                    directory.forgetDevice(notification.userId, recipient.installationId)
                }
            }
        }
        return if (anySent) Outcome("sent", provider, null, attempts)
        else Outcome("failed", provider, lastFailure?.kind?.code ?: RecordingNotifier.UNCLASSIFIED, attempts)
    }

    private fun zoneOf(name: String?): java.time.ZoneId =
        name?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() } ?: DEFAULT_ZONE

    /**
     * The two stops `app.enqueue_outbound_message` (V108) applies, asked again as a row is
     * claimed, as a reason or null: the person is memorialised, or withdrew consent to messages
     * for a reminder or the digest. The worker is on the owner connection, which may ask both.
     */
    private fun stoppedFor(row: Candidate): String? = jdbc.queryForObject(
        """
        select case
                 when not app.never_stopped_by_memorial(cast(:template as text))
                      and app.notifications_stopped(cast(:uid as uuid), cast(:hid as uuid)) then '$NOTIFICATIONS_STOPPED'
                 when (cast(:template as text) like 'reminder.%' or cast(:template as text) = 'still_true.digest')
                      and app.messages_consent_withdrawn(cast(:uid as uuid)) then '$CONSENT_WITHDRAWN'
               end
        """.trimIndent(),
        MapSqlParameterSource()
            .addValue("template", row.template).addValue("uid", row.userId.toString()).addValue("hid", row.householdId?.toString()),
        String::class.java,
    )

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
     * Longer than the provider's whole retry budget. It is set at claim time and
     * renewed when each row's send starts, so the row being sent always has the
     * full budget from its own start and a second worker cannot mistake it for dead,
     * however long the rows before it in the batch took.
     */
    internal fun lease(sender: ChannelSender): Duration {
        val p = props.providers.all().getValue(sender.channel)
        // Push is one call per device, one after another.
        val calls = if (sender.channel == "push") DeliveryDirectory.MAX_DEVICES.toLong() else 1L
        return p.timeout.multipliedBy(p.maxAttempts.toLong())
            .plus(ProviderCalls.MAX_BACKOFF.multipliedBy((p.maxAttempts - 1).toLong()))
            .multipliedBy(calls)
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
        /** A message that belongs to no household is paced in India's time. */
        val DEFAULT_ZONE: java.time.ZoneId = java.time.ZoneId.of("Asia/Kolkata")
        val LEASE_MARGIN: Duration = Duration.ofMinutes(1)
        const val NOTIFICATIONS_STOPPED = "notifications_stopped"
        const val CONSENT_WITHDRAWN = "consent_withdrawn"
    }
}
