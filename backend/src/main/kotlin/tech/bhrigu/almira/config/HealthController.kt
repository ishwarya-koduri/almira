package tech.bhrigu.almira.config

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import javax.sql.DataSource

@RestController
class HealthController(
    private val jdbc: NamedParameterJdbcTemplate,
    private val dataSource: DataSource,
) {
    /**
     * Reports which database role serves requests. If this ever says `almira`
     * (the schema owner) instead of `almira_app`, row-level security is being
     * bypassed and the privacy model is off — worth seeing at a glance.
     */
    @GetMapping("/health")
    fun health(): Map<String, Any> {
        val role = jdbc.jdbcTemplate.queryForObject("select current_user", String::class.java)
        return mapOf(
            "status" to "ok",
            "database" to "up",
            "dbRole" to (role ?: "unknown"),
            "rlsEnforced" to (role != "almira"),
        )
    }
}
