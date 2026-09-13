package tech.bhrigu.almira.auth

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.config.AlmiraProperties
import tech.bhrigu.almira.provider.FailureKind
import tech.bhrigu.almira.provider.ProviderCallFailed
import tech.bhrigu.almira.provider.ProviderCalls
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class OtpChallenge(
    val requestId: String,
    val expiresInSeconds: Long,
    val resendAfterSeconds: Long,
    /** Only when development was explicitly chosen AND the sender is the log one. */
    val developmentCode: String?,
    val channel: OtpChannel = OtpChannel.PHONE,
) {
    /** A data class prints every field. This one must never print the code. */
    override fun toString() =
        "OtpChallenge(requestId=$requestId, expiresInSeconds=$expiresInSeconds, " +
            "resendAfterSeconds=$resendAfterSeconds, developmentCode=${if (developmentCode == null) "null" else "[redacted]"}, " +
            "channel=$channel)"
}

/**
 * What happens to the send when a code is issued.
 *
 * A phone number cannot be on an allowlist, so its sign-in reports every way a
 * send fails in the response. An email sign-in is gated by one, and there the
 * failure contract and the allowlist pull in opposite directions: an answer
 * that waits for the provider, or says "we couldn't deliver", would tell anyone
 * who asks who is in the alpha. And a failure nobody hears about is
 * indistinguishable, for the tester, from a code that never arrived (owner's
 * decision, 2026-09: a failed email send must not be silent).
 *
 * So email sign-in answers at once, sends afterwards, and reports how the send
 * went through a delivery status the code step polls
 * ([OtpService.emailDelivery]) — and an address off the list gets a status too,
 * one that replays what the provider last did for a real send.
 */
enum class OtpDelivery {
    /** Send now, and answer with the outcome — see [OtpService.request]. */
    REPORTED,

    /**
     * Answer at once; send afterwards; report the outcome through the delivery
     * status. The answer cannot depend on the send, so it is the same whether
     * or not it would fail, and it takes the same time as [DECOY]. The send is
     * one attempt under the one-time-code timeout, classified and alarmed by
     * ProviderCalls. See [OtpService.settleInBackground].
     */
    DEFERRED,

    /**
     * Everything [DEFERRED] does — every counter, the cooldown, a stored
     * challenge with its own request id and lifetime, a delivery status that
     * settles the way a real send's would under the provider as it is now —
     * except that nothing is sent and the stored value is random, so no code of
     * any length matches it. For an address that is not allowed to sign in.
     */
    DECOY,
}

/** How a deferred send went, as the code step sees it. */
data class OtpDeliveryStatus(
    val requestId: String,
    /** `sending`, `sent`, `delayed` or `failed`. */
    val status: String,
    /** When failed: `otp_delivery_failed`, `otp_provider_unavailable` or `otp_service_unavailable`. */
    val failure: String?,
    /** For a person, in English; clients show their own words for [failure]. */
    val message: String?,
    /** 0 once delayed or failed: the cooldown was lifted and resend is open. */
    val resendAfterSeconds: Long?,
)

/**
 * One-time codes by phone (docs/09 §9.2) and by email.
 *
 * Challenges are namespaced by PURPOSE. Signing in and confirming yourself
 * before a number is revealed are different flows that can be in flight at the
 * same time on the same phone: sharing one Redis key would let a step-up code
 * silently invalidate a login code, and sharing the 30-second cooldown would
 * refuse a legitimate step-up moments after sign-in. The per-phone hourly cap
 * is deliberately NOT namespaced — that one exists to stop a number being
 * flooded with texts, and every flow's messages count toward it.
 *
 * And by CHANNEL. An email challenge lives under its own keys and is keyed by
 * its own HMAC key, so nothing stored for an address can ever verify for a
 * number or the other way round. The two per-network limits are deliberately
 * NOT split by channel: they bound what one network may do, and alternating
 * channels must not double it.
 *
 * Redis rather than Postgres because every value here is short-lived and
 * write-heavy: a code lives five minutes, counters reset hourly, and none of it
 * belongs in a backup.
 *
 * What it guarantees, each with a test in OtpServiceTest:
 *  - nothing is generated unless the sender can actually deliver it;
 *  - the code is echoed only in explicitly chosen development;
 *  - a stored code cannot be recovered from a Redis dump (keyed, not a bare hash);
 *  - a code works once, even when two correct attempts race;
 *  - wrong guesses are counted atomically, so racing guesses cannot exceed the cap;
 *  - resend cooldown, per-number and per-network hourly caps on requests, and a
 *    per-network hourly cap on wrong codes across all numbers;
 *  - lifetime, length and attempts are bounded, so configuration cannot quietly
 *    turn a six-digit five-minute code into something guessable;
 *  - a send is ONE attempt under its own short timeout, never retried, so one
 *    request can never become two texts (see [request]);
 *  - a send that fails says which way it failed, and a failure that is ours
 *    does not lock the person out (see [request]);
 *  - the same, per channel, for email (EmailOtpTest).
 */
@Service
class OtpService(
    private val redis: StringRedisTemplate,
    private val sender: OtpSender,
    props: AlmiraProperties,
    /**
     * Only for its single-attempt call ([ProviderCalls.callOnce]): the SMS or
     * email provider's account, the OTP timeout, and none of its retry policy.
     */
    private val calls: ProviderCalls = ProviderCalls(props),
    private val emailSender: EmailOtpSender = EmailOtpSender.NONE,
    /** Where [OtpDelivery.DEFERRED] sends and decoys run. A seam so a test can run them inline. */
    private val background: Executor = Executors.newVirtualThreadPerTaskExecutor(),
    /** Where the last real email send's outcome is kept. A seam so unit tests do not share it. */
    private val weatherKey: String = EMAIL_WEATHER_KEY,
) {
    @Autowired
    constructor(
        redis: StringRedisTemplate,
        sender: OtpSender,
        props: AlmiraProperties,
        calls: ProviderCalls,
        emailSender: EmailOtpSender,
    ) : this(redis, sender, props, calls, emailSender, Executors.newVirtualThreadPerTaskExecutor(), EMAIL_WEATHER_KEY)

    private val log = LoggerFactory.getLogger(javaClass)
    private val cfg = props.otp
    private val development = props.isDevelopment
    private val random = SecureRandom()

    /**
     * The stored value used to be an unsalted SHA-256 of the code. A six-digit
     * code has a million possible values, so the "hash" was a lookup away from
     * the code itself — anyone holding a Redis dump or a replica could read
     * every live code and sign in. It is now an HMAC under a key derived from
     * the JWT secret, over purpose, phone and code together: the dump alone
     * gives nothing, and a value moved to another number or flow does not match.
     */
    private val codeKey: SecretKeySpec = deriveKey(props, "almira/otp-code/v1")

    /** Email's own key, so a value can never cross from one channel to the other. */
    private val emailCodeKey: SecretKeySpec = deriveKey(props, "almira/otp-code/email/v1")

    init {
        // Bounds, not preferences. Each of these is a config value that, set
        // wrongly, turns sign-in into something an attacker can guess.
        require(cfg.length in 6..8) {
            "almira.otp.length must be 6 to 8 digits (is ${cfg.length}). Fewer is guessable."
        }
        require(!cfg.ttl.isNegative && !cfg.ttl.isZero && cfg.ttl <= MAX_TTL) {
            "almira.otp.ttl must be more than zero and at most $MAX_TTL (is ${cfg.ttl})."
        }
        require(cfg.maxAttempts in 1..10) {
            "almira.otp.max-attempts must be 1 to 10 (is ${cfg.maxAttempts})."
        }
        require(!cfg.sendTimeout.isNegative && !cfg.sendTimeout.isZero && cfg.sendTimeout <= MAX_SEND_TIMEOUT) {
            "almira.otp.send-timeout must be more than zero and at most $MAX_SEND_TIMEOUT (is ${cfg.sendTimeout}). " +
                "Somebody is waiting for this answer."
        }
    }

    /**
     * A send is **interactive**: somebody is looking at the screen. So it is
     * exactly one attempt, under [AlmiraProperties.Otp.sendTimeout] rather than
     * the provider's timeout, with no retry and no backoff, whatever the
     * provider's `max-attempts` says. The person's resend button is the retry.
     * That is what removes duplicate texts: a timeout means "no answer", not
     * "not delivered", and retrying one was how one request became up to three
     * billed texts (known-issues 21).
     *
     * When the send fails, what happens to the challenge, the cooldown and the
     * hourly counts depends on whether the text could have gone out — because
     * three things must hold: a person must not be locked out by our failure,
     * an old code arriving late must not work once a newer one exists, and an
     * attacker must not get unthrottled requests out of it.
     *
     *  - **Timeout** (`otp_delivery_delayed`): it may still arrive. The
     *    challenge stands, so a late text still works — until a resend
     *    replaces it, when the late code stops matching. The cooldown is
     *    **lifted** (`resendAfterSeconds: 0`), so the person can press resend
     *    at once instead of waiting out a timer for a text that may never come.
     *    Both hourly counts stand, because as far as anyone can tell a text was
     *    sent — so repeated timeouts still run into the per-number and
     *    per-network caps.
     *  - **Rejected** (`otp_delivery_failed`), **unavailable**
     *    (`otp_provider_unavailable`), **insufficient balance**
     *    (`otp_service_unavailable`): nothing was delivered. The challenge is
     *    removed (no live code with nobody to receive it), the cooldown is
     *    lifted and the per-number count is given back, so fixing a typo or
     *    trying again once we are topped up is not refused as "too many". The
     *    per-network count is NOT given back: that is the limit that stops one
     *    network hammering this endpoint, and it holds whether or not our
     *    provider is working.
     *
     * No code, phone number or provider detail appears in any of these errors.
     */
    fun request(phone: String, ip: String?, purpose: String = LOGIN): OtpChallenge =
        issue(OtpChannel.PHONE, phone, ip, purpose, OtpDelivery.REPORTED)

    /** The email equivalent of [request]. [email] must already be normalised. */
    fun requestByEmail(email: String, ip: String?, purpose: String, delivery: OtpDelivery): OtpChallenge =
        issue(OtpChannel.EMAIL, email, ip, purpose, delivery)

    private fun issue(
        channel: OtpChannel,
        address: String,
        ip: String?,
        purpose: String,
        delivery: OtpDelivery,
    ): OtpChallenge {
        // First, before a counter moves or a code exists. A sender that cannot
        // deliver would otherwise leave a live code with nobody to receive it —
        // and, for the log sender, a code in a production log. Checked for a
        // decoy too: whether the server can send email is not a secret, and the
        // answer must not differ by address.
        if (!available(channel)) throw unavailable(channel)
        enforceCooldown(channel, address, purpose)
        enforceHourlyLimit(rateKey(channel, address), cfg.maxPerHour, channel.subject)
        ip?.let { enforceHourlyLimit("otp:rate:ip:$it", cfg.maxPerIpPerHour, "network") }

        val code = (1..cfg.length).map { random.nextInt(10) }.joinToString("")
        val requestId = UUID.randomUUID().toString()
        // Computed for a decoy as well and then thrown away, so the two do the
        // same work in the same order.
        val hash = mac(channel, purpose, address, code)
        val stored = if (delivery == OtpDelivery.DECOY) unmatchable() else hash

        val key = challengeKey(channel, address, purpose)
        redis.opsForHash<String, String>().putAll(
            key, mapOf("hash" to stored, "requestId" to requestId, "attempts" to "0"),
        )
        redis.expire(key, cfg.ttl)
        // Redis rejects a zero or negative TTL outright, so a misconfigured
        // cooldown would turn every sign-in attempt into a 500 rather than
        // simply disabling the cooldown. Guard the config, not the user.
        if (!cfg.resendCooldown.isZero && !cfg.resendCooldown.isNegative) {
            redis.opsForValue().set(cooldownKey(channel, address, purpose), "1", cfg.resendCooldown)
        }

        when (delivery) {
            OtpDelivery.REPORTED -> try {
                timedSend(channel, address, code)
            } catch (failure: ProviderCallFailed) {
                if (failure.kind == FailureKind.TIMEOUT) {
                    // The person may press resend now. The new challenge
                    // overwrites this one, so a late text stops working then.
                    redis.delete(cooldownKey(channel, address, purpose))
                } else {
                    // CONSUME removes it only if it is still this request's challenge.
                    redis.execute(CONSUME, listOf(key), requestId)
                    redis.delete(cooldownKey(channel, address, purpose))
                    redis.execute(GIVE_BACK, listOf(rateKey(channel, address)))
                }
                throw sendFailed(channel, failure.kind, requestId)
            }
            OtpDelivery.DEFERRED, OtpDelivery.DECOY -> {
                val status = deliveryKey(requestId)
                redis.opsForHash<String, String>().putAll(status, mapOf("state" to SENDING))
                redis.expire(status, cfg.ttl)
                settleInBackground(channel, address, purpose, requestId, code, real = delivery == OtpDelivery.DEFERRED)
            }
        }

        return OtpChallenge(
            requestId = requestId,
            expiresInSeconds = cfg.ttl.seconds,
            resendAfterSeconds = cfg.resendCooldown.seconds,
            developmentCode = code.takeIf {
                development && delivery != OtpDelivery.DECOY && exposesCode(channel)
            },
            channel = channel,
        )
    }

    /**
     * The send, after the answer has gone — or, for a decoy, its stand-in.
     *
     * Both run the same code on the same executor, and differ in one line: a
     * real send calls the provider (one attempt, the one-time-code timeout, the
     * ProviderCalls WARN and account ERROR); a decoy waits as long as the last
     * real send took and ends the way it ended ([shadowOfLastSend]). Everything
     * after that — what happens to the challenge, the cooldown and the count,
     * and the status the code step reads — is shared, so an address off the
     * list sees what a listed one would see from the provider as it is now.
     *
     * What each outcome does, the same as a reported send (docs/13):
     *
     *  - **Sent**: status `sent`.
     *  - **Timeout**: it may still arrive, so the challenge stands. The cooldown
     *    is lifted. Status `delayed`.
     *  - **Rejected, unavailable, insufficient balance**: nothing was delivered.
     *    The challenge is removed, the cooldown lifted and the per-address count
     *    given back; the per-network count is kept. Status `failed`.
     *
     * The outcome is applied on a whole-second boundary from when the code was
     * asked for ([SETTLE_QUANTUM]), so a real send's variable latency and a
     * decoy's replayed one land on the same tick unless they differ by most of
     * a second.
     *
     * No database here: this runs on its own thread, with no request identity.
     */
    private fun settleInBackground(
        channel: OtpChannel,
        address: String,
        purpose: String,
        requestId: String,
        code: String,
        real: Boolean,
    ) {
        val asked = System.nanoTime()
        background.execute {
            val outcome: FailureKind? = try {
                if (real) {
                    timedSend(channel, address, code)
                    null
                } else {
                    shadowOfLastSend()
                }
            } catch (failure: ProviderCallFailed) {
                log.warn(
                    "one-time code by {} not confirmed sent: {} after {} attempt(s)",
                    channel.key, failure.kind.code, failure.attempts,
                )
                failure.kind
            } catch (failure: Exception) {
                log.warn("one-time code by {} failed: {}", channel.key, failure.javaClass.simpleName)
                FailureKind.UNAVAILABLE
            }
            runCatching {
                waitForTick(asked)
                settle(channel, address, purpose, requestId, outcome)
            }.onFailure { log.warn("could not settle a one-time code by {}: {}", channel.key, it.javaClass.simpleName) }
        }
    }

    /**
     * One attempt through ProviderCalls. For email it also records how the
     * attempt went and how long it took — the provider's weather, which is
     * what a decoy replays. A rejection says the provider answered, so it is
     * recorded as a healthy provider: it is about that address, not the service.
     */
    private fun timedSend(channel: OtpChannel, address: String, code: String) {
        val started = System.nanoTime()
        var kind: FailureKind? = null
        try {
            calls.callOnce(channel.provider, "otp", cfg.sendTimeout) { send(channel, address, code) }
        } catch (failure: ProviderCallFailed) {
            kind = failure.kind
            throw failure
        } catch (failure: Exception) {
            kind = FailureKind.UNAVAILABLE
            throw failure
        } finally {
            if (channel == OtpChannel.EMAIL) {
                runCatching {
                    redis.opsForHash<String, String>().putAll(
                        weatherKey,
                        mapOf(
                            "kind" to (kind?.takeIf { it != FailureKind.REJECTED }?.code ?: HEALTHY),
                            "millis" to Duration.ofNanos(System.nanoTime() - started).toMillis().toString(),
                        ),
                    )
                    redis.expire(weatherKey, WEATHER_LIFETIME)
                }.onFailure { log.warn("could not record the email provider's last outcome: {}", it.javaClass.simpleName) }
            }
        }
    }

    /**
     * A decoy's stand-in for a send: as long as the last real one took, then the
     * way it ended — read at the end, as a real send's outcome is only known at
     * the end. Never a rejection, which belongs to one address. With nothing
     * recorded yet (a fresh Redis, or a day without a real send), a healthy
     * provider answering in [DEFAULT_SHADOW].
     */
    private fun shadowOfLastSend(): FailureKind? {
        val millis = redis.opsForHash<String, String>().get(weatherKey, "millis")?.toLongOrNull()
            ?: DEFAULT_SHADOW.toMillis()
        Thread.sleep(millis.coerceIn(0, cfg.sendTimeout.toMillis() + SETTLE_QUANTUM.toMillis()))
        val kind = redis.opsForHash<String, String>().get(weatherKey, "kind")
        return FailureKind.entries.firstOrNull { it.code == kind && it != FailureKind.REJECTED }
    }

    private fun waitForTick(asked: Long) {
        val elapsed = System.nanoTime() - asked
        val quantum = SETTLE_QUANTUM.toNanos()
        val tick = ((elapsed + quantum - 1) / quantum).coerceAtLeast(1) * quantum
        val wait = tick - elapsed
        if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
    }

    private fun settle(channel: OtpChannel, address: String, purpose: String, requestId: String, outcome: FailureKind?) {
        val fields = when (outcome) {
            null -> mapOf("state" to SENT)
            FailureKind.TIMEOUT -> {
                redis.delete(cooldownKey(channel, address, purpose))
                mapOf("state" to DELAYED)
            }
            else -> {
                // CONSUME removes it only if it is still this request's challenge.
                redis.execute(CONSUME, listOf(challengeKey(channel, address, purpose)), requestId)
                redis.delete(cooldownKey(channel, address, purpose))
                redis.execute(GIVE_BACK, listOf(rateKey(channel, address)))
                mapOf("state" to FAILED, "failure" to outcome.code)
            }
        }
        val status = deliveryKey(requestId)
        redis.opsForHash<String, String>().putAll(status, fields)
        redis.expire(status, cfg.ttl)
    }

    /**
     * How a deferred email send went, by request id — the only key, because the
     * request id is unguessable and known only to whoever asked. An id that was
     * never issued, or whose challenge lifetime has passed, is 404 whoever asks.
     */
    fun emailDelivery(requestId: String): OtpDeliveryStatus {
        val known = runCatching { UUID.fromString(requestId).toString() == requestId }.getOrDefault(false)
        val stored = if (known) redis.opsForHash<String, String>().entries(deliveryKey(requestId)) else emptyMap()
        if (stored.isEmpty()) {
            throw ApiException(
                HttpStatus.NOT_FOUND, "otp_request_unknown",
                "We don't have that code request any more. Ask for a new code.",
            )
        }
        val state = stored["state"] ?: SENDING
        val failure = stored["failure"]?.let { code -> FailureKind.entries.firstOrNull { it.code == code } }
        return when {
            state == FAILED && failure != null -> sendFailed(OtpChannel.EMAIL, failure, requestId).let {
                OtpDeliveryStatus(requestId, FAILED, it.code, notSent(it.message), 0L)
            }
            state == DELAYED -> sendFailed(OtpChannel.EMAIL, FailureKind.TIMEOUT, requestId).let {
                OtpDeliveryStatus(requestId, DELAYED, null, it.message, 0L)
            }
            state == SENT -> OtpDeliveryStatus(requestId, SENT, null, null, null)
            else -> OtpDeliveryStatus(requestId, SENDING, null, null, null)
        }
    }

    /**
     * Consumes the challenge on success so a code can never be used twice.
     * Wrong codes burn an attempt; after [AlmiraProperties.Otp.maxAttempts] the
     * challenge is destroyed and a fresh one must be requested — this is what
     * makes a 6-digit code safe to use at all.
     *
     * Both outcomes finish in one atomic Redis step that also re-checks the
     * request id. Reading, comparing and then writing separately let two
     * correct attempts both succeed, and let a burst of parallel wrong guesses
     * all be judged against the same attempt count.
     */
    fun verify(phone: String, code: String, requestId: String?, purpose: String = LOGIN, ip: String? = null) =
        verifyCode(OtpChannel.PHONE, phone, code, requestId, purpose, ip)

    /** The email equivalent of [verify]. A decoy challenge answers exactly as a real one with a wrong code. */
    fun verifyByEmail(email: String, code: String, requestId: String?, purpose: String, ip: String? = null) =
        verifyCode(OtpChannel.EMAIL, email, code, requestId, purpose, ip)

    private fun verifyCode(
        channel: OtpChannel,
        address: String,
        code: String,
        requestId: String?,
        purpose: String,
        ip: String?,
    ) {
        // Before the challenge is even read: a network over its allowance gets
        // no verdict at all, not even on a correct code, or the limit would only
        // slow a lucky guess down rather than stop it. The challenge is left
        // untouched, so its owner can still use it from their own network.
        val missKey = ip?.let { "otp:verify-miss:ip:$it" }
        missKey?.let(::enforceVerifyAllowance)

        val key = challengeKey(channel, address, purpose)
        val stored = redis.opsForHash<String, String>().entries(key)
        if (stored.isEmpty()) throw expired(channel)
        val storedRequestId = stored["requestId"].orEmpty()
        if (requestId != null && storedRequestId != requestId) {
            throw ApiException.badRequest("otp_stale", "Please use the most recent code we sent.")
        }

        val matches = MessageDigest.isEqual(
            stored["hash"].orEmpty().toByteArray(Charsets.US_ASCII),
            mac(channel, purpose, address, code).toByteArray(Charsets.US_ASCII),
        )
        if (matches) {
            // 1 only if this call removed this exact challenge. A concurrent
            // winner, an expiry or a newer challenge all leave nothing to take.
            val consumed = redis.execute(CONSUME, listOf(key), storedRequestId) ?: 0L
            if (consumed != 1L) throw expired(channel)
            return
        }

        val attempts = redis.execute(
            RECORD_MISS, listOf(key), storedRequestId, cfg.maxAttempts.toString(),
        ) ?: -1L
        if (attempts >= 0) missKey?.let(::recordNetworkMiss)
        when {
            attempts < 0 -> throw expired(channel)
            attempts >= cfg.maxAttempts -> throw ApiException.badRequest(
                "otp_locked",
                "Too many tries. Ask for a new code to continue.",
            )
            else -> throw ApiException.badRequest(
                "otp_invalid",
                "That code doesn't match. ${cfg.maxAttempts - attempts} tries left.",
                mapOf("attemptsRemaining" to (cfg.maxAttempts - attempts).toInt()),
            )
        }
    }

    private fun available(channel: OtpChannel) = when (channel) {
        OtpChannel.PHONE -> sender.available
        OtpChannel.EMAIL -> emailSender.available
    }

    private fun exposesCode(channel: OtpChannel) = when (channel) {
        OtpChannel.PHONE -> sender.exposesCodeForDevelopment
        OtpChannel.EMAIL -> emailSender.exposesCodeForDevelopment
    }

    private fun send(channel: OtpChannel, address: String, code: String) = when (channel) {
        OtpChannel.PHONE -> sender.send(address, code)
        OtpChannel.EMAIL -> emailSender.send(address, code)
    }

    private fun unavailable(channel: OtpChannel) = ApiException.serviceUnavailable(
        "otp_unavailable",
        when (channel) {
            OtpChannel.PHONE -> "Sign-in by text message isn't available on this server yet. No code was sent."
            OtpChannel.EMAIL -> "Sign-in by email isn't available on this server yet. No code was sent."
        },
    )

    /** One code per way of failing, so a client can say the right thing. See docs/13. */
    private fun sendFailed(channel: OtpChannel, kind: FailureKind, requestId: String): ApiException {
        val email = channel == OtpChannel.EMAIL
        return when (kind) {
            FailureKind.TIMEOUT -> ApiException(
                HttpStatus.GATEWAY_TIMEOUT, "otp_delivery_delayed",
                "Your code is taking longer than usual to send. If it arrives, it will work. " +
                    "If it doesn't, you can ask for a new one now.",
                mapOf(
                    "requestId" to requestId,
                    "expiresInSeconds" to cfg.ttl.seconds,
                    // The cooldown was lifted: resend is the retry, and it is the person's.
                    "resendAfterSeconds" to 0L,
                ),
            )
            FailureKind.REJECTED -> ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY, "otp_delivery_failed",
                if (email) {
                    "We couldn't deliver a code to that email address. Please check it and try again."
                } else {
                    "We couldn't deliver a code to that number. Please check it and try again."
                },
            )
            FailureKind.UNAVAILABLE -> ApiException(
                HttpStatus.SERVICE_UNAVAILABLE, "otp_provider_unavailable",
                "We couldn't reach our ${if (email) "email" else "text message"} service just now, " +
                    "so no code was sent. Please try again in a few minutes.",
            )
            FailureKind.INSUFFICIENT_BALANCE -> ApiException(
                HttpStatus.SERVICE_UNAVAILABLE, "otp_service_unavailable",
                "Sign-in codes can't be sent right now. This is a problem on our side, not with " +
                    "your ${if (email) "address" else "number"}, and we've been alerted. Please try again later.",
            )
        }
    }

    private fun expired(channel: OtpChannel) = ApiException.badRequest(
        "otp_expired",
        if (channel == OtpChannel.EMAIL) {
            "That code has expired. Ask for a new one and we'll email it right away."
        } else {
            "That code has expired. Ask for a new one and we'll text it right away."
        },
    )

    private fun enforceCooldown(channel: OtpChannel, address: String, purpose: String) {
        val ttl = redis.getExpire(cooldownKey(channel, address, purpose), TimeUnit.SECONDS)
        if (ttl > 0) {
            throw ApiException.tooManyRequests(
                "We just sent a code. You can ask for another in $ttl seconds.", ttl,
            )
        }
    }

    private fun enforceVerifyAllowance(key: String) {
        val misses = redis.opsForValue().get(key)?.toLongOrNull() ?: 0
        if (misses >= cfg.maxVerifyFailuresPerIpPerHour) {
            val retry = redis.getExpire(key, TimeUnit.SECONDS).coerceAtLeast(60)
            throw ApiException.tooManyRequests(
                "Too many incorrect codes from this network. Please try again later.", retry,
            )
        }
    }

    /** INCR is atomic; parallel misses can overshoot by at most the number in flight. */
    private fun recordNetworkMiss(key: String) {
        val count = redis.opsForValue().increment(key) ?: 1
        if (count == 1L) redis.expire(key, Duration.ofHours(1))
    }

    private fun enforceHourlyLimit(key: String, max: Int, subject: String) {
        val count = redis.opsForValue().increment(key) ?: 1
        if (count == 1L) redis.expire(key, Duration.ofHours(1))
        if (count > max) {
            val retry = redis.getExpire(key, TimeUnit.SECONDS).coerceAtLeast(60)
            throw ApiException.tooManyRequests(
                "Too many sign-in attempts from this $subject. Please try again later.", retry,
            )
        }
    }

    // Phone keys are unchanged from before email existed, so challenges and
    // counters live across the deploy that added it.
    private fun challengeKey(channel: OtpChannel, address: String, purpose: String) = when (channel) {
        OtpChannel.PHONE -> "otp:challenge:$purpose:$address"
        OtpChannel.EMAIL -> "otp:email:challenge:$purpose:$address"
    }

    private fun cooldownKey(channel: OtpChannel, address: String, purpose: String) = when (channel) {
        OtpChannel.PHONE -> "otp:cooldown:$purpose:$address"
        OtpChannel.EMAIL -> "otp:email:cooldown:$purpose:$address"
    }

    private fun rateKey(channel: OtpChannel, address: String) = "otp:rate:${channel.key}:$address"

    /** The owner's words first: the tester is told plainly that the code did not go. */
    private fun notSent(reason: String) = "We couldn't send the code. $reason"

    private fun deliveryKey(requestId: String) = "otp:email:delivery:$requestId"

    private val OtpChannel.subject get() = if (this == OtpChannel.EMAIL) "email address" else "phone"

    private fun mac(channel: OtpChannel, purpose: String, address: String, code: String): String {
        val mac = Mac.getInstance(HMAC)
        mac.init(if (channel == OtpChannel.EMAIL) emailCodeKey else codeKey)
        return HexFormat.of().formatHex(mac.doFinal("$purpose|$address|$code".toByteArray(Charsets.UTF_8)))
    }

    /** Same length and alphabet as a real MAC, and equal to none: 256 random bits. */
    private fun unmatchable(): String = HexFormat.of().formatHex(ByteArray(32).also(random::nextBytes))

    companion object {
        const val LOGIN = "login"
        const val STEP_UP = "step_up"

        const val SENDING = "sending"
        const val SENT = "sent"
        const val DELAYED = "delayed"
        const val FAILED = "failed"
        private const val HEALTHY = "ok"

        /** One per server fleet: every instance's decoys replay the same last send. */
        const val EMAIL_WEATHER_KEY = "otp:email:provider-weather"
        private val WEATHER_LIFETIME: Duration = Duration.ofDays(1)

        /** A decoy's wait before any real send has been seen: a healthy provider's accept. */
        val DEFAULT_SHADOW: Duration = Duration.ofMillis(300)

        /** Deferred outcomes are applied on these boundaries from the request. */
        val SETTLE_QUANTUM: Duration = Duration.ofSeconds(1)

        private fun deriveKey(props: AlmiraProperties, label: String): SecretKeySpec {
            val mac = Mac.getInstance(HMAC)
            mac.init(SecretKeySpec(props.jwt.secret.toByteArray(Charsets.UTF_8), HMAC))
            return SecretKeySpec(mac.doFinal(label.toByteArray(Charsets.UTF_8)), HMAC)
        }

        /** Returns one request to the hourly count, never below zero. */
        private val GIVE_BACK = DefaultRedisScript(
            """
            local n = tonumber(redis.call('GET', KEYS[1]) or '0')
            if n > 0 then
              return redis.call('DECR', KEYS[1])
            end
            return 0
            """.trimIndent(),
            Long::class.javaObjectType,
        )

        private const val HMAC = "HmacSHA256"
        private val MAX_TTL: Duration = Duration.ofMinutes(10)

        /**
         * The longest a person is kept looking at a spinner. Past this they have
         * given up, and mobile clients and proxies start cutting the request.
         */
        val MAX_SEND_TIMEOUT: Duration = Duration.ofSeconds(15)

        private val CONSUME = DefaultRedisScript(
            """
            if redis.call('HGET', KEYS[1], 'requestId') == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """.trimIndent(),
            Long::class.javaObjectType,
        )

        /** -1 when the challenge is gone or replaced; otherwise the new count. */
        private val RECORD_MISS = DefaultRedisScript(
            """
            if redis.call('HGET', KEYS[1], 'requestId') ~= ARGV[1] then
              return -1
            end
            local n = redis.call('HINCRBY', KEYS[1], 'attempts', 1)
            if n >= tonumber(ARGV[2]) then
              redis.call('DEL', KEYS[1])
            end
            return n
            """.trimIndent(),
            Long::class.javaObjectType,
        )
    }
}
