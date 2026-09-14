package tech.bhrigu.almira.lifecycle

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

/** What one run did. Counts only. */
data class LifecycleSweepResult(
    val accountsErased: Int = 0,
    val departuresCompleted: Int = 0,
    val comingOfAgeNotices: Int = 0,
)

/**
 * The clock behind every waiting period in docs/05 §12: closures whose thirty
 * days are up, departures whose seven days are up, and the month a managed child
 * turns eighteen.
 *
 * Each piece takes the OWNER connection by explicit qualifier (see its class),
 * and each item is its own transaction: one closure that fails is logged and
 * retried on the next run, and does not stop the next person's.
 *
 * [run] takes the time as a parameter so a test can stand thirty days in the
 * future without rewriting the waiting period — which the database refuses to
 * let anyone do (V40, V41).
 */
@Component
class LifecycleSweep(
    private val purge: AccountPurge,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 40 * * * *")
    fun scheduled() {
        runCatching { run() }.onFailure { log.warn("lifecycle sweep failed: {}", it.javaClass.simpleName) }
    }

    fun run(asOf: Instant = Instant.now()): LifecycleSweepResult {
        var erased = 0
        purge.due(asOf).forEach { closure ->
            runCatching { purge.purge(closure, asOf) }
                .onSuccess { if (it != null) erased++ }
                .onFailure { log.warn("account purge {} failed: {}", closure, it.javaClass.simpleName) }
        }
        return LifecycleSweepResult(accountsErased = erased)
    }
}
