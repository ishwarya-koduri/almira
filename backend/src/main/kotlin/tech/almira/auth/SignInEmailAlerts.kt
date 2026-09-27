package tech.almira.auth

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource

/**
 * How many sign-in emails an address on the allowlist did not get, for the
 * operator (owner's decision, 2026-09-15; V130, docs/17 §8).
 *
 * The records are the worker's ([SignInEmailOutbox]) and only the owner
 * connection may read them, so this reads there. It returns counts and a time,
 * never an address. Only the operator's view of `/health` asks
 * ([tech.almira.config.HealthController]), behind
 * `ALMIRA_OPS_HEALTH_TOKEN`: a count anyone could read would tell someone who
 * had just asked for a code for an address whether it is listed.
 */
@Component
class SignInEmailAlerts(@Qualifier("ownerDataSource") ownerDataSource: DataSource) {

    private val jdbc = NamedParameterJdbcTemplate(ownerDataSource)

    data class Recent(val count: Int, val newest: Instant?)

    fun within(window: Duration = WINDOW): Recent =
        jdbc.queryForObject(
            """
            select count(*) as n, max(finished_at) as newest
              from sign_in_code_emails
             where operator_alert and finished_at >= now() - make_interval(secs => :window)
            """.trimIndent(),
            MapSqlParameterSource("window", window.toMillis() / 1000.0),
        ) { rs, _ ->
            Recent(rs.getInt("n"), rs.getObject("newest", Timestamp::class.java)?.toInstant())
        }!!

    companion object {
        /** What /health reports: long enough for a once-a-minute check to see it, short enough to clear. */
        val WINDOW: Duration = Duration.ofHours(1)
    }
}
