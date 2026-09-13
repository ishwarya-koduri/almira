package tech.bhrigu.almira.config

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import javax.sql.DataSource

@RestController
class HealthController(
    private val jdbc: NamedParameterJdbcTemplate,
    private val dataSource: DataSource,
    private val properties: AlmiraProperties,
    private val redis: StringRedisTemplate,
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

    /**
     * Liveness: the process is up and serving HTTP. Deliberately touches
     * nothing else — a liveness probe that fails when the database is down gets
     * a healthy application restarted in a loop during a database outage,
     * which turns one incident into two.
     */
    @GetMapping("/health/live")
    fun live(): Map<String, Any> = mapOf("status" to "alive")

    /**
     * Readiness: safe to send a user here. Three things, all required:
     *
     *  - the database answers on the RUNTIME connection pool;
     *  - that connection is not the schema owner, so row-level security is in
     *    force — an instance that would serve every member every record is not
     *    "ready", however quickly it responds;
     *  - Redis answers, because without it nobody can sign in (one-time codes)
     *    and signed-out sessions are not revoked.
     *
     * Migrations need no check of their own: Flyway runs during startup, before
     * the web server accepts a connection, so an instance that can answer this
     * has already migrated or has already refused to start.
     *
     * Answers 503 when any is false, so `curl -f` and a load balancer can use
     * it directly. It says which, but not the role name or the environment —
     * those stay on /health.
     */
    @GetMapping("/health/ready")
    fun ready(): ResponseEntity<Map<String, Any>> {
        val role = runCatching {
            jdbc.jdbcTemplate.queryForObject("select current_user", String::class.java)
        }.getOrNull()
        val database = role != null
        val rlsEnforced = database && role != properties.db.ownerUser
        val redisUp = runCatching {
            redis.execute { connection -> connection.ping() } == "PONG"
        }.getOrDefault(false)

        val ready = database && rlsEnforced && redisUp
        val body = mapOf(
            "status" to if (ready) "ready" else "not_ready",
            "database" to database,
            "rlsEnforced" to rlsEnforced,
            "redis" to redisUp,
        )
        return ResponseEntity.status(if (ready) HttpStatus.OK else HttpStatus.SERVICE_UNAVAILABLE).body(body)
    }
}
