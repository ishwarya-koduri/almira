package tech.bhrigu.almira.config

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import javax.sql.DataSource

@RestController
class HealthController(
    private val jdbc: NamedParameterJdbcTemplate,
    private val dataSource: DataSource,
    private val properties: AlmiraProperties,
) {
    /**
     * Reports which database role serves requests. If this ever says `almira`
     * (the schema owner) instead of `almira_app`, row-level security is being
     * bypassed and the privacy model is off — worth seeing at a glance.
     *
     * It also reports the environment it believes it is in, because the other
     * way to deploy something dangerous is to deploy it with development
     * settings: one-time codes echoed in responses, and the strict encryption
     * checks relaxed. Both misconfigurations are now visible from outside,
     * without signing in.
     *
     * Deliberately outside the OpenAPI contract — springdoc matches only paths
     * under /api/v1 — because this is an operational endpoint and no client
     * should be built against it.
     */
    @GetMapping("/health")
    fun health(): Map<String, Any> {
        val role = jdbc.jdbcTemplate.queryForObject("select current_user", String::class.java)
        return mapOf(
            "status" to "ok",
            "database" to "up",
            "dbRole" to (role ?: "unknown"),
            "rlsEnforced" to (role != properties.db.ownerUser),
            "environment" to properties.environment,
        )
    }
}
