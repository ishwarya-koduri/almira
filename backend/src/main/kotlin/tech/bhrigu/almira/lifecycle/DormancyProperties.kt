package tech.bhrigu.almira.lifecycle

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.event.ApplicationStartedEvent
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import javax.sql.DataSource

/**
 * How a dormant household is offered (docs/05 §12.7, V135).
 *
 * The owner's answer: "ask the named successor first, give them a longer
 * window, then open it to others." Two waits, one after the other:
 *
 *  - **The general wait** before anyone may take it on: a week after a memorial,
 *    so a false one can be corrected; none after a closure or a departure, whose
 *    thirty or seven days have already passed. Fixed in the database (V120).
 *  - **The successor's window**, which follows it: only the successor the
 *    departed owner named may take it on, for [successorWindow]. After it, or on
 *    their decline, every eligible member may, with no end.
 *
 * Refuses to start outside 1 to 90 days, which the database also refuses.
 */
@ConfigurationProperties(prefix = "almira.lifecycle.dormancy")
data class DormancyProperties(
    val successorWindow: Duration = Duration.ofDays(14),
) {
    init {
        require(successorWindow >= Duration.ofDays(1) && successorWindow <= Duration.ofDays(90)) {
            "almira.lifecycle.dormancy.successor-window is $successorWindow; it must be between 1 and 90 days"
        }
    }
}

/**
 * Writes the successor's window where the database reads it, on the owner
 * connection, once the application has started: a memorial opens a dormancy by
 * a trigger, which cannot read configuration. A dormancy keeps the window it
 * opened with.
 */
@Component
class DormancySettingsSync(
    @Qualifier("ownerDataSource") ownerDataSource: DataSource,
    private val properties: DormancyProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val jdbc = JdbcTemplate(ownerDataSource)

    @EventListener(ApplicationStartedEvent::class)
    fun sync() {
        jdbc.update(
            "update dormancy_settings set successor_window = make_interval(secs => ?), updated_at = now() where id",
            properties.successorWindow.seconds.toDouble(),
        )
        log.info("dormant households: a named successor is asked first for {}", properties.successorWindow)
    }
}
