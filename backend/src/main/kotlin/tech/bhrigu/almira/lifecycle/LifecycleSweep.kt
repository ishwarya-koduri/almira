package tech.bhrigu.almira.lifecycle

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What one run did. Counts only. */
data class LifecycleSweepResult(
    val accountsErased: Int = 0,
    val departuresCompleted: Int = 0,
    val comingOfAgeNotices: Int = 0,
    /**
     * Households a purge left dormant: their last owner was erased on schedule and
     * what they held for the household stayed with it (docs/05 §12.7, V136).
     */
    val householdsLeftDormant: Int = 0,
    /** Dormant households whose successor's window passed (or who declined), opened to everyone eligible (V135). */
    val dormanciesOpenedToOthers: Int = 0,
    /** Dormant households nobody took on for 90 days after they were open to everyone: now an operator's (V147). */
    val dormanciesRoutedToRepair: Int = 0,
)

/**
 * The clock behind every waiting period in docs/05 §12: closures whose thirty
 * days are up, departures whose seven days are up, the month a managed child
 * turns eighteen, and a named successor's window on a dormant household.
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
    private val departures: DepartureCompletion,
    private val comingOfAge: ComingOfAgeNotices,
    private val offers: DormancyOffers,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 40 * * * *")
    fun scheduled() {
        runCatching { run() }.onFailure { log.warn("lifecycle sweep failed: {}", it.javaClass.simpleName) }
    }

    fun run(asOf: Instant = Instant.now()): LifecycleSweepResult {
        // Bytes an earlier purge or departure could not delete (V109).
        runCatching { purge.pendingDeletions.deleteQueued() }
            .onFailure { log.warn("retrying stored document deletions failed: {}", it.javaClass.simpleName) }
        var erased = 0
        var leftDormant = 0
        purge.due(asOf).forEach { closure ->
            runCatching { purge.purge(closure, asOf) }
                .onSuccess {
                    if (it != null) {
                        erased++
                        leftDormant += it.householdsLeftDormant
                    }
                }
                .onFailure { log.warn("account purge {} failed: {}", closure, it.javaClass.simpleName) }
        }
        var departed = 0
        departures.due(asOf).forEach { departure ->
            runCatching { departures.complete(departure, asOf) }
                .onSuccess { if (it != null) departed++ }
                .onFailure { log.warn("departure {} failed: {}", departure, it.javaClass.simpleName) }
        }
        val noticed = runCatching { comingOfAge.run(LocalDate.ofInstant(asOf, INDIA)) }
            .onFailure { log.warn("coming-of-age notices failed: {}", it.javaClass.simpleName) }
            .getOrDefault(0)
        val openedToOthers = runCatching { offers.openExpired() }
            .onFailure { log.warn("opening dormant households to others failed: {}", it.javaClass.simpleName) }
            .getOrDefault(0)
        val routed = runCatching { offers.routeStaleToRepair() }
            .onFailure { log.warn("handing stale dormant households to operators failed: {}", it.javaClass.simpleName) }
            .getOrDefault(0)
        return LifecycleSweepResult(
            accountsErased = erased, departuresCompleted = departed, comingOfAgeNotices = noticed,
            householdsLeftDormant = leftDormant, dormanciesOpenedToOthers = openedToOthers,
            dormanciesRoutedToRepair = routed,
        )
    }

    private companion object {
        /** The birthday month is India's month; a household elsewhere is a day out at most. */
        val INDIA: ZoneId = ZoneId.of("Asia/Kolkata")
    }
}
