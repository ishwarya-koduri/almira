package tech.bhrigu.almira.auth

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import tech.bhrigu.almira.common.ApiException
import tech.bhrigu.almira.common.RateLimit
import tech.bhrigu.almira.config.AlmiraProperties
import tech.bhrigu.almira.provider.FailureKind
import tech.bhrigu.almira.provider.ProviderCallFailed
import tech.bhrigu.almira.provider.ProviderCalls
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.HexFormat
import java.util.UUID
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
 * So email sign-in answers at once and queues the email, for every address
 * alike, and the outbox worker decides whether it goes ([SignInEmailOutbox]):
 * a listed address is sent its code, an unlisted one is dropped with no
 * provider call. The delivery status the code step polls
 * ([OtpService.emailDelivery]) cannot follow the worker without telling
 * strangers who is listed, since only a listed address can ever fail; so it
 * follows the clock, the same for everyone (docs/13 §5).
 */
enum class OtpDelivery {
    /** Send now, and answer with the outcome — see [OtpService.request]. */
    REPORTED,

    /**
     * Answer at once and queue the email ([SignInCodeOutbox]); the worker sends
     * it if the address is on the allowlist when it gets there. Nothing on the
     * request path waits for, or talks to, a provider. See [OtpService.queue].
     */
    DEFERRED,

    /**
     * Everything [DEFERRED] does, statement for statement — every counter, the
     * cooldown, a stored challenge with its own request id and lifetime, the
     * same queued message and the same delivery status — except that the
     * stored value is random, so no code of any length matches it. For an
     * address that is not allowed to sign in; the worker drops its message.
     */
    DECOY,
}

/** How a deferred send went, as the code step sees it. */
data class OtpDeliveryStatus(
    val requestId: String,
    /** `sending`, `sent`, `delayed` or `failed`. */
    val status: String,
    /** Never set for sign-in since the outbox (docs/13 §5); kept for the frozen contract and clients that read it. */
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
 *  - the same, per channel, for email (EmailOtpTest), and an address off the
 *    allowlist that nobody outside can tell from one on it
 *    (EmailOtpTest, EmailSignInApiTest).
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
    /** Where [OtpDelivery.DEFERRED] and [OtpDelivery.DECOY] queue their email. */
    private val outbox: SignInCodeOutbox = SignInCodeOutbox.NONE,
    /**
     * How long after the request the delivery status says `sent`: the
     * one-time-code timeout and a margin, as it was when the status followed a
     * real send. A seam so tests that are not about timing need not sit through it.
     */
    private val settleDelay: Duration = props.otp.sendTimeout.plus(SETTLE_MARGIN),
    private val clock: java.time.Clock = java.time.Clock.systemUTC(),
) {
    @Autowired
    constructor(
        redis: StringRedisTemplate,
        sender: OtpSender,
        props: AlmiraProperties,
        calls: ProviderCalls,
        emailSender: EmailOtpSender,
        outbox: SignInCodeOutbox,
    ) : this(redis, sender, props, calls, emailSender, outbox, props.otp.sendTimeout.plus(SETTLE_MARGIN))

    private val log = LoggerFactory.getLogger(javaClass)

    /** The product's one rate limiter (common/RateLimit.kt); guest links use it too. */
    private val limits = RateLimit(redis)

    private val cfg = props.otp
    private val development = props.isDevelopment
    private val random = SecureRandom()

    /** A queued email's code, derived again by the worker that sends it. */
    private val queuedCodes = QueuedEmailCodes(props)

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
        // Also run by StartupSettingsCheck, before the application context and
        // so before the database is migrated; kept here for anything that
        // builds this class directly.
        checkBounds(cfg)
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
     *    removed (no live code with nobody to receive it) and the one it
     *    replaced, if still live, is put back, so an earlier code that did
     *    arrive keeps working ([notSentFallBack]); the cooldown is
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
        // and, for the log sender, a code in a production log. Checked for an
        // unlisted address too: whether the server can send email is not a
        // secret, and the answer must not differ by address.
        if (!available(channel)) throw unavailable(channel)
        enforceCooldown(channel, address, purpose)
        // The network's cap before the address's count: a request its network
        // is refused must not use up one of that person's hourly sends. The
        // other way round, a network over its cap could lock any number or
        // address out for an hour without a single message being sent.
        ip?.let { enforceHourlyLimit("otp:rate:ip:$it", cfg.maxPerIpPerHour, "network") }
        enforceHourlyLimit(rateKey(channel, address), cfg.maxPerHour, channel.subject)

        val requestId = UUID.randomUUID().toString()
        val queued = delivery != OtpDelivery.REPORTED
        // A queued email's code is derived, so the queue need not hold it
        // (QueuedEmailCodes); a reported send's is random and goes nowhere else.
        val code = if (queued) queuedCodes.code(purpose, address, requestId, cfg.length)
        else (1..cfg.length).map { random.nextInt(10) }.joinToString("")
        // Both computed for every address and one thrown away, so a listed and an
        // unlisted address do the same work in the same order.
        val hash = mac(channel, purpose, address, code)
        val noMatch = unmatchable()
        val stored = if (delivery == OtpDelivery.DECOY) noMatch else hash

        val key = challengeKey(channel, address, purpose)
        // The challenge this one replaces, if any, is set aside rather than
        // overwritten, so a send that fails outright can put it back
        // (known-issues 22). REPLACE returns its request id.
        val previous: String? = redis.execute(
            REPLACE, listOf(key, setAsideKey(requestId)), stored, requestId, cfg.ttl.toMillis().toString(),
        ).takeIf { it.isNotEmpty() }
        // Redis rejects a zero or negative TTL outright, so a misconfigured
        // cooldown would turn every sign-in attempt into a 500 rather than
        // simply disabling the cooldown. Guard the config, not the user.
        if (!cfg.resendCooldown.isZero && !cfg.resendCooldown.isNegative) {
            redis.opsForValue().set(cooldownKey(channel, address, purpose), "1", cfg.resendCooldown)
        }

        when (delivery) {
            OtpDelivery.REPORTED -> try {
                timedSend(channel, address, code)
                // Sent: this is the newest code, and the one it replaced is gone for good.
                redis.delete(setAsideKey(requestId))
            } catch (failure: ProviderCallFailed) {
                if (failure.kind == FailureKind.TIMEOUT) {
                    // The person may press resend now. The new challenge
                    // overwrites this one, so a late text stops working then.
                    // It may have been sent, so the one it replaced is gone.
                    redis.delete(setAsideKey(requestId))
                    redis.delete(cooldownKey(channel, address, purpose))
                } else {
                    notSentFallBack(channel, address, purpose, requestId, previous)
                    redis.delete(cooldownKey(channel, address, purpose))
                    redis.execute(GIVE_BACK, listOf(rateKey(channel, address)))
                }
                throw sendFailed(channel, failure.kind, requestId)
            }
            OtpDelivery.DEFERRED, OtpDelivery.DECOY -> queue(channel, address, purpose, requestId, previous)
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
     * The email, for a listed address and an unlisted one alike: the same
     * statements in the same order, and nothing that waits for or talks to a
     * provider. The outbox worker decides later whether it is sent
     * ([SignInEmailOutbox]); nothing it decides comes back here.
     *
     *  - **The delivery status** records when it will say `sent`: [settleDelay]
     *    after the request, whatever happens to the email. It cannot follow the
     *    send. Only a listed address is ever sent anything, so only a listed
     *    address could ever show `failed` or `delayed`, and an answer that did
     *    would tell anyone with a list of guesses which of them are listed
     *    (docs/13 §5). A tester whose email did not come has the code step's
     *    "Didn't arrive in two minutes? Contact us"; the operator has the ERROR
     *    and WARN lines the worker and ProviderCalls write.
     *  - **The challenge this one replaced** is gone for good at once, as it was
     *    once a send was recorded sent: no outcome will ever come back to put it
     *    back, and a stranger could probe one that did.
     *  - **The cooldown and both hourly counts** stand, whatever the worker
     *    finds, for the same reason.
     *
     * If the message cannot be queued at all (the database refused it), the
     * request fails as a reported send that certainly did not go out does —
     * challenge fallen back, cooldown lifted, the address's count given back —
     * which depends on the database, never on the address.
     */
    private fun queue(channel: OtpChannel, address: String, purpose: String, requestId: String, previous: String?) {
        val status = deliveryKey(requestId)
        redis.opsForHash<String, String>().putAll(
            status,
            mapOf("state" to SENDING, "settleAt" to clock.instant().plus(settleDelay).toEpochMilli().toString()),
        )
        redis.expire(status, cfg.ttl)
        try {
            outbox.enqueue(address, purpose, requestId, cfg.length)
        } catch (failure: Exception) {
            notSentFallBack(channel, address, purpose, requestId, previous)
            redis.delete(cooldownKey(channel, address, purpose))
            redis.execute(GIVE_BACK, listOf(rateKey(channel, address)))
            redis.delete(status)
            log.warn("could not queue a one-time code by {}: {}", channel.key, failure.javaClass.simpleName)
            throw ApiException.serviceUnavailable(
                "otp_service_unavailable",
                "Sign-in codes can't be sent right now. This is a problem on our side, not with " +
                    "your address. Please try again in a few minutes.",
            )
        }
        redis.delete(setAsideKey(requestId))
    }

    /** One attempt through ProviderCalls, under the one-time-code timeout. */
    private fun timedSend(channel: OtpChannel, address: String, code: String) {
        calls.callOnce(channel.provider, "otp", cfg.sendTimeout) { send(channel, address, code) }
    }

    /**
     * A send that certainly did not go out: remove this request's challenge —
     * only if it is still the newest — and put back the one it replaced, if
     * that one is still worth having (known-issues 22).
     *
     * Put back only when:
     *  - this request's challenge is still the current one. A newer request
     *    has replaced it otherwise, and set it aside in turn;
     *  - the replaced challenge has not expired. It kept its own lifetime while
     *    set aside, so it expires when it always would have;
     *  - its own send has not been recorded as failed (a zero cooldown can let
     *    a request replace one whose send is still in flight);
     *  - its attempts, plus the wrong codes tried against this one while it
     *    stood, are under the cap. Failed requests do not buy extra guesses.
     *
     * A restored challenge also accepts this request's id, because a client
     * that was shown this request's id (an emailed code's answer comes before
     * the send) still holds it, and the code it can type is the earlier one.
     *
     * Only a send that was never delivered falls back. A timeout may yet
     * arrive, and a send that worked is the newest code: neither brings an
     * older code back.
     */
    private fun notSentFallBack(
        channel: OtpChannel,
        address: String,
        purpose: String,
        requestId: String,
        previous: String?,
    ) {
        redis.opsForValue().set(notSentKey(requestId), "1", cfg.ttl)
        redis.execute(
            FALL_BACK,
            listOf(challengeKey(channel, address, purpose), setAsideKey(requestId), notSentKey(previous ?: "none")),
            requestId, cfg.maxAttempts.toString(), MAX_FALLBACK_IDS.toString(),
        )
    }

    /**
     * The delivery status of an email sign-in request, by request id — the only
     * key, because the request id is unguessable and known only to whoever
     * asked. An id that was never issued, or whose challenge lifetime has
     * passed, is 404 whoever asks.
     *
     * `sending` until the moment [queue] recorded, then `sent`, for every
     * address: the same answer at the same moment whether the worker sent the
     * email, dropped it or has not reached it yet. `delayed` and `failed` are
     * still read, for a status written before the outbox, and no longer written.
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
            (stored["settleAt"]?.toLongOrNull() ?: Long.MAX_VALUE) <= clock.millis() ->
                OtpDeliveryStatus(requestId, SENT, null, null, null)
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

    /** The email equivalent of [verify]. An unlisted address's challenge answers exactly as a real one with a wrong code. */
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
        if (requestId != null && storedRequestId != requestId && stored[fallbackField(requestId)] == null) {
            throw ApiException.badRequest("otp_stale", "Please use the most recent code we sent.")
        }

        val matches = MessageDigest.isEqual(
            stored["hash"].orEmpty().toByteArray(Charsets.US_ASCII),
            mac(channel, purpose, address, code).toByteArray(Charsets.US_ASCII),
        )
        if (matches) {
            // 1 only if this call removed this exact challenge. A concurrent
            // winner, an expiry or a newer challenge all leave nothing to take.
            val consumed = redis.execute(CONSUME, listOf(key), storedRequestId, cfg.maxAttempts.toString()) ?: 0L
            if (consumed != 1L) throw expired(channel)
            return
        }

        // Counted with the attempts of the challenge this one set aside, while
        // that one waits on this one's send: a resend that then fails puts it
        // back, so the two share one allowance until the send settles.
        val attempts = redis.execute(
            RECORD_MISS, listOf(key, setAsideKey(storedRequestId)), storedRequestId, cfg.maxAttempts.toString(),
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
        // Counted by [recordNetworkMiss] rather than here — only a WRONG code
        // spends one — so this reads the count instead of taking from it.
        val misses = redis.opsForValue().get(key)?.toLongOrNull() ?: 0
        if (misses >= cfg.maxVerifyFailuresPerIpPerHour) {
            throw limits.refusal(key, "Too many incorrect codes from this network. Please try again later.")
        }
    }

    /** INCR is atomic; parallel misses can overshoot by at most the number in flight. */
    private fun recordNetworkMiss(key: String) {
        val count = redis.opsForValue().increment(key) ?: 1
        if (count == 1L) redis.expire(key, Duration.ofHours(1))
    }

    private fun enforceHourlyLimit(key: String, max: Int, subject: String) =
        limits.take(
            key, max, Duration.ofHours(1),
            "Too many sign-in attempts from this $subject. Please try again later.",
        )

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

    /** Where the challenge a request replaced waits until that request's send has settled. */
    private fun setAsideKey(requestId: String) = "otp:replaced-by:$requestId"

    /** A request whose send certainly did not go out, so nothing falls back to its challenge. */
    private fun notSentKey(requestId: String) = "otp:not-sent:$requestId"

    private fun fallbackField(requestId: String) = "fallbackFor:$requestId"

    private val OtpChannel.subject get() = if (this == OtpChannel.EMAIL) "email address" else "phone"

    private fun mac(channel: OtpChannel, purpose: String, address: String, code: String): String {
        val mac = Mac.getInstance(HMAC)
        mac.init(if (channel == OtpChannel.EMAIL) emailCodeKey else codeKey)
        return HexFormat.of().formatHex(mac.doFinal("$purpose|$address|$code".toByteArray(Charsets.UTF_8)))
    }

    /** Same length and alphabet as a real MAC, and equal to none: 256 random bits. */
    private fun unmatchable(): String = HexFormat.of().formatHex(ByteArray(32).also(random::nextBytes))

    companion object {
        /** The configuration bounds. See StartupSettingsCheck. */
        fun checkBounds(cfg: AlmiraProperties.Otp) {
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

        const val LOGIN = "login"
        const val STEP_UP = "step_up"

        /** A code to a number someone wants on their account: proves they receive its texts. */
        const val PHONE_CHANGE = "phone_change"

        const val SENDING = "sending"
        const val SENT = "sent"
        const val DELAYED = "delayed"
        const val FAILED = "failed"

        /**
         * Past the one-time-code timeout, before the delivery status says sent.
         * It once gave ProviderCalls room to hand a failure back; it now keeps
         * the code step's "sending" as long as it was.
         */
        val SETTLE_MARGIN: Duration = Duration.ofSeconds(1)

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

        /** How many failed requests' ids one restored challenge will answer to. */
        private const val MAX_FALLBACK_IDS = 16

        /**
         * KEYS: challenge, set-aside. ARGV: hash, request id, lifetime in ms.
         * Moves any current challenge aside with its remaining lifetime (RENAME
         * keeps it), writes the new one, and returns the replaced request id, or
         * an empty string when there was none.
         */
        private val REPLACE = DefaultRedisScript(
            """
            local previous = ''
            if redis.call('EXISTS', KEYS[1]) == 1 then
              previous = redis.call('HGET', KEYS[1], 'requestId')
              redis.call('RENAME', KEYS[1], KEYS[2])
            end
            redis.call('HSET', KEYS[1], 'hash', ARGV[1], 'requestId', ARGV[2], 'attempts', '0')
            redis.call('PEXPIRE', KEYS[1], ARGV[3])
            return previous
            """.trimIndent(),
            String::class.java,
        )

        /**
         * KEYS: challenge, set-aside, the replaced request's not-sent marker.
         * ARGV: this request id, max attempts, max fallback ids.
         * 1 when the replaced challenge was put back. See [notSentFallBack].
         */
        private val FALL_BACK = DefaultRedisScript(
            """
            if redis.call('HGET', KEYS[1], 'requestId') ~= ARGV[1] then
              redis.call('DEL', KEYS[2])
              return 0
            end
            local misses = tonumber(redis.call('HGET', KEYS[1], 'attempts') or '0')
            redis.call('DEL', KEYS[1])
            if redis.call('EXISTS', KEYS[2]) == 0 then
              return 0
            end
            if redis.call('EXISTS', KEYS[3]) == 1 then
              redis.call('DEL', KEYS[2])
              return 0
            end
            local attempts = tonumber(redis.call('HGET', KEYS[2], 'attempts') or '0') + misses
            if attempts >= tonumber(ARGV[2]) then
              redis.call('DEL', KEYS[2])
              return 0
            end
            redis.call('HSET', KEYS[2], 'attempts', tostring(attempts))
            if redis.call('HLEN', KEYS[2]) < 3 + tonumber(ARGV[3]) then
              redis.call('HSET', KEYS[2], 'fallbackFor:' .. ARGV[1], '1')
            end
            redis.call('RENAME', KEYS[2], KEYS[1])
            return 1
            """.trimIndent(),
            Long::class.javaObjectType,
        )

        /**
         * KEYS: challenge. ARGV: request id, max attempts. 1 when this call took
         * the challenge. A challenge already at its cap is removed and not
         * taken: none should exist (RECORD_MISS and FALL_BACK both remove one),
         * so this is the second lock on the same door.
         */
        private val CONSUME = DefaultRedisScript(
            """
            if redis.call('HGET', KEYS[1], 'requestId') ~= ARGV[1] then
              return 0
            end
            if tonumber(redis.call('HGET', KEYS[1], 'attempts') or '0') >= tonumber(ARGV[2]) then
              redis.call('DEL', KEYS[1])
              return 0
            end
            return redis.call('DEL', KEYS[1])
            """.trimIndent(),
            Long::class.javaObjectType,
        )

        /**
         * KEYS: challenge, the challenge it set aside (usually absent).
         * ARGV: request id, max attempts.
         * -1 when the challenge is gone or replaced; otherwise the count that
         * decides: its own wrong codes plus those of the challenge it set aside,
         * which exists only while this one's send has not settled. At the cap
         * both are removed, so a failing send has nothing to put back. Without
         * the set-aside count, a resend in flight was a fresh allowance on top
         * of the attempts the earlier code had used (known-issues 22).
         */
        private val RECORD_MISS = DefaultRedisScript(
            """
            if redis.call('HGET', KEYS[1], 'requestId') ~= ARGV[1] then
              return -1
            end
            local n = redis.call('HINCRBY', KEYS[1], 'attempts', 1)
            local counted = n + tonumber(redis.call('HGET', KEYS[2], 'attempts') or '0')
            if counted >= tonumber(ARGV[2]) then
              redis.call('DEL', KEYS[1], KEYS[2])
            end
            return counted
            """.trimIndent(),
            Long::class.javaObjectType,
        )
    }
}
