package tech.bhrigu.almira.auth

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tech.bhrigu.almira.common.EmailAddress
import tech.bhrigu.almira.config.AlmiraProperties
import tech.bhrigu.almira.provider.FailureKind
import tech.bhrigu.almira.provider.ProviderCallFailed
import tech.bhrigu.almira.provider.ProviderCalls
import java.math.BigInteger
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.sql.DataSource
import kotlin.concurrent.withLock

/**
 * Where the email sign-in request hands its code over (docs/13 §5).
 *
 * Called once per request, for every address, listed or not, with the same
 * arguments in the same shape. It does not take a code and it is not told
 * whether the address is on the allowlist: the worker behind it derives the one
 * and asks the other when it gets to the message.
 */
fun interface SignInCodeOutbox {
    fun enqueue(address: String, purpose: String, requestId: String, codeLength: Int)

    companion object {
        /** For a service built without email sign-in, as the phone suites are. */
        val NONE = SignInCodeOutbox { _, _, _, _ -> error("no sign-in code outbox configured") }
    }
}

/**
 * The code a queued sign-in email carries, derived rather than stored.
 *
 * An HMAC under a key of its own (derived from the JWT secret, like the stored
 * challenge's key) over purpose, address and request id, reduced to the code's
 * length. The request path derives it to store the challenge's hash; the worker
 * derives it again to send. So the queue holds an address and a request id and
 * never a code: a dump of it, or a replica, is not a list of codes that work.
 * The address is in the input, so a queued row pointed at another address
 * derives another code, and nothing written to the queue can carry one
 * person's code to someone else's mailbox.
 *
 * The request id is 122 random bits the server chose, so without the key the
 * code is as unpredictable as a random one. The reduction from 256 bits is
 * biased by less than one part in 2^200.
 */
class QueuedEmailCodes(props: AlmiraProperties) {
    private val key: SecretKeySpec = run {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(props.jwt.secret.toByteArray(Charsets.UTF_8), HMAC))
        SecretKeySpec(mac.doFinal(LABEL.toByteArray(Charsets.UTF_8)), HMAC)
    }

    fun code(purpose: String, address: String, requestId: String, length: Int): String {
        require(length in 6..8) { "a code is 6 to 8 digits (is $length)" }
        val mac = Mac.getInstance(HMAC)
        mac.init(key)
        val digest = mac.doFinal("$purpose|$address|$requestId".toByteArray(Charsets.UTF_8))
        return BigInteger(1, digest).mod(BigInteger.TEN.pow(length)).toString().padStart(length, '0')
    }

    private companion object {
        const val HMAC = "HmacSHA256"
        const val LABEL = "almira/otp-code/email-queued/v1"
    }
}

/** What one drain did. Counts only: nothing here may name an address. */
data class SignInEmailDrainResult(
    val sent: Int = 0,
    val failed: Int = 0,
    /** Not on the allowlist when the worker got to it: no provider call. */
    val dropped: Int = 0,
    /** Started by a worker that stopped before recording it: marked, never sent again. */
    val unconfirmed: Int = 0,
    /** Queued past the code's lifetime: not sent. */
    val expired: Int = 0,
) {
    operator fun plus(o: SignInEmailDrainResult) = SignInEmailDrainResult(
        sent + o.sent, failed + o.failed, dropped + o.dropped, unconfirmed + o.unconfirmed, expired + o.expired,
    )
}

/**
 * The sign-in email outbox: every email sign-in request queues here, and this
 * worker decides, message by message, whether it goes (owner's decision,
 * 2026-09-15: *always enqueue on the request path, whatever the address, and
 * let the worker drop unlisted ones*).
 *
 * **The request path** ([enqueue]) is one call to
 * `app.enqueue_sign_in_code_email` on the runtime connection and a wake — the
 * same statement with the same arguments for every address — and never a
 * provider call. So how long a request takes, and what it leaves that a client
 * can reach, is the same for a listed address and an unlisted one by
 * construction, and a stranger typing addresses costs nothing: nothing is sent,
 * nothing is billed.
 *
 * **The worker** ([drain]), on the owner connection because nothing else may
 * read these tables, takes each queued message and, before anything else:
 *
 *  - asks [SignInChannels] whether email sign-in is on and the address is on
 *    the allowlist **now**. If not, the message is recorded `dropped` and its
 *    body deleted in the same statement, and no provider is called. A tester
 *    taken off the list while their message waited (a restart with the list
 *    changed) is dropped the same way;
 *  - otherwise stamps and commits `send_started_at`, derives the code
 *    ([QueuedEmailCodes]) and makes one attempt through
 *    [ProviderCalls.callOnce] under the one-time-code timeout, then records
 *    `sent` or `failed` and deletes the body in the same statement.
 *
 * **What the outcome changes for a client: nothing.** Not the delivery status
 * (a function of the time since the request, [OtpService.emailDelivery]), not
 * the challenge, the cooldown or the counts. An unlisted address can never
 * fail, so anything a failure did that a stranger could see would mark the
 * address listed. A provider refusing a listed address is logged at ERROR,
 * `SIGN-IN EMAIL REFUSED`, with the address masked; an outage or an account
 * problem is ProviderCalls' WARN and ERROR, as for any provider call.
 *
 * **At most once.** A message whose send was started by a worker that then
 * stopped is recorded `unconfirmed` and never sent again: a second email with
 * the same code is no help, and a sign-in code is not worth a duplicate. One
 * queued past the code's lifetime is recorded `expired` and not sent.
 *
 * Rows are claimed `for update skip locked`, so two servers never take the same
 * one, and finished records are kept for [RETENTION] and then deleted.
 */
@Component
class SignInEmailOutbox(
    /** The runtime pool: the request path, through the definer function only. */
    @Qualifier("jdbc") private val requests: NamedParameterJdbcTemplate,
    @Qualifier("ownerDataSource") ownerDataSource: DataSource,
    private val emailSender: EmailOtpSender,
    private val calls: ProviderCalls,
    private val channels: SignInChannels,
    private val props: AlmiraProperties,
) : SignInCodeOutbox, AutoCloseable {

    private val log = LoggerFactory.getLogger(javaClass)
    private val jdbc = NamedParameterJdbcTemplate(ownerDataSource)
    private val transactions = TransactionTemplate(DataSourceTransactionManager(ownerDataSource))
    private val codes = QueuedEmailCodes(props)
    private val lock = ReentrantLock()
    private val waker: ExecutorService =
        Executors.newSingleThreadExecutor(Thread.ofVirtual().name("sign-in-email-wake").factory())
    private val wakePending = AtomicBoolean(false)

    /** Test seam: runs for each claimed message just before the allowlist is asked. */
    @Volatile internal var beforeDecision: ((UUID) -> Unit)? = null

    override fun enqueue(address: String, purpose: String, requestId: String, codeLength: Int) {
        requests.query(
            "select app.enqueue_sign_in_code_email(:address, :purpose, cast(:requestId as uuid), :length)",
            MapSqlParameterSource()
                .addValue("address", address).addValue("purpose", purpose)
                .addValue("requestId", requestId).addValue("length", codeLength),
        ) { _, _ -> }
        wake()
    }

    /** The poll: picks up whatever a wake missed, and messages left by a stopped worker. */
    @Scheduled(
        initialDelayString = "\${almira.outbox.poll-interval:PT2S}",
        fixedDelayString = "\${almira.outbox.poll-interval:PT2S}",
    )
    fun scheduled() {
        if (!props.outbox.background) return
        runCatching { drain() }.onFailure { log.warn("sign-in email outbox drain failed: {}", it.javaClass.simpleName) }
    }

    /** Asks for a drain soon, on the worker's own thread; never waits for it. */
    fun wake() {
        if (!props.outbox.background) return
        if (!wakePending.compareAndSet(false, true)) return
        runCatching {
            waker.execute {
                wakePending.set(false)
                scheduled()
            }
        }.onFailure { wakePending.set(false) }
    }

    /** Decides every queued message, and returns when it has. Blocks while another drain here runs. */
    fun drain(): SignInEmailDrainResult = lock.withLock {
        var total = SignInEmailDrainResult()
        repeat(MAX_ROUNDS) {
            val (finished, claimed) = claim()
            total += finished
            claimed.forEach { total += decideAndSend(it) }
            if (claimed.isEmpty()) return@withLock total
        }
        total
    }

    private data class Claimed(
        val id: UUID,
        val token: UUID,
        val address: String,
        val purpose: String,
        val requestId: String,
        val codeLength: Int,
    )

    /** One transaction: finishes what can never be sent, claims the rest, commits before any decision. */
    private fun claim(): Pair<SignInEmailDrainResult, List<Claimed>> = transactions.execute {
        jdbc.update(
            "delete from sign_in_code_emails where status <> 'queued' and finished_at < now() - make_interval(secs => :keep)",
            MapSqlParameterSource("keep", RETENTION.seconds.toDouble()),
        )
        val rows = jdbc.queryForList(
            """
            select e.id, e.send_started_at is not null as started,
                   e.created_at < now() - make_interval(secs => :ttl) as expired,
                   b.address, b.purpose, b.request_id, b.code_length
              from sign_in_code_emails e
              left join sign_in_code_email_bodies b on b.message_id = e.id
             where e.status = 'queued'
               and (e.claimed_until is null or e.claimed_until < now())
             order by e.created_at
             limit :batch
               for update of e skip locked
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("ttl", props.otp.ttl.toMillis() / 1000.0)
                .addValue("batch", props.outbox.batchSize),
        )
        var finished = SignInEmailDrainResult()
        val claimed = mutableListOf<Claimed>()
        rows.forEach { row ->
            val id = row["id"] as UUID
            when {
                row["started"] == true -> {
                    finish(id, null, "unconfirmed", FailureKind.TIMEOUT.code)
                    log.warn("sign-in email outbox: a send was started by a worker that stopped before recording it; " +
                        "marked unconfirmed, not sent again (message {})", id)
                    finished += SignInEmailDrainResult(unconfirmed = 1)
                }
                row["address"] == null -> {
                    finish(id, null, "failed", BODY_NOT_RESTORED)
                    finished += SignInEmailDrainResult(failed = 1)
                }
                row["expired"] == true -> {
                    finish(id, null, "expired", null)
                    finished += SignInEmailDrainResult(expired = 1)
                }
                else -> {
                    val token = UUID.randomUUID()
                    jdbc.update(
                        "update sign_in_code_emails set claim_token = :token, " +
                            "claimed_until = now() + make_interval(secs => :lease) where id = :id",
                        MapSqlParameterSource()
                            .addValue("token", token).addValue("lease", lease().toMillis() / 1000.0).addValue("id", id),
                    )
                    claimed += Claimed(
                        id, token, row["address"] as String, row["purpose"] as String,
                        row["request_id"].toString(), (row["code_length"] as Number).toInt(),
                    )
                }
            }
        }
        finished to claimed.toList()
    } ?: (SignInEmailDrainResult() to emptyList())

    private fun decideAndSend(row: Claimed): SignInEmailDrainResult {
        beforeDecision?.invoke(row.id)
        // First, before the send is stamped or a code derived, let alone a provider
        // called: an address that is not listed now is never sent anything.
        // (docs/known-issues.md, "A guard runs before the action it guards".)
        if (!channels.isEnabled(OtpChannel.EMAIL) || !channels.isAllowed(row.address)) {
            return if (finish(row.id, row.token, "dropped", null) == 1) SignInEmailDrainResult(dropped = 1)
            else SignInEmailDrainResult()
        }
        val started = jdbc.update(
            """
            update sign_in_code_emails
               set send_started_at = now(), claimed_until = now() + make_interval(secs => :lease)
             where id = :id and claim_token = :token and status = 'queued'
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("lease", lease().toMillis() / 1000.0).addValue("id", row.id).addValue("token", row.token),
        )
        if (started != 1) return SignInEmailDrainResult()

        val failure: String? = try {
            val code = codes.code(row.purpose, row.address, row.requestId, row.codeLength)
            calls.callOnce(OtpChannel.EMAIL.provider, "otp", props.otp.sendTimeout) { emailSender.send(row.address, code) }
            null
        } catch (e: ProviderCallFailed) {
            if (e.kind == FailureKind.REJECTED) {
                log.error(
                    "SIGN-IN EMAIL REFUSED: the email provider refused the address {}, which is on the " +
                        "allowlist. The tester is shown the code as sent, because saying otherwise would " +
                        "tell strangers who is listed (docs/13 §5). Check the address with the tester.",
                    EmailAddress.mask(row.address),
                )
            }
            e.kind.code
        } catch (e: Exception) {
            log.warn("sign-in email outbox: send failed: {}", e.javaClass.simpleName)
            UNCLASSIFIED
        }
        val recorded = finish(row.id, row.token, if (failure == null) "sent" else "failed", failure)
        return when {
            recorded != 1 -> SignInEmailDrainResult()
            failure == null -> SignInEmailDrainResult(sent = 1)
            else -> SignInEmailDrainResult(failed = 1)
        }
    }

    /**
     * Records how a message ended and deletes its body, in one statement, so no
     * stopped worker can leave a body behind a finished message. With a [token],
     * only while the claim is still this worker's.
     */
    private fun finish(id: UUID, token: UUID?, status: String, failure: String?): Int =
        jdbc.queryForObject(
            """
            with finished as (
                   update sign_in_code_emails
                      set status = :status, failure = :failure, finished_at = now(),
                          claim_token = null, claimed_until = null
                    where id = :id and status = 'queued'
                      and (cast(:token as uuid) is null or claim_token = cast(:token as uuid))
                   returning id),
                 dropped as (delete from sign_in_code_email_bodies where message_id in (select id from finished))
            select count(*) from finished
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("status", status).addValue("failure", failure)
                .addValue("id", id).addValue("token", token?.toString()),
            Int::class.java,
        )!!

    /** One attempt under the one-time-code timeout, and room to record it. */
    private fun lease(): Duration = props.otp.sendTimeout.plus(LEASE_MARGIN)

    /** Test seam: holds this worker's lock, so nothing here drains until [block] returns. */
    internal fun <T> whilePaused(block: () -> T): T = lock.withLock(block)

    override fun close() {
        waker.shutdownNow()
    }

    companion object {
        const val BODY_NOT_RESTORED = "body_not_restored"
        const val UNCLASSIFIED = "unclassified"
        /** How long a finished record (status and failure, no address) is kept for the operator. */
        val RETENTION: Duration = Duration.ofDays(30)
        private val LEASE_MARGIN: Duration = Duration.ofSeconds(30)
        private const val MAX_ROUNDS = 100
    }
}
