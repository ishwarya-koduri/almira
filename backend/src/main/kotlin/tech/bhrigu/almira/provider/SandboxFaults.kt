package tech.bhrigu.almira.provider

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/** What a sandbox adapter can be told to do instead of succeeding. */
enum class SandboxFault {
    /** Throw [FailureKind.TIMEOUT] at once, as a transport's own read timeout would. */
    TIMEOUT,

    /**
     * Do not answer. Sleeps (interruptibly) for [SandboxFaults.hangFor], so the
     * only thing that can turn it into a timeout is [ProviderCalls] enforcing one.
     */
    HANG,
    UNAVAILABLE,
    REJECTED,
    INSUFFICIENT_BALANCE,
}

/**
 * The failure switch for the sandbox adapters.
 *
 * A sandbox that always succeeds proves the happy path and nothing else, so
 * every sandbox adapter asks this, at the start of each operation, whether it
 * should fail instead — which lets a test produce each [FailureKind] through the
 * real wiring: the real service, the real retry policy, the real HTTP error.
 *
 * Deliberately test-only in practice: there is no endpoint, property or
 * environment variable that sets it, so a running server's sandboxes behave
 * exactly as before. Only code holding this bean can script a fault, and in this
 * repository that is tests. Live adapters never consult it.
 *
 * Adapter names are the provider names, plus `otp` for one-time codes, which
 * share the SMS provider's configuration but are scripted separately so a test
 * failing a sign-in does not also fail a reminder.
 */
@Component
class SandboxFaults(private val hangFor: Duration) {

    @Autowired
    constructor() : this(Duration.ofSeconds(30))

    private val scripted = ConcurrentHashMap<String, ConcurrentLinkedQueue<SandboxFault>>()
    private val sticky = ConcurrentHashMap<String, SandboxFault>()
    private val passes = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

    /**
     * The next [calls] to [adapter] succeed whatever else is scripted, then the
     * faults apply. For a failure partway through a flow — an authorisation code
     * that redeems, then a list that does not.
     */
    fun succeedFirst(adapter: String, calls: Int) {
        passes.computeIfAbsent(adapter) { java.util.concurrent.atomic.AtomicInteger() }.addAndGet(calls)
    }

    /** The next calls to [adapter] fail in this order, then it succeeds again. */
    fun script(adapter: String, vararg faults: SandboxFault) {
        scripted.computeIfAbsent(adapter) { ConcurrentLinkedQueue() }.addAll(faults)
    }

    /** Every call to [adapter] fails this way until [clear]. */
    fun always(adapter: String, fault: SandboxFault) {
        sticky[adapter] = fault
    }

    fun clear() {
        scripted.clear()
        sticky.clear()
        passes.clear()
    }

    /** Called by a sandbox adapter before it does anything. Returns normally to succeed. */
    fun apply(adapter: String) {
        if ((passes[adapter]?.getAndUpdate { if (it > 0) it - 1 else 0 } ?: 0) > 0) return
        val fault = scripted[adapter]?.poll() ?: sticky[adapter] ?: return
        when (fault) {
            SandboxFault.TIMEOUT -> throw ProviderFailure(FailureKind.TIMEOUT, "sandbox: scripted timeout")
            SandboxFault.HANG -> {
                try {
                    Thread.sleep(hangFor.toMillis())
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw ProviderFailure(FailureKind.TIMEOUT, "sandbox: interrupted while hanging")
                }
            }
            SandboxFault.UNAVAILABLE ->
                throw ProviderFailure(FailureKind.UNAVAILABLE, "sandbox: scripted 503")
            SandboxFault.REJECTED ->
                throw ProviderFailure(FailureKind.REJECTED, "sandbox: scripted rejection")
            SandboxFault.INSUFFICIENT_BALANCE ->
                throw ProviderFailure(FailureKind.INSUFFICIENT_BALANCE, "sandbox: scripted zero balance")
        }
    }
}
