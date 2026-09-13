package tech.bhrigu.almira.provider

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import tech.bhrigu.almira.config.AlmiraProperties
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.DoubleSupplier

/** Waits between attempts. A seam so tests assert the backoff instead of sitting through it. */
fun interface Sleeper {
    fun sleep(duration: Duration)

    companion object {
        val REAL = Sleeper { if (!it.isZero && !it.isNegative) Thread.sleep(it.toMillis()) }
    }
}

data class ProviderResult<T>(val value: T, val attempts: Int)

/**
 * The one place a call to an outside service is timed out and retried.
 *
 * `timeout`, `maxAttempts` and `retryBackoff` sat in configuration for every
 * provider and were read by nothing: an adapter that hung would have held a
 * request thread for as long as the provider liked, and a failure was tried
 * exactly once whatever it was. Every adapter call now goes through here, so
 * the policy is the same for all of them and is tested once, rather than being
 * re-invented — slightly differently — in each live adapter as it is written.
 *
 * The policy:
 *  - each attempt gets [AlmiraProperties.Provider.timeout], enforced here, not
 *    trusted to the transport. The call runs on its own (virtual) thread and is
 *    interrupted when the time is up;
 *  - only retryable kinds are retried ([FailureKind.retryable]), up to
 *    `maxAttempts` in total, sleeping `retryBackoff` doubled per attempt with
 *    jitter so a fleet does not retry in lockstep;
 *  - a rejection or an account-level failure is never retried;
 *  - anything that is not a [ProviderFailure] is a bug or a domain refusal and
 *    passes through unchanged, after one attempt;
 *  - giving up is logged at WARN, once per call, with the provider, operation,
 *    kind and attempts only;
 *  - an account-level failure is also logged at ERROR, once per call, and
 *    remembered in [accountProblems], because it is the operator's to fix.
 *
 * What runs inside the block must be the provider call and nothing else. It is
 * on another thread, so it has no request identity and no transaction — which
 * is correct for talking to a provider, and wrong for touching the database.
 */
@Component
class ProviderCalls(
    private val props: AlmiraProperties,
    private val sleeper: Sleeper,
    private val random: DoubleSupplier,
    private val clock: Clock,
) : AutoCloseable {

    @Autowired
    constructor(props: AlmiraProperties) :
        this(props, Sleeper.REAL, DoubleSupplier { ThreadLocalRandom.current().nextDouble() }, Clock.systemUTC())

    private val log = LoggerFactory.getLogger(javaClass)

    private val pool: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

    private val problems = ConcurrentHashMap<String, Instant>()

    init {
        // Bounds, checked at startup: a zero timeout fails every call, and a
        // hundred attempts turns one outage into a hundred bills.
        props.providers.all().forEach { (name, p) ->
            require(!p.timeout.isNegative && !p.timeout.isZero && p.timeout <= MAX_TIMEOUT) {
                "almira.providers.$name.timeout must be more than zero and at most $MAX_TIMEOUT (is ${p.timeout})."
            }
            require(p.maxAttempts in 1..MAX_ATTEMPTS) {
                "almira.providers.$name.max-attempts must be 1 to $MAX_ATTEMPTS (is ${p.maxAttempts})."
            }
            require(!p.retryBackoff.isNegative && p.retryBackoff <= MAX_BACKOFF) {
                "almira.providers.$name.retry-backoff must be 0 to $MAX_BACKOFF (is ${p.retryBackoff})."
            }
        }
    }

    /** The value only. See [execute]. */
    fun <T> call(provider: String, operation: String, idempotent: Boolean = true, block: () -> T): T =
        execute(provider, operation, idempotent, block).value

    /**
     * Runs [block] under [provider]'s timeout and retry policy.
     *
     * [idempotent] = false for an operation that must not happen twice — creating
     * a consent, redeeming a one-time authorisation code. A timeout is then not
     * retried, because the first request may have landed and a second would
     * create a duplicate or be refused as already used; [FailureKind.UNAVAILABLE]
     * still is, because nothing was accepted.
     *
     * @throws ProviderCallFailed once it has given up.
     */
    fun <T> execute(
        provider: String,
        operation: String,
        idempotent: Boolean = true,
        block: () -> T,
    ): ProviderResult<T> {
        val config = props.providers.all()[provider]
            ?: throw IllegalArgumentException("no provider configuration named '$provider'")
        var attempt = 0
        while (true) {
            attempt++
            try {
                return ProviderResult(runAttempt(config.timeout, block), attempt)
            } catch (failure: ProviderFailure) {
                val retry = failure.kind.retryable &&
                    attempt < config.maxAttempts &&
                    (idempotent || failure.kind != FailureKind.TIMEOUT)
                if (!retry) {
                    gaveUp(provider, operation, failure.kind, attempt)
                    if (failure.kind.accountLevel) raiseAccountProblem(provider, operation, failure)
                    throw ProviderCallFailed(provider, operation, failure.kind, attempt, failure)
                }
                log.info(
                    "{} {}: {} on attempt {} of {}, retrying",
                    provider, operation, failure.kind.code, attempt, config.maxAttempts,
                )
                sleeper.sleep(backoff(config.retryBackoff, attempt))
            }
        }
    }

    /**
     * Exactly one attempt, under [timeout] rather than the provider's, with no
     * retry and no backoff — for a call a person is sitting in front of.
     *
     * A one-time code is the case (docs/13 "Interactive and background"): the
     * person is looking at the screen and can press resend, so the server must
     * answer quickly and must never send a second text on its own. A retried
     * timeout was exactly how one request became two or three billed texts
     * (known-issues 21). The provider's `max-attempts` and `retry-backoff` are
     * ignored here on purpose; its account-problem ERROR and the give-up WARN
     * are not.
     *
     * @throws ProviderCallFailed with `attempts = 1` when the attempt fails.
     */
    fun <T> callOnce(provider: String, operation: String, timeout: Duration, block: () -> T): T {
        require(props.providers.all().containsKey(provider)) { "no provider configuration named '$provider'" }
        require(!timeout.isNegative && !timeout.isZero && timeout <= MAX_TIMEOUT) {
            "an interactive timeout must be more than zero and at most $MAX_TIMEOUT (is $timeout)"
        }
        try {
            return runAttempt(timeout, block)
        } catch (failure: ProviderFailure) {
            gaveUp(provider, operation, failure.kind, 1)
            if (failure.kind.accountLevel) raiseAccountProblem(provider, operation, failure)
            throw ProviderCallFailed(provider, operation, failure.kind, 1, failure)
        }
    }

    /**
     * Doubling, with "equal jitter": half the delay is fixed and half is random,
     * so retries spread out without ever collapsing to nothing.
     */
    internal fun backoff(base: Duration, attempt: Int): Duration {
        val full = base.toMillis().toDouble() * (1L shl (attempt - 1).coerceAtMost(20))
        val capped = full.coerceAtMost(MAX_BACKOFF.toMillis().toDouble())
        return Duration.ofMillis((capped / 2 + random.asDouble * capped / 2).toLong())
    }

    /** When each provider last refused us on account grounds. For the operator, not for users. */
    fun accountProblems(): Map<String, Instant> = HashMap(problems)

    private fun <T> runAttempt(timeout: Duration, block: () -> T): T {
        val future = pool.submit(Callable { block() })
        try {
            return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw ProviderFailure(FailureKind.TIMEOUT, "no answer within $timeout")
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw e
        }
    }

    /**
     * One WARN per call that ends in failure, whatever the kind. Without it a
     * sign-in code or a connect that was rejected, or ran out of attempts
     * against an outage, left nothing in the log but INFO retry lines (or, for a
     * rejection, nothing at all) — the person saw an error and the operator saw
     * no trace of it.
     *
     * Provider, operation, kind and attempts, and nothing else: not the
     * [ProviderFailure] (its detail is the adapter's words and could carry a
     * recipient by mistake), not the exception, and never anything from the
     * block — the recipient, the code and the message body stay out.
     */
    private fun gaveUp(provider: String, operation: String, kind: FailureKind, attempts: Int) {
        log.warn(
            "PROVIDER CALL FAILED: provider={} operation={} kind={} attempts={}",
            provider, operation, kind.code, attempts,
        )
    }

    private fun raiseAccountProblem(provider: String, operation: String, failure: ProviderFailure) {
        problems[provider] = clock.instant()
        // ERROR, with fixed wording an alert can match on. This is the one
        // failure that is entirely ours: nothing will be delivered on this
        // provider until somebody tops up or fixes the account.
        log.error(
            "PROVIDER ACCOUNT PROBLEM: {} refused {} on account grounds ({}). Users are being told " +
                "the service is unavailable. Top up or fix the provider account.",
            provider, operation, failure.kind.code,
        )
    }

    override fun close() {
        pool.shutdownNow()
    }

    companion object {
        val MAX_TIMEOUT: Duration = Duration.ofMinutes(2)
        val MAX_BACKOFF: Duration = Duration.ofSeconds(30)
        const val MAX_ATTEMPTS = 10
    }
}
